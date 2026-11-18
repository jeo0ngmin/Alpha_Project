"""전체 색인에서 품목당 사진 수를 제한한 색인을 만든다 (다시 임베딩하지 않는다).

    python scripts/cap_index.py models/index_full.npz --per-class 20 --out data/index.npz

품목마다 앞쪽 2장(식약처 참조 사진. build_index.py에 참조 매니페스트를 먼저 줬을 때)은 그대로 두고,
나머지(실제 촬영 사진)에서 고르게 골라 품목당 최대 --per-class 장으로 맞춘다.
AI Hub 전체 색인(29.9만 장)에서 20장으로 줄이면 Top-1이 같거나 조금 낫고 크기는 절반 이하다 (README 평가 참고).
"""
from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.gallery import Gallery  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("index")
    ap.add_argument("--per-class", type=int, default=20)
    ap.add_argument("--keep-first", type=int, default=2, help="품목마다 무조건 남길 앞쪽 사진 수 (참조 사진)")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if args.per_class <= args.keep_first:
        raise SystemExit("--per-class는 --keep-first보다 커야 합니다")

    z = np.load(args.index)
    embs, labels = z["embs"], z["labels"]
    by_label = defaultdict(list)
    for i, label in enumerate(labels.tolist()):
        by_label[label].append(i)
    keep = []
    for idx in by_label.values():
        if len(idx) <= args.per_class:
            keep += idx
        else:
            head, rest = idx[:args.keep_first], idx[args.keep_first:]
            pick = np.linspace(0, len(rest) - 1, args.per_class - args.keep_first).round().astype(int)
            keep += head + [rest[j] for j in pick]
    keep = np.array(sorted(keep))
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Gallery(embs[keep], labels[keep].tolist()).save(args.out)
    print(f"{len(labels):,}장 → {len(keep):,}장 (품목 {len(by_label):,}개, 품목당 최대 {args.per_class}장) → {args.out}")


if __name__ == "__main__":
    main()
