"""떠 있는 추론 서버에 사진을 보내 식별 정확도를 잰다. Claude를 켠 서버를 평가할 때 쓴다.

    python scripts/eval_server.py labels.csv --out result.csv --limit 10

labels.csv: front,back,item_seq (eval_photos.py와 같은 형식, 경로는 파일 기준 상대 또는 절대)
호출마다 Claude 비용이 들 수 있으므로 --limit으로 조금씩 돌린다. --out 파일에 이미 있는 사진은 건너뛰어,
끊겼을 때나 --limit을 늘렸을 때 이어서 돌아간다.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from pathlib import Path

import httpx

FIELDS = ["front", "item_seq", "rank", "status", "reason", "top1", "score", "margin", "low_confidence",
          "observation_used", "reranked", "print_front", "print_back", "seconds"]


def load_cases(path: Path) -> list[dict]:
    with open(path, newline="", encoding="utf-8-sig") as f:
        return [{"front": str(path.parent / r["front"]), "back": str(path.parent / r["back"]) if r.get("back") else None,
                 "item_seq": r["item_seq"].strip()} for r in csv.DictReader(f)]


def load_done(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def identify(client: httpx.Client, url: str, case: dict, topk: int) -> dict:
    files = {"front": ("front.png", Path(case["front"]).read_bytes())}
    if case["back"]:
        files["back"] = ("back.png", Path(case["back"]).read_bytes())
    start = time.time()
    r = client.post(f"{url}/v1/identify", files=files, data={"topk": str(topk)})
    r.raise_for_status()
    d = r.json()
    seqs = [c["itemSeq"] for c in d.get("candidates", [])]
    scores = [c["score"] for c in d.get("candidates", [])]
    features = d.get("features") or {}
    return {
        "front": case["front"], "item_seq": case["item_seq"],
        "rank": seqs.index(case["item_seq"]) + 1 if case["item_seq"] in seqs else "",
        "status": d.get("status"), "reason": d.get("reason") or "",
        "top1": seqs[0] if seqs else "", "score": scores[0] if scores else "",
        "margin": round(scores[0] - scores[1], 4) if len(scores) > 1 else "",
        "low_confidence": d.get("lowConfidence"), "observation_used": d.get("observationUsed"),
        "reranked": d.get("reranked"), "print_front": features.get("printFront") or "",
        "print_back": features.get("printBack") or "", "seconds": round(time.time() - start, 1),
    }


def is_true(value) -> bool:
    return str(value) == "True"


def summarize(rows: list[dict]) -> None:
    n = len(rows)
    if not n:
        print("결과 없음")
        return
    ranks = [int(r["rank"]) if r["rank"] else None for r in rows]
    for k in (1, 3, 5, 10):
        hit = sum(1 for x in ranks if x is not None and x <= k)
        print(f"Top-{k:<2} {hit}/{n} = {100 * hit / n:.1f}%")
    confident = [r for r in rows if r["status"] == "COMPLETED" and not is_true(r["low_confidence"])]
    right = sum(1 for r in confident if r["rank"] == "1" or r["rank"] == 1)
    print(f"확신 {len(confident)}/{n} = {100 * len(confident) / n:.1f}%, 확신 중 정답 {right}/{len(confident)}")
    print("못 찾음", sum(1 for r in rows if r["status"] == "NOT_FOUND"),
          "/ Claude 사용", sum(1 for r in rows if is_true(r["observation_used"])),
          "/ 재비교", sum(1 for r in rows if is_true(r["reranked"])),
          f"/ 평균 {sum(float(r['seconds']) for r in rows) / n:.1f}초")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("labels")
    ap.add_argument("--out", required=True)
    ap.add_argument("--url", default="http://localhost:8000")
    ap.add_argument("--limit", type=int, help="이번에 새로 보낼 사진 수")
    ap.add_argument("--topk", type=int, default=10)
    args = ap.parse_args()

    out = Path(args.out)
    rows = load_done(out)
    done = {r["front"] for r in rows}
    todo = [c for c in load_cases(Path(args.labels)) if c["front"] not in done]
    if args.limit is not None:
        todo = todo[:args.limit]
    print(f"이미 끝남 {len(rows)}건, 이번에 {len(todo)}건")

    fresh = not out.exists()
    with httpx.Client(timeout=180) as client, open(out, "a", newline="", encoding="utf-8-sig") as f:
        print("서버:", json.dumps(client.get(f"{args.url}/health").json(), ensure_ascii=False))
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        if fresh:
            writer.writeheader()
        for i, case in enumerate(todo, 1):
            row = identify(client, args.url, case, args.topk)
            writer.writerow(row)
            f.flush()
            rows.append({k: str(v) for k, v in row.items()})
            print(f"{i}/{len(todo)} {case['item_seq']} 순위={row['rank'] or '-'} {row['status']} {row['seconds']}초", flush=True)
    summarize(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
