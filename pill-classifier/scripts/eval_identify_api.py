"""서비스와 같은 /v1/identify 경로(사진 분석 → 확신이 없으면 Claude 관찰·재비교)로 정확도를 잰다.

    python scripts/eval_identify_api.py data/aihub/eval_photos4               # Claude 보조 켬 (.anthropic_key)
    python scripts/eval_identify_api.py data/aihub/eval_photos4 --no-ai       # 사진 분석만
    python scripts/eval_identify_api.py data/aihub/eval_photos4 --items scripts/holdout_items_v2.txt

서버를 띄우지 않고 같은 프로세스에서 앱을 불러 부른다 (FastAPI TestClient). 사진 폴더 규칙은 eval_photos.py와 같다.
Claude는 사진 분석이 확신하지 못한 사진에서만 부른다(관찰 1번 + 필요하면 재비교 1번). 요금이 발생한다.
결과는 --out CSV에 사진마다 남긴다. 사진·요청 본문은 저장하지 않는다.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
from eval_photos import load_cases  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder")
    ap.add_argument("--no-ai", action="store_true", help="Claude 보조 없이 사진 분석만")
    ap.add_argument("--items", help="이 목록(줄마다 품목기준코드)에 있는 약의 사진만 평가")
    ap.add_argument("--index", help="색인 파일 (기본: data/index.npz)")
    ap.add_argument("--model", help="임베딩 모델 (기본: models/embedder.onnx, --index와 함께 쓸 때)")
    ap.add_argument("--trusted", help="확신해도 되는 약 목록 파일 (기본: models/trained_items.txt)")
    ap.add_argument("--out", help="사진별 결과 CSV")
    ap.add_argument("--price", default="4,20", help="요금 추정용 입력,출력 달러/100만 토큰 (기본 Opus 5.5: 4,20)")
    args = ap.parse_args()

    if args.no_ai:
        os.environ["PILL_AI_PROVIDER"] = "none"
    else:
        key = ROOT / ".anthropic_key"
        if not os.environ.get("ANTHROPIC_API_KEY") and key.exists():
            os.environ["ANTHROPIC_API_KEY"] = key.read_text(encoding="utf-8").splitlines()[0].strip()
        os.environ.setdefault("PILL_AI_PROVIDER", "anthropic")
    from fastapi.testclient import TestClient

    from server.app import create_app

    cases = load_cases(Path(args.folder))
    if args.items:
        keep = {l.split("#")[0].strip() for l in Path(args.items).read_text(encoding="utf-8").splitlines()}
        cases = [c for c in cases if c["item_seq"] in keep]
    kw = {"internal_key": ""}
    if args.index:
        kw.update(model_path=args.model or str(ROOT / "models" / "embedder.onnx"), index_path=args.index)
    if args.trusted:
        os.environ["PILL_TRAINED_ITEMS"] = args.trusted
    rows, started = [], time.time()
    stats = {"n": 0, "top1": 0, "top3": 0, "conf": 0, "conf_ok": 0, "ai": 0, "rerank": 0, "fail": 0}
    with TestClient(create_app(**kw)) as client:
        for k, c in enumerate(cases, 1):
            files = {"front": ("front.png", Path(c["front"]).read_bytes(), "image/png")}
            if c["back"]:
                files["back"] = ("back.png", Path(c["back"]).read_bytes(), "image/png")
            r = client.post("/v1/identify", files=files, data={"topk": "10"})
            body = r.json() if r.status_code == 200 else {}
            cands = [x["itemSeq"] for x in body.get("candidates") or []]
            rank = cands.index(c["item_seq"]) + 1 if c["item_seq"] in cands else None
            low = body.get("lowConfidence", True)
            used_ai = body.get("observationUsed", False)
            stats["n"] += 1
            stats["top1"] += rank == 1
            stats["top3"] += bool(rank and rank <= 3)
            stats["conf"] += not low and bool(cands)
            stats["conf_ok"] += not low and rank == 1
            stats["ai"] += used_ai
            stats["rerank"] += bool(body.get("reranked"))
            stats["fail"] += body.get("status") != "COMPLETED"
            rows.append({"front": Path(c["front"]).name, "item_seq": c["item_seq"], "status": body.get("status"),
                         "rank": rank, "top1": cands[0] if cands else "", "low_confidence": low,
                         "claude_observe": used_ai, "reranked": body.get("reranked"),
                         "read_front": (body.get("features") or {}).get("printFront"),
                         "read_back": (body.get("features") or {}).get("printBack"),
                         "top1_parts": json.dumps((body.get("candidates") or [{}])[0].get("scoreBreakdown"))})
            if k % 25 == 0:
                print(f"  {k}/{len(cases)} top1 {stats['top1']}/{k} ({time.time() - started:.0f}s)", flush=True)
    n = max(stats["n"], 1)
    print(f"\n=== {args.folder}: {stats['n']}쌍, {time.time() - started:.0f}초, Claude {'끔' if args.no_ai else '켬'} ===")
    print(f"Top-1 {stats['top1']}/{n} ({stats['top1'] / n:.1%})   Top-3 {stats['top3']}/{n} ({stats['top3'] / n:.1%})")
    print(f"확신 표시 {stats['conf']}/{n} ({stats['conf'] / n:.0%})   그중 정답 {stats['conf_ok']}/{max(stats['conf'], 1)}"
          f"   (확신했는데 오답 {stats['conf'] - stats['conf_ok']}건)")
    print(f"Claude 관찰 {stats['ai']}건, 재비교 {stats['rerank']}건, 실패 {stats['fail']}건")
    from pillid.vision import USAGE
    if USAGE["calls"]:
        p_in, p_out = (float(x) for x in args.price.split(","))
        cost = (USAGE["input_tokens"] * p_in + USAGE["output_tokens"] * p_out) / 1e6
        print(f"Claude 호출 {USAGE['calls']}번, 입력 {USAGE['input_tokens']:,} 토큰, 출력(사고 포함) "
              f"{USAGE['output_tokens']:,} 토큰 → 약 ${cost:.2f} (입력 ${p_in:g}·출력 ${p_out:g}/100만 토큰)")
    if args.out:
        with open(args.out, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, list(rows[0]))
            w.writeheader()
            w.writerows(rows)


if __name__ == "__main__":
    main()
