"""식약처 서비스키 없이 전체 흐름을 시험하기 위한 데모 DB와 합성 알약 사진."""
from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np

from .db import PillDB
from .imaging import write_image

# 실제 의약품이 아닌 가짜 품목. 품목기준코드도 실제 체계와 겹치지 않게 DEMO로 시작한다.
DEMO_ITEMS = [
    {"ITEM_SEQ": "DEMO0001", "ITEM_NAME": "데모흰원형정", "DRUG_SHAPE": "원형", "COLOR_CLASS1": "하양",
     "PRINT_FRONT": "DW", "LINE_FRONT": "-", "FORM_CODE_NAME": "나정", "LENG_LONG": "8.0", "LENG_SHORT": "8.0"},
    {"ITEM_SEQ": "DEMO0002", "ITEM_NAME": "데모노랑원형정", "DRUG_SHAPE": "원형", "COLOR_CLASS1": "노랑",
     "PRINT_FRONT": "YH", "FORM_CODE_NAME": "필름코팅정", "LENG_LONG": "9.0", "LENG_SHORT": "9.0"},
    {"ITEM_SEQ": "DEMO0003", "ITEM_NAME": "데모빨강흰캡슐", "DRUG_SHAPE": "장방형", "COLOR_CLASS1": "빨강",
     "COLOR_CLASS2": "하양", "FORM_CODE_NAME": "경질캡슐제", "LENG_LONG": "18.0", "LENG_SHORT": "6.5"},
    {"ITEM_SEQ": "DEMO0004", "ITEM_NAME": "데모파랑타원정", "DRUG_SHAPE": "타원형", "COLOR_CLASS1": "파랑",
     "FORM_CODE_NAME": "필름코팅정", "LENG_LONG": "12.0", "LENG_SHORT": "6.0"},
    {"ITEM_SEQ": "DEMO0005", "ITEM_NAME": "데모분홍삼각정", "DRUG_SHAPE": "삼각형", "COLOR_CLASS1": "분홍",
     "FORM_CODE_NAME": "나정"},
    {"ITEM_SEQ": "DEMO0006", "ITEM_NAME": "데모흰장방정", "DRUG_SHAPE": "장방형", "COLOR_CLASS1": "하양",
     "PRINT_FRONT": "AB", "LINE_FRONT": "+", "FORM_CODE_NAME": "나정"},
    {"ITEM_SEQ": "DEMO0007", "ITEM_NAME": "데모주황육각정", "DRUG_SHAPE": "육각형", "COLOR_CLASS1": "주황",
     "FORM_CODE_NAME": "필름코팅정"},
]
for _it in DEMO_ITEMS:
    _it.setdefault("ENTP_NAME", "데모제약")
    _it.setdefault("CLASS_NAME", "데모분류")
    _it.setdefault("ETC_OTC_NAME", "일반의약품")

BG = (45, 42, 40)  # 어두운 천 배경
COLORS = {"white": (235, 235, 232), "yellow": (60, 215, 238), "red": (45, 45, 200),
          "blue": (200, 95, 35), "pink": (180, 150, 245), "orange": (40, 140, 245)}


def _canvas(w=1200, h=900) -> np.ndarray:
    return np.full((h, w, 3), BG, np.uint8)


def _finish(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """센서 잡음을 더해 실제 사진처럼 만든다 (선명도 검사가 합성 이미지를 흐리다고 오판하지 않도록)."""
    noise = rng.normal(0, 3.0, img.shape)
    return np.clip(img.astype(np.float32) + noise, 0, 255).astype(np.uint8)


def _polygon(img, n, center, r, color, rot=-np.pi / 2):
    cx, cy = center
    pts = [(cx + r * np.cos(rot + 2 * np.pi * k / n), cy + r * np.sin(rot + 2 * np.pi * k / n)) for k in range(n)]
    cv2.fillPoly(img, [np.round(pts).astype(np.int32)], color)


def _capsule(img, center, length, width, left, right):
    cx, cy = center
    half, r = length // 2 - width // 2, width // 2
    cv2.rectangle(img, (cx - half, cy - r), (cx, cy + r), left, -1)
    cv2.circle(img, (cx - half, cy), r, left, -1)
    cv2.rectangle(img, (cx, cy - r), (cx + half, cy + r), right, -1)
    cv2.circle(img, (cx + half, cy), r, right, -1)


def demo_photos() -> dict[str, np.ndarray]:
    rng = np.random.default_rng(0)
    photos = {}

    img = _canvas()
    cv2.circle(img, (600, 450), 170, COLORS["yellow"], -1)
    photos["yellow_round.jpg"] = _finish(img, rng)

    img = _canvas()
    _capsule(img, (600, 450), 520, 180, COLORS["red"], COLORS["white"])
    photos["red_white_capsule.jpg"] = _finish(img, rng)

    img = _canvas()
    cv2.ellipse(img, (600, 450), (260, 130), 20, 0, 360, COLORS["blue"], -1)
    photos["blue_oval.jpg"] = _finish(img, rng)

    img = _canvas()
    _polygon(img, 3, (600, 480), 200, COLORS["pink"])
    photos["pink_triangle.jpg"] = _finish(img, rng)

    img = _canvas()
    _polygon(img, 6, (600, 450), 190, COLORS["orange"])
    photos["orange_hexagon.jpg"] = _finish(img, rng)

    img = _canvas()
    cv2.circle(img, (380, 450), 150, COLORS["yellow"], -1)
    cv2.circle(img, (820, 450), 150, COLORS["white"], -1)
    photos["two_pills.jpg"] = _finish(img, rng)  # 알약 2개 → 다시 찍기 안내

    photos["no_pill.jpg"] = _finish(_canvas(), rng)  # 알약 없음 → 다시 찍기 안내

    img = _canvas()
    cv2.circle(img, (600, 450), 170, COLORS["yellow"], -1)
    photos["blurry.jpg"] = cv2.GaussianBlur(_finish(img, rng), (0, 0), 8)  # 업로드 품질 검사(422)용
    return photos


def build_demo(out_dir) -> tuple[Path, Path]:
    """out_dir/pills.db 와 out_dir/photos/*.jpg 를 만든다."""
    out = Path(out_dir)
    db_path = out / "pills.db"
    if db_path.exists():
        db_path.unlink()
    db = PillDB(db_path)
    db.upsert_many(DEMO_ITEMS)
    db.close()
    photos_dir = out / "photos"
    for name, img in demo_photos().items():
        write_image(photos_dir / name, img)
    return db_path, photos_dir
