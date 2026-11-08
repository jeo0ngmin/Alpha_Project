"""학습된 임베딩 모델로 참조 크롭들을 임베딩해 검색 색인(.npz)을 만든다.

새 약이 추가되면 build_db → prepare_reference → 이 스크립트만 다시 실행하면 된다 (재학습 불필요).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.data import read_manifest  # noqa: E402
from pillid.embedding import Embedder  # noqa: E402
from pillid.gallery import Gallery  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", default=str(ROOT / "models" / "embedder.pt"))
    ap.add_argument("--manifest", nargs="+", default=[str(ROOT / "data" / "reference" / "manifest.csv")],
                    help="AI Hub 크롭 매니페스트도 함께 넣으면 품목당 참조 시점이 늘어 재현율이 오른다")
    ap.add_argument("--out", default=str(ROOT / "data" / "index.npz"))
    ap.add_argument("--batch-size", type=int, default=128)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--device")
    args = ap.parse_args()

    rows = read_manifest(args.manifest)
    embedder = Embedder(args.model, args.device)
    print(f"{len(rows):,}장 임베딩 ({embedder.device})")
    embs = embedder.embed_paths([r["path"] for r in rows], args.batch_size, args.workers)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Gallery(embs, [r["label"] for r in rows]).save(args.out)
    print(f"색인 저장: {embs.shape} → {args.out}")


if __name__ == "__main__":
    main()
