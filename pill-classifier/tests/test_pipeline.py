"""합성 도형으로 분할·모양·색·정규화 크롭·점수 계산을 검증한다 (torch/OCR 불필요).

    python -m pytest tests -q
"""
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pillid.attributes import estimate_colors, estimate_shape, name_color_by_rule  # noqa: E402
from pillid.db import PillDB  # noqa: E402
from pillid.imaging import BG_FILL, canonical_crop, segment_pills, segment_reference  # noqa: E402
from pillid.ocr import (ImprintReader, canonical_imprint, imprint_pair_score,  # noqa: E402
                        imprint_similarity, imprint_tokens)
from pillid.pipeline import PillIdentifier  # noqa: E402

BG = (40, 40, 40)
WHITE, YELLOW, RED, BLUE = (240, 240, 240), (60, 220, 240), (40, 40, 200), (200, 90, 30)


def canvas():
    return np.full((600, 600, 3), BG, np.uint8)


def polygon(n, color=WHITE, r=150, rot=-np.pi / 2):
    img = canvas()
    pts = [(300 + r * np.cos(rot + 2 * np.pi * k / n), 300 + r * np.sin(rot + 2 * np.pi * k / n))
           for k in range(n)]
    cv2.fillPoly(img, [np.round(pts).astype(np.int32)], color)
    return img


def circle(color=WHITE):
    img = canvas()
    cv2.circle(img, (300, 300), 150, color, -1)
    return img


def ellipse(color=WHITE, angle=30):
    img = canvas()
    cv2.ellipse(img, (300, 300), (180, 100), angle, 0, 360, color, -1)
    return img


def stadium(left=WHITE, right=WHITE):
    """캡슐 모양 (길이 340, 폭 100). 좌우 색을 다르게 줄 수 있다."""
    img = canvas()
    cv2.rectangle(img, (180, 250), (300, 350), left, -1)
    cv2.circle(img, (180, 300), 50, left, -1)
    cv2.rectangle(img, (300, 250), (420, 350), right, -1)
    cv2.circle(img, (420, 300), 50, right, -1)
    return img


def semicircle(color=WHITE):
    img = canvas()
    cv2.ellipse(img, (300, 250), (160, 160), 0, 0, 180, color, -1)
    return img


def only_region(img):
    regions = segment_pills(img)
    assert len(regions) == 1
    return regions[0]


@pytest.mark.parametrize("make,expected", [
    (circle, "원형"),
    (ellipse, "타원형"),
    (stadium, "장방형"),
    (semicircle, "반원형"),
    (lambda: polygon(3), "삼각형"),
    (lambda: polygon(4, rot=np.pi / 4), "사각형"),
    (lambda: polygon(5), "오각형"),
    (lambda: polygon(6), "육각형"),
    (lambda: polygon(8), "팔각형"),
])
def test_shape(make, expected):
    assert estimate_shape(only_region(make()).contour) == expected


@pytest.mark.parametrize("color,expected", [
    (WHITE, "하양"), (YELLOW, "노랑"), (RED, "빨강"), (BLUE, "파랑"),
])
def test_single_color(color, expected):
    img = circle(color)
    assert estimate_colors(img, only_region(img).mask) == [expected]


def test_two_tone_capsule():
    img = stadium(left=RED, right=WHITE)
    assert set(estimate_colors(img, only_region(img).mask)) == {"빨강", "하양"}


@pytest.mark.parametrize("bgr,expected", [
    ((172, 190, 232), "분홍"),   # 식약처 사진의 '분홍'은 대부분 살구색이다 (색상 12~18도)
    ((120, 215, 150), "연두"),   # 밝은 초록은 '연두'
    ((70, 150, 40), "초록"),     # 어둡고 진한 초록만 '초록'
    ((225, 232, 240), "하양"),   # 채도가 조금 있는 흰색
])
def test_color_rules_follow_mfds_naming(bgr, expected):
    assert name_color_by_rule(bgr) == expected


def reference_photo():
    """식약처 참조 사진처럼 연한 파랑 배경에 앞·뒷면이 좌우로 놓인 사진 (아래에는 띠 광고)."""
    img = np.full((500, 1000, 3), (224, 195, 165), np.uint8)
    cv2.rectangle(img, (0, 450), (999, 499), (150, 120, 100), -1)
    return img


def test_reference_segmentation_keeps_both_capsule_halves():
    """배경과 비슷한 밝은 쪽 절반까지 한 알약으로 잡고, 가장자리에 닿은 띠는 버린다."""
    img = reference_photo()
    for cx in (250, 750):
        cv2.rectangle(img, (cx - 110, 200), (cx, 280), (40, 60, 230), -1)       # 주황빨강 쪽
        cv2.circle(img, (cx - 110, 240), 40, (40, 60, 230), -1)
        cv2.rectangle(img, (cx, 200), (cx + 110, 280), (190, 225, 245), -1)     # 미색 쪽
        cv2.circle(img, (cx + 110, 240), 40, (190, 225, 245), -1)
    regions = segment_reference(img)
    assert len(regions) == 2 and regions[0].bbox[0] < regions[1].bbox[0]
    for r in regions:
        assert 290 <= r.bbox[2] <= 310 and estimate_shape(r.contour) == "장방형"


def test_reference_segmentation_fills_white_pill():
    """흰 알약은 밝은 면이 배경과 비슷해 그림자 쪽만 잡힌다. 볼록 껍질로 메워 원형으로 본다."""
    img = reference_photo()
    for cx in (250, 750):
        cv2.circle(img, (cx + 4, 244), 100, (120, 110, 100), -1)   # 오른쪽 아래 그림자
        cv2.circle(img, (cx, 240), 100, (236, 222, 205), -1)       # 배경과 비슷한 푸르스름한 흰색
        cv2.ellipse(img, (cx, 240), (100, 100), 0, -20, 110, (246, 244, 240), -1)  # 배경과 구별되는 부분
    regions = segment_reference(img)
    assert len(regions) == 2
    for r in regions:
        assert estimate_shape(r.contour) == "원형" and r.bbox[2] >= 195


def test_multiple_pills_are_separated():
    img = canvas()
    cv2.circle(img, (150, 150), 60, WHITE, -1)
    cv2.circle(img, (450, 450), 80, YELLOW, -1)
    assert len(segment_pills(img)) == 2


def test_canonical_crop_aligns_major_axis_horizontally():
    img = ellipse(angle=60)
    crop = canonical_crop(img, only_region(img), size=224)
    fg = np.any(crop != np.array(BG_FILL, np.uint8), axis=2)
    ys, xs = np.nonzero(fg)
    assert (xs.max() - xs.min()) > 1.5 * (ys.max() - ys.min())


def test_imprint_matching():
    assert canonical_imprint("마크 DW 분할선") == "DW"
    assert canonical_imprint("1O5") == canonical_imprint("IOS")  # 혼동 문자 통일
    assert canonical_imprint("V분할선T") == "VT" and canonical_imprint("KDC NTC     8") == "KDCNTCB"
    assert imprint_tokens("TONEX-F 마크") == ("TONEXF",)
    assert imprint_similarity("TYLENOL", "TYLENOL") == 1.0
    # 일부만 읽힘: 정확히 읽은 것보다는 낮고, 무관한 글자보다는 높다
    assert imprint_similarity("XYZ", "TYLENOL") < imprint_similarity("TYLEN", "TYLENOL") < 1.0
    # 조각 순서는 무시한다 (OCR이 읽는 순서와 DB에 적힌 순서가 다를 수 있다)
    assert imprint_similarity("APT AJU", "AJU APT") == 1.0
    assert imprint_similarity("N P", "P분할선N") == 1.0
    assert imprint_similarity("APTAJU", "AJU APT") == 1.0  # OCR이 조각을 붙여 읽어도
    # 영문 OCR이 읽을 수 없는 한글 조각은 정답 쪽에서 뺀다. 전부 한글이면 그대로 비교한다
    assert imprint_similarity("CP", "CP 마이페노") == 1.0
    assert imprint_similarity("CP 마이페노", "CP 마이페노") == 1.0
    assert imprint_similarity("8", "덴티골드") < 0.5
    # 앞/뒤를 바꿔 찍어도 같은 점수
    assert imprint_pair_score(["AB", "CD"], ("CD", "AB")) == imprint_pair_score(["AB", "CD"], ("AB", "CD"))


def test_reader_drops_pill_outline_read_as_letter():
    """캡슐 이음매나 알약 윤곽이 한 글자("D")로 읽히면 버리고, 작은 글자와 여러 글자는 남긴다."""
    def box(x, y, w, h):
        return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]

    class FakeOcr:
        def __init__(self, results):
            self.results = results

        def __call__(self, image, use_cls=None):
            return self.results, None

    crop = np.full((480, 480, 3), BG_FILL, np.uint8)
    crop[175:305, 45:435] = (230, 230, 230)  # 390x130 캡슐
    reader = ImprintReader(engine="rapidocr")
    reader.engine = "rapidocr"

    reader._reader = FakeOcr([(box(240, 175, 190, 130), "D", 0.9)])
    assert reader.read(crop) == ("", 0.0)
    reader._reader = FakeOcr([(box(100, 210, 50, 60), "D", 0.9)])
    assert reader.read(crop)[0] == "D"
    reader._reader = FakeOcr([(box(60, 190, 360, 110), "TYLENOL", 0.9)])
    assert reader.read(crop)[0] == "TYLENOL"


def test_identifier_ranks_matching_item_first(tmp_path):
    db = PillDB(tmp_path / "pills.db")
    db.upsert_many([
        {"ITEM_SEQ": "1", "ITEM_NAME": "흰원형정", "DRUG_SHAPE": "원형", "COLOR_CLASS1": "하양"},
        {"ITEM_SEQ": "2", "ITEM_NAME": "노랑원형정", "DRUG_SHAPE": "원형", "COLOR_CLASS1": "노랑"},
        {"ITEM_SEQ": "3", "ITEM_NAME": "빨강흰캡슐", "DRUG_SHAPE": "장방형",
         "COLOR_CLASS1": "빨강", "COLOR_CLASS2": "하양"},
    ])
    db.close()
    ident = PillIdentifier(tmp_path / "pills.db", use_ocr=False)

    img = canvas()
    cv2.circle(img, (150, 150), 70, YELLOW, -1)
    cv2.rectangle(img, (300, 400), (400, 460), RED, -1)
    cv2.circle(img, (300, 430), 30, RED, -1)
    cv2.rectangle(img, (400, 400), (500, 460), WHITE, -1)
    cv2.circle(img, (500, 430), 30, WHITE, -1)
    photo = tmp_path / "photo.png"
    cv2.imwrite(str(photo), img)

    results = ident.identify_photo(photo, topk=3)
    tops = sorted(r.candidates[0].item_seq for r in results)
    assert tops == ["2", "3"]


def test_eval_photos_groups_front_and_back(tmp_path):
    """eval_photos.py의 파일 이름 규칙: <품목기준코드>_<번호>.jpg 와 _back.jpg 를 한 건으로 묶는다."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
    from eval_photos import load_cases
    for name in ("200808876_1.jpg", "200808876_1_back.jpg", "200808876_2.jpg", "199900054_a.png", "memo.txt"):
        (tmp_path / name).write_bytes(b"")
    cases = load_cases(tmp_path)
    assert [(c["item_seq"], Path(c["front"]).name, Path(c["back"]).name if c["back"] else None) for c in cases] == [
        ("199900054", "199900054_a.png", None),
        ("200808876", "200808876_1.jpg", "200808876_1_back.jpg"),
        ("200808876", "200808876_2.jpg", None),
    ]


def phone_photo():
    """폰 사진처럼 배경 밝기가 한쪽으로 기울고 천 질감 잡음이 있는 사진."""
    rng = np.random.default_rng(0)
    base = np.linspace(70, 150, 1200, dtype=np.float32)[None, :, None]  # 왼쪽 어둡고 오른쪽 밝은 책상
    img = np.repeat(np.repeat(base, 900, axis=0), 3, axis=2) * np.array([0.95, 1.0, 1.05], np.float32)
    img += rng.normal(0, 3, img.shape).astype(np.float32)
    return np.clip(img, 0, 255).astype(np.uint8)


def test_phone_segmentation_ignores_lighting_gradient_and_shadow():
    """조명 기울기 영역이 아니라 작은 알약을 잡고, 알약에 붙은 그림자는 빼서 원형으로 본다."""
    img = phone_photo()
    cv2.circle(img, (520, 470), 42, (50, 55, 60), -1)   # 오른쪽 아래로 진 그림자 (배경보다 어둡고 같은 색조)
    cv2.circle(img, (500, 450), 40, (235, 238, 240), -1)  # 작은 흰 알약 (사진 면적의 0.5%)
    regions = segment_pills(img)
    assert regions, "알약을 찾지 못함"
    r = regions[0]
    x, y, w, h = r.bbox
    assert 440 <= x <= 465 and 400 <= y <= 420 and w <= 100 and h <= 100
    assert estimate_shape(r.contour) == "원형"
    assert estimate_colors(img, r.mask) == ["하양"]
