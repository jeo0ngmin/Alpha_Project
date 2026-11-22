"""미국 NIH C3PI 소비자 알약 사진(폰·웹캠으로 찍은 실제 사진)으로 분할·모양·색·각인 OCR을 검증한다.

    python scripts/eval_nih_photos.py data/nih_photos
    python scripts/eval_nih_photos.py data/nih_photos --ocr-engine easyocr

미국 약이라 식약처 DB와 맞춰 볼 수는 없다(Top-1 측정 불가). 대신 사진마다 정답(각인, 모양, 색)이 있어서
서비스 경로(화이트밸런스 → 배경 분리 분할 → 모양·색 추정 → OCR)가 **실제 사진 조건**에서 어느 정도
맞는지 잰다. 사진은 대부분 밝은 배경(종이, 책상)이라 촬영 가이드(어두운 배경)와 다른 조건이기도 하다.

준비: data/nih_photos/selected.txt (NDC|경로|이름), images/ (사진), xml/ (디스크별 메타데이터).
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
import time
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

# NIH 표기 → 식약처 표기. 식약처가 더 잘게 나누는 색(연두/초록 등)은 NIH 쪽으로 합쳐 비교한다
SHAPE_TO_KO = {"ROUND": "원형", "OVAL": "타원형", "CAPSULE": "장방형", "RECTANGLE": "장방형", "TRIANGLE": "삼각형",
               "SQUARE": "사각형", "DIAMOND": "마름모형", "PENTAGON": "오각형", "HEXAGON": "육각형",
               "OCTAGON": "팔각형", "SEMI-CIRCLE": "반원형"}
KO_TO_NIH_COLOR = {"하양": "WHITE", "노랑": "YELLOW", "주황": "ORANGE", "분홍": "PINK", "빨강": "RED", "갈색": "BROWN",
                   "연두": "GREEN", "초록": "GREEN", "청록": "TURQUOISE", "파랑": "BLUE", "남색": "BLUE",
                   "자주": "PURPLE", "보라": "PURPLE", "회색": "GRAY", "검정": "BLACK", "투명": "CLEAR"}
# 식약처 분류에서는 타원형/장방형 구분이 흐리므로 둘은 서로 맞는 것으로 친다
LENIENT_SHAPE = {frozenset(("타원형", "장방형"))}
LIGHT_BACKGROUND_WORDS = ("white", "light", "paper", "laminate")


def load_metadata(folder: Path) -> dict[str, dict]:
    """사진 파일 이름 → {imprint, color, shape, camera, background, rating_imprint, ndc, name}"""
    meta = {}
    for xml_path in sorted((folder / "xml").glob("*.xml")):
        for img in ET.parse(xml_path).getroot().iter("Image"):
            def text(tag):
                el = img.find(tag)
                return (el.text or "").strip() if el is not None else ""
            name = img.findtext("File/Name") or ""
            meta[name] = {"ndc": text("NDC11"), "name": text("ProprietaryName") or text("GenericName"),
                          "imprint": text("Imprint"), "color": text("Color"), "shape": text("Shape"),
                          "camera": text("Camera"), "background": text("Background"),
                          "rating_imprint": text("RatingImprint")}
    return meta


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", nargs="?", default=str(ROOT / "data" / "nih_photos"))
    ap.add_argument("--ocr-engine", choices=("auto", "rapidocr", "easyocr"), default="auto")
    ap.add_argument("--no-ocr", action="store_true")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--save-crops", help="디버깅용: 분할된 알약 크롭을 저장할 폴더")
    ap.add_argument("--out")
    args = ap.parse_args()

    os.environ["PILL_OCR_ENGINE"] = args.ocr_engine
    from pillid.imaging import write_image  # noqa: E402
    from pillid.ocr import imprint_components, imprint_tokens  # noqa: E402
    from pillid.pipeline import PillIdentifier  # noqa: E402

    folder = Path(args.folder)
    meta = load_metadata(folder)
    photos = sorted(p for p in (folder / "images").iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
    photos = [p for p in photos if p.name in meta][: args.limit]
    if not photos:
        sys.exit(f"[실패] {folder}/images 에 메타데이터와 짝이 맞는 사진이 없습니다.")

    db = ROOT / "data" / "pills.db"
    ident = PillIdentifier(db if db.exists() else ROOT / "data" / "demo" / "pills.db", use_ocr=not args.no_ocr)
    engine = ident.ocr.engine if ident.ocr else None
    print(f"NIH 소비자 사진 {len(photos)}장, OCR {engine or '없음'}", flush=True)

    rows, stats, started = [], Counter(), time.time()
    for n, p in enumerate(photos, 1):
        m = meta[p.name]
        row = {"file": p.name, "ndc": m["ndc"], "name": m["name"], "imprint_true": m["imprint"],
               "shape_true": m["shape"], "color_true": m["color"], "camera": m["camera"][:40],
               "background": m["background"][:40], "rating_imprint": m["rating_imprint"]}
        rows.append(row)
        try:
            found = ident.observe_photo(p)
        except ValueError as e:
            row["error"] = str(e)
            continue
        stats["사진"] += 1
        if not found:
            row["error"] = "알약을 찾지 못함"
            continue
        o = max(found, key=lambda x: x.area)
        stats["분할 성공"] += 1
        if args.save_crops:
            write_image(Path(args.save_crops) / f"{p.stem}.png", o.crop)

        shape_true = SHAPE_TO_KO.get(m["shape"].upper())
        row.update(shape_pred=o.shape, colors_pred="/".join(o.colors), imprint_read=o.imprint)
        if shape_true:
            stats["모양 평가"] += 1
            hit = o.shape == shape_true or frozenset((o.shape, shape_true)) in LENIENT_SHAPE
            stats["모양 일치"] += hit
            row["shape_ok"] = hit
        true_colors = {c.strip().upper() for c in m["color"].replace(",", ";").split(";") if c.strip()}
        if true_colors and o.colors:
            stats["색 평가"] += 1
            hit = KO_TO_NIH_COLOR.get(o.colors[0]) in true_colors
            stats["색 일치"] += hit
            row["color_ok"] = hit
        truth = imprint_tokens(m["imprint"].replace(";", " "))
        read = imprint_tokens(o.imprint)
        if truth and engine:
            stats["각인 평가"] += 1
            comp = imprint_components(read, truth) if read else None
            exact = comp == (1.0, 1.0, 1.0)
            partial = bool(comp) and not exact and (comp[1] >= 0.8 or comp[0] >= 0.6)
            row["imprint_result"] = "정확" if exact else "부분" if partial else "없음" if not read else "오류"
            stats["각인 " + row["imprint_result"]] += 1
            if m["rating_imprint"]:
                stats["각인 정확/" + m["rating_imprint"]] += exact
                stats["각인 평가/" + m["rating_imprint"]] += 1
        if n % 25 == 0:
            print(f"  {n}/{len(photos)} ({(time.time() - started) / n:.1f}초/장)", flush=True)

    out = Path(args.out) if args.out else folder / f"eval_{engine or 'noocr'}.csv"
    fields = list(dict.fromkeys(k for r in rows for k in r))
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)

    def pct(a, b):
        return f"{a}/{b} ({a / b:.0%})" if b else "-"

    print(f"\n=== NIH 소비자 사진 {len(photos)}장 ({time.time() - started:.0f}초, OCR {engine or '없음'}) ===")
    print(f"알약 분할 성공      {pct(stats['분할 성공'], stats['사진'])}")
    print(f"모양 일치           {pct(stats['모양 일치'], stats['모양 평가'])}   (타원형/장방형은 같은 것으로 침)")
    print(f"주색상 일치         {pct(stats['색 일치'], stats['색 평가'])}")
    if engine:
        t = stats["각인 평가"]
        print(f"각인 OCR            정확 {pct(stats['각인 정확'], t)}  부분 {pct(stats['각인 부분'], t)}  "
              f"오류 {pct(stats['각인 오류'], t)}  못 읽음 {pct(stats['각인 없음'], t)}")
        for rating in ("Clear", "Partial", "Unclear", "None"):
            if stats["각인 평가/" + rating]:
                print(f"   사진에서 각인이 {rating:8}: 정확 {pct(stats['각인 정확/' + rating], stats['각인 평가/' + rating])}")
    light = Counter()
    for r in rows:
        kind = "밝은 배경" if any(k in r["background"].lower() for k in LIGHT_BACKGROUND_WORDS) else "기타 배경"
        light[kind + (" 성공" if "error" not in r else " 실패")] += 1
    print("배경별 분할:", dict(sorted(light.items())))
    print(f"\n장별 결과: {out}")


if __name__ == "__main__":
    main()
