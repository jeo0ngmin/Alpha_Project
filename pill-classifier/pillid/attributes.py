"""학습 없이 쓰는 고전 CV 기반 속성 추정: 모양, 색상.

라벨 체계는 식약처 낱알식별 DB(DRUG_SHAPE, COLOR_CLASS1/2)와 동일하게 맞춘다.
모양 임계값과 색 기준표는 식약처 참조 사진과 DB 라벨로 맞췄다 (scripts/build_color_refs.py,
scripts/prepare_reference.py가 일치율을 출력한다).
"""
from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path

import cv2
import numpy as np

SHAPES = ["원형", "타원형", "장방형", "반원형", "삼각형", "사각형", "마름모형",
          "오각형", "육각형", "팔각형", "기타"]
COLORS = ["하양", "노랑", "주황", "분홍", "빨강", "갈색", "연두", "초록", "청록",
          "파랑", "남색", "자주", "보라", "회색", "검정", "투명"]

CORNER_SHAPES = {3: "삼각형", 4: "사각형", 5: "오각형", 6: "육각형", 7: "팔각형", 8: "팔각형"}
ELONGATED_SHAPES = {"타원형", "장방형", "반원형", "마름모형"}
# 꼭짓점 수별로, 원형이 아니라 다각형으로 보려면 넘어야 하는 윤곽 반지름의 변동계수.
# 원형 알약도 그림자 때문에 꼭짓점이 3~5개 잡히지만 변동계수는 0.02 이하다.
CORNER_MIN_CV = {3: 0.05, 4: 0.03, 5: 0.03, 6: 0.02, 7: 0.012, 8: 0.012}
# 주축 정렬 박스 대비 면적비가 이 이상이면 장방형, 아니면 타원형
OBLONG_RECT_RATIO = 0.8675


# ── 모양 ──────────────────────────────────────────────────────────────────

def radial_profile(pts: np.ndarray, center: np.ndarray, bins: int = 360, smooth: int = 9) -> np.ndarray:
    """무게중심에서 윤곽선까지의 거리를 각도별로 구한다. 꼭짓점은 극대점이 된다."""
    d = pts - center
    ang = (np.floor(np.degrees(np.arctan2(d[:, 1], d[:, 0])) % 360 * bins / 360)).astype(int) % bins
    r = np.hypot(d[:, 0], d[:, 1])
    prof = np.full(bins, np.nan)
    np.fmax.at(prof, ang, r)
    valid = ~np.isnan(prof)
    idx = np.arange(bins)
    prof = np.interp(idx, idx[valid], prof[valid], period=bins)
    if smooth > 1:
        k = np.ones(smooth) / smooth
        padded = np.concatenate([prof[-smooth:], prof, prof[:smooth]])
        prof = np.convolve(padded, k, mode="same")[smooth:-smooth]
    return prof


def count_corners(prof: np.ndarray, min_sep: int = 18, rel_prom: float = 0.015) -> int:
    """원형 프로파일에서 주변보다 충분히 튀어나온 극대점(꼭짓점) 수를 센다."""
    n = len(prof)
    mean = prof.mean()
    ext = np.concatenate([prof[-min_sep:], prof, prof[:min_sep]])
    peaks: list[int] = []
    for i in range(n):
        win = ext[i:i + 2 * min_sep + 1]  # prof[i-min_sep .. i+min_sep]
        if prof[i] >= win.max() and prof[i] - win.min() > rel_prom * mean:
            if peaks and i - peaks[-1] <= min_sep:
                continue
            peaks.append(i)
    if len(peaks) > 1 and peaks[0] + n - peaks[-1] <= min_sep:
        peaks.pop()
    return len(peaks)


def shape_features(contour: np.ndarray) -> dict | None:
    pts = contour.reshape(-1, 2).astype(np.float64)
    m = cv2.moments(contour)
    if m["m00"] <= 0 or len(pts) < 8:
        return None
    center = np.array([m["m10"] / m["m00"], m["m01"] / m["m00"]])
    theta = 0.5 * np.arctan2(2 * m["mu11"], m["mu20"] - m["mu02"])
    major = np.array([np.cos(theta), np.sin(theta)])
    minor = np.array([-np.sin(theta), np.cos(theta)])
    d = pts - center
    pm, pn = d @ major, d @ minor
    ext_major, ext_minor = np.ptp(pm), np.ptp(pn)
    if ext_major < ext_minor:
        ext_major, ext_minor, pm, pn = ext_minor, ext_major, pn, pm
    ext_minor = max(ext_minor, 1e-6)

    prof = radial_profile(pts, center)
    return {
        "aspect": ext_major / ext_minor,
        # 주축 정렬 박스 대비 면적비: 타원 0.785, 장방형(캡슐형) ~0.9, 마름모 0.5
        "rect_ratio": m["m00"] / (ext_major * ext_minor),
        # 단축 방향 비대칭도: 반원형 ~0.076, 대칭 도형 ~0
        "offset": abs(pn.max() + pn.min()) / 2 / ext_minor,
        "cv": prof.std() / prof.mean(),
        "corners": count_corners(prof),
    }


def estimate_shape(contour: np.ndarray) -> str:
    """임계값은 식약처 참조 사진 2,000품목으로 맞추고 다른 1,000품목으로 확인했다 (2026-10)."""
    f = shape_features(contour)
    if f is None:
        return "기타"
    n = f["corners"]
    if f["aspect"] >= 1.2:
        if n == 4 and f["rect_ratio"] < 0.72:
            return "마름모형"
        if n == 4 and f["aspect"] < 1.6 and f["rect_ratio"] >= 0.80:
            return "팔각형"  # 모서리를 깎은 직사각형
        if n in (5, 6, 7, 8) and f["rect_ratio"] < 0.85:
            return CORNER_SHAPES[n]
        if n == 2 and f["offset"] > 0.07 and f["rect_ratio"] >= 0.775:
            return "반원형"  # DB에 3품목뿐이라 아주 뚜렷할 때만
        if (n == 3 or f["offset"] > 0.04) and f["rect_ratio"] < 0.80:
            return "삼각형"
        return "장방형" if f["rect_ratio"] >= OBLONG_RECT_RATIO else "타원형"
    if n in CORNER_SHAPES and f["cv"] >= CORNER_MIN_CV[n]:
        return CORNER_SHAPES[n]
    # 길쭉하지 않고 꼭짓점도 뚜렷하지 않으면 원형. '기타'는 윤곽만으로 가려낼 수 없어 추정하지 않는다.
    return "원형"


# ── 색상 ──────────────────────────────────────────────────────────────────

COLOR_REFS_PATH = Path(__file__).with_name("color_refs.npz")
COLOR_KNN = 5
# 밝기(L)는 노출·조명에 따라 크게 흔들리므로 거의 보지 않고 색조·채도로 정한다. 0.75 → 0.15로 낮췄을 때
# 참조 사진은 그대로(88%), AI Hub 실제 사진 25% → 31%, NIH 폰 사진 34% → 41% (2026-10).
COLOR_L_WEIGHT = 0.15
COLOR_MAX_DIST = 12.0   # 가장 가까운 기준색이 이보다 멀면 기준표에 없는 색으로 보고 규칙으로 정한다


def to_lab(bgr) -> np.ndarray:
    """BGR(0~255) → Lab (L 0~100, a·b 약 -127~127)."""
    px = np.clip(np.asarray(bgr, np.float32), 0, 255).reshape(1, 1, 3) / 255
    return cv2.cvtColor(px, cv2.COLOR_BGR2LAB)[0, 0]


@lru_cache(maxsize=1)
def _color_refs():
    """식약처 참조 사진에서 뽑은 (Lab 색, 색 이름 번호) 기준표. 파일이 없으면 None."""
    if not COLOR_REFS_PATH.exists():
        return None
    with np.load(COLOR_REFS_PATH) as f:
        return f["lab"].astype(np.float32) * (COLOR_L_WEIGHT, 1, 1), f["labels"].astype(np.int64)


def name_color(bgr) -> str:
    """BGR 색 하나를 식약처 색상 분류명으로 매핑한다.

    식약처 사진에서 같은 색으로 분류된 알약들의 실제 색(기준표)과 비교해 가까운 것들의 다수결로 정한다.
    식약처의 색 이름은 눈으로 보는 색상환과 다르다 (살구색은 '분홍', 밝은 초록은 '연두').
    기준표에 가까운 색이 없으면 색상·채도·명도 규칙을 쓴다.
    """
    refs = _color_refs()
    if refs is not None:
        lab, labels = refs
        dist = np.linalg.norm(lab - to_lab(bgr) * (COLOR_L_WEIGHT, 1, 1), axis=1)
        near = np.argpartition(dist, COLOR_KNN)[:COLOR_KNN]
        if dist[near].min() <= COLOR_MAX_DIST:
            votes = np.bincount(labels[near], weights=1 / (dist[near] + 2), minlength=len(COLORS))
            return COLORS[int(votes.argmax())]
    return name_color_by_rule(bgr)


def name_color_by_rule(bgr) -> str:
    px = np.clip(np.asarray(bgr, np.float32), 0, 255).reshape(1, 1, 3) / 255
    h, s, v = (float(x) for x in cv2.cvtColor(px, cv2.COLOR_BGR2HSV)[0, 0])  # h: 0~360

    if v < 0.22:
        return "검정"
    if s < 0.10:
        return "하양" if v > 0.75 else "회색"
    if h < 25 or h >= 340:
        if s < 0.55 and v > 0.7:
            return "분홍"
        if h < 10 or h >= 340:
            return "갈색" if v < 0.5 else "빨강"
        return "갈색" if v < 0.65 else "주황"
    if h < 40:
        return "갈색" if v < 0.6 else "주황"
    if h < 68:
        return "노랑"
    if h < 165:
        return "연두" if (v >= 0.7 and h < 130) else "초록"
    if h < 185:
        return "청록"
    if h < 250:
        return "남색" if v < 0.5 else "파랑"
    if h < 290:
        return "보라"
    return "분홍" if (s < 0.5 and v > 0.7) else "자주"


def color_samples(img: np.ndarray, mask: np.ndarray, max_samples: int = 5000):
    """알약 안쪽 픽셀 표본 (BGR 값, (x, y) 위치, 알약 면적). 알약이 없으면 None."""
    area = cv2.countNonZero(mask)
    if area == 0:
        return None
    k = max(3, int(np.sqrt(area) * 0.08)) | 1
    inner = cv2.erode(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))  # 테두리 그림자 제외
    if cv2.countNonZero(inner) < 100:
        inner = mask
    ys, xs = np.nonzero(inner)
    if len(ys) > max_samples:
        sel = np.random.default_rng(0).choice(len(ys), max_samples, replace=False)
        ys, xs = ys[sel], xs[sel]
    return img[ys, xs], np.stack([xs, ys], axis=1), area


def estimate_colors(img: np.ndarray, mask: np.ndarray, max_colors: int = 2) -> list[str]:
    """알약 영역의 대표 색 1~2개. 두 색이 공간적으로 나뉘어 있을 때만(캡슐 등) 2색으로 본다."""
    samples = color_samples(img, mask)
    return colors_from_samples(*samples, max_colors=max_colors) if samples else []


def colors_from_samples(px: np.ndarray, pos: np.ndarray, area: float, max_colors: int = 2) -> list[str]:
    xs, ys = pos[:, 0], pos[:, 1]
    if max_colors >= 2 and len(px) >= 200:
        lab = cv2.cvtColor(px.reshape(-1, 1, 3), cv2.COLOR_BGR2LAB).reshape(-1, 3).astype(np.float32)
        crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0)
        _, labels, centers = cv2.kmeans(lab, 2, None, crit, 3, cv2.KMEANS_PP_CENTERS)
        labels = labels.ravel()
        fracs = np.bincount(labels, minlength=2) / len(labels)
        if fracs.min() >= 0.25 and np.linalg.norm(centers[0] - centers[1]) >= 30:
            pos = np.stack([xs, ys], axis=1).astype(np.float32)
            sep = np.linalg.norm(pos[labels == 0].mean(0) - pos[labels == 1].mean(0))
            # 각인 음영은 알약 중앙에 흩어져 있어 두 군집의 중심이 가깝다 → 2색으로 보지 않음
            if sep >= 0.25 * np.sqrt(area):
                names = [name_color(np.median(px[labels == j], axis=0)) for j in np.argsort(-fracs)]
                if names[0] != names[1]:
                    return names
    return [name_color(np.median(px, axis=0))]


def parse_colors(*values) -> list[str]:
    """DB 색상 문자열("하양, 노랑" 등)을 목록으로 분해한다."""
    out: list[str] = []
    for v in values:
        for c in re.split(r"[,/|·\s]+", str(v or "")):
            if c and c not in out:
                out.append(c)
    return out


# ── 유사도 ────────────────────────────────────────────────────────────────

_SHAPE_SIMILAR = {
    frozenset(("타원형", "장방형")): 0.6,
    frozenset(("사각형", "마름모형")): 0.6,
    frozenset(("원형", "팔각형")): 0.5,
    frozenset(("육각형", "팔각형")): 0.4,
    frozenset(("오각형", "육각형")): 0.4,
    frozenset(("원형", "육각형")): 0.3,
    frozenset(("사각형", "장방형")): 0.3,
    frozenset(("반원형", "타원형")): 0.3,
    frozenset(("원형", "오각형")): 0.2,
    frozenset(("원형", "타원형")): 0.2,
    frozenset(("삼각형", "반원형")): 0.2,
}

_COLOR_SIMILAR = {
    frozenset(pair): s for pair, s in {
        ("하양", "회색"): 0.5, ("하양", "투명"): 0.5, ("하양", "노랑"): 0.3,
        ("회색", "투명"): 0.4, ("회색", "검정"): 0.3,
        ("노랑", "주황"): 0.5, ("노랑", "연두"): 0.4,
        ("주황", "갈색"): 0.5, ("주황", "빨강"): 0.4, ("주황", "분홍"): 0.5,
        ("분홍", "빨강"): 0.5, ("분홍", "자주"): 0.5,
        ("빨강", "갈색"): 0.4, ("빨강", "자주"): 0.3,
        ("연두", "초록"): 0.5, ("초록", "청록"): 0.5, ("청록", "파랑"): 0.5,
        ("파랑", "남색"): 0.6, ("남색", "보라"): 0.4, ("보라", "자주"): 0.5,
        ("갈색", "검정"): 0.3,
    }.items()
}


def shape_similarity(a: str, b: str) -> float:
    if not a or not b:
        return 0.5  # 정보 없음 → 중립
    if a == b:
        return 1.0
    if "기타" in (a, b):
        return 0.3
    return _SHAPE_SIMILAR.get(frozenset((a, b)), 0.0)


def color_similarity(a: str, b: str) -> float:
    if a == b:
        return 1.0
    return _COLOR_SIMILAR.get(frozenset((a, b)), 0.0)


def color_set_similarity(observed, item_colors) -> float:
    """관측된 각 색이 DB 색 중 하나와 얼마나 맞는지의 평균."""
    if not observed or not item_colors:
        return 0.5
    return float(np.mean([max(color_similarity(o, c) for c in item_colors) for o in observed]))
