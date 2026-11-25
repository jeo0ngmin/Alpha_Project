"""사진으로 알약을 식별한다.

  # 사진 한 장 (여러 알약이 있으면 각각 식별)
  python scripts/identify.py photo.jpg

  # 같은 알약의 앞/뒷면 두 장 (정확도 ↑)
  python scripts/identify.py front.jpg --back back.jpg

임베딩 모델/색인이 없으면 모양·색·각인만으로 식별한다 (학습 전에도 동작).
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.embedding import find_default_model  # noqa: E402
from pillid.imaging import write_image  # noqa: E402
from pillid.pipeline import Identification, PillIdentifier  # noqa: E402

PART_NAMES = {"embedding": "임베딩", "imprint": "각인", "color": "색", "shape": "모양"}


def print_result(title: str, res: Identification) -> None:
    for o in res.observations:
        print(f"{title} 위치 {o.bbox} | 추정: 모양={o.shape}, 색={'+'.join(o.colors) or '?'}, "
              f"각인='{o.imprint}'" + (f" ({o.imprint_conf:.2f})" if o.imprint else ""))
    for rank, c in enumerate(res.candidates, 1):
        parts = " · ".join(f"{PART_NAMES[k]} {v:.2f}" for k, v in c.parts.items())
        print(f"  {rank}. {c.item_name} ({c.entp_name})  점수 {c.score:.3f}  [{parts}]")
        print(f"     {c.drug_shape} / {c.colors} / 각인 앞 '{c.print_front}' 뒤 '{c.print_back}'"
              f" / 품목기준코드 {c.item_seq}")
    if res.low_confidence:
        print("  ⚠ 신뢰도가 낮습니다. 후보 이미지와 직접 비교하고, 확실하지 않으면 약사에게 확인하세요.")
    print()


def to_json(res: Identification) -> dict:
    return {
        "observations": [{k: v for k, v in asdict(o).items() if k != "crop"} for o in res.observations],
        "candidates": [asdict(c) for c in res.candidates],
        "weights": res.weights,
        "low_confidence": res.low_confidence,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("photo")
    ap.add_argument("--back", help="같은 알약의 뒷면 사진")
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--model", help="임베딩 모델 .onnx/.pt (기본: models/embedder.onnx 또는 .pt가 있으면 사용)")
    ap.add_argument("--index", help="임베딩 색인 (기본: data/index.npz가 있으면 사용)")
    ap.add_argument("--detector", help="YOLO 탐지 가중치 (없으면 배경 분리로 분할)")
    ap.add_argument("--topk", type=int, default=5)
    ap.add_argument("--no-ocr", action="store_true")
    ap.add_argument("--json", action="store_true", help="결과를 JSON으로 출력")
    ap.add_argument("--save-crops", help="디버깅용: 정규화 크롭을 저장할 폴더")
    args = ap.parse_args()

    model, index = args.model, args.index
    if model is None and index is None:
        model, index = find_default_model(ROOT)

    ident = PillIdentifier(args.db, model_path=model, index_path=index,
                           detector_weights=args.detector, use_ocr=not args.no_ocr)
    if args.back:
        results = [ident.identify_sides(args.photo, args.back, args.topk)]
    else:
        results = ident.identify_photo(args.photo, args.topk)

    if args.save_crops:
        for i, res in enumerate(results):
            for j, o in enumerate(res.observations):
                write_image(Path(args.save_crops) / f"pill{i + 1}_side{j + 1}.png", o.crop)

    if args.json:
        print(json.dumps([to_json(r) for r in results], ensure_ascii=False, indent=2))
        return
    if not results:
        print("알약을 찾지 못했습니다. 어두운 단색 배경 위에서 알약이 겹치지 않게 다시 찍어 주세요.")
        return
    mode = "임베딩+" if ident.embedder else ""
    print(f"식별 모드: {mode}{'각인+' if ident.ocr else ''}색+모양 | DB {len(ident.items):,}품목\n")
    for i, res in enumerate(results, 1):
        print_result(f"[알약 {i}]", res)


if __name__ == "__main__":
    main()
