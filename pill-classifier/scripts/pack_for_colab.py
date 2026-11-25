"""Colab에서 임베딩을 학습할 수 있도록 코드와 학습 크롭을 zip 두 개로 묶는다.

    python scripts/pack_for_colab.py            → colab/pill-classifier-code.zip, colab/pill-classifier-data.zip

두 파일을 Google Drive의 MediMate/ 폴더에 올린 뒤 notebooks/train_embedder_colab.ipynb 를 Colab에서 연다.
데이터 zip에는 학습 크롭(data/reference/crops, data/aihub/crops)과 매니페스트, 평가 사진 목록(labels.csv)만 넣는다.
식약처 원본 사진(2.9GB)·DB·가상환경은 넣지 않는다.
"""
from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CODE_DIRS = ("pillid", "scripts", "tests", "notebooks")
CODE_FILES = ("requirements.txt", "README.md")
DATA_ITEMS = ("data/reference/manifest.csv", "data/reference/crops", "data/aihub/manifest.csv", "data/aihub/crops",
              "data/aihub/eval_photos/labels.csv", "data/aihub/eval_photos4/labels.csv")


def add_tree(z: zipfile.ZipFile, path: Path, skip=("__pycache__",)):
    files = [path] if path.is_file() else sorted(p for p in path.rglob("*") if p.is_file())
    for p in files:
        if any(s in p.parts for s in skip):
            continue
        z.write(p, p.relative_to(ROOT).as_posix())
    return len(files)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(ROOT / "colab"))
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    code = out / "pill-classifier-code.zip"
    with zipfile.ZipFile(code, "w", zipfile.ZIP_DEFLATED) as z:
        n = sum(add_tree(z, ROOT / d) for d in CODE_DIRS if (ROOT / d).exists())
        n += sum(add_tree(z, ROOT / f) for f in CODE_FILES if (ROOT / f).exists())
    print(f"코드 {n:,}개 파일 → {code} ({code.stat().st_size / 1e6:.1f} MB)")

    missing = [d for d in DATA_ITEMS if not (ROOT / d).exists()]
    if missing:
        sys.exit(f"[실패] 없는 항목: {missing}\n  prepare_reference.py, convert_aihub.py, aihub_eval_set.py 를 먼저 실행하세요.")
    data = out / "pill-classifier-data.zip"
    with zipfile.ZipFile(data, "w", zipfile.ZIP_STORED) as z:  # PNG는 이미 압축돼 있어 저장만 한다
        n = sum(add_tree(z, ROOT / d) for d in DATA_ITEMS)
    print(f"데이터 {n:,}개 파일 → {data} ({data.stat().st_size / 1e9:.2f} GB)")
    print("\n두 파일을 Google Drive의 MediMate/ 폴더에 올린 뒤 notebooks/train_embedder_colab.ipynb 를 Colab에서 여세요.")


if __name__ == "__main__":
    main()
