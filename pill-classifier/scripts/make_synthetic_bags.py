"""약봉투 인식 평가용 가상 약봉투 사진과 정답표를 만든다 (실제 사진이 모이기 전 임시 평가용).

    python scripts/make_synthetic_bags.py --out data/eval/bags_synth --n 12

양식 4가지(표형 복약안내문, 시간 칸 체크형 봉투, 번호 목록형, 처방전형 표)에 식약처 DB의 실제 품목명을 넣고,
폰으로 찍은 것처럼 기울기·원근·그림자·흐림·JPEG 압축을 더한다. 환자 정보는 모두 가짜다.
--hard: 약 개수를 늘리고(최대 7개) 사진 상태 L3(구겨짐·반사광·손가락 가림·어두운 잡음)를 섞는다.
정답은 <out>/truth.json (사진마다 약 목록: 품목기준코드, 인쇄 이름, 1회량, 단위, 하루 횟수, 시점, 식사 관계, 일수).
실제 약봉투보다 깨끗하고 양식이 단순하므로, 여기서 나온 수치는 상한에 가깝다고 봐야 한다.
"""
from __future__ import annotations

import argparse
import json
import random
import re
import sqlite3
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FONT_DIR = Path("C:/Windows/Fonts")
FONTS = {"regular": ["malgun.ttf", "NanumGothic.ttf", "gulim.ttc"], "bold": ["malgunbd.ttf", "NanumGothicBold.ttf", "malgun.ttf"]}
PHARMACIES = ["온누리푸른약국", "행복한약국", "새봄약국", "중앙메디약국", "하나로약국", "건강한사랑약국"]
HOSPITALS = ["서울내과의원", "튼튼정형외과의원", "맑은이비인후과", "연세가정의학과의원", "바른신경과의원"]
NAMES = ["김민수", "이영희", "박정호", "최순자", "정대현", "한미경"]
SLOT_KO = {"MORNING": "아침", "LUNCH": "점심", "DINNER": "저녁", "BEDTIME": "취침전"}
SLOTS_BY_TIMES = {1: [["MORNING"], ["DINNER"], ["BEDTIME"]], 2: [["MORNING", "DINNER"]],
                  3: [["MORNING", "LUNCH", "DINNER"]], 4: [["MORNING", "LUNCH", "DINNER", "BEDTIME"]]}
MEALS = [("AFTER_MEAL", "식후 30분", 30), ("AFTER_MEAL", "식후 즉시", 0), ("BEFORE_MEAL", "식전 30분", 30),
         ("ANYTIME", "", None)]
CAUTIONS = ["졸음이 올 수 있으니 운전에 주의하세요", "술과 함께 드시지 마세요", "충분한 물과 함께 드세요",
            "증상이 좋아져도 정해진 기간 동안 드세요", "어지러우면 의사와 상의하세요"]


def font(kind: str, size: int):
    for name in FONTS[kind]:
        p = FONT_DIR / name
        if p.exists():
            return ImageFont.truetype(str(p), size)
    sys.exit("한글 글꼴을 찾지 못했습니다 (C:/Windows/Fonts/malgun.ttf)")


def printed_name(item_name: str) -> str:
    """약봉투에 흔한 표기: 괄호 속 성분명을 빼고 밀리그램 → mg."""
    n = re.sub(r"\(.*?\)", "", item_name).strip()
    return n.replace("밀리그램", "mg").replace("마이크로그램", "mcg").replace("그램", "g")


def pick_drugs(db: Path, rng: random.Random) -> list[dict]:
    con = sqlite3.connect(db)
    rows = con.execute("select item_seq, item_name, form_code_name from pills where item_name like '%밀리그램%' "
                       "and (item_name like '%정%' or item_name like '%캡슐%') and length(item_name) <= 30").fetchall()
    con.close()
    rng.shuffle(rows)
    seen, out = set(), []
    for seq, name, form in rows:
        p = printed_name(name)
        if p in seen or len(p) < 4:
            continue
        seen.add(p)
        out.append({"item_seq": seq, "item_name": name, "printed": p,
                    "unit": "CAPSULE" if any(k in f"{form or ''} {name}" for k in ("캡슐", "스팬슐")) else "TABLET"})
    return out


def make_regimen(drug: dict, rng: random.Random, days: int) -> dict:
    times = rng.choices([1, 2, 3, 4], weights=[3, 3, 5, 1])[0]
    slots = rng.choice(SLOTS_BY_TIMES[times])
    meal, meal_text, offset = rng.choice(MEALS if times < 4 else MEALS[:2])
    dose = rng.choices([1, 2, 0.5], weights=[8, 2, 1])[0]
    return {**drug, "dose": dose, "times": times, "slots": slots, "meal": meal, "meal_text": meal_text,
            "meal_offset": offset, "days": days}


def dose_text(d: dict) -> str:
    unit = "캡슐" if d["unit"] == "CAPSULE" else "정"
    v = {0.5: "1/2", 1: "1", 2: "2"}[d["dose"]]
    return f"{v}{unit}"


# ── 양식 ──────────────────────────────────────────────────────────────────

def header(draw, w, y, info, f_title, f_body):
    draw.text((w // 2, y), f"{info['pharmacy']} 복약안내", font=f_title, fill=(20, 20, 20), anchor="mt")
    y += 70
    draw.text((40, y), f"환자명: {info['patient']}    조제일자: {info['date']}", font=f_body, fill=(30, 30, 30))
    y += 40
    draw.text((40, y), f"처방병원: {info['hospital']}    교부번호: {info['rx_no']}", font=f_body, fill=(30, 30, 30))
    return y + 55


def style_table(meds, info, cautions):
    w, h = 1400, 1500
    img = Image.new("RGB", (w, h), (252, 251, 246))
    d = ImageDraw.Draw(img)
    ft, fb, fs = font("bold", 46), font("regular", 28), font("regular", 26)
    y = header(d, w, 40, info, ft, fb)
    cols = [40, 500, 620, 740, 860, 1360]
    heads = ["약품명", "1회", "1일", "일수", "복용법"]
    d.rectangle((cols[0], y, cols[-1], y + 50), fill=(225, 232, 240), outline=(60, 60, 60), width=2)
    for x, t in zip(cols, heads):
        d.text((x + 12, y + 10), t, font=fb, fill=(10, 10, 10))
    y += 50
    for m in meds:
        d.rectangle((cols[0], y, cols[-1], y + 64), outline=(90, 90, 90), width=1)
        for x in cols[1:-1]:
            d.line((x, y, x, y + 64), fill=(90, 90, 90))
        how = " ".join([*(SLOT_KO[s] for s in m["slots"]), m["meal_text"]]).strip()
        for x, t in zip(cols, [m["printed"], dose_text(m), f"{m['times']}회", f"{m['days']}일", how]):
            d.text((x + 12, y + 18), t, font=fs, fill=(15, 15, 15))
        y += 64
    y += 40
    d.text((40, y), "※ 복약 시 주의사항", font=fb, fill=(150, 20, 20))
    for c in cautions:
        y += 42
        d.text((60, y), f"- {c}", font=fs, fill=(30, 30, 30))
    return img


def style_envelope(meds, info, cautions):
    """같은 시간에 먹는 약끼리 묶은 봉투형. 시간 칸에 ■ 표시."""
    w, h = 1100, 1450
    img = Image.new("RGB", (w, h), (246, 241, 226))
    d = ImageDraw.Draw(img)
    ft, fb, fs, fbig = font("bold", 50), font("regular", 30), font("regular", 27), font("bold", 40)
    d.text((w // 2, 40), info["pharmacy"], font=ft, fill=(30, 60, 120), anchor="mt")
    d.text((50, 120), f"{info['patient']} 님    {info['date']} 조제    {info['hospital']}", font=fb, fill=(30, 30, 30))
    slots = meds[0]["slots"]
    y = 190
    x = 50
    for s in ("MORNING", "LUNCH", "DINNER", "BEDTIME"):
        mark = "■" if s in slots else "□"
        d.text((x, y), f"{mark} {SLOT_KO[s]}", font=fbig, fill=(20, 20, 20))
        x += 250
    y += 80
    meal = meds[0]["meal_text"] or "시간 관계없이"
    d.text((50, y), f"{meal} 복용  ·  1일 {meds[0]['times']}회  ·  {meds[0]['days']}일분", font=fbig, fill=(160, 30, 30))
    y += 90
    d.line((50, y, w - 50, y), fill=(120, 120, 120), width=2)
    y += 25
    for m in meds:
        d.text((60, y), m["printed"], font=fb, fill=(15, 15, 15))
        d.text((w - 60, y), f"1회 {dose_text(m)}", font=fb, fill=(15, 15, 15), anchor="ra")
        y += 58
    y += 30
    for c in cautions:
        d.text((60, y), f"* {c}", font=fs, fill=(60, 60, 60))
        y += 42
    d.text((50, h - 80), f"교부번호 {info['rx_no']}  ☎ 02-123-4567", font=fs, fill=(90, 90, 90))
    return img


def style_list(meds, info, cautions):
    w, h = 1150, 1400
    img = Image.new("RGB", (w, h), (255, 255, 255))
    d = ImageDraw.Draw(img)
    ft, fb, fs = font("bold", 42), font("regular", 29), font("regular", 26)
    y = header(d, w, 40, info, ft, fb)
    for i, m in enumerate(meds, 1):
        d.text((40, y), f"{i}. {m['printed']}", font=fb, fill=(10, 10, 10))
        y += 44
        when = ", ".join(SLOT_KO[s] for s in m["slots"])
        line = f"   {dose_text(m)}씩 1일 {m['times']}회 ({when}) {m['meal_text']} {m['days']}일분".replace("  ", " ")
        d.text((40, y), line, font=fs, fill=(40, 40, 40))
        y += 60
    y += 20
    for c in cautions:
        d.text((40, y), f"○ {c}", font=fs, fill=(40, 40, 40))
        y += 40
    return img


def style_prescription(meds, info, cautions):
    """처방전·조제내역서처럼 칸이 많은 표. 약 이름 칸이 넓고 숫자 칸이 좁다."""
    w, h = 1500, 1300 + 60 * max(0, len(meds) - 4)
    img = Image.new("RGB", (w, h), (255, 255, 255))
    d = ImageDraw.Draw(img)
    ft, fb, fs = font("bold", 40), font("regular", 26), font("regular", 24)
    d.text((w // 2, 30), "조 제 내 역 및 복 약 안 내", font=ft, fill=(0, 0, 0), anchor="mt")
    d.text((40, 100), f"성명: {info['patient']}   조제일: {info['date']}   {info['pharmacy']}", font=fb, fill=(0, 0, 0))
    d.text((40, 140), f"의료기관: {info['hospital']}   처방전 교부번호: {info['rx_no']}", font=fb, fill=(0, 0, 0))
    cols = [40, 620, 760, 900, 1040, 1460]
    heads = ["처방 의약품의 명칭", "1회 투약량", "1일 투여횟수", "총 투약일수", "용법"]
    y = 200
    for x, t in zip(cols, heads):
        d.text((x + 8, y + 12), t, font=fs, fill=(0, 0, 0))
    d.rectangle((cols[0], y, cols[-1], y + 52), outline=(0, 0, 0), width=2)
    y += 52
    for m in meds:
        d.rectangle((cols[0], y, cols[-1], y + 56), outline=(60, 60, 60), width=1)
        for x in cols[1:-1]:
            d.line((x, y, x, y + 56), fill=(60, 60, 60))
        dose = {0.5: "0.5", 1: "1", 2: "2"}[m["dose"]]
        how = "/".join(SLOT_KO[s] for s in m["slots"]) + (" " + m["meal_text"] if m["meal_text"] else "")
        for x, t in zip(cols, [m["printed"], dose, str(m["times"]), str(m["days"]), how]):
            d.text((x + 8, y + 15), t, font=fs, fill=(0, 0, 0))
        y += 56
    y += 40
    for c in cautions:
        d.text((40, y), f"▶ {c}", font=fs, fill=(0, 0, 0))
        y += 38
    return img


# ── 사진처럼 ──────────────────────────────────────────────────────────────

def crumple(a: np.ndarray, rng: random.Random) -> np.ndarray:
    """구겨진 종이: 부드러운 변위장으로 글자 줄을 휘게 하고, 접힌 자국에 음영을 준다."""
    h, w = a.shape[:2]
    g = np.random.default_rng(rng.randint(0, 1 << 30))
    dx = cv2.GaussianBlur(g.normal(0, 1, (h // 40 + 2, w // 40 + 2)).astype(np.float32), (0, 0), 1.2)
    dy = cv2.GaussianBlur(g.normal(0, 1, (h // 40 + 2, w // 40 + 2)).astype(np.float32), (0, 0), 1.2)
    amp = rng.uniform(5, 10)
    dx = cv2.resize(dx, (w, h)) * amp
    dy = cv2.resize(dy, (w, h)) * amp
    xx, yy = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))
    out = cv2.remap(a, xx + dx, yy + dy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    shade = 1 - 0.12 * np.clip(cv2.Sobel(dy, cv2.CV_32F, 0, 1, ksize=5) / 30, -1, 1)
    return np.clip(out * shade[..., None], 0, 255).astype(np.uint8)


def glare_and_finger(a: np.ndarray, rng: random.Random) -> np.ndarray:
    h, w = a.shape[:2]
    out = a.astype(np.float32)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = rng.uniform(0.3, 0.8) * w, rng.uniform(0.2, 0.8) * h
    rx, ry = rng.uniform(0.08, 0.18) * w, rng.uniform(0.04, 0.1) * h
    out += rng.uniform(80, 150) * np.exp(-(((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2))[..., None]
    # 가장자리를 가리는 손가락 (글자를 덮지 않도록 아래쪽 모서리)
    fx, fy = rng.choice([0.05, 0.95]) * w, rng.uniform(0.9, 1.0) * h
    finger = ((xx - fx) / (0.07 * w)) ** 2 + ((yy - fy) / (0.16 * h)) ** 2 < 1
    out[finger] = np.array([120, 150, 205], np.float32) * rng.uniform(0.8, 1.05)
    return np.clip(out, 0, 255).astype(np.uint8)


def photograph(img: Image.Image, rng: random.Random, level: int) -> np.ndarray:
    """level 0: 스캔처럼 깨끗, 1: 보통 폰 사진, 2: 기울고 그늘지고 흐린 사진, 3: 2 + 구겨짐·반사광·손가락·어두운 잡음."""
    a = cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)
    if level == 3:
        a = glare_and_finger(crumple(a, rng), rng)
    h, w = a.shape[:2]
    pad = 120
    canvas = np.full((h + 2 * pad, w + 2 * pad, 3), rng.choice([(70, 80, 90), (150, 140, 120), (40, 40, 45)]), np.uint8)
    canvas[pad:pad + h, pad:pad + w] = a
    H, W = canvas.shape[:2]
    hard = level == 3
    level = min(level, 2)
    if level:
        j = [40, 90][level - 1]
        src = np.float32([[0, 0], [W, 0], [W, H], [0, H]])
        dst = src + np.float32([[rng.uniform(-j, j), rng.uniform(-j, j)] for _ in range(4)])
        canvas = cv2.warpPerspective(canvas, cv2.getPerspectiveTransform(src, dst), (W, H), borderMode=cv2.BORDER_REPLICATE)
        ang = rng.uniform(-1, 1) * [4, 9][level - 1]
        M = cv2.getRotationMatrix2D((W / 2, H / 2), ang, 1.0)
        canvas = cv2.warpAffine(canvas, M, (W, H), borderMode=cv2.BORDER_REPLICATE)
        # 한쪽이 어두운 조명
        gx = np.linspace(rng.uniform(0.55, 0.85) if level == 2 else 0.85, 1.0, W)[None, :, None]
        if rng.random() < 0.5:
            gx = gx[:, ::-1]
        canvas = np.clip(canvas * gx * (rng.uniform(0.55, 0.7) if hard else rng.uniform(0.85, 1.05)), 0, 255).astype(np.uint8)
        k = [3, 5][level - 1]
        canvas = cv2.GaussianBlur(canvas, (k, k), 0)
        noise = np.random.default_rng(rng.randint(0, 1 << 30)).normal(0, 10 if hard else [3, 6][level - 1], canvas.shape)
        canvas = np.clip(canvas + noise, 0, 255).astype(np.uint8)
        scale = [0.8, 0.65][level - 1]
        canvas = cv2.resize(canvas, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    return canvas


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(ROOT / "data" / "eval" / "bags_synth"))
    ap.add_argument("--n", type=int, default=12)
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--hard", action="store_true", help="약 최대 7개, 처방전형 양식, 사진 상태 L3 포함")
    args = ap.parse_args()
    rng = random.Random(args.seed)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    pool = pick_drugs(Path(args.db), rng)
    styles = [("table", style_table), ("envelope", style_envelope), ("list", style_list)]
    if args.hard:
        styles.append(("rx", style_prescription))
    truth = {}
    for i in range(args.n):
        style_name, style = styles[i % len(styles)]
        level = (i // len(styles)) % (4 if args.hard else 3)
        days = rng.choice([3, 5, 7, 14, 30])
        drugs = [pool.pop() for _ in range(rng.randint(3, 7) if args.hard else rng.randint(2, 4))]
        if style_name == "envelope":  # 한 봉투 = 같은 시간에 먹는 약
            base = make_regimen(drugs[0], rng, days)
            meds = [{**dr, **{k: base[k] for k in ("times", "slots", "meal", "meal_text", "meal_offset", "days")},
                     "dose": rng.choices([1, 2, 0.5], weights=[8, 2, 1])[0]} for dr in drugs]
            meds[0]["dose"] = base["dose"]
        else:
            meds = [make_regimen(dr, rng, days) for dr in drugs]
        info = {"pharmacy": rng.choice(PHARMACIES), "hospital": rng.choice(HOSPITALS), "patient": rng.choice(NAMES),
                "date": f"2026-11-{rng.randint(1, 18):02d}", "rx_no": f"2026{rng.randint(10**7, 10**8 - 1)}"}
        cautions = rng.sample(CAUTIONS, rng.randint(1, 2))
        photo = photograph(style(meds, info, cautions), rng, level)
        name = f"bag{i + 1:02d}_{style_name}_L{level}.jpg"
        cv2.imwrite(str(out / name), photo, [cv2.IMWRITE_JPEG_QUALITY, [92, 85, 75, 70][level]])
        truth[name] = {"style": style_name, "level": level, "cautions": cautions,
                       "medications": [{k: m[k] for k in ("item_seq", "item_name", "printed", "dose", "unit", "times",
                                                          "slots", "meal", "meal_offset", "days")} for m in meds]}
    (out / "truth.json").write_text(json.dumps(truth, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{args.n}장 → {out}")


if __name__ == "__main__":
    main()
