"""실제로 찍은 알약 사진으로 식별 정확도(Top-1/3/5/10)와 신뢰도 표시를 잰다.

    python scripts/eval_photos.py data/photos
    python scripts/eval_photos.py data/photos --ocr-engine easyocr      # OCR 엔진 비교
    python scripts/eval_photos.py data/photos --no-ocr                  # 모양·색만

사진 폴더 규칙 (둘 중 하나):
  1) 파일 이름:  <품목기준코드>_<번호>.jpg          앞면 (예: 200808876_1.jpg)
                <품목기준코드>_<번호>_back.jpg     같은 알약의 뒷면 (없어도 됨)
  2) labels.csv: front,back,item_seq  (back은 비워도 됨. 경로는 폴더 기준 상대 경로)

품목기준코드는 식약처 DB의 ITEM_SEQ다. 약 포장의 '품목기준코드' 또는 nedrug.mfds.go.kr에서 찾을 수 있다.
사진은 서비스와 같은 경로(화이트밸런스 → 배경 분리 분할 → 가장 큰 알약)로 처리한다.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}
NAME_RE = re.compile(r"^(?P<seq>\d{6,})_(?P<n>[^_]+?)(?P<back>_back)?$", re.IGNORECASE)


def load_cases(folder: Path) -> list[dict]:
    """[{front, back, item_seq}]"""
    labels = folder / "labels.csv"
    if labels.exists():
        with open(labels, newline="", encoding="utf-8-sig") as f:
            return [{"front": str(folder / r["front"]), "back": str(folder / r["back"]) if r.get("back") else None,
                     "item_seq": r["item_seq"].strip()} for r in csv.DictReader(f)]
    groups: dict[tuple, dict] = defaultdict(dict)
    for p in sorted(folder.iterdir()):
        m = NAME_RE.match(p.stem)
        if p.suffix.lower() in IMAGE_EXTS and m:
            groups[(m["seq"], m["n"])]["back" if m["back"] else "front"] = str(p)
    cases = []
    for (seq, _), g in sorted(groups.items()):
        if "front" in g:
            cases.append({"front": g["front"], "back": g.get("back"), "item_seq": seq})
    return cases


def rank_of(candidates, item_seq) -> int | None:
    for i, c in enumerate(candidates, 1):
        if c.item_seq == item_seq:
            return i
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder")
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--model", help="임베딩 모델 (기본: models/embedder.pt가 있으면 사용)")
    ap.add_argument("--index", help="임베딩 색인 (기본: data/index.npz가 있으면 사용)")
    ap.add_argument("--no-embedding", action="store_true")
    ap.add_argument("--no-ocr", action="store_true")
    ap.add_argument("--ocr-engine", choices=("auto", "rapidocr", "easyocr"), default="auto")
    ap.add_argument("--topk", type=int, default=10)
    ap.add_argument("--save-crops", help="디버깅용: 분할된 알약 크롭을 저장할 폴더")
    ap.add_argument("--out", help="품목별 결과 CSV (기본: <folder>/eval_result.csv)")
    ap.add_argument("--save-obs", help="관측(모양·색·각인)을 JSON으로 저장 → tune_scoring.py --obs 로 점수 조정에 쓴다")
    args = ap.parse_args()

    os.environ["PILL_OCR_ENGINE"] = args.ocr_engine
    from pillid.imaging import write_image  # noqa: E402  (환경변수 설정 뒤에 불러온다)
    from pillid.pipeline import PillIdentifier  # noqa: E402

    folder = Path(args.folder)
    cases = load_cases(folder)
    if not cases:
        sys.exit(f"[실패] {folder}에서 평가할 사진을 찾지 못했습니다. 파일 이름 규칙이나 labels.csv를 확인하세요.")

    model, index = args.model, args.index
    if not args.no_embedding and model is None and index is None:
        from pillid.embedding import find_default_model
        model, index = find_default_model(ROOT)
    ident = PillIdentifier(args.db, model_path=model, index_path=index, use_ocr=not args.no_ocr)
    by_seq = {it["item_seq"]: it for it in ident.items}
    unknown = [c["item_seq"] for c in cases if c["item_seq"] not in by_seq]
    if unknown:
        print(f"[주의] DB에 없는 품목기준코드 {len(unknown)}건은 뺍니다: {unknown[:5]}")
        cases = [c for c in cases if c["item_seq"] in by_seq]
    engine = ident.ocr.engine if ident.ocr else None
    print(f"사진 {len(cases)}건 (뒷면 있음 {sum(bool(c['back']) for c in cases)}건), DB {len(ident.items):,}품목, "
          f"OCR {engine or '없음'}, 임베딩 {'있음' if ident.embedder else '없음'}", flush=True)

    rows, stats, started, observed = [], Counter(), time.time(), {}
    for n, c in enumerate(cases, 1):
        it = by_seq[c["item_seq"]]
        row = {"item_seq": c["item_seq"], "item_name": it.get("item_name"), "front": Path(c["front"]).name,
               "back": Path(c["back"]).name if c["back"] else "", "shape_true": it["_shape"],
               "colors_true": "/".join(it["_colors"]), "imprint_true": " | ".join(" ".join(t) for t in it["_imp"])}
        rows.append(row)
        obs = []
        for side in (c["front"], c["back"]):
            if side:
                found = ident.observe_photo(side)
                if found:
                    obs.append(max(found, key=lambda o: o.area))
                    stats["분할 성공(사진)"] += 1
                stats["사진"] += 1
        if not obs:
            row["error"] = "알약을 찾지 못함"
            continue
        if args.save_crops:
            for j, o in enumerate(obs):
                write_image(Path(args.save_crops) / f"{c['item_seq']}_{Path(c['front']).stem}_{j}.png", o.crop)
        observed[f"{c['item_seq']}#{Path(c['front']).stem}"] = [
            {"shape": o.shape, "colors": o.colors, "imprint": o.imprint, "imprint_conf": o.imprint_conf} for o in obs]
        res = ident.identify(obs, args.topk)
        row.update(shape_pred=obs[0].shape, colors_pred="/".join(obs[0].colors),
                   imprint_read=" | ".join(o.imprint for o in obs), rank=rank_of(res.candidates, c["item_seq"]),
                   top1=res.candidates[0].item_name, top_score=round(res.candidates[0].score, 3),
                   margin=round(res.candidates[0].score - res.candidates[1].score, 3),
                   low_confidence=res.low_confidence,
                   # 1위 후보의 단서별 점수 (신뢰도 기준을 다시 잡을 때 쓴다)
                   **{f"top_{k}": round(v, 3) for k, v in res.candidates[0].parts.items()},
                   # 정답 품목의 단서별 점수 (10위 안에 있을 때)
                   **{f"true_{k}": round(v, 3) for cand in res.candidates if cand.item_seq == c["item_seq"]
                      for k, v in cand.parts.items()})
        stats["식별 시도"] += 1
        stats["모양 일치"] += obs[0].shape == it["_shape"]
        stats["색 일치"] += bool(obs[0].colors) and obs[0].colors[0] in it["_colors"]
        correct = row["rank"] == 1
        stats["확신+정답"] += correct and not res.low_confidence
        stats["확신+오답"] += (not correct) and not res.low_confidence
        stats["저신뢰+정답"] += correct and res.low_confidence
        mark = "O" if correct else f"{row['rank']}위" if row["rank"] else f"{args.topk}위 밖"
        print(f"  [{n}/{len(cases)}] {mark:6} {it.get('item_name', '')[:24]:24} 읽은 각인 '{row['imprint_read']}' "
              f"(정답 '{row['imprint_true']}') 모양 {obs[0].shape} 색 {'/'.join(obs[0].colors)}", flush=True)

    if args.save_obs:
        Path(args.save_obs).parent.mkdir(parents=True, exist_ok=True)
        Path(args.save_obs).write_text(json.dumps({"ocr_engine": engine, "items": observed}, ensure_ascii=False),
                                       encoding="utf-8")
    out = Path(args.out) if args.out else folder / "eval_result.csv"
    fields = list(dict.fromkeys(k for r in rows for k in r))
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    tried = stats["식별 시도"]

    def pct(a, b):
        return f"{a}/{b} ({a / b:.0%})" if b else "-"

    def topk(k):
        return sum(1 for r in rows if r.get("rank") and r["rank"] <= k)

    print(f"\n=== {folder} : {len(cases)}건, {time.time() - started:.0f}초 ===")
    print(f"알약 분할 성공      {pct(stats['분할 성공(사진)'], stats['사진'])} (사진 기준)")
    print(f"모양 추정 일치      {pct(stats['모양 일치'], tried)}")
    print(f"주색상 추정 일치    {pct(stats['색 일치'], tried)}")
    print("\n식별 정확도" + "".join(f"   Top-{k} {pct(topk(k), len(cases))}" for k in (1, 3, 5, 10) if k <= args.topk))
    confident = stats["확신+정답"] + stats["확신+오답"]
    print(f"\n확신 표시           {pct(confident, tried)}   그중 정답 {pct(stats['확신+정답'], confident)}"
          f"   (확신했는데 오답 {stats['확신+오답']}건)")
    print(f"저신뢰 표시         {pct(tried - confident, tried)}   그중 정답 {pct(stats['저신뢰+정답'], tried - confident)}")
    print(f"\n건별 결과: {out}")


if __name__ == "__main__":
    main()
