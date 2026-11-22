"""식약처 참조 이미지(앞·뒷면이 좌우로 나란히 있는 사진)를 알약별 정규화 크롭으로 자른다.

동시에 고전 CV 속성 추정기(모양/색)를 DB 라벨과 비교해 일치율을 보고한다.
→ attributes.py의 임계값을 조정할 때 기준으로 쓴다.
"""
from __future__ import annotations

import argparse
import os
import sys
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from tqdm import tqdm

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.attributes import estimate_colors, estimate_shape, parse_colors  # noqa: E402
from pillid.data import write_manifest  # noqa: E402
from pillid.db import PillDB  # noqa: E402
from pillid.imaging import canonical_crop, preprocess, read_image, segment_reference, write_image  # noqa: E402


def process(task):
    seq, img_path, crops_dir, size = task
    try:
        img, _ = preprocess(read_image(img_path), max_side=1200, white_balance=False)
    except Exception as e:  # noqa: BLE001
        return seq, [], [], str(e)
    regions = segment_reference(img)  # [앞면, 뒷면]
    rows, preds = [], []
    for side, reg in zip(("front", "back"), regions):
        out = Path(crops_dir) / f"{seq}_{side}.png"
        write_image(out, canonical_crop(img, reg, size))
        rows.append((str(out), seq, side))
        preds.append((estimate_shape(reg.contour), estimate_colors(img, reg.mask)))
    return seq, rows, preds, None if regions else "알약 영역을 찾지 못함"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--images-dir", default=str(ROOT / "data" / "images"))
    ap.add_argument("--out-dir", default=str(ROOT / "data" / "reference"))
    ap.add_argument("--size", type=int, default=224)
    ap.add_argument("--limit", type=int, help="테스트용: 앞쪽 N개 품목만")
    ap.add_argument("--workers", type=int, default=os.cpu_count())
    args = ap.parse_args()

    db = PillDB(args.db)
    items = {it["item_seq"]: it for it in db.all_items()}
    db.close()
    crops_dir = Path(args.out_dir) / "crops"
    tasks = []
    for seq in items:
        p = Path(args.images_dir) / f"{seq}.jpg"
        if p.exists():
            tasks.append((seq, str(p), str(crops_dir), args.size))
    if args.limit:
        tasks = tasks[:args.limit]
    print(f"참조 이미지 {len(tasks):,}장 처리")

    all_rows, errors = [], 0
    shape_hit = shape_n = color_hit = color_n = 0
    shape_confusion: Counter = Counter()
    with ProcessPoolExecutor(args.workers) as ex:
        for seq, rows, preds, err in tqdm(ex.map(process, tasks, chunksize=16), total=len(tasks)):
            if err:
                errors += 1
            all_rows.extend(rows)
            it = items[seq]
            true_shape = (it.get("drug_shape") or "").strip()
            true_colors = parse_colors(it.get("color_class1"), it.get("color_class2"))
            for shape, colors in preds:
                if true_shape:
                    shape_n += 1
                    shape_hit += shape == true_shape
                    if shape != true_shape:
                        shape_confusion[(true_shape, shape)] += 1
                if true_colors and colors:
                    color_n += 1
                    color_hit += colors[0] in true_colors

    manifest = Path(args.out_dir) / "manifest.csv"
    write_manifest(manifest, all_rows)
    print(f"크롭 {len(all_rows):,}장 → {manifest} (실패 {errors:,}건)")
    if shape_n:
        print(f"모양 추정 일치율: {shape_hit / shape_n:.1%} ({shape_n:,}면)")
        print("  자주 틀리는 조합 (정답 → 추정):")
        for (t, p), c in shape_confusion.most_common(10):
            print(f"    {t} → {p}: {c:,}")
    if color_n:
        print(f"주색상 추정 일치율: {color_hit / color_n:.1%} ({color_n:,}면)")


if __name__ == "__main__":
    main()
