"""식약처 참조 사진으로 식별 파이프라인을 평가한다 (분할, 모양·색 추정, 각인 OCR, Top-k, 신뢰도 표시).

참조 사진은 앞·뒷면이 좌우로 나란히 있는 스튜디오 사진이다. 폰 사진보다 조건이 좋으므로
여기서 나온 수치는 '상한'에 가깝다. 실제 성능은 폰으로 찍은 평가셋으로 따로 재야 한다.

    python scripts/eval_reference.py --n 400 --save-obs data/eval/obs.json   # 사진을 관측(OCR 포함, 느림)
    python scripts/eval_reference.py --load-obs data/eval/obs.json           # 저장한 관측으로 점수만 다시 계산(빠름)
    python scripts/eval_reference.py --load-obs data/eval/obs.json --part validation

관측을 저장해 두면 점수 계산 방식을 바꿀 때마다 OCR을 다시 돌리지 않아도 된다.
표본의 앞 절반은 조정용(tune), 뒤 절반은 검증용(validation)이다. 점수 상수는 조정용으로만 고른다.
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import sys
import time
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.imaging import preprocess, read_image, segment_reference  # noqa: E402
from pillid.ocr import imprint_components, imprint_tokens  # noqa: E402
from pillid.pipeline import PillIdentifier, PillObservation  # noqa: E402

TOPK = 50


def observe_item(ident: PillIdentifier, image_path: Path) -> list[dict]:
    img, _ = preprocess(read_image(image_path), max_side=1200, white_balance=False)
    return [{"shape": o.shape, "colors": o.colors, "imprint": o.imprint, "imprint_conf": o.imprint_conf}
            for o in (ident.observe(img, r) for r in segment_reference(img))]


def to_observations(sides: list[dict]) -> list[PillObservation]:
    return [PillObservation(crop=None, shape=s["shape"], colors=s["colors"], imprint=s["imprint"],
                            imprint_conf=s["imprint_conf"], bbox=(0, 0, 0, 0), area=0.0) for s in sides]


def imprint_exact(reads: list[tuple], truth: tuple) -> bool:
    """조각 순서와 앞/뒤 배정에 상관없이 각인을 빠짐없이 맞게 읽었는지."""
    def same(read, true):
        return (not read and not true) or imprint_components(read, true) == (1.0, 1.0, 1.0)
    if len(reads) == 1:
        return any(same(reads[0], t) for t in truth if t)
    return (same(reads[0], truth[0]) and same(reads[1], truth[1])) or \
           (same(reads[0], truth[1]) and same(reads[1], truth[0]))


def rank_of(candidates, item_seq) -> int | None:
    for i, c in enumerate(candidates, 1):
        if c.item_seq == item_seq:
            return i
    return None


def load_part(path, part: str) -> tuple[dict, str | None]:
    """저장한 관측에서 조정용/검증용 부분만 꺼낸다. (품목별 관측, OCR 엔진 이름)"""
    cache = json.loads(Path(path).read_text(encoding="utf-8"))
    keys = list(cache["items"])
    # 같은 품목의 관측(키 "품목코드#사진")은 같은 쪽에 둔다 (eval_photos.py --save-obs 는 품목당 여러 장)
    seqs = list(dict.fromkeys(item_seq_of(k) for k in keys))
    half = set(seqs[: len(seqs) // 2])
    chosen = [k for k in keys if part == "all" or (item_seq_of(k) in half) == (part == "tune")]
    return {k: cache["items"][k] for k in chosen}, cache["ocr_engine"]


def item_seq_of(key: str) -> str:
    return key.split("#")[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--images-dir", default=str(ROOT / "data" / "images"))
    ap.add_argument("--n", type=int, default=150, help="평가할 품목 수 (무작위 표본)")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--no-ocr", action="store_true", help="OCR 없이 모양·색만 관측")
    ap.add_argument("--save-obs", help="관측 결과를 저장할 JSON 경로")
    ap.add_argument("--load-obs", help="저장한 관측을 불러와 점수만 다시 계산")
    ap.add_argument("--items-from", help="무작위 표본 대신 이 관측 파일에 있는 품목을 같은 순서로 다시 관측")
    ap.add_argument("--part", choices=("all", "tune", "validation"), default="all")
    ap.add_argument("--out", default=str(ROOT / "data" / "eval" / "reference_eval.csv"))
    args = ap.parse_args()

    started = time.time()
    if args.load_obs:
        ident = PillIdentifier(args.db, use_ocr=False)
        observed, engine = load_part(args.load_obs, args.part)
    else:
        ident = PillIdentifier(args.db, use_ocr=not args.no_ocr)
        engine = ident.ocr.engine if ident.ocr else None
        in_db = {it["item_seq"] for it in ident.items}
        have = sorted(p.stem for p in Path(args.images_dir).glob("*.jpg") if p.stem in in_db)
        if args.items_from:  # 관측 코드를 고친 뒤 같은 품목으로 다시 잴 때
            sample = list(json.loads(Path(args.items_from).read_text(encoding="utf-8"))["items"])
        else:
            sample = random.Random(args.seed).sample(have, min(args.n, len(have)))
        print(f"사진 {len(have):,}장 중 {len(sample)}품목 관측, OCR 엔진: {engine or '없음'}", flush=True)
        observed = {}
        for n, seq in enumerate(sample, 1):
            observed[seq] = observe_item(ident, Path(args.images_dir) / f"{seq}.jpg")
            if n % 20 == 0:
                print(f"  {n}/{len(sample)} ({(time.time() - started) / n:.1f}초/품목)", flush=True)
        if args.save_obs:
            Path(args.save_obs).parent.mkdir(parents=True, exist_ok=True)
            Path(args.save_obs).write_text(json.dumps(
                {"ocr_engine": engine, "seed": args.seed, "items": observed}, ensure_ascii=False), encoding="utf-8")
            print(f"관측 저장: {args.save_obs}")

    by_seq = {it["item_seq"]: it for it in ident.items}
    use_imprint = engine is not None

    rows, stats = [], Counter()
    for key, sides in observed.items():
        seq = item_seq_of(key)
        it = by_seq[seq]
        row = {"item_seq": seq, "item_name": it.get("item_name"), "sides": len(sides),
               "shape_true": it["_shape"], "colors_true": "/".join(it["_colors"]),
               "imprint_true_front": " ".join(it["_imp"][0]), "imprint_true_back": " ".join(it["_imp"][1])}
        rows.append(row)
        if not sides:
            continue
        obs = to_observations(sides)
        stats["분할 성공"] += 1
        stats["두 면 모두"] += len(sides) == 2
        row["shape_pred"], row["colors_pred"] = obs[0].shape, "/".join(obs[0].colors)
        stats["모양 일치"] += obs[0].shape == it["_shape"]
        stats["색 일치"] += bool(obs[0].colors) and obs[0].colors[0] in it["_colors"]

        reads = [imprint_tokens(o.imprint) for o in obs]
        for side, o in zip(("front", "back"), obs):
            row[f"ocr_{side}"] = o.imprint
        if any(it["_imp"]):  # 각인이 있는 약만 판독률 계산에 넣는다
            stats["각인 있는 품목"] += 1
            row["imprint_exact"] = imprint_exact(reads, it["_imp"])
            stats["각인 정확"] += row["imprint_exact"]
            stats["각인 전혀 못 읽음"] += not any(reads)

        res = ident.identify(obs, TOPK, use_imprint=use_imprint)
        row["rank"] = rank_of(res.candidates, seq)
        row["rank_shape_color_only"] = rank_of(ident.identify(obs, TOPK, use_imprint=False).candidates, seq)
        row["top_score"] = round(res.candidates[0].score, 3)
        row["margin"] = round(res.candidates[0].score - res.candidates[1].score, 3)
        row["low_confidence"] = res.low_confidence
        correct = row["rank"] == 1
        stats["확신+정답"] += correct and not res.low_confidence
        stats["확신+오답"] += (not correct) and not res.low_confidence
        stats["저신뢰+정답"] += correct and res.low_confidence

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    fields = list(dict.fromkeys(k for r in rows for k in r))
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    total, done = len(rows), stats["분할 성공"]

    def pct(a, b):
        return f"{a}/{b} ({a / b:.0%})" if b else "-"

    def topk(key, k):
        return sum(1 for r in rows if r.get(key) and r[key] <= k)

    print(f"\n=== 결과: {args.part} {total}품목, DB {len(ident.items):,}품목 ({time.time() - started:.0f}초) ===")
    print(f"알약 분할 성공      {pct(done, total)}   두 면 모두 {pct(stats['두 면 모두'], total)}")
    print(f"모양 추정 일치      {pct(stats['모양 일치'], done)}")
    print(f"주색상 추정 일치    {pct(stats['색 일치'], done)}")
    if use_imprint:
        t = stats["각인 있는 품목"]
        print(f"각인 정확히 읽음    {pct(stats['각인 정확'], t)}   전혀 못 읽음 {pct(stats['각인 전혀 못 읽음'], t)}"
              f"   (엔진: {engine})")
    print("\n식별 정확도 (정답이 k위 안에 든 비율)")
    print(f"{'':22}{'Top-1':>14}{'Top-5':>14}{'Top-10':>14}{'Top-50':>14}")
    labels = [("모양+색만", "rank_shape_color_only")] + ([("모양+색+각인", "rank")] if use_imprint else [])
    for label, key in labels:
        print(f"{label:22}" + "".join(f"{pct(topk(key, k), total):>14}" for k in (1, 5, 10, 50)))
    confident = stats["확신+정답"] + stats["확신+오답"]
    print("\n신뢰도 표시 (1위 기준)")
    print(f"확신한다고 한 것    {pct(confident, done)}   그중 정답 {pct(stats['확신+정답'], confident)}"
          f"   (오답 {stats['확신+오답']}건이 가장 위험)")
    print(f"저신뢰로 표시한 것  {pct(done - confident, done)}   그중 정답 {pct(stats['저신뢰+정답'], done - confident)}")
    print(f"\n품목별 결과: {out}")


if __name__ == "__main__":
    main()
