"""점수 상수(각인 부분 일치 인정 정도, 단서별 가중치)와 신뢰도 기준을 조정용 표본에서 고른다.

    python scripts/tune_scoring.py --obs data/eval/obs.json

eval_reference.py --save-obs 로 저장한 관측을 쓴다. 표본의 앞 절반(조정용)에서 값을 고르고
뒤 절반(검증용)에서 확인한다. 고른 값은 자동으로 반영하지 않는다. 검증용에서도 나아졌을 때만
pillid/ocr.py, pillid/pipeline.py의 상수를 직접 고친다.

DB 전체(2만여 품목)와의 각인 비교 재료(편집거리·부분 일치·읽은 비율)를 질의마다 한 번만 계산해 두고,
상수 조합을 바꿔 가며 정답 품목의 순위만 다시 센다.
"""
from __future__ import annotations

import argparse
import itertools
import sys
from dataclasses import dataclass, replace
from pathlib import Path

import numpy as np
from rapidfuzz import fuzz, process
from rapidfuzz.distance import Levenshtein

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
from eval_reference import item_seq_of, load_part, to_observations  # noqa: E402
from pillid import ocr, pipeline  # noqa: E402
from pillid.attributes import color_set_similarity, shape_similarity  # noqa: E402
from pillid.pipeline import PillIdentifier  # noqa: E402

GRID = {
    "alpha": (0.5, 0.6, 0.7, 0.8, 0.9),
    "beta": (0.0, 0.25, 0.5, 0.75),
    "missed": (0.3, 0.4, 0.5, 0.6),
    "false_read": (0.1, 0.2, 0.3, 0.4),
}
IMPRINT_SHARES = (0.45, 0.55, 0.65, 0.75, 0.85)  # 각인이 (각인+색+모양)에서 차지하는 비중
COLOR_SHARES = (0.5, 0.6, 0.7, 0.8)              # 나머지에서 색이 차지하는 비중
CONF_SCORES = tuple(round(0.5 + 0.05 * i, 2) for i in range(10))
CONF_MARGINS = (0.0, 0.01, 0.02, 0.03, 0.05, 0.08, 0.1, 0.15, 0.2)
CONF_IMPRINTS = (0.0, 0.5, 0.7, 0.9, 1.0)


@dataclass(frozen=True)
class Params:
    alpha: float
    beta: float
    missed: float
    false_read: float
    both_empty: float
    imprint_share: float
    color_share: float

    @classmethod
    def current(cls) -> "Params":
        w = pipeline.BASE_WEIGHTS
        rest = w["color"] + w["shape"]
        return cls(ocr.PARTIAL_ALPHA, ocr.PARTIAL_BETA, ocr.MISSED, ocr.FALSE_READ, ocr.BOTH_EMPTY,
                   w["imprint"] / (w["imprint"] + rest), w["color"] / rest)

    def weights(self) -> tuple[float, float, float]:
        """(각인, 색, 모양) 가중치. 합은 1."""
        rest = 1 - self.imprint_share
        return self.imprint_share, rest * self.color_share, rest * (1 - self.color_share)

    def imprint_key(self) -> tuple:
        return self.alpha, self.beta, self.missed, self.false_read, self.both_empty


class Targets:
    """DB 각인을 중복 없이 모은 표. 0번은 '각인 없음'."""

    def __init__(self, items):
        ids: dict[tuple, int] = {(): 0}
        self.front = np.array([ids.setdefault(it["_imp"][0], len(ids)) for it in items])
        self.back = np.array([ids.setdefault(it["_imp"][1], len(ids)) for it in items])
        tokens = list(ids)[1:]
        # 읽은 글자에 한글이 없을 때는 정답의 한글 조각을 뺀 표(ocr.imprint_components와 같은 규칙)도 만든다
        self.tables = {False: self._table(tokens),
                       True: self._table([tuple(x for x in t if not ocr._HANGUL.search(x)) or t for t in tokens])}

    @staticmethod
    def _table(tokens):
        arrangements = [ocr._arrangements(t) for t in tokens]
        return {"strings": [s for group in arrangements for s in group],
                "starts": np.cumsum([0] + [len(g) for g in arrangements[:-1]]),
                "lengths": np.array([sum(len(x) for x in t) for t in tokens], np.float32)}

    @property
    def lengths(self):
        return self.tables[False]["lengths"]

    def components(self, query: tuple) -> tuple[np.ndarray, np.ndarray, np.ndarray] | None:
        """ocr.imprint_components를 DB 각인 전체에 대해 한 번에 계산한다. 0번 자리는 쓰지 않는다."""
        if not query:
            return None
        tb = self.tables[not any(ocr._HANGUL.search(x) for x in query)]
        strings, starts, lengths = tb["strings"], tb["starts"], tb["lengths"]
        qs = "".join(query)
        lev = process.cdist([qs], strings, scorer=Levenshtein.normalized_similarity, dtype=np.float32)[0]
        lev = np.maximum.reduceat(lev, starts)
        if len(qs) >= 2:
            partial = process.cdist([qs], strings, scorer=fuzz.partial_ratio, dtype=np.float32)[0] / 100
            partial = np.maximum.reduceat(partial, starts)
            partial[lengths < 2] = 0.0
        else:
            partial = np.zeros_like(lev)
        partial[lev == 1.0] = 1.0
        coverage = np.minimum(len(qs), lengths) / np.maximum(len(qs), lengths)
        pad = np.zeros(1, np.float32)
        return tuple(np.concatenate([pad, a]).astype(np.float32) for a in (lev, partial, coverage))


@dataclass
class Query:
    seq: str
    true_idx: int
    shape: np.ndarray
    color: np.ndarray
    sides: list  # 면마다 Targets.components() 결과


def build_queries(ident: PillIdentifier, targets: Targets, observed: dict) -> list[Query]:
    index = {it["item_seq"]: i for i, it in enumerate(ident.items)}
    queries = []
    for key, sides in observed.items():
        if not sides:
            continue
        obs = to_observations(sides)
        queries.append(Query(
            key, index[item_seq_of(key)],
            ident._per_item("_shape", lambda v: np.mean([shape_similarity(o.shape, v) for o in obs])),
            ident._per_item("_colors", lambda v: np.mean([color_set_similarity(o.colors, v) for o in obs])),
            [targets.components(ocr.imprint_tokens(o.imprint)) for o in obs]))
    return queries


def side_similarity(components, p: Params) -> np.ndarray:
    """한 면의 관측 각인과 DB 각인 전체의 유사도 (ocr.combine_components와 같은 식)."""
    if components is None:
        out = np.full(len(TARGETS.lengths) + 1, p.missed, np.float32)
        out[0] = p.both_empty
        return out
    lev, partial, coverage = components
    out = np.maximum(lev, p.alpha * partial * (p.beta + (1 - p.beta) * coverage))
    out[0] = p.false_read
    return out


def imprint_scores(q: Query, p: Params) -> np.ndarray:
    sims = [side_similarity(c, p) for c in q.sides[:2]]
    if len(sims) == 1:
        return np.maximum(sims[0][TARGETS.front], sims[0][TARGETS.back])
    straight = sims[0][TARGETS.front] + sims[1][TARGETS.back]
    swapped = sims[0][TARGETS.back] + sims[1][TARGETS.front]
    return np.maximum(straight, swapped) / 2


def total_scores(q: Query, imprint: np.ndarray, p: Params) -> np.ndarray:
    wi, wc, ws = p.weights()
    return wi * imprint + wc * q.color + ws * q.shape


def rank_of_truth(score: np.ndarray, true_idx: int) -> int:
    """파이프라인과 같은 순위: 점수가 같으면 DB 순서가 앞선 품목이 먼저다."""
    s = score[true_idx]
    return 1 + int((score > s).sum()) + int((score[:true_idx] == s).sum())


def summarize(ranks: list[int]) -> dict:
    r = np.array(ranks)
    return {"top1": (r <= 1).mean(), "top5": (r <= 5).mean(), "top10": (r <= 10).mean(),
            "mrr": (1 / r).mean()}


def evaluate(queries: list[Query], p: Params) -> dict:
    return summarize([rank_of_truth(total_scores(q, imprint_scores(q, p), p), q.true_idx) for q in queries])


def grid_search(queries: list[Query], base: Params) -> list[tuple[dict, Params]]:
    results = []
    for values in itertools.product(*GRID.values()):
        p_imprint = replace(base, **dict(zip(GRID, values)))
        imprints = [imprint_scores(q, p_imprint) for q in queries]
        for share, color in itertools.product(IMPRINT_SHARES, COLOR_SHARES):
            p = replace(p_imprint, imprint_share=share, color_share=color)
            ranks = [rank_of_truth(total_scores(q, imp, p), q.true_idx) for q, imp in zip(queries, imprints)]
            results.append((summarize(ranks), p))
    # Top-1과 Top-5를 함께 보고, 같으면 정답의 평균 역순위가 높은 쪽
    results.sort(key=lambda r: (-(r[0]["top1"] + r[0]["top5"]), -r[0]["mrr"]))
    return results


def confidence_features(queries: list[Query], p: Params) -> np.ndarray:
    """질의마다 (1위 점수, 1·2위 점수 차, 1위의 각인 점수, 1위가 정답인지)."""
    rows = []
    for q in queries:
        imprint = imprint_scores(q, p)
        score = total_scores(q, imprint, p)
        top = int(np.argmax(score))  # 동점이면 DB 순서가 앞선 품목
        second = np.partition(score, -2)[-2]
        rows.append((score[top], score[top] - second, imprint[top], top == q.true_idx))
    return np.array(rows, np.float64)


def confidence_table(features: np.ndarray, rule: tuple[float, float, float]) -> dict:
    score, margin, imprint = rule
    eps = 1e-6
    confident = (features[:, 0] >= score - eps) & (features[:, 1] >= margin - eps) & (features[:, 2] >= imprint - eps)
    correct = features[:, 3] > 0
    n = int(confident.sum())
    return {"confident": n, "coverage": n / len(features), "wrong": int((confident & ~correct).sum()),
            "precision": (confident & correct).sum() / n if n else 1.0,
            "missed_correct": int((~confident & correct).sum())}


def pick_confidence_rule(features: np.ndarray, min_precision: float) -> tuple[float, float, float]:
    """'확신' 표시의 정확도가 min_precision 이상인 기준 중에서 확신으로 표시되는 비율이 가장 큰 것."""
    best, best_key = None, None
    for rule in itertools.product(CONF_SCORES, CONF_MARGINS, CONF_IMPRINTS):
        t = confidence_table(features, rule)
        if t["confident"] and t["precision"] >= min_precision:
            key = (t["coverage"], t["precision"], rule[1], rule[0])  # 같으면 더 엄격한 기준
            if best_key is None or key > best_key:
                best, best_key = rule, key
    return best


def check_against_pipeline(ident: PillIdentifier, observed: dict, queries: list[Query], p: Params, n: int = 5):
    """벡터 계산이 실제 파이프라인과 같은 점수를 내는지 몇 건 확인한다."""
    for q in queries[:n]:
        res = ident.identify(to_observations(observed[q.seq]), 1, use_imprint=True)
        mine = float(total_scores(q, imprint_scores(q, p), p).max())
        if abs(mine - res.candidates[0].score) > 1e-4:
            sys.exit(f"[실패] {q.seq}: 조정 스크립트 점수 {mine:.4f} != 파이프라인 점수 {res.candidates[0].score:.4f}")


def fmt(m: dict) -> str:
    return f"Top-1 {m['top1']:.1%}  Top-5 {m['top5']:.1%}  Top-10 {m['top10']:.1%}  MRR {m['mrr']:.3f}"


def fmt_conf(t: dict) -> str:
    return (f"확신 표시 {t['confident']}건({t['coverage']:.0%}), 그중 정답 {t['precision']:.1%}, "
            f"확신했는데 오답 {t['wrong']}건, 정답인데 저신뢰 {t['missed_correct']}건")


def main():
    global TARGETS
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--obs", default=str(ROOT / "data" / "eval" / "obs.json"))
    ap.add_argument("--min-precision", type=float, default=0.95, help="'확신' 표시가 맞아야 하는 최소 비율")
    args = ap.parse_args()

    ident = PillIdentifier(args.db, use_ocr=False)
    TARGETS = Targets(ident.items)
    parts = {}
    for part in ("tune", "validation"):
        observed, engine = load_part(args.obs, part)
        parts[part] = (observed, build_queries(ident, TARGETS, observed))
    if engine is None:
        sys.exit("[실패] OCR 없이 만든 관측입니다. 각인 점수를 조정할 수 없습니다.")
    tune, validation = parts["tune"][1], parts["validation"][1]
    print(f"조정용 {len(tune)}품목, 검증용 {len(validation)}품목, DB {len(ident.items):,}품목 "
          f"(서로 다른 각인 {len(TARGETS.lengths):,}개)")

    current = Params.current()
    check_against_pipeline(ident, parts["tune"][0], tune, current)

    results = grid_search(tune, current)
    print(f"\n조정용 상위 조합 ({len(results):,}개 중)")
    for m, p in results[:8]:
        print(f"  {fmt(m)} | alpha {p.alpha} beta {p.beta} missed {p.missed} false_read {p.false_read} "
              f"각인 비중 {p.imprint_share} 색 비중 {p.color_share}")
    best = results[0][1]

    print("\n                 조정용                                              검증용")
    for label, p in (("현재 값", current), ("고른 값", best)):
        print(f"{label}   {fmt(evaluate(tune, p))}   |   {fmt(evaluate(validation, p))}")

    wi, wc, ws = best.weights()
    rest = 1 - pipeline.BASE_WEIGHTS["embedding"]
    print(f"\n고른 값: PARTIAL_ALPHA={best.alpha} PARTIAL_BETA={best.beta} MISSED={best.missed} "
          f"FALSE_READ={best.false_read}")
    print(f"BASE_WEIGHTS (embedding {pipeline.BASE_WEIGHTS['embedding']} 유지): "
          f"imprint {wi * rest:.3f}, color {wc * rest:.3f}, shape {ws * rest:.3f}")

    print(f"\n신뢰도 기준 ('확신' 표시의 정답률 {args.min_precision:.0%} 이상을 목표로 조정용에서 고름)")
    old_rule = (pipeline.LOW_CONF_SCORE, pipeline.LOW_CONF_MARGIN, pipeline.LOW_CONF_IMPRINT)
    named = (("조정용", tune), ("검증용", validation))
    before = {name: confidence_features(qs, current) for name, qs in named}
    after = {name: confidence_features(qs, best) for name, qs in named}
    rule = pick_confidence_rule(after["조정용"], args.min_precision)
    for label, r, feats in (("현재 값 + 현재 기준", old_rule, before), ("고른 값 + 고른 기준", rule, after)):
        if r is None:
            print(f"{label}: 목표 정답률을 만족하는 기준이 없습니다.")
            continue
        print(f"{label}: 1위 점수 >= {r[0]}, 1·2위 차 >= {r[1]}, 1위의 각인 점수 >= {r[2]}")
        for name, f in feats.items():
            print(f"   {name}  {fmt_conf(confidence_table(f, r))}")


if __name__ == "__main__":
    main()
