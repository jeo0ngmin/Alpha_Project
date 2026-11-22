"""추론 서버 API 테스트. 데모 DB와 합성 사진을 쓴다 (OCR/torch 불필요).

    python -m pytest tests -q
"""
import base64
import json
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pillid.demo import build_demo  # noqa: E402
from pillid.vision import OBSERVATION_SCHEMA, RERANK_SCHEMA, AiError, PillVision  # noqa: E402
from server.app import create_app  # noqa: E402


@pytest.fixture(scope="module")
def demo(tmp_path_factory):
    return build_demo(tmp_path_factory.mktemp("demo"))


@pytest.fixture(scope="module")
def client(demo):
    db_path, _ = demo
    with TestClient(create_app(model_path="", index_path="", db_path=db_path, use_ocr=False, internal_key="", trusted_items=None)) as c:
        yield c


def post_photo(client, photos_dir, name, back=None, hints=None):
    files = {"front": (name, (photos_dir / name).read_bytes(), "image/jpeg")}
    if back:
        files["back"] = (back, (photos_dir / back).read_bytes(), "image/jpeg")
    data = {"hints": json.dumps(hints)} if hints else {}
    return client.post("/v1/identify", files=files, data=data)


def test_health(client):
    body = client.get("/health").json()
    assert body["status"] == "UP" and body["items"] == 7 and body["ocr"] is False


@pytest.mark.parametrize("photo,expected_seq,shape,colors", [
    ("yellow_round.jpg", "DEMO0002", "ROUND", {"YELLOW"}),
    ("red_white_capsule.jpg", "DEMO0003", "CAPSULE", {"RED", "WHITE"}),  # 두 색의 순서는 면적 순이라 고정되지 않음
    ("blue_oval.jpg", "DEMO0004", "OVAL", {"BLUE"}),
    ("pink_triangle.jpg", "DEMO0005", "TRIANGLE", {"PINK"}),
    ("orange_hexagon.jpg", "DEMO0007", "HEXAGON", {"ORANGE"}),
])
def test_identify_demo_photos(client, demo, photo, expected_seq, shape, colors):
    body = post_photo(client, demo[1], photo).json()
    assert body["status"] == "COMPLETED"
    assert body["candidates"][0]["itemSeq"] == expected_seq
    f = body["features"]
    assert f["drugShape"] == shape
    assert {f["colorClass1"], f["colorClass2"]} - {None} == colors
    assert set(body["candidates"][0]["scoreBreakdown"]) == {"shape", "color"}


def test_multiple_pills_asks_for_retake(client, demo):
    body = post_photo(client, demo[1], "two_pills.jpg").json()
    assert body["status"] == "NOT_FOUND" and body["reason"] == "MULTIPLE_PILLS" and body["pillCount"] == 2


def test_no_pill(client, demo):
    body = post_photo(client, demo[1], "no_pill.jpg").json()
    assert body["status"] == "NOT_FOUND" and body["reason"] == "NO_PILL"


def test_hints_override_vision(client, demo):
    # 사진은 노란 원형이지만 사용자가 흰색이라고 입력하면 흰 원형정이 1위가 된다
    body = post_photo(client, demo[1], "yellow_round.jpg", hints={"colorClass": ["WHITE"]}).json()
    assert body["candidates"][0]["itemSeq"] == "DEMO0001"
    assert body["features"]["colorClass1"] == "WHITE"


class FakeClaude:
    """정해 둔 JSON을 돌려주는 Claude 대역. 받은 요청을 기록한다."""

    def __init__(self, observation=None, rerank=None, fail=False):
        self.responses = {"pill-observe-v1": observation, "pill-rerank-v1": rerank}
        self.fail = fail
        self.calls = []

    def complete(self, task, system, content, schema, effort="low", max_tokens=8000):
        self.calls.append({"task": task, "content": content, "effort": effort})
        if self.fail or self.responses[task] is None:
            raise AiError("UNAVAILABLE", "테스트용 실패")
        return self.responses[task]


def seen(shape="원형", colors=("노랑",), imprint_front=None, readability="HIGH", issues=(), **extra):
    return {"pillFound": True, "multiplePills": False, "form": "정제", "shape": shape, "colors": list(colors),
            "imprintFront": imprint_front, "imprintBack": None, "lineFront": "없음", "lineBack": None,
            "imprintReadability": readability, "imageIssues": list(issues), **extra}


def identify_with(demo, claude, photo="yellow_round.jpg", hints=None, images_dir=None, trusted_items=None):
    app = create_app(model_path="", index_path="", db_path=demo[0], use_ocr=False, internal_key="", vision=PillVision(claude),
                     images_dir=images_dir, trusted_items=trusted_items)
    with TestClient(app) as c:
        return post_photo(c, demo[1], photo, hints=hints).json()


def test_ai_imprint_is_scored_even_without_ocr(demo):
    # OCR이 꺼져 있어도 Claude가 읽은 각인으로 각인 점수를 계산한다
    claude = FakeClaude(observation=seen(imprint_front="YH"))
    body = identify_with(demo, claude)
    top = body["candidates"][0]
    assert top["itemSeq"] == "DEMO0002" and top["scoreBreakdown"]["imprint"] == 1.0
    assert body["features"]["printFront"] == "YH" and body["observationUsed"] is True
    assert [c["task"] for c in claude.calls] == ["pill-observe-v1"]  # 확신하므로 재순위는 부르지 않는다


def test_ai_observation_is_blended_with_cv(demo):
    # CV는 노랑으로 보지만 Claude가 '하양 + DW'라고 하면 흰 원형정(DW)이 1위가 된다
    body = identify_with(demo, FakeClaude(observation=seen(colors=["하양"], imprint_front="DW",
                                                            readability="MEDIUM")))
    top = body["candidates"][0]
    assert top["itemSeq"] == "DEMO0001"
    assert top["scoreBreakdown"]["color"] == pytest.approx(0.65)  # (CV 0.3 + 관찰 1.0) / 2


def test_unreadable_ai_imprint_is_ignored(demo):
    body = identify_with(demo, FakeClaude(observation=seen(imprint_front="XX", readability="LOW")))
    assert "imprint" not in body["candidates"][0]["scoreBreakdown"]
    assert body["features"]["printFront"] is None


def test_user_hint_beats_ai_observation(demo):
    body = identify_with(demo, FakeClaude(observation=seen(colors=["하양"], imprint_front="DW")),
                         hints={"colorClass": ["YELLOW"], "printText": "YH"})
    top = body["candidates"][0]
    assert top["itemSeq"] == "DEMO0002"
    assert top["scoreBreakdown"]["color"] == 1.0 and top["scoreBreakdown"]["imprint"] == 1.0


def test_claude_seeing_no_pill_asks_for_retake(demo):
    body = identify_with(demo, FakeClaude(observation={**seen(), "pillFound": False, "imageIssues": ["NO_PILL"]}))
    assert body["status"] == "NOT_FOUND" and body["reason"] == "NO_PILL" and body["imageIssues"] == ["NO_PILL"]


def test_claude_single_pill_overrides_cv_blob_count(demo):
    # CV는 덩어리 2개로 보지만 Claude가 알약 한 개라고 하면 가장 큰 것으로 식별을 계속한다
    body = identify_with(demo, FakeClaude(observation=seen()), photo="two_pills.jpg")
    assert body["status"] == "COMPLETED"
    body = identify_with(demo, FakeClaude(observation={**seen(), "multiplePills": True}), photo="two_pills.jpg")
    assert body["status"] == "NOT_FOUND" and body["reason"] == "MULTIPLE_PILLS"


def test_unsure_result_is_reranked(demo, tmp_path):
    # CV는 노랑, Claude는 하양이라고 보면 노랑 원형정과 흰 원형정이 동점이 된다 → 재순위 대상
    (tmp_path / "DEMO0001.jpg").write_bytes((demo[1] / "yellow_round.jpg").read_bytes())
    claude = FakeClaude(observation=seen(colors=["하양"], issues=["GLARE"]),
                        rerank={"results": [
                            {"itemSeq": "DEMO0001", "match": "DIFFERENT", "note": "각인이 다름"},
                            {"itemSeq": "DEMO0002", "match": "SAME_LIKELY", "note": "각인 일치"},
                            {"itemSeq": "NOT_A_CANDIDATE", "match": "SAME_LIKELY", "note": "무시되어야 함"}]})
    body = identify_with(demo, claude, images_dir=tmp_path)

    assert body["reranked"] is True and body["imageIssues"] == ["GLARE"]
    assert body["candidates"][0]["itemSeq"] == "DEMO0002"
    assert body["candidates"][0]["scoreBreakdown"]["rerank"] == 1.0
    assert body["candidates"][-1]["itemSeq"] == "DEMO0001"  # '다른 약' 판정은 맨 뒤로
    assert body["lowConfidence"] is False
    rerank_call = claude.calls[1]
    assert rerank_call["task"] == "pill-rerank-v1" and rerank_call["effort"] == "medium"
    texts = [b["text"] for b in rerank_call["content"] if b["type"] == "text"]
    assert any("itemSeq=DEMO0001" in t and t.endswith("참조 사진:") for t in texts)
    assert any("itemSeq=DEMO0002" in t and t.endswith("참조 사진: 없음") for t in texts)
    # 사용자 사진 1장 + 참조 사진이 있는 후보 1개
    assert sum(b["type"] == "image" for b in rerank_call["content"]) == 2


def test_claude_failure_falls_back_to_cv(demo):
    body = identify_with(demo, FakeClaude(fail=True))
    assert body["status"] == "COMPLETED" and body["candidates"][0]["itemSeq"] == "DEMO0002"
    assert body["observationUsed"] is False and body["reranked"] is False


def test_images_sent_to_claude_are_shrunk(demo):
    claude = FakeClaude(observation=seen())
    identify_with(demo, claude)
    image = next(b for b in claude.calls[0]["content"] if b["type"] == "image")
    assert image["source"]["media_type"] == "image/jpeg"
    decoded = cv2.imdecode(np.frombuffer(base64.b64decode(image["source"]["data"]), np.uint8), cv2.IMREAD_COLOR)
    assert max(decoded.shape[:2]) == 1024  # 데모 사진은 1200×900


def test_schemas_follow_structured_output_rules():
    def check(schema):
        if schema.get("type") == "object":
            assert schema["additionalProperties"] is False
            assert schema["required"] == list(schema["properties"])
            for child in schema["properties"].values():
                check(child)
        for child in schema.get("anyOf", []) + ([schema["items"]] if "items" in schema else []):
            check(child)
    check(OBSERVATION_SCHEMA)
    check(RERANK_SCHEMA)

def test_reference_image_endpoint(demo, tmp_path):
    (tmp_path / "DEMO0001.jpg").write_bytes((demo[1] / "yellow_round.jpg").read_bytes())
    with TestClient(create_app(model_path="", index_path="", db_path=demo[0], use_ocr=False, internal_key="", images_dir=tmp_path)) as c:
        assert c.get("/v1/items/DEMO0001/image").headers["content-type"] == "image/jpeg"
        assert c.get("/v1/items/DEMO0002/image").status_code == 404
        assert c.get("/v1/items/..%2Fpills.db/image").status_code == 404
        body = post_photo(c, demo[1], "yellow_round.jpg").json()
        has_image = {x["itemSeq"]: x["hasImage"] for x in body["candidates"]}
        assert has_image["DEMO0001"] is True and has_image["DEMO0002"] is False


def test_front_and_back(client, demo):
    body = post_photo(client, demo[1], "yellow_round.jpg", back="yellow_round.jpg").json()
    assert body["sidesUsed"] == 2 and body["candidates"][0]["itemSeq"] == "DEMO0002"


def test_invalid_hints_and_image(client, demo):
    assert post_photo(client, demo[1], "yellow_round.jpg", hints={"drugShape": "STAR"}).status_code == 400
    r = client.post("/v1/identify", files={"front": ("x.jpg", b"not an image", "image/jpeg")})
    assert r.status_code == 415


def test_item_detail_and_search(client):
    item = client.get("/v1/items/DEMO0003").json()
    assert item["drugShape"] == "CAPSULE" and item["appearance"]["colorClass2"] == "WHITE"
    assert client.get("/v1/items/NOPE").status_code == 404

    body = client.get("/v1/items", params={"drugShape": "ROUND"}).json()
    assert {i["itemSeq"] for i in body["items"]} == {"DEMO0001", "DEMO0002"}
    body = client.get("/v1/items", params={"printText": "ab", "lineFront": "PLUS"}).json()
    assert [i["itemSeq"] for i in body["items"]] == ["DEMO0006"]
    body = client.get("/v1/items", params={"limit": 3}).json()
    assert body["total"] == 7 and body["nextOffset"] == 3


def test_internal_key(demo):
    with TestClient(create_app(model_path="", index_path="", db_path=demo[0], use_ocr=False, internal_key="secret")) as c:
        assert c.get("/v1/items/DEMO0001").status_code == 401
        assert c.get("/v1/items/DEMO0001", headers={"X-Internal-Key": "secret"}).status_code == 200


def rerank_to_demo2():
    return FakeClaude(observation=seen(colors=["하양"]),
                      rerank={"results": [{"itemSeq": "DEMO0001", "match": "DIFFERENT", "note": ""},
                                          {"itemSeq": "DEMO0002", "match": "SAME_LIKELY", "note": ""}]})


def test_untrained_top_item_is_never_confident(demo, tmp_path):
    # 재비교로 확신이 생겨도 1위가 학습하지 않은 약이면 '확실하지 않음'으로 낸다
    (tmp_path / "DEMO0001.jpg").write_bytes((demo[1] / "yellow_round.jpg").read_bytes())
    trusted = identify_with(demo, rerank_to_demo2(), images_dir=tmp_path, trusted_items={"DEMO0002"})
    assert trusted["candidates"][0]["itemSeq"] == "DEMO0002" and trusted["lowConfidence"] is False

    untrusted = identify_with(demo, rerank_to_demo2(), images_dir=tmp_path, trusted_items={"DEMO0001"})
    assert untrusted["candidates"][0]["itemSeq"] == "DEMO0002" and untrusted["lowConfidence"] is True


def test_default_trusts_nothing_without_embedding(demo):
    with TestClient(create_app(model_path="", index_path="", db_path=demo[0], use_ocr=False, internal_key="")) as c:
        assert c.get("/health").json()["trustedItems"] == 0
        body = post_photo(c, demo[1], "yellow_round.jpg", hints={"printText": "YH"}).json()
        assert body["status"] == "COMPLETED" and body["lowConfidence"] is True


def test_trained_items_file(tmp_path):
    from server.app import load_trained_items
    f = tmp_path / "trained.txt"
    f.write_text("# 설명\n201603070\n\n 201603090 \n", encoding="utf-8")
    assert load_trained_items(f) == {"201603070", "201603090"}
    assert load_trained_items(tmp_path / "없음.txt") == set()
