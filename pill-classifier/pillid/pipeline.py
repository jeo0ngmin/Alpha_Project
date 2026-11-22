"""사진 → 알약 후보 Top-K. 탐지 → 속성/각인/임베딩 추출 → DB 전체와 점수 비교."""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from .attributes import (ELONGATED_SHAPES, color_set_similarity, estimate_colors,
                         estimate_shape, parse_colors, shape_similarity)
from .db import PillDB
from .detect import PillDetector
from .imaging import (PillRegion, canonical_crop, drop_glare_and_partial, preprocess, read_image,
                      region_in_original)
from .ocr import ImprintReader, imprint_pair_score, imprint_tokens

# 사용 가능한 단서만 골라 합이 1이 되도록 다시 정규화해서 쓴다.
# 각인·색·모양의 비율(80:10:10)과 신뢰도 기준은 scripts/tune_scoring.py로 고른 값이다. 참조 사진으로 75:12.5:12.5를
# 골랐다가, 실제 사진(AI Hub)에서는 색이 조명에 따라 자주 틀려서 각인 쪽으로 조금 더 옮겼다 (참조 사진 Top-1은 그대로,
# 실제 사진 Top-1 55% → 60%).
# 임베딩 비중 0.55: AI Hub 전체로 학습한 모델(2026-11)에서 임베딩이 정답을 가리키는데 잘못 읽은 각인이 뒤집는 일이
# 많아 0.45에서 올렸다. AI Hub 실제 사진 276쌍을 품목 기준 반반으로 나눠 조정용에서 고르고 검증용에서 확인
# (Top-1 조정용 95.7% → 97.1%, 검증용 93.4% → 95.6%, Top-5 100%). 각인·색·모양 비율 80:10:10은 그대로다.
BASE_WEIGHTS = {"embedding": 0.55, "imprint": 0.36, "color": 0.045, "shape": 0.045}
# 아래 셋을 모두 넘어야 '확신'으로 본다. 검증용 표본에서 확신 표시의 93%가 정답이었다.
LOW_CONF_SCORE = 0.85
OCR_MIN_AREA_RATIO = 0.25  # 서버가 "또 하나의 알약"으로 세는 기준(MAIN_PILL_AREA_RATIO)과 같다
LOW_CONF_MARGIN = 0.08
LOW_CONF_IMPRINT = 0.9  # 1위 후보의 각인 점수. 각인 없이 모양·색만 맞은 것은 확신하지 않는다
# 임베딩이 있을 때의 기준. 점수 척도가 달라지고(임베딩 비중 45%) 임베딩 점수 자체가 좋은 근거라서 따로 둔다.
# AI Hub 실제 사진 276쌍으로 고름: 조정용 확신 83%·정답률 100%, 검증용 확신 80%·정답률 99% (2026-10).
# 1위가 오답일 때 임베딩 점수는 모두 0.5 미만이었고, 정답일 때는 대부분 0.6 이상이었다.
LOW_CONF_EMB_SCORE = 0.6
LOW_CONF_EMB_MARGIN = 0.03
LOW_CONF_EMBEDDING = 0.7  # 1위 후보의 임베딩 점수가 이 이상이면 각인을 못 읽었어도 확신할 수 있다
OCR_CROP_SIZE = 480
# 질의 크롭을 90°씩 돌려 4번 임베딩하고 평균한다 (색인은 한 번씩). AI Hub 실제 사진 276쌍:
# Top-1 270 → 272, 확신 표시 178 → 186, 확신 오답 0 그대로. 사진 한 장에 임베딩 33ms → 113ms (2026-11).
EMBED_ROTATIONS = (0, 90, 180, 270)
# 색인에 식약처 사진만 있는 약(AI Hub 촬영 사진이 없는 약, DB의 대부분)은 실제 사진과의 임베딩 유사도가 낮게 나와,
# 촬영 사진이 있는 비슷한 약에 밀린다. AI Hub 실제 사진 276쌍에서 평가 약의 색인을 식약처 사진 2장만 남기면
# Top-1 98.9% → 58.0%로 임베딩 없이(65.6%)보다도 나빴다. 이런 약의 임베딩 점수에 보정값을 더한다:
# 0.15에서 식약처 사진만 남긴 경우 58.0% → 80.1%, 지금 색인(촬영 사진 있음)은 98.9% 그대로 (0.20부터 떨어짐). 2026-11
REFERENCE_ONLY_MAX = 2
REFERENCE_ONLY_BONUS = 0.15
# 읽은 각인(이 글자 수 이상)이 학습하지 않은 같은 모양의 다른 약과 정확히 맞는데 1위는 각인이 맞지 않으면 확신하지 않는다.
# AI Hub에 사진이 없는 약을 찍으면 임베딩이 학습한 비슷한 약을 1위로 올려 확신 오답이 났다(처음 보는 약 135쌍 중 3건:
# 각인 GGZ를 정확히 읽었는데 ggr 약, PG 75 → 다른 회사 PG75). 학습한 약의 확신 표시는 262 → 253으로 줄었다. 2026-11
RIVAL_MIN_LEN = 3
# 위와 같은 정확 일치 약은 순위가 낮아도 Claude 재비교에 이 개수까지 더 넣는다 (처음 보는 약 오답 17건 중 5건이
# 각인을 정확히 읽었는데 정답이 2~8위였다)
IMPRINT_EXTRA = 3
# 단, 사진 분석 1위가 학습한 약이고 임베딩이 받쳐 주며 2위와 이만큼 벌어져 있으면 더하지 않는다. 학습한 아스피린(각인 AA)을
# OCR이 AST로 읽자 각인 AST인 다른 약이 더해져 Claude가 그쪽을 골랐다(1위 정답 3장 → 저신뢰 오답). 사진 분석만 기록했을 때
# 이 3장은 2위와 0.06~0.10, 추가 후보로 정답을 찾은 처음 보는 약 사진은 0.01~0.02 차이였다. 2026-11
EXTRA_SKIP_MARGIN = 0.05


@dataclass
class PillObservation:
    crop: np.ndarray = field(repr=False)  # 임베딩용 정규화 크롭 (BGR)
    shape: str
    colors: list[str]
    imprint: str
    imprint_conf: float
    bbox: tuple[int, int, int, int]
    area: float


@dataclass
class AiObservation:
    """비전 모델이 사진에서 읽은 외형 (식약처 분류명). 값이 없으면 그 단서는 쓰지 않는다."""
    shape: str | None = None
    colors: list[str] = field(default_factory=list)
    # 관측한 면 순서(앞, 뒤)의 각인. 각인이 없으면 "", 판독이 어려워 쓰지 않을 때는 None.
    imprints: list[str] | None = None


@dataclass
class Candidate:
    item_seq: str
    item_name: str
    entp_name: str
    score: float
    parts: dict[str, float]
    drug_shape: str
    colors: str
    print_front: str
    print_back: str
    image_url: str


@dataclass
class Identification:
    observations: list[PillObservation]
    candidates: list[Candidate]
    weights: dict[str, float]
    low_confidence: bool
    # 읽은 각인이 정확히 맞는 품목 번호(self.items 기준)와 품목별 각인 점수. 각인을 충분히 읽지 못했으면 비어 있다
    exact_imprint: list[int] = field(default_factory=list)
    imprint_scores: np.ndarray | None = field(default=None, repr=False)
    scores: np.ndarray | None = field(default=None, repr=False)
    parts: dict | None = field(default=None, repr=False)


class PillIdentifier:
    def __init__(self, db_path, model_path=None, index_path=None, detector_weights=None,
                 use_ocr: bool = True, ocr_langs=("en",), device: str | None = None,
                 crop_size: int = 224, trusted_items=None):
        """trusted_items: 확신해도 되는 품목기준코드 집합. 1위가 여기 없으면 점수와 상관없이 저신뢰로 낸다.
        None이면 제한하지 않는다(평가 스크립트 기본값). 서비스는 임베딩으로 학습한 약만 넣는다."""
        self.trusted_items = None if trusted_items is None else set(trusted_items)
        db = PillDB(db_path)
        self.items = db.all_items()
        db.close()
        if not self.items:
            raise ValueError(f"DB가 비어 있습니다: {db_path} (scripts/build_db.py를 먼저 실행하세요)")
        self._pos = {it["item_seq"]: i for i, it in enumerate(self.items)}
        for it in self.items:
            it["_imp"] = (imprint_tokens(it.get("print_front")), imprint_tokens(it.get("print_back")))
            it["_colors"] = tuple(parse_colors(it.get("color_class1"), it.get("color_class2")))
            it["_shape"] = (it.get("drug_shape") or "").strip()

        model_path, index_path = model_path or None, index_path or None  # ""도 '없음'으로
        if (model_path is None) != (index_path is None):
            raise ValueError("model_path와 index_path는 함께 지정해야 합니다.")
        self.embedder = self.gallery = None
        if model_path:
            from .embedding import Embedder
            from .gallery import Gallery
            self.embedder = Embedder(model_path, device)
            self.gallery = Gallery.load(index_path)
            pos = {it["item_seq"]: i for i, it in enumerate(self.items)}
            item_idx = np.array([pos.get(l, -1) for l in self.gallery.labels], dtype=np.int64)
            valid = item_idx >= 0  # DB에 없는 라벨(예: AI Hub 전용 코드)은 제외
            self._gallery_embs = self.gallery.embs[valid]
            self._gallery_item = item_idx[valid]
            counts = np.bincount(self._gallery_item, minlength=len(self.items))
            # 색인에 식약처 사진(품목당 2장 이하)만 있는 품목. 실제 사진과의 유사도가 낮게 나온다
            self._reference_only = (counts > 0) & (counts <= REFERENCE_ONLY_MAX)
            crop_size = self.embedder.img_size
        self.crop_size = crop_size

        self.detector = PillDetector(detector_weights)
        self.ocr = ImprintReader(ocr_langs) if use_ocr else None
        if self.ocr is not None and not self.ocr.available:
            self.ocr = None

    # ── 관측 ──────────────────────────────────────────────────────────────

    def observe(self, img: np.ndarray, region: PillRegion, read_imprint: bool = True,
                original: np.ndarray | None = None, scale: float = 1.0) -> PillObservation:
        """original, scale: preprocess()로 줄이기 전 사진과 축소 배율. 주면 각인은 원본 해상도에서 읽는다."""
        shape = estimate_shape(region.contour)
        text, conf = "", 0.0
        if self.ocr is not None and read_imprint:
            if original is not None and scale < 1.0:
                ocr_crop = canonical_crop(*region_in_original(original, region, scale), OCR_CROP_SIZE)
            else:
                ocr_crop = canonical_crop(img, region, OCR_CROP_SIZE)
            text, conf = self.ocr.read(ocr_crop, all_rotations=shape not in ELONGATED_SHAPES)
        return PillObservation(
            crop=canonical_crop(img, region, self.crop_size),
            shape=shape,
            colors=estimate_colors(img, region.mask),
            imprint=text,
            imprint_conf=conf,
            bbox=region.bbox,
            area=region.area,
        )

    def observe_array(self, img: np.ndarray) -> list[PillObservation]:
        """BGR 이미지 속 알약들을 면적이 큰 순서로 관측한다."""
        original = img
        img, scale = preprocess(img)
        regions = drop_glare_and_partial(img, self.detector(img))
        # 각인 판독(OCR)이 가장 오래 걸린다. 가장 큰 알약보다 훨씬 작은 덩어리(그림자, 부스러기)는 읽지 않는다
        largest = regions[0].area if regions else 0
        return [self.observe(img, r, read_imprint=r.area >= OCR_MIN_AREA_RATIO * largest, original=original, scale=scale)
                for r in regions]

    def observe_photo(self, path) -> list[PillObservation]:
        return self.observe_array(read_image(path))

    # ── 식별 ──────────────────────────────────────────────────────────────

    def identify_photo(self, path, topk: int = 5) -> list[Identification]:
        """사진 속 알약 각각을 따로 식별한다."""
        return [self.identify([o], topk) for o in self.observe_photo(path)]

    def identify_sides(self, front_path, back_path, topk: int = 5) -> Identification:
        """같은 알약의 앞/뒷면 사진 두 장으로 식별한다 (각 사진에서 가장 큰 알약 사용)."""
        obs = []
        for p in (front_path, back_path):
            found = self.observe_photo(p)
            if not found:
                raise ValueError(f"알약을 찾지 못했습니다: {p}")
            obs.append(max(found, key=lambda o: o.area))
        return self.identify(obs, topk)

    def identify(self, obs: list[PillObservation], topk: int = 5, ai: AiObservation | None = None,
                 use_imprint: bool | None = None) -> Identification:
        """ai: 비전 모델(Claude)의 관찰. CV 추정과 다르면 두 유사도를 평균하고, 각인은 더 잘 맞는 쪽을 쓴다.

        use_imprint: 관측한 각인(OCR·사용자 입력)을 점수에 넣을지. None이면 OCR이 켜져 있거나
        읽은 글자가 있을 때 넣는다. 평가에서 켜고 끈 결과를 비교할 때 직접 지정한다.
        """
        parts = {
            "shape": self._per_item("_shape", lambda v: np.mean([shape_similarity(o.shape, v) for o in obs])),
            "color": self._per_item("_colors", lambda v: np.mean([color_set_similarity(o.colors, v) for o in obs])),
        }
        if ai is not None and ai.shape and any(o.shape != ai.shape for o in obs):
            parts["shape"] = (parts["shape"] + self._per_item(
                "_shape", lambda v: shape_similarity(ai.shape, v))) / 2
        if ai is not None and ai.colors and any(set(o.colors) != set(ai.colors) for o in obs):
            parts["color"] = (parts["color"] + self._per_item(
                "_colors", lambda v: color_set_similarity(ai.colors, v))) / 2

        # 각인 근거: OCR(또는 사용자가 입력한 글자)과 비전 모델의 판독. 품목마다 더 잘 맞는 쪽 점수를 쓴다.
        readings = []
        if use_imprint is None:
            use_imprint = self.ocr is not None or any(o.imprint for o in obs)
        if use_imprint:
            readings.append([imprint_tokens(o.imprint) for o in obs])
        if ai is not None and ai.imprints is not None:
            readings.append([imprint_tokens(t) for t in ai.imprints[:len(obs)]])
        if readings:
            parts["imprint"] = self._per_item(
                "_imp", lambda v: max(imprint_pair_score(texts, v) for texts in readings))
        exact = []
        if readings and max(len("".join(texts[0])) for texts in readings if texts) >= RIVAL_MIN_LEN:
            exact = np.flatnonzero(parts["imprint"] >= LOW_CONF_IMPRINT - 1e-6).tolist()
        if self.embedder is not None:
            parts["embedding"] = self._embedding_scores(obs)

        total = sum(BASE_WEIGHTS[k] for k in parts)
        weights = {k: BASE_WEIGHTS[k] / total for k in parts}
        score = sum(weights[k] * parts[k] for k in parts)

        order = np.argsort(-score, kind="stable")[:topk]
        candidates = []
        for i in order:
            candidates.append(self._candidate(i, score, parts))
        top = candidates[0].score if candidates else 0.0
        second = candidates[1].score if len(candidates) > 1 else 0.0
        # 한 면만 각인이 있는 약을 정확히 읽으면 각인 점수가 (1.0 + BOTH_EMPTY) / 2 = 0.9로 기준과 같다.
        # float32 오차로 기준에 못 미치는 일이 없도록 여유를 둔다.
        eps = 1e-6
        parts0 = candidates[0].parts if candidates else {}
        imprint_ok = parts0.get("imprint", 0.0) >= LOW_CONF_IMPRINT - eps
        if "embedding" in parts:
            low = top < LOW_CONF_EMB_SCORE - eps or (top - second) < LOW_CONF_EMB_MARGIN - eps or not (
                imprint_ok or parts0.get("embedding", 0.0) >= LOW_CONF_EMBEDDING - eps)
        else:
            low = top < LOW_CONF_SCORE - eps or (top - second) < LOW_CONF_MARGIN - eps or not imprint_ok
        res = Identification(obs, candidates, weights, low, exact, parts.get("imprint"), score, parts)
        if candidates and (not self.is_trusted(candidates[0].item_seq) or self.imprint_rival(res, candidates[0].item_seq)):
            res.low_confidence = True
        return res

    def _candidate(self, i: int, score: np.ndarray, parts: dict) -> Candidate:
        it = self.items[i]
        return Candidate(
            item_seq=it["item_seq"], item_name=it.get("item_name") or "",
            entp_name=it.get("entp_name") or "", score=float(score[i]),
            parts={k: float(v[i]) for k, v in parts.items()},
            drug_shape=it["_shape"], colors=", ".join(it["_colors"]),
            print_front=it.get("print_front") or "", print_back=it.get("print_back") or "",
            image_url=it.get("item_image") or "",
        )

    def imprint_candidates(self, res: Identification, limit: int = IMPRINT_EXTRA, skip=()) -> list[Candidate]:
        """읽은 각인이 정확히 맞는 약 중 skip(이미 비교할 후보)에 없는 것을 점수 순으로 limit개.
        AI Hub에 사진이 없는 약은 각인을 정확히 읽어도 학습한 비슷한 약에 밀려 6위 밖으로 나가, Claude 재비교가 정답을 보지 못했다."""
        if not res.exact_imprint or res.scores is None or limit <= 0:
            return []
        c = res.candidates
        if (c and self.trusted_items is not None and c[0].item_seq in self.trusted_items
                and c[0].parts.get("embedding", 0.0) >= LOW_CONF_EMB_SCORE - 1e-6
                and (len(c) < 2 or c[0].score - c[1].score >= EXTRA_SKIP_MARGIN - 1e-6)):
            return []
        skip = set(skip)
        idx = [i for i in res.exact_imprint if self.items[i]["item_seq"] not in skip]
        idx.sort(key=lambda i: -res.scores[i])
        return [self._candidate(i, res.scores, res.parts) for i in idx[:limit]]

    def imprint_rival(self, res: Identification, item_seq: str) -> str | None:
        """1위(item_seq)의 각인은 맞지 않는데, 읽은 각인이 정확히 맞는 학습하지 않은 같은 모양의 약이 있으면 그 품목기준코드.
        재비교로 1위가 바뀐 뒤에도 다시 확인할 수 있게 따로 둔다."""
        if not res.exact_imprint or res.imprint_scores is None or self.trusted_items is None:
            return None
        top = self._pos.get(item_seq)
        if top is None or res.imprint_scores[top] >= LOW_CONF_IMPRINT - 1e-6:
            return None
        shape = self.items[top]["_shape"]
        for i in res.exact_imprint:
            it = self.items[i]
            if it["_shape"] == shape and it["item_seq"] not in self.trusted_items:
                return it["item_seq"]
        return None

    def is_trusted(self, item_seq: str) -> bool:
        """이 약을 1위로 냈을 때 확신해도 되는가. 학습하지 않은 약은 겉모습이 비슷한 다른 약과 자주 헷갈린다."""
        return self.trusted_items is None or item_seq in self.trusted_items

    def _per_item(self, key: str, fn) -> np.ndarray:
        """같은 속성값을 가진 품목이 많으므로 값별로 한 번만 계산한다."""
        cache: dict = {}
        out = np.empty(len(self.items), np.float32)
        for i, it in enumerate(self.items):
            v = it[key]
            if v not in cache:
                cache[v] = fn(v)
            out[i] = cache[v]
        return out

    def _embedding_scores(self, obs: list[PillObservation]) -> np.ndarray:
        crops = [np.ascontiguousarray(np.rot90(o.crop, r // 90)) for o in obs for r in EMBED_ROTATIONS]
        q = self.embedder.embed_bgr(crops).reshape(len(obs), len(EMBED_ROTATIONS), -1).sum(axis=1)
        q /= np.linalg.norm(q, axis=1, keepdims=True)
        per_obs = []
        for vec in q:
            sims = np.clip(self._gallery_embs @ vec, 0.0, 1.0)
            best = np.zeros(len(self.items), np.float32)  # 참조 이미지가 없는 품목은 0
            np.maximum.at(best, self._gallery_item, sims)  # 품목별 참조 이미지 중 최고 유사도
            per_obs.append(best)
        return np.minimum(np.mean(per_obs, axis=0) + REFERENCE_ONLY_BONUS * self._reference_only, 1.0)
