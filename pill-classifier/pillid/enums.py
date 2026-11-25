"""식약처 한글 분류값 ↔ MediMate API enum(DrugShape, ColorClass, LineType) 변환.

변환은 이 파일에서만 한다. Java 백엔드는 API enum만 다룬다.
"""
from __future__ import annotations

SHAPE_TO_API = {
    "원형": "ROUND", "타원형": "OVAL", "장방형": "OBLONG", "삼각형": "TRIANGLE",
    "사각형": "SQUARE", "마름모형": "DIAMOND", "오각형": "PENTAGON", "육각형": "HEXAGON",
    "팔각형": "OCTAGON",
    # TODO(API 명세): DrugShape에 반원형이 없어 OTHER로 보낸다. SEMICIRCLE 추가를 제안.
    "반원형": "OTHER", "기타": "OTHER",
}
# 힌트(사용자 입력) → 점수 계산용 한글 모양. CAPSULE은 식약처 분류상 장방형이다.
API_TO_SHAPE = {v: k for k, v in SHAPE_TO_API.items() if v != "OTHER"}
API_TO_SHAPE.update({"CAPSULE": "장방형", "OTHER": None})

COLOR_TO_API = {
    "하양": "WHITE", "노랑": "YELLOW", "주황": "ORANGE", "분홍": "PINK", "빨강": "RED",
    "갈색": "BROWN", "연두": "LIGHT_GREEN", "초록": "GREEN", "파랑": "BLUE", "남색": "NAVY",
    "보라": "PURPLE", "회색": "GRAY", "검정": "BLACK", "투명": "TRANSPARENT",
    # TODO(API 명세): ColorClass에 청록·자주가 없어 가장 가까운 값으로 임시 매핑. TEAL, MAGENTA 추가를 제안.
    "청록": "BLUE", "자주": "PURPLE",
}
# 힌트 → 점수 계산용 대표 한글 색
API_TO_COLOR = {
    "WHITE": "하양", "YELLOW": "노랑", "ORANGE": "주황", "PINK": "분홍", "RED": "빨강",
    "BROWN": "갈색", "LIGHT_GREEN": "연두", "GREEN": "초록", "BLUE": "파랑", "NAVY": "남색",
    "PURPLE": "보라", "GRAY": "회색", "BLACK": "검정", "TRANSPARENT": "투명",
}
# 검색 필터용: API 색 하나에 대응하는 한글 색 전체 (BLUE → 파랑, 청록)
API_COLOR_MATCHES = {api: {ko for ko, a in COLOR_TO_API.items() if a == api} for api in API_TO_COLOR}

LINE_TYPES = ("NONE", "MINUS", "PLUS", "OTHER")


def is_capsule(form_code_name) -> bool:
    return "캡슐" in (form_code_name or "")


def shape_to_api(shape_ko, capsule: bool = False) -> str | None:
    if not shape_ko:
        return None
    api = SHAPE_TO_API.get(shape_ko.strip(), "OTHER")
    return "CAPSULE" if capsule and api == "OBLONG" else api


def color_to_api(color_ko) -> str | None:
    return COLOR_TO_API.get(color_ko) if color_ko else None


def line_to_api(value) -> str:
    v = (value or "").strip()
    if not v or v == "없음":
        return "NONE"
    if "+" in v:
        return "PLUS"
    if "-" in v:
        return "MINUS"
    return "OTHER"


def to_float(value) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
