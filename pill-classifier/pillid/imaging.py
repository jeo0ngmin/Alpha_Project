"""이미지 입출력, 배경 분리, 알약 영역 분할, 정규화 크롭."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

# 크롭 배경을 통일하는 회색. 참조 이미지(식약처)와 폰 사진의 배경 차이를 없애 도메인 격차를 줄인다.
BG_FILL = (127, 127, 127)
# 식약처 참조 사진에서 배경과 알약을 가르는 Lab 색차
REFERENCE_THRESHOLD = 12.0


@dataclass
class PillRegion:
    contour: np.ndarray  # (N, 1, 2) int32, 이미지 좌표
    mask: np.ndarray     # uint8 0/255, 이미지 전체 크기
    bbox: tuple[int, int, int, int]  # x, y, w, h

    @property
    def area(self) -> float:
        return float(cv2.contourArea(self.contour))


# ── 입출력 ────────────────────────────────────────────────────────────────

def read_image(path) -> np.ndarray:
    # cv2.imread는 Windows의 한글 경로를 읽지 못하므로 imdecode를 사용한다.
    data = np.fromfile(str(path), dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError(f"이미지를 읽을 수 없습니다: {path}")
    return img


def write_image(path, img) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    ok, buf = cv2.imencode(path.suffix or ".png", img)
    if not ok:
        raise ValueError(f"이미지를 저장할 수 없습니다: {path}")
    buf.tofile(str(path))


# ── 전처리 ────────────────────────────────────────────────────────────────

def border_pixels(img: np.ndarray, frac: float = 0.03) -> np.ndarray:
    h, w = img.shape[:2]
    b = max(2, int(min(h, w) * frac))
    c = img.shape[2]
    return np.concatenate([
        img[:b].reshape(-1, c), img[-b:].reshape(-1, c),
        img[:, :b].reshape(-1, c), img[:, -b:].reshape(-1, c),
    ])


def balance_by_background(img: np.ndarray, max_chroma: float = 0.25,
                          min_brightness: float = 80.0) -> np.ndarray:
    """무채색 배경이라고 가정하고 배경이 회색이 되도록 색온도를 보정한다.

    배경이 어둡거나 유채색이면 보정하지 않는다 (잘못 보정하면 알약 색이 틀어진다).
    어두운 배경은 색 기운이 조금만 있어도 보정 배율이 커진다. 예: 배경 (45, 42, 40)이면
    흰 알약 (235, 235, 232)가 미색 (221, 237, 245)로 바뀌어 '노랑'으로 분류된다.
    """
    bg = np.median(border_pixels(img), axis=0).astype(np.float32)
    if bg.max() < min_brightness or (bg.max() - bg.min()) / bg.max() > max_chroma:
        return img
    gain = bg.mean() / np.maximum(bg, 1.0)
    return np.clip(img.astype(np.float32) * gain, 0, 255).astype(np.uint8)


def preprocess(img: np.ndarray, max_side: int | None = 1600,
               white_balance: bool = True) -> tuple[np.ndarray, float]:
    """크기 제한 + 배경 기준 화이트밸런스. (보정된 이미지, 축소 배율)을 반환한다.

    white_balance: 폰 사진의 조명 색을 배경(무채색 가정)으로 보정한다. 식약처 참조 사진처럼
    배경이 일부러 유채색(연한 파랑)이고 이미 색이 맞춰진 사진에는 끈다. 켜면 알약 색이 틀어진다.
    """
    scale = 1.0
    if max_side:
        h, w = img.shape[:2]
        if max(h, w) > max_side:
            scale = max_side / max(h, w)
            img = cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    return (balance_by_background(img) if white_balance else img), scale


# ── 분할 ──────────────────────────────────────────────────────────────────

def _region_from_contour(contour: np.ndarray, shape: tuple[int, int]) -> PillRegion:
    mask = np.zeros(shape, np.uint8)
    cv2.drawContours(mask, [contour], -1, 255, thickness=cv2.FILLED)
    x, y, w, h = cv2.boundingRect(contour)
    return PillRegion(contour=contour, mask=mask, bbox=(x, y, w, h))


def background_model(lab: np.ndarray, iters: int = 3, fit_width: int = 96) -> np.ndarray:
    """배경색을 위치에 따라 완만하게 변하는 곡면(2차 다항식)으로 추정한다.

    폰 사진은 조명 기울기와 비네팅 때문에 배경이 한 가지 색이 아니다. 가장자리 색 하나로 비교하면
    밝기가 변한 배경 영역이 알약으로 잡힌다. 가장자리 픽셀로 먼저 맞추고, 잔차가 작은 픽셀(배경)만
    남겨 두 번 더 맞춘다. 알약은 잔차가 커서 빠진다. 입력 해상도의 배경 추정값을 돌려준다.
    """
    h, w = lab.shape[:2]
    scale = fit_width / max(w, 1)
    small = cv2.resize(lab, (max(8, int(w * scale)), max(8, int(h * scale))), interpolation=cv2.INTER_AREA)
    hs, ws = small.shape[:2]
    ys, xs = np.mgrid[0:hs, 0:ws]
    x, y = xs.ravel() / max(ws - 1, 1) * 2 - 1, ys.ravel() / max(hs - 1, 1) * 2 - 1
    A = np.stack([np.ones_like(x), x, y, x * x, x * y, y * y], axis=1).astype(np.float32)
    pix = small.reshape(-1, 3).astype(np.float32)
    b = max(1, int(min(hs, ws) * 0.08))
    inlier = np.zeros((hs, ws), bool)
    inlier[:b], inlier[-b:], inlier[:, :b], inlier[:, -b:] = True, True, True, True
    inlier = inlier.ravel()
    coef = None
    for _ in range(iters):
        coef, *_ = np.linalg.lstsq(A[inlier], pix[inlier], rcond=None)
        resid = np.linalg.norm((pix - A @ coef) * (0.6, 1, 1), axis=1)
        noise = np.median(resid[inlier])
        inlier = resid < max(3 * noise, 6.0)
        if inlier.sum() < 6:
            break
    # 가장자리에 다른 물체(책상 모서리 등)가 있어도 곡면이 배경색 범위를 벗어나 튀지 않게 한다
    lo, hi = np.percentile(pix[inlier], 1, axis=0), np.percentile(pix[inlier], 99, axis=0)
    bg_small = np.clip(A @ coef, lo, hi).reshape(hs, ws, 3)
    return cv2.resize(bg_small, (w, h), interpolation=cv2.INTER_LINEAR)


def _pill_like(contour) -> bool:
    """알약다운 덩어리인지: 볼록하고(속이 찬 윤곽) 지나치게 길쭉하지 않다. 조명 얼룩·천 주름은 걸러진다."""
    area = cv2.contourArea(contour)
    hull_area = cv2.contourArea(cv2.convexHull(contour))
    if hull_area <= 0 or area / hull_area < 0.85:
        return False
    (_, _), (rw, rh), _ = cv2.minAreaRect(contour)
    return min(rw, rh) > 0 and max(rw, rh) / min(rw, rh) <= 5.0


def segment_pills(img: np.ndarray, min_area_frac: float = 0.0005, max_pills: int = 20,
                  min_dist: float = 15.0, threshold: float | None = None) -> list[PillRegion]:
    """단색 배경 위의 알약들을 분할한다. 면적이 큰 순서로 반환.

    배경색을 완만한 곡면으로 추정하고(background_model, 폰 사진), Lab 공간에서 배경과의 색차를 Otsu로 이진화한
    뒤 알약답지 않은 덩어리(볼록하지 않거나 너무 길쭉한 것)를 버린다.
    배경과 알약 색이 비슷하면(흰 알약 + 흰 종이) 실패하므로 어두운 단색 배경을 권장한다.

    threshold: Otsu 대신 쓸 고정 색차 기준. 배경이 고르고 그림자가 옅은 스튜디오 사진용이다.
    Otsu는 알약 안에서 색차가 크게 갈리면(흰 알약의 그림자, 두 색 캡슐) 알약의 일부만 잡는다.
    조명이 고르지 않은 폰 사진에 쓰면 그림자까지 알약으로 잡힌다.
    """
    h, w = img.shape[:2]
    lab = cv2.cvtColor(cv2.GaussianBlur(img, (5, 5), 0), cv2.COLOR_BGR2LAB).astype(np.float32)
    # 고정 기준을 쓰는 스튜디오 사진은 배경이 고르므로 가장자리 중앙값 하나로 충분하다
    bg = np.median(border_pixels(lab), axis=0) if threshold is not None else background_model(lab)
    diff = lab - bg
    if threshold is None:
        # 배경과 색조가 같으면서 더 어두운 픽셀은 알약이 아니라 그림자일 가능성이 크다. 어두운 쪽 명도 차는
        # 조금만 세고, 밝은 쪽(흰 알약)은 거의 그대로 센다. 권장 배경(어두운 천)에서는 알약이 늘 더 밝다.
        diff[..., 0] *= np.where(diff[..., 0] < 0, 0.25, 0.8)
    else:
        diff[..., 0] *= 0.6  # 명도 차 가중치를 낮춰 그림자가 알약으로 잡히는 것을 줄인다
    dist = np.sqrt((diff ** 2).sum(axis=2))
    dmax = float(dist.max())
    if dmax < min_dist:
        return []

    if threshold is None:
        norm = (dist / dmax * 255).astype(np.uint8)
        t, _ = cv2.threshold(norm, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        thr = max(t / 255 * dmax, min_dist)
    else:
        thr = threshold
    mask = (dist > thr).astype(np.uint8) * 255

    k = max(3, int(min(h, w) * 0.006)) | 1
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=2)

    # CHAIN_APPROX_NONE: 모양 추정(방사 프로파일)에 윤곽선의 모든 점이 필요하다
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    min_area, max_area = min_area_frac * h * w, 0.9 * h * w
    contours = [c for c in contours if min_area <= cv2.contourArea(c) <= max_area]
    if threshold is None:  # 고정 기준(참조 사진)에서는 일부만 잡힌 알약도 살려 두고 호출한 쪽이 볼록 껍질로 메운다
        contours = [c for c in contours if _pill_like(c)]
    contours.sort(key=cv2.contourArea, reverse=True)
    return [_region_from_contour(c, (h, w)) for c in contours[:max_pills]]


def segment_reference(img: np.ndarray, threshold: float = REFERENCE_THRESHOLD) -> list[PillRegion]:
    """식약처 참조 사진(연한 단색 배경에 앞·뒷면이 좌우로 나란히)에서 [앞면, 뒷면]을 찾는다.

    사진 가장자리에 닿은 덩어리(하단 띠 광고 등)와 눈금·로고 같은 작은 덩어리는 버린다.
    흰 알약은 밝은 쪽 면이 배경과 비슷해 안쪽이 파여 잡히므로 볼록 껍질로 메운다 (알약 윤곽은 볼록하다).
    """
    h, w = img.shape[:2]
    regions = []
    for r in segment_pills(img, min_area_frac=0.01, max_pills=6, threshold=threshold):
        x, y, bw, bh = r.bbox
        if x > 0 and y > 0 and x + bw < w and y + bh < h:
            regions.append(_convex(r, (h, w)))
    regions.sort(key=lambda r: r.area, reverse=True)
    regions = [r for r in regions[:2] if r.area >= 0.3 * regions[0].area]
    return sorted(regions, key=lambda r: r.bbox[0])


def _convex(region: PillRegion, shape: tuple[int, int]) -> PillRegion:
    mask = np.zeros(shape, np.uint8)
    cv2.fillConvexPoly(mask, cv2.convexHull(region.contour), 255)
    # 모양 추정에는 윤곽선의 모든 점이 필요하므로 꼭짓점만 있는 껍질 대신 마스크에서 다시 뽑는다
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    return _region_from_contour(max(contours, key=cv2.contourArea), shape)


def region_from_box(img: np.ndarray, xyxy, expand: float = 0.15) -> PillRegion:
    """탐지 박스 안에서 알약 윤곽을 분할한다. 실패하면 박스에 내접하는 타원을 쓴다."""
    h, w = img.shape[:2]
    x1, y1, x2, y2 = (float(v) for v in xyxy)
    bw, bh = x2 - x1, y2 - y1
    X1, Y1 = max(0, int(x1 - bw * expand)), max(0, int(y1 - bh * expand))
    X2, Y2 = min(w, int(np.ceil(x2 + bw * expand))), min(h, int(np.ceil(y2 + bh * expand)))
    sub = img[Y1:Y2, X1:X2]
    center = (float((x1 + x2) / 2 - X1), float((y1 + y2) / 2 - Y1))

    if sub.size and min(sub.shape[:2]) >= 8:
        for reg in segment_pills(sub, min_area_frac=0.05, max_pills=5):
            if cv2.pointPolygonTest(reg.contour, center, False) >= 0:
                contour = reg.contour + np.array([[[X1, Y1]]], dtype=reg.contour.dtype)
                return _region_from_contour(contour, (h, w))

    ellipse = cv2.ellipse2Poly((int((x1 + x2) / 2), int((y1 + y2) / 2)),
                               (max(1, int(bw / 2)), max(1, int(bh / 2))), 0, 0, 360, 2)
    return _region_from_contour(ellipse.reshape(-1, 1, 2).astype(np.int32), (h, w))


# ── 정규화 크롭 ──────────────────────────────────────────────────────────

def major_axis_angle(contour: np.ndarray) -> float:
    """이미지 좌표계(y 아래쪽)에서 장축의 각도(도)."""
    m = cv2.moments(contour)
    if m["m00"] == 0:
        return 0.0
    return float(np.degrees(0.5 * np.arctan2(2 * m["mu11"], m["mu20"] - m["mu02"])))


def canonical_crop(img: np.ndarray, region: PillRegion, size: int = 224,
                   margin: float = 0.12, rotate: bool = True) -> np.ndarray:
    """알약만 남기고 배경을 회색으로 채운 뒤, 장축을 수평으로 맞춘 정사각 크롭을 만든다."""
    x, y, w, h = region.bbox
    half = int(np.ceil(np.hypot(w, h) / 2 * (1 + margin))) + 2
    pad = half
    img_p = cv2.copyMakeBorder(img, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=BG_FILL)
    mask_p = cv2.copyMakeBorder(region.mask, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=0)
    cx, cy = int(round(x + w / 2)) + pad, int(round(y + h / 2)) + pad
    patch = img_p[cy - half:cy + half, cx - half:cx + half]
    mpatch = mask_p[cy - half:cy + half, cx - half:cx + half]

    if rotate:
        M = cv2.getRotationMatrix2D((half, half), major_axis_angle(region.contour), 1.0)
        patch = cv2.warpAffine(patch, M, (2 * half, 2 * half), flags=cv2.INTER_LINEAR,
                               borderValue=BG_FILL)
        mpatch = cv2.warpAffine(mpatch, M, (2 * half, 2 * half), flags=cv2.INTER_NEAREST,
                                borderValue=0)

    out = patch.copy()
    out[mpatch == 0] = BG_FILL
    ys, xs = np.nonzero(mpatch)
    if len(ys):
        out = out[ys.min():ys.max() + 1, xs.min():xs.max() + 1]

    side = int(max(out.shape[:2]) * (1 + 2 * margin)) + 1
    canvas = np.full((side, side, 3), BG_FILL, np.uint8)
    oy, ox = (side - out.shape[0]) // 2, (side - out.shape[1]) // 2
    canvas[oy:oy + out.shape[0], ox:ox + out.shape[1]] = out
    interp = cv2.INTER_AREA if side > size else cv2.INTER_CUBIC
    return cv2.resize(canvas, (size, size), interpolation=interp)
