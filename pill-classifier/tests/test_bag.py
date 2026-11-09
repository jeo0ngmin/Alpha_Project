"""약봉투 인식: CLOVA OCR과 Claude는 가짜로 바꿔 흐름과 검증 규칙을 확인한다."""
import json

import cv2
import httpx
import numpy as np
import pytest
from fastapi.testclient import TestClient

from pillid.bag import (BagError, BagReader, ClovaOcr, DrugMatcher, Line, OcrField, assemble_lines, check_ocr,
                        load_clova_config, mask_lines, validate)

ITEMS = [
    {"item_seq": "1001", "item_name": "타이레놀정500밀리그람(아세트아미노펜)", "entp_name": "한국얀센", "form_code_name": "필름코팅정"},
    {"item_seq": "1002", "item_name": "타이레놀정160밀리그람(아세트아미노펜)", "entp_name": "한국얀센", "form_code_name": "정제"},
    {"item_seq": "2001", "item_name": "뮤코스타정(레바미피드)", "entp_name": "한국오츠카제약", "form_code_name": "필름코팅정"},
    {"item_seq": "3001", "item_name": "아모잘탄캡슐5/50밀리그램", "entp_name": "한미약품", "form_code_name": "경질캡슐제"},
]

LINES = [
    Line(1, 0.99, "행복약국 복약안내"),
    Line(2, 0.98, "환자명 : [이름]"),
    Line(3, 0.97, "약품명 | 1회 | 1일 | 일수"),
    Line(4, 0.96, "타이레놀정500밀리그람 | 1 | 3 | 5"),
    Line(5, 0.95, "뮤코스타정 | 1 | 3 | 5"),
    Line(6, 0.95, "매 식후 30분에 복용하세요"),
]


def med(name, corrected=None, lines=(4, 6), quotes=None, dose=1, times=3, days=5, slots=("MORNING", "LUNCH", "EVENING"),
        confidence="HIGH", unit="정"):
    return {"nameAsPrinted": name, "nameCorrected": corrected or name, "strength": None, "dosePerTake": dose,
            "doseUnit": unit, "timesPerDay": times, "slots": list(slots), "mealRelation": "AFTER_MEAL",
            "mealOffsetMin": 30, "durationDays": days, "instructions": None, "cautions": [], "evidenceLines": list(lines),
            "evidenceQuotes": quotes if quotes is not None else [name], "confidence": confidence, "uncertainReason": None}


def extraction(*meds):
    return {"documentType": "MEDICATION_BAG", "pharmacyName": "행복약국", "hospitalName": None,
            "dispensedDate": "2026-11-01", "medications": list(meds), "generalInstructions": [], "generalCautions": [],
            "unreadable": []}


class FakeClaude:
    model = "fake-claude"

    def __init__(self, *answers):
        self.answers = list(answers)
        self.calls = []

    def complete(self, task, system, content, schema, effort="low", max_tokens=8000):
        self.calls.append({"task": task, "content": content})
        return self.answers.pop(0)


class FakeOcr:
    def __init__(self, lines):
        self.lines = lines

    def read(self, jpeg):
        fields = []
        for i, text in enumerate(self.lines):
            x = 10
            for part in text.split(" | "):
                fields.append(OcrField(part, 0.97, x, 20 + i * 40, 12 * len(part), 24))
                x += 12 * len(part) + 60
        return fields


def photo() -> bytes:
    img = np.full((400, 300, 3), 255, np.uint8)
    return cv2.imencode(".jpg", img)[1].tobytes()


# ── 줄 재구성·가림·OCR 판정 ──

def test_fields_become_lines_and_table_cells():
    fields = [OcrField("타이레놀정", 0.9, 10, 100, 60, 20), OcrField("500mg", 0.8, 74, 102, 40, 20),
              OcrField("1", 0.95, 300, 99, 8, 20), OcrField("복용", 0.99, 10, 140, 30, 20)]
    lines = assemble_lines(fields)
    assert [l.text for l in lines] == ["타이레놀정 500mg | 1", "복용"]
    assert lines[0].conf == 0.8 and lines[0].no == 1


def test_personal_information_is_masked():
    lines = [Line(1, 1, "환자명 : 홍길동  주민번호 900101-2345678"), Line(2, 1, "약국 전화 02-123-4567"),
             Line(3, 1, "교부번호 : 2026-1234"), Line(4, 1, "타이레놀정 1정")]
    masked, patient = mask_lines(lines)
    text = "\n".join(l.text for l in masked)
    assert patient == "홍O동"
    assert "홍길동" not in text and "900101" not in text and "4567" not in text and "1234" not in text
    assert "[이름]" in text and "[주민번호]" in text and "[전화]" in text and "[번호]" in text
    assert masked[3].text == "타이레놀정 1정"


def test_too_little_or_blurry_text_asks_for_retake():
    with pytest.raises(BagError) as e:
        check_ocr([OcrField("약", 0.9, 0, 0, 10, 10)])
    assert e.value.code == "OCR_NO_TEXT"
    blurry = [OcrField("타이레놀정을드세요", 0.3, 0, i * 20, 10, 10) for i in range(6)]
    with pytest.raises(BagError) as e:
        check_ocr(blurry)
    assert e.value.code == "OCR_LOW_QUALITY"


# ── 검증 ──

def test_values_without_evidence_are_removed():
    checked = validate([med("타이레놀정500밀리그람", days=7)], LINES)  # 일수 7은 문서에 없다
    assert checked[0].med["durationDays"] is None
    assert any("일수" in r for r in checked[0].reasons)


def test_out_of_range_and_hallucinated_names_are_flagged():
    checked = validate([med("타이레놀정500밀리그람", times=9), med("아스피린정", lines=(5,))], LINES)
    assert checked[0].med["timesPerDay"] is None
    assert not checked[1].evidence_ok  # 근거 줄에 없는 이름


def test_name_correction_must_stay_close_to_printed_name():
    checked = validate([med("뮤코스타정", corrected="리피토정10밀리그램", lines=(5,))], LINES)
    assert checked[0].med["nameCorrected"] == "뮤코스타정"


def test_missing_slots_get_defaults_and_need_review():
    checked = validate([med("뮤코스타정", lines=(5,), slots=())], LINES)
    assert checked[0].med["slots"] == ["MORNING", "LUNCH", "DINNER"] and checked[0].slots_inferred


def test_duplicate_medications_are_merged():
    checked = validate([med("뮤코스타정", lines=(5,)), med("뮤코스타정", lines=(5,))], LINES)
    assert len(checked) == 1 and "같은 약이 두 번 적혀 있습니다" in checked[0].reasons


# ── 품목 대조 ──

def test_strength_decides_between_similar_items():
    matcher = DrugMatcher(ITEMS)
    best = matcher.match("타이레놀정500밀리그램")[0]
    assert best[0]["item_seq"] == "1001" and best[2]
    assert matcher.match("뮤코스타정")[0][0]["item_seq"] == "2001"
    assert matcher.match("전혀다른약") == []


# ── 전체 흐름 ──

def test_bag_is_read_and_matched(tmp_path):
    claude = FakeClaude(extraction(med("타이레놀정500밀리그람", lines=(4, 6), quotes=["타이레놀정500밀리그람 | 1 | 3 | 5"]),
                                   med("뮤코스타정", lines=(5, 6))))
    ocr_lines = ["행복약국 복약안내", "환자명 : 홍길동", "약품명 | 1회 | 1일 | 일수", "타이레놀정500밀리그람 | 1 | 3 | 5",
                 "뮤코스타정 | 1 | 3 | 5", "매 식후 30분에 복용하세요"]
    result = BagReader(ITEMS, claude, FakeOcr(ocr_lines)).recognize(photo())

    assert result["status"] == "COMPLETED", result
    assert result["source"]["patientNameMasked"] == "홍O동"
    sent = claude.calls[0]["content"][0]["text"]
    assert "홍길동" not in sent and "[이름]" in sent
    first, second = result["items"]
    assert first["itemSeq"] == "1001" and first["matched"] and not first["needsReview"]
    assert first["dosageUnit"] == "TABLET" and first["timeSlots"] == ["MORNING", "LUNCH", "DINNER"]
    assert first["mealRelation"] == "AFTER_MEAL" and first["totalQuantity"] == 15
    assert second["itemSeq"] == "2001"
    assert len(claude.calls) == 1  # 문제가 없으면 재확인하지 않는다


def test_problems_trigger_one_recheck_with_the_photo():
    first = extraction(med("뮤코스타정", lines=(5, 6), confidence="LOW"))
    second = extraction(med("뮤코스타정", lines=(5, 6)))
    claude = FakeClaude(first, second)
    ocr_lines = ["행복약국 복약안내 약국", "약품명 | 1회 | 1일 | 일수", "기타 안내 문구입니다", "다른 줄입니다",
                 "뮤코스타정 | 1 | 3 | 5", "매 식후 30분에 복용하세요"]
    result = BagReader(ITEMS, claude, FakeOcr(ocr_lines)).recognize(photo())

    assert result["rechecked"] is True and len(claude.calls) == 2
    assert claude.calls[1]["task"] == "bag-recheck-v2"
    assert claude.calls[1]["content"][0]["type"] == "image"
    assert "<problems>" in claude.calls[1]["content"][1]["text"]


def test_unknown_drug_needs_review_and_keeps_printed_name():
    claude = FakeClaude(extraction(med("데모없는약정", lines=(1,), quotes=["데모없는약정"])))
    result = BagReader(ITEMS, claude, None).recognize(None, ocr_text="데모없는약정 1정 1일 3회 5일분")
    item = result["items"][0]
    assert result["status"] == "PARTIAL" and item["needsReview"] and not item["matched"]
    assert item["itemSeq"] is None and item["refinedName"] == "데모없는약정"
    assert "식약처 자료에서 약을 찾지 못했습니다" in item["reviewReasons"]


def test_failures_are_reported_with_speech():
    no_ai = BagReader(ITEMS, None, None).recognize(None, ocr_text="타이레놀정 1정")
    assert no_ai["status"] == "FAILED" and no_ai["error"]["code"] == "AI_UNAVAILABLE" and no_ai["error"]["ttsMessage"]
    no_ocr = BagReader(ITEMS, FakeClaude(), None).recognize(photo())
    assert no_ocr["error"]["code"] == "OCR_UNAVAILABLE"
    empty = BagReader(ITEMS, FakeClaude(extraction()), None).recognize(None, ocr_text="안내문만 있습니다")
    assert empty["error"]["code"] == "NO_MEDICATION"


# ── CLOVA OCR 호출 ──

def clova(handler):
    return ClovaOcr("https://example.invalid/ocr", "secret-value", client=httpx.Client(transport=httpx.MockTransport(handler)))


def test_clova_request_and_response():
    seen = {}

    def handler(request):
        seen["secret"] = request.headers["X-OCR-SECRET"]
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"images": [{"inferResult": "SUCCESS", "fields": [
            {"inferText": "타이레놀정", "inferConfidence": 0.98,
             "boundingPoly": {"vertices": [{"x": 10, "y": 20}, {"x": 70, "y": 20}, {"x": 70, "y": 40}, {"x": 10, "y": 40}]}}]}]})

    fields = clova(handler).read(b"jpeg")
    assert seen["secret"] == "secret-value" and seen["body"]["images"][0]["format"] == "jpg"
    assert seen["body"]["lang"] == "ko"
    assert fields == [OcrField("타이레놀정", 0.98, 10, 20, 60, 20)]


def test_clova_errors():
    calls = []

    def down(request):
        calls.append(1)
        return httpx.Response(503)
    with pytest.raises(BagError) as e:
        clova(down).read(b"jpeg")
    assert e.value.code == "OCR_UNAVAILABLE" and len(calls) == 2  # 한 번 다시 시도

    with pytest.raises(BagError) as e:
        clova(lambda r: httpx.Response(200, json={"images": [{"inferResult": "FAILURE"}]})).read(b"jpeg")
    assert e.value.code == "OCR_FAILED"
    with pytest.raises(BagError) as e:
        clova(lambda r: httpx.Response(401, text="secret-value")).read(b"jpeg")
    assert e.value.code == "OCR_UNAVAILABLE" and "secret-value" not in str(e.value)


def test_clova_key_file(tmp_path, monkeypatch):
    monkeypatch.delenv("CLOVA_OCR_INVOKE_URL", raising=False)
    monkeypatch.delenv("CLOVA_OCR_SECRET", raising=False)
    monkeypatch.delenv("CLOVA_OCR_KEY_FILE", raising=False)
    assert load_clova_config(tmp_path) is None
    (tmp_path / ".clova_ocr").write_text("# 설명\nCLOVA_OCR_INVOKE_URL=https://x/general\nCLOVA_OCR_SECRET=\"abc==\"\n",
                                         encoding="utf-8")
    assert load_clova_config(tmp_path) == ("https://x/general", "abc==")


# ── 추론 서버 API ──

def test_server_endpoint(tmp_path):
    from pillid.demo import build_demo
    from server.app import create_app

    db_path, _ = build_demo(tmp_path)
    claude = FakeClaude(extraction(med("타이레놀정500밀리그람", lines=(1,), quotes=["타이레놀정500밀리그람"])))
    app = create_app(model_path="", index_path="", db_path=db_path, use_ocr=False, internal_key="",
                     bag_reader=BagReader(ITEMS, claude, None))
    with TestClient(app) as c:
        health = c.get("/health").json()
        assert health["bagAi"] is True and health["bagOcr"] is False
        assert c.post("/v1/bag/recognize").status_code == 400
        body = c.post("/v1/bag/recognize", data={"ocrText": "타이레놀정500밀리그람 1정 1일 3회 5일분"}).json()
        assert body["items"][0]["itemSeq"] == "1001" and body["processingMs"] >= 0


def test_cautions_are_kept_only_when_printed():
    lines = LINES + [Line(7, 0.95, "졸음이 올 수 있으니 운전을 피하세요"), Line(8, 0.95, "뮤코스타정: 우유와 함께 드시지 마세요")]
    m = med("뮤코스타정", lines=(5, 8))
    m["cautions"] = ["우유와 함께 드시지 마세요", "술을 마시지 마세요"]  # 두 번째는 문서에 없다
    extracted = extraction(m)
    extracted["generalCautions"] = ["졸음이 올 수 있으니 운전을 피하세요", "임산부는 의사와 상의하세요"]
    text = "\n".join(l.text for l in lines)
    result = BagReader(ITEMS, FakeClaude(extracted), None).recognize(None, ocr_text=text)
    assert result["items"][0]["cautions"] == ["우유와 함께 드시지 마세요"]
    assert result["generalCautions"] == ["졸음이 올 수 있으니 운전을 피하세요"]
