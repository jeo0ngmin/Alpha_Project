"""각인(imprint) 인식과 퍼지 매칭."""
from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor
import re
import warnings
from itertools import permutations

import cv2
import numpy as np
from rapidfuzz import fuzz
from rapidfuzz.distance import Levenshtein

from .imaging import BG_FILL

ALLOWLIST = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-"

# 식약처 DB는 각인의 조각(줄, 분할선 양쪽, 로고 자리)을 공백이나 이 단어들로 구분해 적는다.
# 예: "V분할선T", "마크분할선C8", "KDC NTC 8"
_SEPARATOR = re.compile(r"십자분할선|분할선|마크|각인|없음|\s+")
_HANGUL = re.compile(r"[가-힣]")
# OCR이 자주 혼동하는 글자를 한쪽으로 통일해 비교한다
_CONFUSABLE = str.maketrans({"0": "O", "1": "I", "5": "S", "8": "B", "2": "Z"})

# 점수 상수. scripts/tune_scoring.py로 조정용 표본(식약처 참조 사진 200품목)에서 고르고
# 검증용 표본(다른 200품목)에서 확인한 값이다 (2026-10, RapidOCR 기준).
BOTH_EMPTY = 0.8      # 관측도 정답도 각인 없음
MISSED = 0.5          # 정답에는 각인이 있는데 못 읽음 (음각·같은 색 각인은 흔히 놓친다)
FALSE_READ = 0.4      # 각인 없는 면인데 글자가 읽힘 (분할선·반사·윤곽을 글자로 읽는 일이 잦다)
PARTIAL_ALPHA = 0.8   # 일부만 읽힌 경우의 최대 점수
PARTIAL_BETA = 0.25   # 읽은 비율이 낮을수록 깎는 정도 (1이면 깎지 않음)
MAX_PERMUTED_TOKENS = 4
OUTLINE_FRAC = 0.8    # 한 글자 상자가 알약 짧은 변의 이 비율 이상이면 윤곽을 글자로 읽은 것


def _raw_tokens(text) -> list[str]:
    if not text:
        return []
    parts = (re.sub(r"[^0-9A-Z가-힣]", "", p) for p in _SEPARATOR.split(str(text).upper()))
    return [p for p in parts if p]


def normalize_imprint(text) -> str:
    """화면 표시용. 조각을 공백으로 구분한 대문자 영숫자 (예: "kdc-ntc 8" → "KDCNTC 8")."""
    return " ".join(_raw_tokens(text))


def imprint_tokens(text) -> tuple[str, ...]:
    """비교용 조각. 혼동 문자를 통일한다."""
    return tuple(t.translate(_CONFUSABLE) for t in _raw_tokens(text))


def canonical_imprint(text) -> str:
    """조각을 이어 붙인 비교용 문자열 (검색에 쓴다)."""
    return "".join(imprint_tokens(text))


def _arrangements(tokens: tuple[str, ...]) -> list[str]:
    """조각을 이어 붙이는 모든 순서. OCR이 읽는 순서와 DB에 적힌 순서가 다를 수 있다."""
    if len(tokens) <= 1 or len(tokens) > MAX_PERMUTED_TOKENS:
        return ["".join(tokens)]
    return list({"".join(p) for p in permutations(tokens)})


def imprint_components(query, target) -> tuple[float, float, float] | None:
    """(편집거리 유사도, 부분 일치도, 읽은 비율). 둘 중 하나라도 비어 있으면 None.

    조각 순서는 무시한다. 점수 상수를 바꿔 가며 다시 계산할 수 있도록 재료만 돌려준다.
    """
    q, t = _as_tokens(query), _as_tokens(target)
    if q and not any(_HANGUL.search(x) for x in q):
        # 영문 OCR은 한글 각인("CP 마이페노"의 "마이페노")을 읽지 못한다. 읽은 쪽에 한글이 없으면
        # 정답의 한글 조각은 비교에서 뺀다 (전부 한글이면 그대로 둔다)
        t = tuple(x for x in t if not _HANGUL.search(x)) or t
    if not q or not t:
        return None
    qs = "".join(q)
    lev = partial = 0.0
    for ts in _arrangements(t):
        if qs == ts:
            return 1.0, 1.0, 1.0
        lev = max(lev, Levenshtein.normalized_similarity(qs, ts))
        if min(len(qs), len(ts)) >= 2:
            partial = max(partial, fuzz.partial_ratio(qs, ts) / 100)
    total = sum(len(x) for x in t)
    return lev, partial, min(len(qs), total) / max(len(qs), total)


def combine_components(components, both_empty: bool, query_empty: bool) -> float:
    if components is None:
        return BOTH_EMPTY if both_empty else MISSED if query_empty else FALSE_READ
    lev, partial, coverage = components
    # 일부만 읽힌 경우: 읽은 글자가 정답 안에 그대로 있어도, 못 읽은 비율만큼 깎아서 인정한다
    return float(max(lev, PARTIAL_ALPHA * partial * (PARTIAL_BETA + (1 - PARTIAL_BETA) * coverage)))


def imprint_similarity(query, target) -> float:
    """두 각인의 유사도 0~1. 문자열이나 imprint_tokens() 결과를 받는다."""
    q, t = _as_tokens(query), _as_tokens(target)
    return combine_components(imprint_components(q, t), both_empty=not q and not t, query_empty=not q)


def _as_tokens(value) -> tuple[str, ...]:
    return value if isinstance(value, tuple) else imprint_tokens(value)


def imprint_pair_score(texts: list, sides: tuple) -> float:
    """관측 각인(1~2면)과 DB의 (앞, 뒤) 각인 비교. 두 면이면 앞/뒤 배정을 모두 시도한다."""
    front, back = sides
    if len(texts) == 1:
        return max(imprint_similarity(texts[0], front), imprint_similarity(texts[0], back))
    q1, q2 = texts[:2]
    straight = imprint_similarity(q1, front) + imprint_similarity(q2, back)
    swapped = imprint_similarity(q1, back) + imprint_similarity(q2, front)
    return max(straight, swapped) / 2

class ImprintReader:
    """각인 판독기. OCR 엔진을 쓸 수 없으면 빈 결과를 돌려준다.

    engine (또는 환경변수 PILL_OCR_ENGINE): auto(기본) | rapidocr | easyocr
      rapidocr  ONNX Runtime 기반(PP-OCR 모델). PyTorch가 필요 없고 가볍다. Windows의 Smart App Control처럼
                서명되지 않은 PyTorch DLL이 차단되는 환경에서도 동작한다
      easyocr   PyTorch 기반
      auto      rapidocr를 먼저 시도하고 안 되면 easyocr

    식약처 참조 사진 400품목으로 같은 조건에서 비교했을 때(2026-10) 각인을 양면 모두 정확히 읽은 비율이
    rapidocr 56%, easyocr 29%였고 속도도 rapidocr가 두 배 빨랐다. 그래서 rapidocr를 먼저 쓴다.
    점수 상수와 신뢰도 기준도 rapidocr 결과로 맞췄다.
    """

    ENGINES = ("rapidocr", "easyocr")

    def __init__(self, langs=("en",), gpu: bool | None = None, min_conf: float = 0.25,
                 engine: str | None = None):
        self.langs = tuple(langs)
        self.min_conf = min_conf
        self.engine: str | None = None
        self._reader = None
        requested = (engine or os.environ.get("PILL_OCR_ENGINE") or "auto").lower()
        for name in (self.ENGINES if requested == "auto" else (requested,)):
            try:
                self._reader = self._load(name, gpu)
                self.engine = name
                break
            except (ImportError, OSError) as e:  # OSError: DLL 로드 실패(애플리케이션 제어 정책 차단 등)
                warnings.warn(f"OCR 엔진 {name}을(를) 쓸 수 없습니다: {e}")
        if self._reader is None:
            warnings.warn("쓸 수 있는 OCR 엔진이 없어 각인 인식을 건너뜁니다 "
                          "(pip install easyocr 또는 rapidocr-onnxruntime).")

    def _load(self, name: str, gpu: bool | None):
        if name == "easyocr":
            import easyocr
            if gpu is None:
                import torch
                gpu = torch.cuda.is_available()
            return easyocr.Reader(list(self.langs), gpu=gpu, verbose=False)
        if name == "rapidocr":
            from rapidocr_onnxruntime import RapidOCR
            return RapidOCR()
        raise ValueError(f"알 수 없는 OCR 엔진: {name} (easyocr, rapidocr, auto 중 하나)")

    @property
    def available(self) -> bool:
        return self._reader is not None

    def read(self, crop_bgr: np.ndarray, all_rotations: bool = True) -> tuple[str, float]:
        """(정규화된 각인 문자열, 평균 신뢰도). 크롭은 장축이 수평으로 정렬되어 있다고 가정한다."""
        if self._reader is None:
            return "", 0.0
        outline = _outline_size(crop_bgr)
        gray = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2GRAY)
        # 음각/양각은 대비가 약하므로 국소 대비를 강화한다
        gray = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8)).apply(gray)
        # 장축 정렬된 길쭉한 알약은 글자가 뒤집혀 있을 수만 있고, 원형은 방향을 알 수 없다
        rotations = [90, 180, 270] if all_rotations else [180]
        if self.engine == "easyocr":
            allow = ALLOWLIST if set(self.langs) <= {"en"} else None
            results = self._reader.readtext(gray, allowlist=allow, detail=1, paragraph=False,
                                            rotation_info=rotations)
            return self._join(results, outline)
        # rapidocr: 회전 탐색 기능이 없으므로 직접 돌려 가며 읽고 가장 확실한 결과를 고른다.
        # 방향마다 독립이라 동시에 읽는다(ONNX Runtime은 계산 중 GIL을 놓는다). 결과는 차례로 읽을 때와 같다
        image = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        angles = [0, *rotations]

        def read_one(angle):
            rotated = image if angle == 0 else cv2.rotate(image, _ROTATE[angle])
            # 방향 판별(use_cls)은 끈다. 이미 네 방향으로 돌려 읽고 있어 같은 일을 두 번 하는 셈이고, 판독 시간의 절반을 차지했다
            result, _ = self._reader(rotated, use_cls=_USE_CLS)
            return self._join(result or [], outline)

        results = list(_POOL.map(read_one, angles))
        best, best_score = ("", 0.0), 0.0
        for text, conf in results:  # 같은 점수면 앞 방향을 고르는 것도 차례로 읽을 때와 같다
            score = conf * len(text.replace(" ", ""))
            if score > best_score:
                best, best_score = (text, conf), score
        return best

    def _join(self, results, outline: float = float("inf")) -> tuple[str, float]:
        """[(상자, 글자, 신뢰도)] → 위→아래, 왼→오른 순으로 이어 붙인 각인.

        outline: 한 글자짜리 상자가 이보다 크면 버린다. 캡슐 이음매나 알약 윤곽이 "D", "C"로 읽힌 것이다.
        """
        pieces = [(box, normalize_imprint(t).replace(" ", ""), float(c)) for box, t, c in results
                  if float(c) >= self.min_conf and normalize_imprint(t)]
        pieces = [p for p in pieces if len(p[1]) > 1 or _box_size(p[0]) < outline]
        if not pieces:
            return "", 0.0
        pieces.sort(key=lambda p: (min(pt[1] for pt in p[0]), min(pt[0] for pt in p[0])))
        return " ".join(t for _, t, _ in pieces), float(np.mean([c for *_, c in pieces]))


_ROTATE = {90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_COUNTERCLOCKWISE}
_USE_CLS = os.environ.get("PILL_OCR_CLS", "0") == "1"
# 각인 판독용 스레드. 방향 4개 × 앞·뒷면 2장을 한꺼번에 처리할 만큼 둔다
_POOL = ThreadPoolExecutor(max_workers=min(8, max(2, (os.cpu_count() or 2))), thread_name_prefix="ocr")


def _box_size(box) -> float:
    xs, ys = [p[0] for p in box], [p[1] for p in box]
    return float(max(max(xs) - min(xs), max(ys) - min(ys)))


def _outline_size(crop_bgr: np.ndarray) -> float:
    """글자 상자가 이 크기 이상이면 알약 윤곽을 읽은 것으로 본다 (알약 짧은 변의 OUTLINE_FRAC배)."""
    ys, xs = np.nonzero(np.any(crop_bgr != BG_FILL, axis=2))
    if not len(ys):
        return float("inf")
    return OUTLINE_FRAC * min(ys.max() - ys.min() + 1, xs.max() - xs.min() + 1)