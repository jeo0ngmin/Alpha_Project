"""약봉투 인식: CLOVA OCR → 줄 재구성 → 개인정보 가림 → Claude 구조화 → 검증 → 식약처 품목 대조.

docs/03_AI_OCR_연동설계서.md §1.2, §2.3–2.4, §4, §5를 따른다. 결과는 사용자가 확인하기 전의 초안이다.
틀린 값으로 알림이 울리면 안 되므로, 근거가 부족한 값은 비우고 needsReview로 표시한다.

환경변수
    CLOVA_OCR_INVOKE_URL, CLOVA_OCR_SECRET   CLOVA OCR (General) API Gateway 주소와 시크릿
    CLOVA_OCR_KEY_FILE                       위 두 값을 KEY=VALUE로 적은 파일 (기본 pill-classifier/.clova_ocr)
"""
from __future__ import annotations

import base64
import json
import logging
import os
import re
import time
import uuid
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from pathlib import Path

import cv2
import numpy as np

from .vision import AiError, ClaudeJson, _enum, _load_prompt, _nullable, _object

log = logging.getLogger("pillid.bag")

EXTRACT_PROMPT = "bag-extract-v2"  # v2: 주의사항(cautions, generalCautions) 추가
RECHECK_PROMPT = "bag-recheck-v2"
RECHECK_MAX_SIDE = 1600
REVIEW_CONFIDENCE = 0.7      # 0.6 × OCR 신뢰도 + 0.4 × 품목 유사도가 이보다 낮으면 사용자 확인
MATCHED_SIMILARITY = 0.85
CANDIDATE_SIMILARITY = 0.6
APP_TEXT_CONFIDENCE = 0.9    # 앱이 보낸 OCR 원문은 줄별 신뢰도를 모른다
DEFAULT_SLOTS = {1: ["MORNING"], 2: ["MORNING", "DINNER"], 3: ["MORNING", "LUNCH", "DINNER"],
                 4: ["MORNING", "LUNCH", "DINNER", "BEDTIME"]}
SLOT_TO_API = {"MORNING": "MORNING", "LUNCH": "LUNCH", "EVENING": "DINNER", "BEDTIME": "BEDTIME"}
MEAL_TO_API = {"BEFORE_MEAL": "BEFORE_MEAL", "AFTER_MEAL": "AFTER_MEAL", "WITH_MEAL": "WITH_MEAL", "ANY": "ANYTIME"}
UNIT_TO_API = {"정": "TABLET", "캡슐": "CAPSULE", "포": "PACK", "mL": "ML", "g": "UNIT", "매": "UNIT", "기타": "UNIT"}


class BagError(Exception):
    """사용자에게 다시 찍기 등을 안내해야 하는 실패. code는 docs/03 §2.4의 오류 코드."""

    def __init__(self, code: str, message: str, tts: str):
        super().__init__(message)
        self.code, self.tts = code, tts


# ── CLOVA OCR ─────────────────────────────────────────────────────────────

@dataclass
class OcrField:
    text: str
    conf: float
    x: float
    y: float
    w: float
    h: float


def load_clova_config(root: Path) -> tuple[str, str] | None:
    """환경변수가 먼저고, 없으면 키 파일을 읽는다. 값은 로그에 남기지 않는다."""
    url, secret = os.environ.get("CLOVA_OCR_INVOKE_URL"), os.environ.get("CLOVA_OCR_SECRET")
    if not (url and secret):
        path = Path(os.environ.get("CLOVA_OCR_KEY_FILE") or root / ".clova_ocr")
        if path.is_file():
            values = {}
            for line in path.read_text(encoding="utf-8-sig").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    values[k.strip()] = v.strip().strip("\"'")
            url = url or values.get("CLOVA_OCR_INVOKE_URL")
            secret = secret or values.get("CLOVA_OCR_SECRET")
    return (url.strip(), secret.strip()) if url and secret else None


class ClovaOcr:
    def __init__(self, invoke_url: str, secret: str, client=None, timeout: float = 30):
        import httpx

        self.url, self.secret = invoke_url, secret
        self.client = client or httpx.Client(timeout=timeout)

    def read(self, jpeg: bytes) -> list[OcrField]:
        import httpx

        body = {"version": "V2", "requestId": uuid.uuid4().hex, "timestamp": int(time.time() * 1000), "lang": "ko",
                "images": [{"format": "jpg", "name": "bag", "data": base64.b64encode(jpeg).decode("ascii")}]}
        response = None
        for attempt in range(2):
            try:
                response = self.client.post(self.url, json=body, headers={"X-OCR-SECRET": self.secret})
            except httpx.HTTPError as e:
                log.warning("CLOVA OCR 연결 실패(%d회): %s", attempt + 1, type(e).__name__)
                response = None
                continue
            if response.status_code < 500:
                break
            log.warning("CLOVA OCR 응답 %d (%d회)", response.status_code, attempt + 1)
        if response is None or response.status_code >= 500:
            raise BagError("OCR_UNAVAILABLE", "CLOVA OCR 호출 실패",
                           "지금은 글자 인식을 할 수 없습니다. 잠시 후 다시 시도해 주세요.")
        if response.status_code != 200:
            # 401·403은 키나 주소 설정 문제다. 응답 본문에 키가 있을 수 있어 상태만 남긴다
            log.error("CLOVA OCR 요청 거절 %d: 키와 Invoke URL을 확인하세요", response.status_code)
            raise BagError("OCR_UNAVAILABLE", f"CLOVA OCR 요청 거절 ({response.status_code})",
                           "지금은 글자 인식을 할 수 없습니다. 잠시 후 다시 시도해 주세요.")
        image = (response.json().get("images") or [{}])[0]
        if image.get("inferResult") != "SUCCESS":
            raise BagError("OCR_FAILED", "글자 인식 실패", "사진을 읽지 못했습니다. 다시 찍어 주세요.")
        fields = []
        for f in image.get("fields") or []:
            pts = (f.get("boundingPoly") or {}).get("vertices") or []
            xs = [p.get("x", 0) for p in pts] or [0]
            ys = [p.get("y", 0) for p in pts] or [0]
            fields.append(OcrField(f.get("inferText", ""), float(f.get("inferConfidence", 0)),
                                   min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)))
        return fields


# ── 줄 재구성 (§2.3) ──────────────────────────────────────────────────────

@dataclass
class Line:
    no: int
    conf: float
    text: str


def assemble_lines(fields: list[OcrField]) -> list[Line]:
    """세로 위치가 비슷한 글자 덩어리를 한 줄로 묶고, 간격이 넓으면 표의 칸으로 보고 ' | '를 넣는다."""
    fields = [f for f in fields if f.text.strip()]
    if not fields:
        return []
    median_h = float(np.median([f.h for f in fields])) or 1.0
    rows: list[list[OcrField]] = []
    for f in sorted(fields, key=lambda f: f.y + f.h / 2):
        cy = f.y + f.h / 2
        if rows and abs(cy - np.mean([g.y + g.h / 2 for g in rows[-1]])) <= 0.5 * median_h:
            rows[-1].append(f)
        else:
            rows.append([f])
    char_w = sum(f.w for f in fields) / max(1, sum(len(f.text) for f in fields))
    lines = []
    for i, row in enumerate(rows, 1):
        row.sort(key=lambda f: f.x)
        text = row[0].text
        for prev, cur in zip(row, row[1:]):
            text += (" | " if cur.x - (prev.x + prev.w) > 2 * char_w else " ") + cur.text
        lines.append(Line(i, min(f.conf for f in row), text))
    return lines


def check_ocr(fields: list[OcrField]) -> None:
    """§2.4: 글자가 너무 적거나 흐리면 구조화하지 않고 다시 찍게 한다."""
    hangul = sum(len(re.findall(r"[가-힣]", f.text)) for f in fields)
    if len(fields) < 5 or hangul < 10:
        raise BagError("OCR_NO_TEXT", "인식된 글자가 너무 적음",
                       "글자가 거의 보이지 않습니다. 약봉투의 글씨 면이 보이게 찍어 주세요.")
    if np.mean([f.conf for f in fields]) < 0.6:
        raise BagError("OCR_LOW_QUALITY", "인식 신뢰도가 낮음",
                       "사진이 흐립니다. 밝은 곳에서 흔들리지 않게 다시 찍어 주세요.")


def lines_from_text(text: str) -> list[Line]:
    rows = [r.strip() for r in text.splitlines() if r.strip()]
    return [Line(i, APP_TEXT_CONFIDENCE, r) for i, r in enumerate(rows, 1)]


# ── 개인정보 가림 (§4.2) ──────────────────────────────────────────────────

_RRN = re.compile(r"\d{6}\s*[-–]\s*[1-8][\d*]{6}|\d{6}\s*[-–]\s*[1-8]\*+")
_PHONE = re.compile(r"(?<!\d)0\d{1,2}[-\s)]?\d{3,4}[-\s]?\d{4}(?!\d)")
_NAME = re.compile(r"(환\s*자\s*명|성\s*명|수\s*진\s*자|이\s*름|환\s*자\s*[:：])\s*[:：]?\s*([가-힣]{2,4})")
_NUMBER = re.compile(r"(교부\s*번호|요양\s*기관\s*번호|기관\s*번호|처방전?\s*번호|조제\s*번호|영수증\s*번호)\s*[:：]?\s*[\d\-]+")


def mask_name(name: str) -> str:
    return name[0] + "O" if len(name) == 2 else name[0] + "O" * (len(name) - 2) + name[-1]


def mask_lines(lines: list[Line]) -> tuple[list[Line], str | None]:
    """식별 정보를 가린 줄과, 가린 환자 이름(예: 홍O동). 가리기 전 원문은 어디에도 남기지 않는다."""
    patient = None
    out = []
    for line in lines:
        text = _RRN.sub("[주민번호]", line.text)
        text = _NUMBER.sub(lambda m: m.group(1) + " [번호]", text)
        text = _PHONE.sub("[전화]", text)

        def name(m):
            nonlocal patient
            patient = patient or mask_name(m.group(2))
            return m.group(1) + " [이름]"
        text = _NAME.sub(name, text)
        out.append(Line(line.no, line.conf, text))
    return out, patient


def format_lines(lines: list[Line]) -> str:
    return "\n".join(f"L{l.no:02d} [{l.conf:.2f}] {l.text}" for l in lines)


# ── Claude 구조화 (§4.3–4.5) ───────────────────────────────────────────────

_str_or_null = _nullable({"type": "string"})
_int_or_null = _nullable({"type": "integer"})

EXTRACTION_SCHEMA = _object(
    documentType=_enum("MEDICATION_BAG", "PRESCRIPTION", "UNKNOWN"),
    pharmacyName=_str_or_null,
    hospitalName=_str_or_null,
    dispensedDate=_str_or_null,
    medications={"type": "array", "items": _object(
        nameAsPrinted={"type": "string"},
        nameCorrected={"type": "string"},
        strength=_str_or_null,
        dosePerTake=_nullable({"type": "number"}),
        doseUnit=_nullable(_enum("정", "캡슐", "포", "mL", "g", "매", "기타")),
        timesPerDay=_int_or_null,
        slots={"type": "array", "items": _enum("MORNING", "LUNCH", "EVENING", "BEDTIME")},
        mealRelation=_nullable(_enum("BEFORE_MEAL", "AFTER_MEAL", "WITH_MEAL", "ANY")),
        mealOffsetMin=_int_or_null,
        durationDays=_int_or_null,
        instructions=_str_or_null,
        cautions={"type": "array", "items": {"type": "string"}},
        evidenceLines={"type": "array", "items": {"type": "integer"}},
        evidenceQuotes={"type": "array", "items": {"type": "string"}},
        confidence=_enum("HIGH", "MEDIUM", "LOW"),
        uncertainReason=_str_or_null,
    )},
    generalInstructions={"type": "array", "items": {"type": "string"}},
    generalCautions={"type": "array", "items": {"type": "string"}},
    unreadable={"type": "array", "items": {"type": "string"}},
)


def _image_block(jpeg: bytes, max_side: int = RECHECK_MAX_SIDE) -> dict:
    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("이미지를 해석할 수 없습니다.")
    h, w = img.shape[:2]
    scale = min(1.0, max_side / max(h, w))
    if scale < 1.0:
        img = cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 90])
    return {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                                        "data": base64.standard_b64encode(buf.tobytes()).decode("ascii")}}


# ── 이름 비교와 품목 대조 (§5.3) ───────────────────────────────────────────

_PAREN = re.compile(r"\([^)]*\)|\[[^\]]*\]")
_STRENGTH = re.compile(r"(\d+(?:\.\d+)?)(mg|mcg|g|ml|iu)")


def normalize_name(name: str | None) -> str:
    s = _PAREN.sub("", name or "")
    s = re.sub(r"\s+", "", s).lower()
    for a, b in (("밀리그람", "mg"), ("밀리그램", "mg"), ("㎎", "mg"), ("마이크로그램", "mcg"), ("㎍", "mcg"),
                 ("µg", "mcg"), ("μg", "mcg"), ("밀리리터", "ml"), ("㎖", "ml"), ("그램", "g"), ("그람", "g")):
        s = s.replace(a, b)
    return s


def strength_of(norm: str) -> str | None:
    m = _STRENGTH.search(norm)
    return m.group(1) + m.group(2) if m else None


def without_strength(norm: str) -> str:
    return re.sub(r"[/,]+", "", _STRENGTH.sub("", norm))


def _bigrams(s: str) -> frozenset:
    return frozenset([s] if len(s) == 1 else (s[i:i + 2] for i in range(len(s) - 1)))


def dice(a: frozenset, b: frozenset) -> float:
    return 0.0 if not a or not b else 2 * len(a & b) / (len(a) + len(b))


class DrugMatcher:
    """식약처 품목명과 약봉투 약품명의 유사도. DB 확장 기능 없이도 같은 결과가 나오게 직접 계산한다."""

    def __init__(self, items: list[dict]):
        self.rows = []
        for it in items:
            norm = normalize_name(it.get("item_name"))
            if norm:
                self.rows.append((it, strength_of(norm), _bigrams(norm), _bigrams(without_strength(norm))))

    def match(self, name: str, topn: int = 5) -> list[tuple[dict, float, bool]]:
        """[(품목, 유사도, 함량 일치)] 유사도 높은 순."""
        q = normalize_name(name)
        if len(q) < 2:
            return []
        q_strength, q_full, q_bare = strength_of(q), _bigrams(q), _bigrams(without_strength(q))
        scored = []
        for it, strength, full, bare in self.rows:
            # 봉투에 함량이 없으면 품목명에서도 함량을 빼고 비교한다. 이때는 함량이 다른 품목과 구별되지 않는다
            sim = max(dice(q_full, full), 0.95 * dice(q_bare, bare))
            if sim >= 0.4:
                same = q_strength == strength
                scored.append((it, min(1.0, sim + (0.05 if same and q_strength else 0)), same))
        scored.sort(key=lambda r: (-r[1], r[0]["item_seq"]))
        return scored[:topn]


# ── 검증 (§5.1–5.2) ───────────────────────────────────────────────────────

# 한자어 수사(일·이·삼…)는 "1일", "이 약"처럼 흔한 글자라 근거로 쓰지 않는다
_NUMERAL_WORDS = {1: ["한"], 2: ["두"], 3: ["세"], 4: ["네"], 5: ["다섯"], 6: ["여섯"], 7: ["일곱"],
                  0.5: ["반", "1/2", "½"]}


def _nospace(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def _fuzzy_in(quote: str, text: str, threshold: float = 0.9) -> bool:
    q, t = _nospace(quote), _nospace(text)
    if not q:
        return False
    if q in t:
        return True
    if len(q) > len(t) + 2:
        return SequenceMatcher(None, q, t).ratio() >= threshold
    return any(SequenceMatcher(None, q, t[i:i + len(q)]).ratio() >= threshold for i in range(max(1, len(t) - len(q) + 1)))


def _number_in(value: float, text: str) -> bool:
    t = _nospace(text)
    as_text = str(int(value)) if float(value).is_integer() else str(value)
    if re.search(rf"(?<![\d.]){re.escape(as_text)}(?![\d.])", t):
        return True
    return any(w in t for w in _NUMERAL_WORDS.get(value if not float(value).is_integer() else int(value), []))


@dataclass
class Checked:
    med: dict
    reasons: list[str] = field(default_factory=list)
    evidence_ok: bool = True
    slots_inferred: bool = False


def printed_cautions(cautions, lines: list[Line]) -> list[str]:
    """문서 어느 줄에도 없는 주의사항은 버린다. 그럴듯한 일반 주의사항을 지어내는 것을 막는다."""
    text = " ".join(l.text for l in lines)
    out = []
    for c in cautions or []:
        c = (c or "").strip()
        if c and c not in out and _fuzzy_in(c, text, 0.85):
            out.append(c)
    return out


def validate(meds: list[dict], lines: list[Line]) -> list[Checked]:
    by_no = {l.no: l.text for l in lines}
    out: list[Checked] = []
    seen: dict[str, Checked] = {}
    for med in meds:
        c = Checked(dict(med))
        m = c.med
        m["cautions"] = printed_cautions(m.get("cautions"), lines)
        evidence = " ".join(by_no.get(n, "") for n in m.get("evidenceLines") or [])
        if not evidence or not all(_fuzzy_in(q, evidence) for q in m.get("evidenceQuotes") or []):
            c.evidence_ok = False
            c.reasons.append("근거 줄을 확인하지 못했습니다")
        if not _fuzzy_in(m.get("nameAsPrinted", ""), evidence):
            c.evidence_ok = False
            c.reasons.append("약 이름이 문서에서 확인되지 않습니다")
        if dice(_bigrams(normalize_name(m.get("nameCorrected"))), _bigrams(normalize_name(m.get("nameAsPrinted")))) < 0.7:
            # OCR 오탈자 수정을 넘어 다른 약 이름으로 바뀐 것으로 본다
            m["nameCorrected"] = m.get("nameAsPrinted")
            c.reasons.append("이름을 고친 폭이 커서 인쇄된 이름을 그대로 썼습니다")
        for key, low, high, label in (("dosePerTake", 0, 10, "1회량"), ("timesPerDay", 1, 6, "하루 횟수"),
                                      ("durationDays", 1, 180, "일수")):
            v = m.get(key)
            if v is None:
                continue
            if not (low < v <= high if key == "dosePerTake" else low <= v <= high):
                m[key] = None
                c.reasons.append(f"{label} 값이 범위를 벗어났습니다")
            elif evidence and not _number_in(v, evidence):
                m[key] = None
                c.reasons.append(f"{label}의 근거를 찾지 못했습니다")
        times, slots = m.get("timesPerDay"), list(m.get("slots") or [])
        if slots and times and len(slots) != times:
            c.reasons.append("복용 시점 수와 하루 횟수가 다릅니다")
        if not slots and times in DEFAULT_SLOTS:
            m["slots"] = DEFAULT_SLOTS[times][:]
            c.slots_inferred = True
        else:
            m["slots"] = [SLOT_TO_API[s] for s in slots]
        key = normalize_name(m.get("nameCorrected"))
        if key in seen:
            seen[key].reasons.append("같은 약이 두 번 적혀 있습니다")
            continue
        seen[key] = c
        out.append(c)
    return out


# ── 전체 흐름 ─────────────────────────────────────────────────────────────

class BagReader:
    def __init__(self, items: list[dict], claude: ClaudeJson | None, ocr: ClovaOcr | None, effort: str = "medium"):
        self.matcher = DrugMatcher(items)
        self.claude, self.ocr, self.effort = claude, ocr, effort
        self._extract_system = _load_prompt(EXTRACT_PROMPT)
        self._recheck_system = _load_prompt(RECHECK_PROMPT)

    def recognize(self, jpeg: bytes | None, ocr_text: str | None = None) -> dict:
        started = time.monotonic()
        try:
            result = self._recognize(jpeg, ocr_text)
        except BagError as e:
            result = {"status": "FAILED", "error": {"code": e.code, "message": str(e), "ttsMessage": e.tts},
                      "items": []}
        result["processingMs"] = int((time.monotonic() - started) * 1000)
        return result

    def _recognize(self, jpeg: bytes | None, ocr_text: str | None) -> dict:
        if ocr_text and ocr_text.strip():
            lines = lines_from_text(ocr_text)
        elif jpeg is not None and self.ocr is not None:
            fields = self.ocr.read(jpeg)
            check_ocr(fields)
            lines = assemble_lines(fields)
        else:
            raise BagError("OCR_UNAVAILABLE", "OCR이 설정되지 않음",
                           "지금은 글자 인식을 할 수 없습니다. 잠시 후 다시 시도해 주세요.")
        if self.claude is None:
            raise BagError("AI_UNAVAILABLE", "Claude가 설정되지 않음",
                           "지금은 약봉투를 정리할 수 없습니다. 잠시 후 다시 시도해 주세요.")
        lines, patient = mask_lines(lines)
        ocr_conf = float(np.mean([l.conf for l in lines])) if lines else 0.0

        extracted = self._ask(self._extract_system, [{"type": "text", "text": f"<ocr_lines>\n{format_lines(lines)}\n</ocr_lines>"}],
                              EXTRACT_PROMPT)
        checked = validate(extracted.get("medications") or [], lines)
        problems = self._problems(extracted, checked, lines)
        rechecked = False
        if problems and jpeg is not None:
            content = [_image_block(jpeg),
                       {"type": "text", "text": f"<ocr_lines>\n{format_lines(lines)}\n</ocr_lines>\n"
                                                f"<first_result>\n{json.dumps(extracted, ensure_ascii=False)}\n</first_result>\n"
                                                f"<problems>\n" + "\n".join(problems) + "\n</problems>"}]
            try:
                extracted = self._ask(self._recheck_system, content, RECHECK_PROMPT)
                checked = validate(extracted.get("medications") or [], lines)
                rechecked = True
            except BagError:
                log.warning("재확인 실패: 1차 결과를 그대로 쓴다")

        by_no = {l.no: l for l in lines}
        items = [self._item(i, c, by_no) for i, c in enumerate(checked, 1)]
        general_cautions = printed_cautions(extracted.get("generalCautions"), lines)
        if not items:
            raise BagError("NO_MEDICATION", "약을 찾지 못함",
                           "약 이름을 찾지 못했습니다. 약 이름이 적힌 면이 보이게 다시 찍어 주세요.")
        review = sum(1 for it in items if it["needsReview"])
        return {
            "status": "PARTIAL" if review or extracted.get("unreadable") else "COMPLETED",
            "documentType": extracted.get("documentType"),
            "source": {"pharmacyName": extracted.get("pharmacyName"), "hospitalName": extracted.get("hospitalName"),
                       "dispensedDate": _date_or_none(extracted.get("dispensedDate")), "patientNameMasked": patient},
            "items": items,
            "generalInstructions": extracted.get("generalInstructions") or [],
            "generalCautions": general_cautions,
            "unreadable": extracted.get("unreadable") or [],
            "ocrConfidence": round(ocr_conf, 3),
            "overallConfidence": round(float(np.mean([it["confidence"] for it in items])), 3),
            "rechecked": rechecked,
            "extractor": self.claude.model,
        }

    def _ask(self, system: str, content: list[dict], task: str) -> dict:
        try:
            return self.claude.complete(task, system, content, EXTRACTION_SCHEMA, self.effort, 16000)
        except AiError as e:
            log.warning("약봉투 구조화 실패 kind=%s: %s", e.kind, e)
            raise BagError("AI_UNAVAILABLE", f"구조화 실패 ({e.kind})",
                           "지금은 약봉투를 정리할 수 없습니다. 잠시 후 다시 시도해 주세요.") from e

    def _problems(self, extracted: dict, checked: list[Checked], lines: list[Line]) -> list[str]:
        """§4.5 재확인 조건."""
        problems = []
        hangul = sum(len(re.findall(r"[가-힣]", l.text)) for l in lines)
        if not checked and hangul >= 20:
            problems.append("약을 하나도 찾지 못했지만 글자는 충분합니다. 약 목록을 다시 확인하세요.")
        for c in checked:
            name = c.med.get("nameAsPrinted")
            if c.med.get("confidence") == "LOW" or c.reasons:
                problems.append(f"{name}: {', '.join(c.reasons) or c.med.get('uncertainReason') or '신뢰도 낮음'}")
            elif not self.matcher.match(c.med.get("nameCorrected") or "", 1):
                problems.append(f"{name}: 식약처 품목과 이름이 맞지 않습니다. 이름을 다시 확인하세요.")
        return problems

    def _item(self, index: int, c: Checked, by_no: dict) -> dict:
        m = c.med
        matches = self.matcher.match(m.get("nameCorrected") or "")
        best = matches[0] if matches else None
        sim = best[1] if best else 0.0
        q_strength = strength_of(normalize_name(m.get("nameCorrected")))
        strength_ok = best is not None and (best[2] if q_strength else strength_of(normalize_name(best[0].get("item_name"))) is None)
        grade = "MATCHED" if best and sim >= MATCHED_SIMILARITY and strength_ok else (
            "CANDIDATE" if best and sim >= CANDIDATE_SIMILARITY else "UNMATCHED")
        reasons = list(c.reasons)
        if grade == "CANDIDATE":
            reasons.append("비슷한 약이 여러 개라 골라야 합니다")
        elif grade == "UNMATCHED":
            reasons.append("식약처 자료에서 약을 찾지 못했습니다")
        if m.get("confidence") == "LOW":
            reasons.append(m.get("uncertainReason") or "글자가 흐립니다")
        if c.slots_inferred:
            reasons.append("복용 시간이 적혀 있지 않아 기본 시간으로 정했습니다")
        for key, label in (("dosePerTake", "1회량을"), ("timesPerDay", "하루 횟수를"), ("durationDays", "일수를")):
            if m.get(key) is None:
                reasons.append(f"{label} 읽지 못했습니다")
        ev = [by_no[n].conf for n in m.get("evidenceLines") or [] if n in by_no]
        ocr_conf = min(ev) if ev else 0.5
        confidence = round(0.6 * ocr_conf + 0.4 * sim, 3)
        if confidence < REVIEW_CONFIDENCE:
            reasons.append("인식 신뢰도가 낮습니다")
        it = best[0] if grade == "MATCHED" else None
        unit = UNIT_TO_API.get(m.get("doseUnit") or "")
        if unit is None and it is not None:
            unit = "CAPSULE" if "캡슐" in (it.get("form_code_name") or "") else "TABLET"
        dose, times, days = m.get("dosePerTake"), m.get("timesPerDay"), m.get("durationDays")
        return {
            "itemId": f"i{index}",
            "rawText": m.get("nameAsPrinted"),
            "refinedName": m.get("nameCorrected"),
            "matched": grade == "MATCHED",
            "matchGrade": grade,
            "itemSeq": it["item_seq"] if it else None,
            "itemName": it.get("item_name") if it else None,
            "entpName": it.get("entp_name") if it else None,
            "dosageForm": it.get("form_code_name") if it else None,
            "strength": m.get("strength"),
            "dosageAmount": dose,
            "dosageUnit": unit,
            "frequencyPerDay": times,
            "timeSlots": m.get("slots") or [],
            "slotsInferred": c.slots_inferred,
            "mealRelation": MEAL_TO_API.get(m.get("mealRelation") or ""),
            "mealOffsetMinutes": m.get("mealOffsetMin"),
            "durationDays": days,
            "totalQuantity": dose * times * days if dose and times and days else None,
            "instructions": m.get("instructions"),
            "cautions": m.get("cautions") or [],
            "confidence": confidence,
            "needsReview": bool(reasons),
            "reviewReasons": list(dict.fromkeys(reasons)),
            "candidates": [{"itemSeq": r[0]["item_seq"], "itemName": r[0].get("item_name"),
                            "entpName": r[0].get("entp_name"), "similarity": round(r[1], 3)} for r in matches],
        }


def _date_or_none(value: str | None) -> str | None:
    return value if value and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) else None
