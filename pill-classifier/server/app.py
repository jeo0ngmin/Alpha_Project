"""pill-classifier 추론 서버. MediMate Java 백엔드가 내부망에서만 호출한다 (외부 공개 금지).

    uvicorn server.app:app --port 8000

환경변수
    PILL_DB             식약처 DB 경로 (기본 data/pills.db)
    PILL_MODEL, PILL_INDEX  임베딩 모델/색인 (비우면 models/embedder.onnx 또는 .pt 와 data/index.npz가 있을 때 사용)
    PILL_EMBEDDING      0이면 임베딩을 끈다 (학습하지 않은 약에서의 성능을 잴 때)
    PILL_TRAINED_ITEMS  확신해도 되는 약 목록 (기본 models/trained_items.txt). 여기 없는 약이 1위면
                        '확실하지 않음'으로 낸다. 임베딩이 꺼져 있으면 어떤 약도 확신하지 않는다
    PILL_DETECTOR       YOLO 가중치 (선택)
    PILL_USE_OCR        0이면 각인 OCR 끔 (쓸 수 있는 OCR 엔진이 없으면 자동으로 꺼짐)
    PILL_INTERNAL_KEY   설정하면 X-Internal-Key 헤더가 일치해야 호출 가능
    PILL_IMAGES         식약처 참조 사진 폴더 (기본 data/images)
    PILL_AI_PROVIDER    Claude 비전 보조: none(기본) | bedrock | anthropic (pillid/vision.py 참고)
    PILL_AI_ALWAYS      1이면 사진 분석만으로 확신해도 Claude를 부른다 (기본은 확신이 없을 때만)
    CLOVA_OCR_*         약봉투 글자 인식 (pillid/bag.py 참고). 키 파일은 .clova_ocr

사진은 저장하지 않는다. 요청 처리 중 메모리에만 존재한다.
Claude 비전 보조를 켜면 사진(긴 변 1024px로 축소)이 Claude API로 전송된다.
"""
from __future__ import annotations

import json
import logging
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from dataclasses import replace
from pathlib import Path

import cv2
import numpy as np
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse

from pillid import __version__
from pillid.bag import BagReader, ClovaOcr, load_clova_config
from pillid.attributes import COLORS, SHAPES, parse_colors
from pillid.enums import (API_COLOR_MATCHES, API_TO_COLOR, API_TO_SHAPE, LINE_TYPES, color_to_api,
                          is_capsule, line_to_api, shape_to_api, to_float)
from pillid.ocr import canonical_imprint, normalize_imprint
from pillid.imaging import read_image
from pillid.pipeline import AiObservation, PillIdentifier, PillObservation
from pillid.vision import RERANK_TOP_N, PillVision, apply_rerank, claude_from_env, needs_rerank

log = logging.getLogger("pillid.server")
ROOT = Path(__file__).resolve().parents[1]
MAX_IMAGE_BYTES = 15 * 1024 * 1024
_SIDES = ThreadPoolExecutor(max_workers=2, thread_name_prefix="side")
MAIN_PILL_AREA_RATIO = 0.25  # 가장 큰 알약 대비 이 비율 이상이면 '또 하나의 알약'으로 센다


# ── 변환 ──────────────────────────────────────────────────────────────────

def item_summary(it: dict) -> dict:
    colors = parse_colors(it.get("color_class1"), it.get("color_class2"))
    return {
        "itemSeq": it["item_seq"],
        "itemName": it.get("item_name"),
        "entpName": it.get("entp_name"),
        "className": it.get("class_name"),
        "etcOtcName": it.get("etc_otc_name"),
        "dosageForm": it.get("form_code_name"),
        "drugShape": shape_to_api(it.get("drug_shape"), capsule=is_capsule(it.get("form_code_name"))),
        "colorClass1": color_to_api(colors[0]) if colors else None,
        "printFront": it.get("print_front"),
        "itemImageUrl": it.get("item_image"),
    }


def permit_date(it: dict) -> str | None:
    """식약처 원본 응답의 ITEM_PERMIT_DATE(YYYYMMDD) → YYYY-MM-DD."""
    try:
        d = str(json.loads(it.get("raw_json") or "{}").get("ITEM_PERMIT_DATE") or "")
    except ValueError:
        return None
    return f"{d[:4]}-{d[4:6]}-{d[6:8]}" if len(d) == 8 and d.isdigit() else None


def item_detail(it: dict) -> dict:
    colors = parse_colors(it.get("color_class1"), it.get("color_class2"))
    detail = item_summary(it)
    detail["itemPermitDate"] = permit_date(it)
    detail["appearance"] = {
        "drugShape": detail["drugShape"],
        "colorClass1": color_to_api(colors[0]) if colors else None,
        "colorClass2": color_to_api(colors[1]) if len(colors) > 1 else None,
        "printFront": it.get("print_front"),
        "printBack": it.get("print_back"),
        "lineFront": line_to_api(it.get("line_front")),
        "lengLong": to_float(it.get("leng_long")),
        "lengShort": to_float(it.get("leng_short")),
        "thick": to_float(it.get("thick")),
        "featureConfidence": None,
    }
    return detail


def features_payload(obs: list[PillObservation], hinted: set[str]) -> dict:
    o = obs[0]
    colors = [color_to_api(c) for c in o.colors]
    capsule = o.shape == "장방형" and len(set(o.colors)) >= 2  # 두 색 장방형은 거의 캡슐이다
    # 휴리스틱 신뢰도: 사용자가 입력한 값은 1.0, 영상 추정 모양/색은 0.7, 각인은 OCR 신뢰도
    conf = [1.0 if "drugShape" in hinted else 0.7, 1.0 if "colorClass" in hinted else 0.7]
    if "printText" in hinted:
        conf.append(1.0)
    elif o.imprint:
        conf.append(o.imprint_conf)
    return {
        "drugShape": shape_to_api(o.shape, capsule=capsule),
        "colorClass1": colors[0] if colors else None,
        "colorClass2": colors[1] if len(colors) > 1 else None,
        "printFront": o.imprint or None,
        "printBack": (obs[1].imprint or None) if len(obs) > 1 else None,
        "lineFront": None,  # 분할선 인식은 아직 미구현
        "lengLong": None,   # 크기(mm)는 기준 물체 없이는 추정 불가
        "lengShort": None,
        "thick": None,
        "featureConfidence": round(float(np.mean(conf)), 3),
    }


def parse_hints(raw: str | None) -> dict:
    if not raw:
        return {}
    try:
        hints = json.loads(raw)
    except ValueError:
        raise HTTPException(400, "hints가 올바른 JSON이 아닙니다.") from None
    if not isinstance(hints, dict):
        raise HTTPException(400, "hints는 JSON 객체여야 합니다.")
    shape = hints.get("drugShape")
    if shape is not None and shape not in API_TO_SHAPE:
        raise HTTPException(400, f"알 수 없는 drugShape: {shape}")
    colors = hints.get("colorClass") or []
    if not isinstance(colors, list) or any(c not in API_TO_COLOR for c in colors):
        raise HTTPException(400, f"알 수 없는 colorClass: {colors}")
    return {k: v for k, v in {"drugShape": shape, "colorClass": colors,
                              "printText": hints.get("printText")}.items() if v}


def observation_for_scoring(o: dict | None, hints: dict) -> tuple[AiObservation | None, list]:
    """비전 모델(Claude)의 관찰(docs/03 §6.1)을 점수 계산용으로 바꾼다. 값은 식약처 분류명(한글)이다.

    사용자가 직접 입력한 항목은 사용자를 믿고 관찰값을 버린다. 분류에 없는 값도 버린다.
    (관찰값, 화면에 보여 줄 각인 [앞, 뒤])를 돌려준다.
    """
    if not o:
        return None, [None, None]
    shape = o.get("shape")
    colors = [c for c in (o.get("colors") or []) if c in COLORS][:2]
    raw_imprints = [o.get("imprintFront"), o.get("imprintBack")]
    readable = o.get("imprintReadability") != "LOW"  # 판독이 어려우면 각인은 근거로 쓰지 않는다
    return AiObservation(
        shape=shape if shape in SHAPES and "drugShape" not in hints else None,
        colors=colors if "colorClass" not in hints else [],
        imprints=[t or "" for t in raw_imprints] if readable and "printText" not in hints else None,
    ), raw_imprints if readable else [None, None]


def describe_item(it: dict) -> str:
    """재순위 프롬프트에 넣는 후보의 외형 설명 (식약처 기재 내용)."""
    return (f"모양 {it.get('drug_shape') or '미상'}, 색 {', '.join(it['_colors']) or '미상'}, "
            f"앞면 각인 {it.get('print_front') or '없음'}, 뒷면 각인 {it.get('print_back') or '없음'}, "
            f"제형 {it.get('form_code_name') or '미상'}")


def apply_hints(obs: list[PillObservation], hints: dict) -> list[PillObservation]:
    """사용자가 직접 입력한 값은 영상 추정보다 정확하다고 보고 덮어쓴다."""
    out = []
    for i, o in enumerate(obs):
        changes = {}
        if hints.get("drugShape") and API_TO_SHAPE.get(hints["drugShape"]):
            changes["shape"] = API_TO_SHAPE[hints["drugShape"]]
        if hints.get("colorClass"):
            changes["colors"] = [API_TO_COLOR[c] for c in hints["colorClass"][:2]]
        if i == 0 and hints.get("printText"):
            changes["imprint"] = normalize_imprint(hints["printText"])
            changes["imprint_conf"] = 1.0
        out.append(replace(o, **changes) if changes else o)
    return out


def decode_upload(f: UploadFile, name: str) -> np.ndarray:
    data = f.file.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(413, f"{name} 이미지가 너무 큽니다.")
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise HTTPException(415, f"{name} 이미지를 해석할 수 없습니다.")
    return img


# ── 앱 ────────────────────────────────────────────────────────────────────

def load_reference_image(path: Path) -> np.ndarray | None:
    if not path.is_file():
        return None
    try:
        return read_image(path)
    except ValueError:
        return None


AUTO = "auto"


def load_trained_items(path: Path) -> set[str]:
    """한 줄에 품목기준코드 하나. #으로 시작하는 줄은 설명이다."""
    if not path.is_file():
        log.warning("학습한 약 목록이 없습니다: %s → 모든 결과를 '확실하지 않음'으로 냅니다 "
                    "(scripts/write_trained_items.py로 만드세요)", path)
        return set()
    lines = path.read_text(encoding="utf-8").splitlines()
    return {s.strip() for s in lines if s.strip() and not s.lstrip().startswith("#")}


def create_app(db_path=None, model_path=None, index_path=None, detector=None,
               use_ocr: bool | None = None, internal_key: str | None = None, images_dir=None,
               vision: PillVision | None = None, trusted_items=AUTO, bag_reader: BagReader | None = None,
               ai_always: bool | None = None) -> FastAPI:
    """vision: 테스트에서 가짜 비전을 넣을 때 쓴다. 비우면 환경변수(PILL_AI_PROVIDER)로 정한다.
    trusted_items: 확신해도 되는 약. 기본(AUTO)은 임베딩이 켜져 있을 때 학습 목록 파일, 꺼져 있으면 빈 집합.
    None이면 제한하지 않는다(테스트용)."""
    state: dict = {}
    lock = threading.Lock()  # OCR/모델이 스레드 안전하다고 보장되지 않으므로 식별은 한 번에 하나씩

    @asynccontextmanager
    async def lifespan(_app):
        env = os.environ
        # None = 지정 안 함(기본 위치 탐색), "" = 임베딩 끄기
        model = env.get("PILL_MODEL") if model_path is None else model_path
        index = env.get("PILL_INDEX") if index_path is None else index_path
        if model_path is None and index_path is None and env.get("PILL_EMBEDDING", "1") == "0":
            model, index = "", ""
        if model is None and index is None:  # 지정이 없으면 기본 위치(models/embedder.onnx 또는 .pt + data/index.npz)
            from pillid.embedding import find_default_model
            model, index = find_default_model(ROOT)
        ident = PillIdentifier(
            db_path or env.get("PILL_DB") or ROOT / "data" / "pills.db",
            model_path=model,
            index_path=index,
            detector_weights=detector or env.get("PILL_DETECTOR"),
            use_ocr=use_ocr if use_ocr is not None else env.get("PILL_USE_OCR", "1") != "0",
        )
        if trusted_items == AUTO:
            trained = env.get("PILL_TRAINED_ITEMS") or ROOT / "models" / "trained_items.txt"
            ident.trusted_items = load_trained_items(Path(trained)) if ident.embedder is not None else set()
        else:
            ident.trusted_items = None if trusted_items is None else set(trusted_items)
        log.info("확신 가능한 약: %s", "제한 없음" if ident.trusted_items is None else f"{len(ident.trusted_items)}종")
        state["ident"] = ident
        state["by_seq"] = {it["item_seq"]: it for it in ident.items}
        state["key"] = internal_key if internal_key is not None else env.get("PILL_INTERNAL_KEY")
        # 식약처 참조 이미지 폴더 (build_db.py --download-images가 {품목기준코드}.jpg로 저장)
        state["images"] = Path(images_dir or env.get("PILL_IMAGES") or ROOT / "data" / "images")
        claude = None if vision is not None else claude_from_env()
        state["vision"] = vision if vision is not None else (PillVision(claude) if claude else None)
        # 1이면 확신하더라도 항상 Claude를 부른다 (평가·비교용)
        state["ai_always"] = ai_always if ai_always is not None else env.get("PILL_AI_ALWAYS", "0") == "1"
        log.info("Claude 비전 보조: %s", "켜짐" if state["vision"] else "꺼짐 (CV만으로 식별)")
        if bag_reader is not None:
            state["bag"] = bag_reader
        else:
            clova = load_clova_config(ROOT)
            state["bag"] = BagReader(ident.items, claude, ClovaOcr(*clova) if clova else None)
        log.info("약봉투 인식: OCR %s, 정리 %s", "켜짐" if state["bag"].ocr else "꺼짐 (CLOVA 키 없음)",
                 "켜짐" if state["bag"].claude else "꺼짐 (Claude 없음)")
        yield

    app = FastAPI(title="pill-classifier inference", version=__version__, lifespan=lifespan)

    def check_key(x_internal_key: str | None = Header(None)):
        if state.get("key") and x_internal_key != state["key"]:
            raise HTTPException(401, "내부 호출 키가 올바르지 않습니다.")

    @app.get("/health")
    def health():
        ident: PillIdentifier = state["ident"]
        return {"status": "UP", "version": __version__, "items": len(ident.items),
                "ocr": ident.ocr is not None, "embedding": ident.embedder is not None,
                "vision": state["vision"] is not None,
                "trustedItems": None if ident.trusted_items is None else len(ident.trusted_items),
                "bagOcr": state["bag"].ocr is not None, "bagAi": state["bag"].claude is not None}

    @app.post("/v1/bag/recognize", dependencies=[Depends(check_key)])
    def recognize_bag(image: UploadFile | None = File(None), ocrText: str | None = Form(None)):
        """약봉투 사진(또는 앱이 읽은 글자)을 복약 정보 초안으로 바꾼다. 사진은 저장하지 않는다."""
        if image is None and not (ocrText and ocrText.strip()):
            raise HTTPException(400, "image 또는 ocrText가 필요합니다.")
        if ocrText and len(ocrText) > 20000:
            raise HTTPException(413, "ocrText가 너무 깁니다.")
        data = None
        if image is not None:
            data = image.file.read(MAX_IMAGE_BYTES + 1)
            if len(data) > MAX_IMAGE_BYTES:
                raise HTTPException(413, "이미지가 너무 큽니다.")
        return state["bag"].recognize(data, ocrText)

    @app.post("/v1/identify", dependencies=[Depends(check_key)])
    def identify(front: UploadFile = File(...), back: UploadFile | None = File(None),
                 hints: str | None = Form(None), topk: int = Form(10)):
        ident: PillIdentifier = state["ident"]
        pill_vision: PillVision | None = state["vision"]
        h = parse_hints(hints)
        topk = max(1, min(topk, 50))
        front_img = decode_upload(front, "앞면")
        back_img = decode_upload(back, "뒷면") if back is not None else None

        def run(seen: dict | None, use_ai: bool) -> dict:
            """seen: Claude의 관찰(없으면 None). use_ai가 거짓이면 재비교도 하지 않는다."""
            issues = list(seen.get("imageIssues") or []) if seen else []
            base = {"version": __version__, "pillCount": 0, "features": None, "candidates": [],
                    "lowConfidence": True, "observationUsed": seen is not None, "imageIssues": issues,
                    "reranked": False}
            if seen and not seen.get("pillFound"):
                return {**base, "status": "NOT_FOUND", "reason": "NO_PILL"}
            if seen and seen.get("multiplePills"):
                return {**base, "status": "NOT_FOUND", "reason": "MULTIPLE_PILLS", "pillCount": 2}
            ai, ai_imprints = observation_for_scoring(seen, h)

            # 2) CV로 알약을 분할하고, Claude의 관찰과 합쳐 후보 점수를 낸다
            with lock:
                # 앞면과 뒷면을 동시에 분석한다
                back_found_future = _SIDES.submit(ident.observe_array, back_img) if back_img is not None else None
                found = ident.observe_array(front_img)
                # 뒷면 분석이 끝날 때까지 여기서 기다린다 (잠금 밖으로 작업이 새지 않게)
                back_found = back_found_future.result() if back_found_future is not None else None
                main = [o for o in found if found and o.area >= MAIN_PILL_AREA_RATIO * found[0].area]
                base["pillCount"] = len(main)
                if not main:
                    return {**base, "status": "NOT_FOUND", "reason": "NO_PILL"}
                # 그림자 등으로 CV가 덩어리를 여럿 잡아도, Claude가 한 개라고 봤으면 가장 큰 것을 쓴다
                if len(main) > 1 and not seen:
                    return {**base, "status": "NOT_FOUND", "reason": "MULTIPLE_PILLS"}
                obs = [main[0]]
                if back_found:
                    obs.append(back_found[0])
                obs = apply_hints(obs, h)
                res = ident.identify(obs, topk, ai)

            by_seq = state["by_seq"]
            candidates = []
            for c in res.candidates:
                summary = item_summary(by_seq[c.item_seq])
                candidates.append({
                    "itemSeq": c.item_seq, "itemName": summary["itemName"], "entpName": summary["entpName"],
                    "className": summary["className"], "etcOtcName": summary["etcOtcName"],
                    "itemImageUrl": summary["itemImageUrl"], "score": round(c.score, 4),
                    "scoreBreakdown": {k: round(v, 4) for k, v in c.parts.items()},
                    "description": describe_item(by_seq[c.item_seq]),
                    "hasImage": (state["images"] / f"{c.item_seq}.jpg").is_file(),
                })
            features = features_payload(obs, set(h))
            # OCR이 읽지 못한 각인은 비전 모델이 읽은 값으로 보여 준다
            features["printFront"] = features["printFront"] or ai_imprints[0]
            if len(obs) > 1:
                features["printBack"] = features["printBack"] or ai_imprints[1]
            # 3) 확신이 없을 때만 Claude에게 상위 후보를 다시 비교시킨다 (비용이 드는 호출)
            low_confidence, reranked = res.low_confidence, False
            if use_ai and pill_vision and needs_rerank(candidates, low_confidence):
                top = [{"itemSeq": c["itemSeq"], "itemName": c["itemName"], "description": c["description"],
                        "image": load_reference_image(state["images"] / f"{c['itemSeq']}.jpg")}
                       for c in candidates[:RERANK_TOP_N]]
                matches = pill_vision.rerank(front_img, back_img, top)
                if matches is not None:
                    candidates, low_confidence = apply_rerank(candidates, matches)
                    reranked = True
            if candidates and not ident.is_trusted(candidates[0]["itemSeq"]):
                low_confidence = True
            return {**base, "status": "COMPLETED", "reason": None, "sidesUsed": len(obs),
                    "features": features, "candidates": candidates,
                    "lowConfidence": low_confidence, "reranked": reranked}

        # 1) 사진 분석(CV+임베딩)만으로 먼저 본다. 학습한 약을 확신하면 Claude를 부르지 않는다.
        #    AI Hub 사진 90종: 정확도는 그대로(1위 89/90, 확신 오답 0)이고 Claude 호출은 90건 → 15건으로 준다.
        # 2) 확신이 없거나 알약을 못 찾으면 Claude가 사진을 보고, 필요하면 후보를 다시 비교한다.
        if pill_vision is None:
            return run(None, False)
        if not state["ai_always"]:
            quick = run(None, False)
            if quick["status"] == "COMPLETED" and not quick["lowConfidence"]:
                return {**quick, "aiSkipped": True}
        return run(pill_vision.observe(front_img, back_img), True)

    @app.get("/v1/items/{item_seq}", dependencies=[Depends(check_key)])
    def get_item(item_seq: str):
        it = state["by_seq"].get(item_seq)
        if it is None:
            raise HTTPException(404, "품목을 찾을 수 없습니다.")
        return item_detail(it)

    @app.get("/v1/items/{item_seq}/image", dependencies=[Depends(check_key)])
    def get_item_image(item_seq: str):
        """식약처 참조 이미지. 재순위(docs/03 §6.3)에서 사용자 사진과 나란히 비교하는 데 쓴다."""
        if item_seq not in state["by_seq"]:  # DB에 있는 코드만 허용 (경로 조작 방지)
            raise HTTPException(404, "품목을 찾을 수 없습니다.")
        path = state["images"] / f"{item_seq}.jpg"
        if not path.is_file():
            raise HTTPException(404, "참조 이미지가 없습니다.")
        return FileResponse(path, media_type="image/jpeg")

    @app.get("/v1/items", dependencies=[Depends(check_key)])
    def search_items(drugShape: str | None = None, colorClass: list[str] = Query(default=[]),
                     printText: str | None = None, lineFront: str | None = None,
                     itemName: str | None = None, offset: int = Query(0, ge=0),
                     limit: int = Query(20, ge=1, le=100)):
        if drugShape is not None and drugShape not in API_TO_SHAPE:
            raise HTTPException(400, f"알 수 없는 drugShape: {drugShape}")
        if any(c not in API_TO_COLOR for c in colorClass):
            raise HTTPException(400, f"알 수 없는 colorClass: {colorClass}")
        if lineFront is not None and lineFront not in LINE_TYPES:
            raise HTTPException(400, f"알 수 없는 lineFront: {lineFront}")

        wanted_colors = set().union(*(API_COLOR_MATCHES[c] for c in colorClass)) if colorClass else None
        imprint = canonical_imprint(printText) if printText else None
        name = itemName.strip().lower() if itemName else None
        hits = []
        for it in state["ident"].items:
            if drugShape:
                capsule = is_capsule(it.get("form_code_name"))
                if drugShape == "CAPSULE" and not capsule:
                    continue
                if drugShape != "CAPSULE" and API_TO_SHAPE[drugShape] and it["_shape"] != API_TO_SHAPE[drugShape]:
                    continue
            if wanted_colors and not wanted_colors & set(it["_colors"]):
                continue
            if imprint and not any(imprint in "".join(side) for side in it["_imp"]):
                continue
            if lineFront and line_to_api(it.get("line_front")) != lineFront:
                continue
            if name and name not in (it.get("item_name") or "").lower():
                continue
            hits.append(it)
        hits.sort(key=lambda it: (it.get("item_name") or "", it["item_seq"]))
        page = hits[offset:offset + limit]
        next_offset = offset + limit if offset + limit < len(hits) else None
        return {"items": [item_summary(it) for it in page], "total": len(hits), "nextOffset": next_offset}

    return app


app = create_app()
