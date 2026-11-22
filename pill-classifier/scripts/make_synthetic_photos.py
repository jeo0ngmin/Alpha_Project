"""식약처 참조 사진에서 알약만 오려 폰 사진 비슷하게 합성한다 (eval_photos.py의 동작 확인용).

    python scripts/make_synthetic_photos.py --items scripts/heldout_items.txt --n 200

어두운 천 배경, 그림자, 흐림, 색온도 변화, 회전, JPEG 압축을 흉내 낸다. 그래도 알약 자체는
스튜디오 조명으로 찍힌 것이라 **실제 폰 사진의 대체물이 아니다**. 서비스 경로(화이트밸런스 →
배경 분리 분할)가 돌아가는지 확인하고, 실제 사진을 모으기 전까지의 기준선으로만 쓴다.
파일 이름은 eval_photos.py 규칙(<품목기준코드>_1.jpg, <품목기준코드>_1_back.jpg)을 따른다.
"""
from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.imaging import preprocess, read_image, segment_reference  # noqa: E402

# 어두운 천·종이 배경 (BGR). 조금씩 색이 돌아 화이트밸런스가 시험된다
BACKGROUNDS = [(38, 36, 34), (52, 40, 30), (30, 34, 48), (45, 45, 45), (28, 30, 26), (60, 55, 50)]


def composite(img, region, rng: random.Random, size: int = 900) -> np.ndarray:
    """알약 하나를 어두운 배경 위에 그림자와 함께 놓는다."""
    x, y, w, h = region.bbox
    pill = img[y:y + h, x:x + w]
    # 윤곽 바로 바깥의 배경(연한 파랑)이 테두리로 묻어 나오지 않도록 마스크를 조금 깎는다
    k = max(3, int(min(w, h) * 0.03)) | 1
    mask = cv2.erode(region.mask[y:y + h, x:x + w], cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    # 폰 사진에서 알약이 차지하는 크기: 긴 변이 사진의 25~45%
    scale = size * rng.uniform(0.25, 0.45) / max(w, h)
    pill = cv2.resize(pill, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    mask = cv2.resize(mask, (pill.shape[1], pill.shape[0]), interpolation=cv2.INTER_NEAREST)
    angle = rng.uniform(0, 360)
    M = cv2.getRotationMatrix2D((pill.shape[1] / 2, pill.shape[0] / 2), angle, 1.0)
    side = int(np.hypot(*pill.shape[:2])) + 4
    M[:, 2] += (side - pill.shape[1]) / 2, (side - pill.shape[0]) / 2
    pill = cv2.warpAffine(pill, M, (side, side), flags=cv2.INTER_LINEAR)
    mask = cv2.warpAffine(mask, M, (side, side), flags=cv2.INTER_NEAREST)

    bg = np.array(rng.choice(BACKGROUNDS), np.float32)
    canvas = np.empty((size, size, 3), np.float32)
    canvas[:] = bg
    canvas += rng.uniform(-6, 6) * np.linspace(-1, 1, size)[None, :, None]  # 조명 기울기
    canvas += np.random.default_rng(rng.randrange(1 << 30)).normal(0, 3, canvas.shape)  # 천 질감
    ox, oy = (size - side) // 2 + rng.randint(-size // 8, size // 8), (size - side) // 2 + rng.randint(-size // 8, size // 8)
    ox, oy = int(np.clip(ox, 0, size - side)), int(np.clip(oy, 0, size - side))

    soft = cv2.GaussianBlur(mask.astype(np.float32) / 255, (0, 0), 1.0)[..., None]
    shadow = cv2.GaussianBlur(mask.astype(np.float32) / 255, (0, 0), 12)[..., None]
    dx, dy = rng.randint(6, 18), rng.randint(6, 18)
    region_s = canvas[oy + dy:oy + dy + side, ox + dx:ox + dx + side]
    s = shadow[:region_s.shape[0], :region_s.shape[1]]
    region_s *= 1 - 0.45 * s
    region_c = canvas[oy:oy + side, ox:ox + side]
    region_c[:] = region_c * (1 - soft) + pill.astype(np.float32) * soft

    gain = np.array([rng.uniform(0.9, 1.1), 1.0, rng.uniform(0.9, 1.15)], np.float32)  # 색온도
    canvas = np.clip(canvas * gain * rng.uniform(0.8, 1.1), 0, 255)
    canvas = cv2.GaussianBlur(canvas, (0, 0), rng.uniform(0.3, 1.4))  # 초점·손떨림
    return canvas.astype(np.uint8)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--images-dir", default=str(ROOT / "data" / "images"))
    ap.add_argument("--items", help="쓸 품목기준코드 목록 파일 (없으면 무작위)")
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default=str(ROOT / "data" / "synthetic_photos"))
    args = ap.parse_args()

    rng = random.Random(args.seed)
    if args.items:
        seqs = [s for s in Path(args.items).read_text(encoding="utf-8").split()
                if (Path(args.images_dir) / f"{s}.jpg").exists()]
    else:
        seqs = sorted(p.stem for p in Path(args.images_dir).glob("*.jpg"))
        rng.shuffle(seqs)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    made = 0
    for seq in seqs:
        if made >= args.n:
            break
        img, _ = preprocess(read_image(Path(args.images_dir) / f"{seq}.jpg"), max_side=1200, white_balance=False)
        regions = segment_reference(img)
        if not regions:
            continue
        for region, suffix in zip(regions, ("", "_back")):
            photo = composite(img, region, rng)
            cv2.imencode(".jpg", photo, [cv2.IMWRITE_JPEG_QUALITY, rng.randint(70, 92)])[1].tofile(
                str(out / f"{seq}_1{suffix}.jpg"))
        made += 1
    print(f"합성 사진 {made}품목 → {out}")


if __name__ == "__main__":
    main()
