"""임베딩을 학습한 약의 목록(models/trained_items.txt)을 만든다. 추론 서버는 이 목록에 있는 약만 확신한다.

    python scripts/write_trained_items.py                 # data/aihub/crops 의 폴더 이름(품목기준코드)
    python scripts/write_trained_items.py --crops <폴더>   # 새로 학습한 크롭 폴더

모델을 새로 학습하면 학습에 쓴 크롭 폴더로 이 목록도 다시 만들어야 한다.
"""
from __future__ import annotations

import argparse
import re
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--crops", default=str(ROOT / "data" / "aihub" / "crops"))
    ap.add_argument("--out", default=str(ROOT / "models" / "trained_items.txt"))
    args = ap.parse_args()
    crops = Path(args.crops)
    # 품목기준코드(숫자)만 쓴다. K-코드 등 식약처 DB와 연결되지 않은 폴더는 확신 대상이 될 수 없다
    items = sorted(p.name for p in crops.iterdir() if p.is_dir() and re.fullmatch(r"\d{6,}", p.name))
    if not items:
        raise SystemExit(f"품목 폴더가 없습니다: {crops}")
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f"# 임베딩을 학습한 약 {len(items)}종 ({crops.name}, {date.today()})\n" + "\n".join(items) + "\n",
                   encoding="utf-8")
    print(f"{len(items)}종 → {out}")


if __name__ == "__main__":
    main()
