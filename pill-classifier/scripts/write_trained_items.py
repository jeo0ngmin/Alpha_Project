"""임베딩을 학습한 약의 목록(models/trained_items.txt)을 만든다. 추론 서버는 이 목록에 있는 약만 확신한다.

    python scripts/write_trained_items.py                 # data/aihub/crops 의 폴더 이름(품목기준코드)
    python scripts/write_trained_items.py --crops <폴더>   # 새로 학습한 크롭 폴더
    python scripts/write_trained_items.py --index models/index_full.npz   # 크롭 폴더가 없을 때 (학교 서버에서 학습)

모델을 새로 학습하면 학습에 쓴 크롭 폴더로 이 목록도 다시 만들어야 한다.
--index: 색인에 사진이 3장 이상인 약 = 실제 촬영 사진(AI Hub)으로 학습한 약. 식약처 참조 사진은 품목당 2장 이하다.
"""
from __future__ import annotations

import argparse
import re
from collections import Counter
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--crops", default=str(ROOT / "data" / "aihub" / "crops"))
    ap.add_argument("--index", help="크롭 폴더 대신 학습 때 만든 전체 색인(.npz)에서 고른다")
    ap.add_argument("--out", default=str(ROOT / "models" / "trained_items.txt"))
    args = ap.parse_args()
    if args.index:
        import numpy as np

        counts = Counter(np.load(args.index)["labels"].tolist())
        names, source = [k for k, v in counts.items() if v >= 3], Path(args.index).name
    else:
        crops = Path(args.crops)
        names, source = [p.name for p in crops.iterdir() if p.is_dir()], crops.name
    # 품목기준코드(숫자)만 쓴다. K-코드 등 식약처 DB와 연결되지 않은 품목은 확신 대상이 될 수 없다
    items = sorted(n for n in names if re.fullmatch(r"\d{6,}", n))
    if not items:
        raise SystemExit(f"학습한 품목이 없습니다: {args.index or args.crops}")
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f"# 임베딩을 학습한 약 {len(items)}종 ({source}, {date.today()})\n" + "\n".join(items) + "\n",
                   encoding="utf-8")
    print(f"{len(items)}종 → {out}")


if __name__ == "__main__":
    main()
