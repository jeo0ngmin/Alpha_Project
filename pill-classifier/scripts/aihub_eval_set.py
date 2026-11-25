"""AI Hub 경구약제 Validation 사진에서 실제 사진 평가셋을 뽑는다 (eval_photos.py 입력).

    python scripts/aihub_eval_set.py
    python scripts/eval_photos.py data/aihub/eval_photos

압축을 풀지 않고 zip에서 바로 읽는다. 식약처 DB에 있는 약만 고르고, 약마다 조건(배경·조명·카메라 각도)이
다른 앞면/뒷면 쌍을 --pairs-per-item 개 뽑아 저장한다. labels.csv(front,back,item_seq)를 함께 쓴다.

사진 이름: K-000059_0_0_0_0_60_000_200.png
  K코드 _ 알약상태 _ 배경(0 검정, 1 흰색?) _ 면(0 앞, 1 뒤) _ 조명 _ 카메라 위도 _ 카메라 경도 _ 크기
  (정확한 뜻은 라벨 JSON의 drug_S, back_color, drug_dir, light_color, camera_la, camera_lo, size 참고)
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import re
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.db import PillDB  # noqa: E402

DEFAULT_BASE = Path(r"D:\aihub\166.약품식별 인공지능 개발을 위한 경구약제 이미지 데이터\01.데이터")
CODE_RE = re.compile(r"(K-\d+)")


def read_labels(label_zips, wanted_codes: set[str]) -> dict[str, dict]:
    """사진 파일 이름 → 라벨 JSON의 images[0] (필요한 K코드만)."""
    meta = {}
    for zp in label_zips:
        with zipfile.ZipFile(zp) as z:
            for n in z.namelist():
                m = CODE_RE.search(n)
                if n.endswith(".json") and m and m.group(1) in wanted_codes:
                    info = json.loads(z.read(n))["images"][0]
                    meta[info["file_name"]] = info
    return meta


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default=str(DEFAULT_BASE))
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--out", default=str(ROOT / "data" / "aihub" / "eval_photos"))
    ap.add_argument("--pairs-per-item", type=int, default=2)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    base = Path(args.base)
    image_zips = sorted((base / "2.Validation/원천데이터/단일경구약제 5000종").glob("*.zip"))
    label_zips = sorted((base / "2.Validation/라벨링데이터/단일경구약제 5000종").glob("*.zip"))
    if not image_zips or not label_zips:
        sys.exit(f"[실패] {base} 아래에서 Validation 사진/라벨 zip을 찾지 못했습니다.")

    members: dict[str, tuple[Path, str]] = {}  # 파일 이름 → (zip, 멤버 경로)
    for zp in image_zips:
        with zipfile.ZipFile(zp) as z:
            for n in z.namelist():
                if n.lower().endswith(".png"):
                    members[Path(n).name] = (zp, n)
    codes = {m.group(1) for m in map(CODE_RE.search, members) if m}
    print(f"사진 {len(members):,}장, K코드 {len(codes)}개", flush=True)

    meta = read_labels(label_zips, codes)
    db = PillDB(args.db)
    in_db = {it["item_seq"] for it in db.all_items()}
    db.close()

    # K코드 → 조건(배경, 조명, 위도, 경도, 크기) → {앞면: 파일, 뒷면: 파일}
    by_code: dict[str, dict[tuple, dict]] = defaultdict(lambda: defaultdict(dict))
    seq_of, name_of = {}, {}
    for name, info in meta.items():
        if name not in members or str(info.get("item_seq") or "") not in in_db:
            continue
        code = info["drug_N"]
        seq_of[code], name_of[code] = str(info["item_seq"]), info.get("dl_name", "")
        cond = (info.get("back_color"), info.get("light_color"), info.get("camera_la"), info.get("camera_lo"),
                info.get("size"))
        by_code[code][cond][info.get("drug_dir")] = name
    print(f"식약처 DB에 있는 약 {len(by_code)}종", flush=True)

    rng = random.Random(args.seed)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rows = []
    for code in sorted(by_code):
        pairs = [(cond, d) for cond, d in by_code[code].items() if "앞면" in d and "뒷면" in d]
        rng.shuffle(pairs)
        # 조건이 서로 다른 쌍을 고른다 (배경·조명이 겹치지 않게)
        chosen, used = [], set()
        for cond, d in pairs:
            key = cond[:2]
            if key in used:
                continue
            used.add(key)
            chosen.append(d)
            if len(chosen) >= args.pairs_per_item:
                break
        for i, d in enumerate(chosen, 1):
            names = {}
            for side, suffix in (("앞면", ""), ("뒷면", "_back")):
                zp, member = members[d[side]]
                target = out / f"{seq_of[code]}_{i}{suffix}.png"
                if not target.exists():
                    with zipfile.ZipFile(zp) as z:
                        target.write_bytes(z.read(member))
                names[side] = target.name
            rows.append({"front": names["앞면"], "back": names["뒷면"], "item_seq": seq_of[code],
                         "k_code": code, "item_name": name_of[code],
                         # 원본 파일 이름: 임베딩 학습 크롭에서 평가 사진을 빼는 데 쓴다 (notebooks/ 참고)
                         "front_src": d["앞면"], "back_src": d["뒷면"]})
    with open(out / "labels.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["front", "back", "item_seq", "k_code", "item_name", "front_src", "back_src"])
        w.writeheader()
        w.writerows(rows)
    print(f"평가셋 {len(rows)}쌍 ({len({r['item_seq'] for r in rows})}종) → {out}")


if __name__ == "__main__":
    main()
