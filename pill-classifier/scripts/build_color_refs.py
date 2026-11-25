"""식약처 참조 사진과 DB 색 라벨로 색 기준표(pillid/color_refs.npz)를 만든다.

    python scripts/build_color_refs.py --exclude scripts/heldout_items.txt

DB에 색이 하나만 적힌 품목의 참조 사진에서 알약의 대표색(Lab)을 뽑아 (색, 이름) 쌍으로 모은다.
pillid.attributes.name_color가 이 표에서 가까운 색들의 다수결로 색 이름을 정한다.

--exclude: 평가에 쓰는 품목(한 줄에 품목기준코드 하나)은 기준표에서 뺀다. 넣으면 평가할 때
자기 자신과 비교하게 되어 색 일치율이 실제보다 높게 나온다.

--aihub-base: AI Hub 경구약제 Validation 폴더(01.데이터/2.Validation)를 주면 실제 카메라 사진(전구색·주광색 조명,
검정·회색·파랑 배경)에서 뽑은 색도 기준표에 넣는다. 스튜디오 사진만으로는 어둡고 누런 조명의 사진에서
흰 알약을 갈색·노랑으로 읽는다. 사진은 zip에서 바로 읽는다(압축 해제 불필요).
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import zipfile
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from tqdm import tqdm

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.attributes import COLOR_REFS_PATH, COLORS, color_samples, parse_colors, to_lab  # noqa: E402
from pillid.db import PillDB  # noqa: E402
from pillid.imaging import preprocess, read_image, segment_pills, segment_reference  # noqa: E402

CODE_RE = re.compile(r"(K-\d+)")


def representative_colors(img_path: str) -> list[np.ndarray]:
    """참조 사진 속 앞·뒷면 각각의 대표색(Lab)."""
    try:
        img, _ = preprocess(read_image(img_path), max_side=1200, white_balance=False)
    except ValueError:
        return []
    out = []
    for region in segment_reference(img):
        px, _, _ = color_samples(img, region.mask)
        out.append(to_lab(np.median(px, axis=0)))
    return out


def aihub_photo_colors(base: Path, per_code: int, excluded: set[str], seed: int) -> list[tuple[np.ndarray, str]]:
    """AI Hub Validation 사진에서 서비스 경로(화이트밸런스 → 배경 분리 분할)로 뽑은 (대표색 Lab, 색 이름)."""
    import cv2

    members, zips = {}, {}
    for zp in sorted((base / "원천데이터/단일경구약제 5000종").glob("*.zip")):
        zips[zp] = zipfile.ZipFile(zp)
        for n in zips[zp].namelist():
            if n.lower().endswith(".png"):
                members[Path(n).name] = (zp, n)
    codes = {m.group(1) for m in map(CODE_RE.search, members) if m}
    meta = {}
    for zp in sorted((base / "라벨링데이터/단일경구약제 5000종").glob("*.zip")):
        with zipfile.ZipFile(zp) as z:
            for n in z.namelist():
                m = CODE_RE.search(n)
                if n.endswith(".json") and m and m.group(1) in codes:
                    info = json.loads(z.read(n))["images"][0]
                    if info["file_name"] in members:
                        meta[info["file_name"]] = info
    by_code: dict[str, list] = {}
    for name, info in meta.items():
        colors = parse_colors(info.get("color_class1"), info.get("color_class2"))
        if len(colors) == 1 and colors[0] in COLORS and str(info.get("item_seq") or "") not in excluded:
            by_code.setdefault(info["drug_N"], []).append((name, colors[0]))
    rng = random.Random(seed)
    out = []
    for code in sorted(by_code):
        names = by_code[code]
        rng.shuffle(names)
        for name, color in tqdm(names[:per_code], desc=code, leave=False):
            zp, member = members[name]
            img = cv2.imdecode(np.frombuffer(zips[zp].read(member), np.uint8), cv2.IMREAD_COLOR)
            img, _ = preprocess(img)
            regions = segment_pills(img)
            if regions:
                px, _, _ = color_samples(img, max(regions, key=lambda r: r.area).mask)
                out.append((to_lab(np.median(px, axis=0)), color))
    print(f"AI Hub 실제 사진 {len(by_code)}종에서 기준색 {len(out):,}개")
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--images-dir", default=str(ROOT / "data" / "images"))
    ap.add_argument("--exclude", help="기준표에서 뺄 품목기준코드 목록 파일")
    ap.add_argument("--n", type=int, default=8000, help="쓸 품목 수 (무작위 표본)")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default=str(COLOR_REFS_PATH))
    ap.add_argument("--workers", type=int, default=os.cpu_count())
    ap.add_argument("--aihub-base", help="AI Hub 경구약제 '01.데이터/2.Validation' 폴더 (실제 사진 색을 함께 넣음)")
    ap.add_argument("--aihub-per-code", type=int, default=60, help="AI Hub 약 하나당 쓸 사진 수")
    args = ap.parse_args()

    excluded = set(Path(args.exclude).read_text(encoding="utf-8").split()) if args.exclude else set()
    db = PillDB(args.db)
    single = {}  # 색이 하나만 적힌 품목 → 색 이름
    for it in db.all_items():
        colors = parse_colors(it.get("color_class1"), it.get("color_class2"))
        if len(colors) == 1 and colors[0] in COLORS and it["item_seq"] not in excluded:
            single[it["item_seq"]] = colors[0]
    db.close()

    have = sorted(seq for seq in single if (Path(args.images_dir) / f"{seq}.jpg").exists())
    sample = random.Random(args.seed).sample(have, min(args.n, len(have)))
    print(f"단색 품목 {len(have):,}개 중 {len(sample):,}개 사용 (제외 {len(excluded):,}개)")

    lab, labels = [], []
    paths = [str(Path(args.images_dir) / f"{seq}.jpg") for seq in sample]
    with ProcessPoolExecutor(args.workers) as ex:
        for seq, colors in tqdm(zip(sample, ex.map(representative_colors, paths, chunksize=16)), total=len(sample)):
            for c in colors:
                lab.append(c)
                labels.append(COLORS.index(single[seq]))

    source = [0] * len(lab)  # 0 = 식약처 참조 사진, 1 = AI Hub 실제 사진
    if args.aihub_base:
        for c, color in aihub_photo_colors(Path(args.aihub_base), args.aihub_per_code, excluded, args.seed):
            lab.append(c)
            labels.append(COLORS.index(color))
            source.append(1)
    np.savez_compressed(args.out, lab=np.array(lab, np.float32), labels=np.array(labels, np.uint8),
                        source=np.array(source, np.uint8))
    counts = Counter(COLORS[i] for i in labels)
    print(f"기준색 {len(lab):,}개 (실제 사진 {sum(source):,}개) → {args.out}")
    print("색별 개수:", ", ".join(f"{c} {n:,}" for c, n in counts.most_common()))


if __name__ == "__main__":
    main()
