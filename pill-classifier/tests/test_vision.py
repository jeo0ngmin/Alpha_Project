"""ClaudeJson의 재시도 규칙(docs/03 §3.5)을 가짜 SDK 클라이언트로 검증한다. 실제 호출은 scripts/check_claude.py."""
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pillid.vision import AiError, ClaudeJson, apply_rerank, claude_from_env, needs_rerank  # noqa: E402


def message(stop_reason="end_turn", text='{"ok": true}'):
    return SimpleNamespace(stop_reason=stop_reason, stop_details="category=test",
                           content=[SimpleNamespace(type="thinking", thinking=""),
                                    SimpleNamespace(type="text", text=text)],
                           usage=SimpleNamespace(input_tokens=10, output_tokens=5))


class FakeSdk:
    """client.messages.create를 흉내 낸다. 준비한 응답을 순서대로 돌려주고 요청 인자를 기록한다."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []
        self.messages = SimpleNamespace(create=self._create)

    def _create(self, **kwargs):
        self.requests.append(kwargs)
        return self.responses.pop(0)


def complete(sdk, fallback=None):
    return ClaudeJson(sdk, "claude-fable-5-1", fallback).complete(
        "pill-observe-v1", "system", [{"type": "text", "text": "x"}], {"type": "object"}, "low", 8000)


def test_request_follows_fable_rules():
    sdk = FakeSdk(message())
    assert complete(sdk) == {"ok": True}
    req = sdk.requests[0]
    assert req["model"] == "claude-fable-5-1" and req["max_tokens"] == 8000
    assert req["output_config"] == {"effort": "low", "format": {"type": "json_schema", "schema": {"type": "object"}}}
    # Fable 5.1은 thinking·temperature·tool_choice를 보내면 400이다
    assert not {"thinking", "temperature", "top_p", "tools", "tool_choice"} & set(req)


def test_refusal_retries_once_on_fallback_model():
    sdk = FakeSdk(message("refusal"), message())
    assert complete(sdk, fallback="claude-opus-4-8") == {"ok": True}
    assert [r["model"] for r in sdk.requests] == ["claude-fable-5-1", "claude-opus-4-8"]


def test_refusal_without_fallback_raises():
    with pytest.raises(AiError) as e:
        complete(FakeSdk(message("refusal")))
    assert e.value.kind == "REFUSED"


def test_refusal_on_fallback_too_raises():
    with pytest.raises(AiError) as e:
        complete(FakeSdk(message("refusal"), message("refusal")), fallback="claude-opus-4-8")
    assert e.value.kind == "REFUSED"


def test_truncation_retries_once_with_more_tokens():
    sdk = FakeSdk(message("max_tokens", text='{"ok": tr'), message())
    assert complete(sdk) == {"ok": True}
    assert [r["max_tokens"] for r in sdk.requests] == [8000, 12000]
    with pytest.raises(AiError) as e:
        complete(FakeSdk(message("max_tokens"), message("max_tokens")))
    assert e.value.kind == "TRUNCATED"


def test_invalid_json_retries_once():
    assert complete(FakeSdk(message(text="not json"), message())) == {"ok": True}
    with pytest.raises(AiError) as e:
        complete(FakeSdk(message(text="not json"), message(text="still not json")))
    assert e.value.kind == "INVALID_OUTPUT"


def test_disabled_by_default(monkeypatch):
    monkeypatch.delenv("PILL_AI_PROVIDER", raising=False)
    assert claude_from_env() is None
    monkeypatch.setenv("PILL_AI_PROVIDER", "openai")
    with pytest.raises(ValueError):
        claude_from_env()


def test_rerank_scoring():
    cands = [{"itemSeq": s, "score": sc, "scoreBreakdown": {}} for s, sc in
             [("A", 0.60), ("B", 0.58), ("C", 0.50), ("D", 0.20)]]
    assert needs_rerank(cands, low_confidence=False)             # 1·2위 차이 0.02 < 0.1
    assert not needs_rerank([cands[0], cands[3]], low_confidence=False)
    assert needs_rerank([cands[0], cands[3]], low_confidence=True)

    ranked, low = apply_rerank(cands, {"A": "DIFFERENT", "B": "SAME_LIKELY", "C": "POSSIBLE"})
    assert [c["itemSeq"] for c in ranked] == ["B", "C", "D", "A"]  # D는 판정 없음(0), A는 '다른 약'이라 맨 뒤
    assert ranked[0]["score"] == pytest.approx(0.79)               # 0.5×0.58 + 0.5×1.0
    assert low is False

    _, low = apply_rerank(cands, {"A": "POSSIBLE", "B": "POSSIBLE"})
    assert low is True  # '같아 보임' 판정이 없으면 여전히 저신뢰
