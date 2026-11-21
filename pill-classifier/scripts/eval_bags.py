"""약봉투 인식 정확도를 정답표와 비교해 잰다 (CLOVA OCR + Claude, 서버와 같은 BagReader).

    python scripts/eval_bags.py data/eval/bags_synth            # truth.json이 있는 폴더
    python scripts/eval_bags.py data/eval/bags_synth --reuse    # 저장한 인식 결과로 점수만 다시 계산

키는 서버와 같은 파일에서 읽는다(.clova_ocr, .anthropic_key). 사진 한 장에 CLOVA 1건, Claude 1~2번 호출.
인식 결과는 <폴더>/results.json에 저장한다 (사진·요청 본문은 저장하지 않는다).
--cache-ocr: CLOVA가 읽은 글자 상자를 <폴더>/ocr_fields.json에 저장해 두고 다음부터 다시 쓴다 (CLOVA 무료 한도 절약).
가상 약봉투에만 쓴다. 실제 약봉투의 글자에는 개인정보가 있으므로 저장하지 않는다.

항목별 정답률과 함께, 값이 틀렸는데 '확인 필요'가 붙지 않은 경우(조용한 오답)를 따로 센다.
틀린 값으로 알림이 울리면 안 되므로 이 수가 가장 중요하다.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.bag import BagReader, ClovaOcr, OcrField, _bigrams, dice, load_clova_config, normalize_name  # noqa: E402
from pillid.db import PillDB  # noqa: E402
from pillid.vision import claude_from_env  # noqa: E402

FIELDS = ("name", "dose", "unit", "times", "slots", "meal", "days")


class CachedOcr:
    """가상 약봉투용: 같은 사진이면 저장해 둔 CLOVA 결과를 쓴다."""

    def __init__(self, ocr: ClovaOcr, path: Path):
        self.ocr, self.path = ocr, path
        self.cache = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}

    def read(self, jpeg: bytes) -> list[OcrField]:
        key = hashlib.sha1(jpeg).hexdigest()
        if key not in self.cache:
            self.cache[key] = [dataclasses.asdict(f) for f in self.ocr.read(jpeg)]
            self.path.write_text(json.dumps(self.cache, ensure_ascii=False), encoding="utf-8")
        return [OcrField(**f) for f in self.cache[key]]


def make_reader(cache: Path | None = None) -> BagReader:
    key_file = ROOT / ".anthropic_key"
    if not os.environ.get("ANTHROPIC_API_KEY") and key_file.exists():
        os.environ["ANTHROPIC_API_KEY"] = key_file.read_text(encoding="utf-8").splitlines()[0].strip()
    os.environ.setdefault("PILL_AI_PROVIDER", "anthropic")
    clova = load_clova_config(ROOT)
    claude = claude_from_env()
    if clova is None or claude is None:
        sys.exit("CLOVA OCR(.clova_ocr) 또는 Claude(.anthropic_key) 설정이 없습니다.")
    db = PillDB(ROOT / "data" / "pills.db")
    items = db.all_items()
    db.close()
    ocr = ClovaOcr(*clova)
    return BagReader(items, claude, CachedOcr(ocr, cache) if cache else ocr)


def similarity(a: str | None, b: str | None) -> float:
    return dice(_bigrams(normalize_name(a)), _bigrams(normalize_name(b)))


def pair(truth_meds: list[dict], items: list[dict]) -> list[tuple[dict, dict | None]]:
    """정답 약마다 이름이 가장 비슷한 인식 결과를 짝짓는다 (한 결과는 한 번만)."""
    scores = sorted(((similarity(t["printed"], it.get("refinedName") or it.get("rawText")), i, j)
                     for i, t in enumerate(truth_meds) for j, it in enumerate(items)), reverse=True)
    used_t, used_i, out = set(), set(), {}
    for s, i, j in scores:
        if s < 0.4 or i in used_t or j in used_i:
            continue
        used_t.add(i), used_i.add(j)
        out[i] = items[j]
    return [(t, out.get(i)) for i, t in enumerate(truth_meds)]


def field_values(t: dict, it: dict) -> dict:
    """(정답, 인식) 값 쌍. 인식 값이 None이면 '읽지 못함'."""
    meal_truth = t["meal"]
    return {
        "name": (t["item_seq"], it.get("itemSeq")),
        "dose": (float(t["dose"]), None if it.get("dosageAmount") is None else float(it["dosageAmount"])),
        "unit": (t["unit"], it.get("dosageUnit")),
        "times": (t["times"], it.get("frequencyPerDay")),
        "slots": (sorted(t["slots"]), None if it.get("slotsInferred") and not it.get("timeSlots") else sorted(it.get("timeSlots") or []) or None),
        # 봉투에 식사 관계가 적혀 있지 않은 약(ANYTIME)은 비워 두는 것도 맞다
        "meal": (meal_truth, it.get("mealRelation") or ("ANYTIME" if meal_truth == "ANYTIME" else None)),
        "days": (t["days"], it.get("durationDays")),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder")
    ap.add_argument("--reuse", action="store_true", help="results.json을 다시 쓰지 않고 점수만 계산")
    ap.add_argument("--cache-ocr", action="store_true", help="CLOVA 결과를 저장해 다시 쓴다 (가상 약봉투 전용)")
    ap.add_argument("--results", default="results.json", help="인식 결과 파일 이름 (설정을 바꿔 비교할 때)")
    args = ap.parse_args()
    folder = Path(args.folder)
    truth = json.loads((folder / "truth.json").read_text(encoding="utf-8"))
    res_path = folder / args.results
    results = json.loads(res_path.read_text(encoding="utf-8")) if res_path.exists() else {}
    if not args.reuse:
        reader = make_reader(folder / "ocr_fields.json" if args.cache_ocr else None)
        for name in truth:
            if name in results:
                continue
            r = reader.recognize((folder / name).read_bytes())
            results[name] = r
            res_path.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
            print(f"{name}: {r.get('status')} 약 {len(r.get('items') or [])}개 ({r.get('processingMs')}ms)"
                  f"{' 재확인' if r.get('rechecked') else ''}", flush=True)

    stats = {f: {"ok": 0, "wrong": 0, "missing": 0, "silent": 0} for f in FIELDS}
    meds = found = extra = review_items = 0
    fully_ok = 0
    by_level: dict[int, list[int]] = {}
    problems = []
    for name, t in truth.items():
        r = results.get(name)
        if r is None:
            continue
        items = r.get("items") or []
        pairs = pair(t["medications"], items)
        extra += len(items) - sum(1 for _, it in pairs if it is not None)
        lv = by_level.setdefault(t["level"], [0, 0])
        for tm, it in pairs:
            meds += 1
            lv[1] += 1
            if it is None:
                problems.append(f"{name}: {tm['printed']} 못 찾음")
                continue
            found += 1
            review_items += bool(it.get("needsReview"))
            all_ok = True
            for f, (want, got) in field_values(tm, it).items():
                s = stats[f]
                if got is None:
                    s["missing"] += 1
                    all_ok = False
                elif got == want:
                    s["ok"] += 1
                else:
                    s["wrong"] += 1
                    all_ok = False
                    if not it.get("needsReview"):
                        s["silent"] += 1
                    problems.append(f"{name}: {tm['printed']} {f} 정답 {want} / 인식 {got}"
                                    f"{'' if it.get('needsReview') else '  ← 확인 필요 표시 없음'}")
            fully_ok += all_ok
            lv[0] += all_ok

    print(f"\n=== {folder} : 봉투 {len(results)}장, 약 {meds}개 ===")
    print(f"약 찾음 {found}/{meds}   정답에 없는 약 {extra}개   '확인 필요' 표시 {review_items}/{found}")
    print(f"모든 항목이 맞은 약 {fully_ok}/{meds} ({fully_ok / max(meds, 1):.0%})")
    labels = {"name": "품목(식약처 일치)", "dose": "1회량", "unit": "단위", "times": "하루 횟수", "slots": "복용 시점",
              "meal": "식사 관계", "days": "일수"}
    for f in FIELDS:
        s = stats[f]
        n = s["ok"] + s["wrong"] + s["missing"]
        print(f"  {labels[f]:<12} 맞음 {s['ok']}/{n}  틀림 {s['wrong']}  비움 {s['missing']}  조용한 오답 {s['silent']}")
    print("사진 상태별 (모든 항목이 맞은 약): " + ", ".join(
        f"L{k} {v[0]}/{v[1]}" for k, v in sorted(by_level.items())))
    if problems:
        print("\n틀리거나 비운 곳")
        for p in problems:
            print("  " + p)


if __name__ == "__main__":
    main()
