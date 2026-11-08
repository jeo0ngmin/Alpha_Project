"""Claude Fable 5.1 비전 보조: 알약 관찰과 후보 재순위 (docs/03_AI_OCR_연동설계서.md §3, §6).

Claude 호출이 꺼져 있거나 실패해도 식별은 CV만으로 계속되어야 하므로, PillVision의 메서드는
예외를 던지지 않고 None을 돌려준다.

환경변수
    PILL_AI_PROVIDER        none(기본) | bedrock | anthropic
    PILL_AI_MODEL           비우면 anthropic.claude-fable-5-1(Bedrock) 또는 claude-fable-5-1
    PILL_AI_FALLBACK_MODEL  안전 분류기 거절 시 1회 재시도할 모델 (비우면 재시도 없음)
    PILL_AI_TIMEOUT         요청 제한 시간(초), 기본 40
    AWS_REGION              bedrock일 때 필수. 자격 증명은 AWS 기본 체인(IAM Role, AWS_PROFILE 등)
    ANTHROPIC_API_KEY       anthropic일 때
"""
from __future__ import annotations

import base64
import json
import logging
import os
import time
from pathlib import Path

import cv2
import numpy as np

from .attributes import COLORS, SHAPES

log = logging.getLogger("pillid.vision")

PROMPTS_DIR = Path(__file__).parent / "prompts"
OBSERVE_PROMPT = "pill-observe-v1"
RERANK_PROMPT = "pill-rerank-v1"
MAX_SIDE = 1024       # 설계서 기준 입력 크기. 이미지 토큰 ≈ 가로×세로÷750 이라 크기가 곧 비용이다
RERANK_TOP_N = 5
RERANK_MARGIN = 0.1   # 1·2위 점수 차가 이보다 작으면 재순위 대상이고, 재순위 뒤에도 작으면 저신뢰다
MATCH_VALUES = {"SAME_LIKELY": 1.0, "POSSIBLE": 0.5, "DIFFERENT": 0.0}


# ── 구조화 출력 스키마: 객체마다 모든 속성을 required로 두고 추가 속성을 막는다 ──────────

def _object(**properties) -> dict:
    return {"type": "object", "properties": properties, "required": list(properties),
            "additionalProperties": False}


def _enum(*values) -> dict:
    return {"type": "string", "enum": list(values)}


def _nullable(schema: dict) -> dict:
    return {"anyOf": [schema, {"type": "null"}]}


OBSERVATION_SCHEMA = _object(
    pillFound={"type": "boolean"},
    multiplePills={"type": "boolean"},
    form=_enum("정제", "경질캡슐", "연질캡슐", "기타"),
    shape=_enum(*SHAPES),
    colors={"type": "array", "items": _enum(*COLORS)},
    imprintFront=_nullable({"type": "string"}),
    imprintBack=_nullable({"type": "string"}),
    lineFront=_nullable(_enum("없음", "-", "+", "기타")),
    lineBack=_nullable(_enum("없음", "-", "+", "기타")),
    imprintReadability=_enum("HIGH", "MEDIUM", "LOW"),
    imageIssues={"type": "array", "items": _enum("BLURRY", "GLARE", "BAD_BACKGROUND", "CROPPED", "NO_PILL")},
)

RERANK_SCHEMA = _object(
    results={"type": "array", "items": _object(
        itemSeq={"type": "string"},
        match=_enum(*MATCH_VALUES),
        imprintMatches=_nullable({"type": "boolean"}),
        shapeMatches={"type": "boolean"},
        colorMatches={"type": "boolean"},
        note={"type": "string"},
    )},
)


# ── Claude 호출 ───────────────────────────────────────────────────────────

class AiError(Exception):
    """kind: REFUSED | TRUNCATED | INVALID_OUTPUT | BAD_REQUEST | UNAVAILABLE (docs/03 §3.5)."""

    def __init__(self, kind: str, message: str):
        super().__init__(f"{kind}: {message}")
        self.kind = kind


class ClaudeJson:
    """Claude에 한 번 물어 JSON Schema에 맞는 JSON을 받는다 (구조화 출력, 1턴).

    Fable 5.1 규칙: thinking·temperature를 보내지 않는다(400). 깊이는 effort로만 조절한다.
    거절은 대체 모델로 1회, 잘림은 max_tokens 1.5배로 1회 재시도한다. Bedrock에서는 서버 측
    fallbacks를 쓸 수 없어서 여기서 직접 재시도한다.
    """

    def __init__(self, client, model: str, fallback_model: str | None = None):
        self.client = client
        self.model = model
        self.fallback_model = fallback_model or None

    def complete(self, task: str, system: str, content: list[dict], schema: dict,
                 effort: str = "low", max_tokens: int = 8000) -> dict:
        model = self.model
        used_fallback = retried_truncation = retried_invalid = False
        while True:
            message = self._send(task, model, system, content, schema, effort, max_tokens)
            if message.stop_reason == "refusal":
                details = getattr(message, "stop_details", None)
                if self.fallback_model and not used_fallback:
                    log.warning("Claude 거절 task=%s model=%s details=%s → 대체 모델로 재시도", task, model, details)
                    used_fallback, model = True, self.fallback_model
                    continue
                raise AiError("REFUSED", str(details))
            if message.stop_reason == "max_tokens":
                if not retried_truncation:
                    retried_truncation, max_tokens = True, max_tokens * 3 // 2
                    continue
                raise AiError("TRUNCATED", f"max_tokens={max_tokens}")
            text = "".join(b.text for b in message.content if b.type == "text")
            try:
                return json.loads(text)
            except ValueError:
                if not retried_invalid:
                    retried_invalid = True
                    continue
                raise AiError("INVALID_OUTPUT", "JSON 파싱 실패") from None

    def _send(self, task, model, system, content, schema, effort, max_tokens):
        import anthropic

        started = time.monotonic()
        try:
            message = self.client.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=system,
                output_config={"effort": effort, "format": {"type": "json_schema", "schema": schema}},
                messages=[{"role": "user", "content": content}],
            )
        except anthropic.BadRequestError as e:
            # 요청(설정·코드) 문제라 재시도하지 않는다. 모델 ID, 스키마를 먼저 의심할 것
            raise AiError("BAD_REQUEST", e.message) from e
        except (anthropic.AuthenticationError, anthropic.PermissionDeniedError, anthropic.NotFoundError) as e:
            raise AiError("BAD_REQUEST", f"자격 증명·모델 접근 권한·모델 ID 확인 필요: {e.message}") from e
        except anthropic.APIStatusError as e:
            # SDK가 429·5xx는 이미 2회 재시도했다
            raise AiError("UNAVAILABLE", f"HTTP {e.status_code}") from e
        except anthropic.APIConnectionError as e:
            raise AiError("UNAVAILABLE", "연결 실패 또는 타임아웃") from e
        log.info("Claude 호출 task=%s model=%s stop=%s input_tokens=%s output_tokens=%s ms=%d",
                 task, model, message.stop_reason, message.usage.input_tokens, message.usage.output_tokens,
                 (time.monotonic() - started) * 1000)
        return message


def claude_from_env() -> ClaudeJson | None:
    """환경변수로 Claude 클라이언트를 만든다. 꺼져 있으면 None. 키는 코드·파일에 두지 않는다."""
    provider = os.environ.get("PILL_AI_PROVIDER", "none").strip().lower()
    if provider in ("", "none"):
        return None
    import anthropic

    timeout = float(os.environ.get("PILL_AI_TIMEOUT", "40"))
    if provider == "bedrock":
        client = anthropic.AnthropicBedrockMantle(aws_region=os.environ.get("AWS_REGION"), timeout=timeout)
        default_model = "anthropic.claude-fable-5-1"  # Bedrock은 anthropic. 접두사가 붙는다
    elif provider == "anthropic":
        client = anthropic.Anthropic(timeout=timeout)
        default_model = "claude-fable-5-1"
    else:
        raise ValueError(f"PILL_AI_PROVIDER는 none, bedrock, anthropic 중 하나여야 합니다: {provider}")
    return ClaudeJson(client, os.environ.get("PILL_AI_MODEL") or default_model,
                      os.environ.get("PILL_AI_FALLBACK_MODEL"))


# ── 알약 관찰·재순위 ──────────────────────────────────────────────────────

def _load_prompt(name: str) -> str:
    return (PROMPTS_DIR / f"{name}.md").read_text(encoding="utf-8")


def _image_block(img: np.ndarray) -> dict:
    """BGR 이미지를 긴 변 1024px JPEG로 줄여 이미지 블록으로 만든다."""
    h, w = img.shape[:2]
    scale = min(1.0, MAX_SIDE / max(h, w))
    if scale < 1.0:
        img = cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 90])
    if not ok:
        raise ValueError("이미지를 JPEG로 변환할 수 없습니다.")
    return {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg",
                                        "data": base64.standard_b64encode(buf.tobytes()).decode("ascii")}}


class PillVision:
    def __init__(self, claude: ClaudeJson, observe_effort: str = "low", rerank_effort: str = "medium",
                 max_tokens: int = 8000):
        self.claude = claude
        self.observe_effort = observe_effort
        self.rerank_effort = rerank_effort
        self.max_tokens = max_tokens
        self._observe_system = _load_prompt(OBSERVE_PROMPT)
        self._rerank_system = _load_prompt(RERANK_PROMPT)

    def observe(self, front: np.ndarray, back: np.ndarray | None = None, strict: bool = False) -> dict | None:
        """§6.1 관찰. OBSERVATION_SCHEMA 형태의 dict, 실패하면 None (strict=True면 AiError를 그대로 던진다)."""
        def content():
            blocks = [{"type": "text", "text": "앞면 사진:"}, _image_block(front)]
            if back is not None:
                blocks += [{"type": "text", "text": "뒷면 사진:"}, _image_block(back)]
            return blocks
        return self._call(OBSERVE_PROMPT, self._observe_system, content, OBSERVATION_SCHEMA, self.observe_effort,
                          strict)

    def rerank(self, front: np.ndarray, back: np.ndarray | None, candidates: list[dict]) -> dict[str, str] | None:
        """§6.3 재순위. candidates: [{itemSeq, itemName, description, image(BGR 또는 None)}].

        {itemSeq: 'SAME_LIKELY' | 'POSSIBLE' | 'DIFFERENT'}, 실패하면 None.
        """
        if not candidates:
            return None

        def content():
            blocks = [{"type": "text", "text": "사용자 사진 (앞면):"}, _image_block(front)]
            if back is not None:
                blocks += [{"type": "text", "text": "사용자 사진 (뒷면):"}, _image_block(back)]
            for c in candidates:
                has_image = c.get("image") is not None
                blocks.append({"type": "text", "text": (
                    f"후보 itemSeq={c['itemSeq']}\n이름: {c['itemName']}\n식약처 기재 외형: {c['description']}\n"
                    + ("참조 사진:" if has_image else "참조 사진: 없음"))})
                if has_image:
                    blocks.append(_image_block(c["image"]))
            return blocks

        result = self._call(RERANK_PROMPT, self._rerank_system, content, RERANK_SCHEMA, self.rerank_effort)
        if result is None:
            return None
        known = {c["itemSeq"] for c in candidates}
        return {r["itemSeq"]: r["match"] for r in result.get("results", [])
                if r.get("itemSeq") in known and r.get("match") in MATCH_VALUES}

    def _call(self, task, system, build_content, schema, effort, strict: bool = False) -> dict | None:
        try:
            return self.claude.complete(task, system, build_content(), schema, effort, self.max_tokens)
        except AiError as e:
            if strict:
                raise
            log.warning("Claude 호출 실패 task=%s kind=%s: %s", task, e.kind, e)
        except Exception:  # noqa: BLE001 - 비전 보조가 없어도 식별은 계속되어야 한다
            log.exception("Claude 요청·응답 처리 실패 task=%s", task)
        return None


def needs_rerank(candidates: list[dict], low_confidence: bool) -> bool:
    return bool(candidates) and (low_confidence or _margin(candidates) < RERANK_MARGIN)


def apply_rerank(candidates: list[dict], matches: dict[str, str]) -> tuple[list[dict], bool]:
    """판정을 점수에 반영해 다시 정렬한다. (새 후보 목록, 저신뢰 여부)를 돌려준다.

    최종 점수 = 0.5 × CV 점수 + 0.5 × 판정값. 판정받지 않은 후보(6위 이하)는 판정값 0으로 계산하고,
    '다른 약'(DIFFERENT) 판정은 점수와 상관없이 목록 뒤로 보낸다.
    """
    rescored = []
    for c in candidates:
        value = MATCH_VALUES.get(matches.get(c["itemSeq"]), 0.0)
        rescored.append({**c, "score": round(0.5 * c["score"] + 0.5 * value, 4),
                         "scoreBreakdown": {**c["scoreBreakdown"], "rerank": value}})
    rescored.sort(key=lambda c: (matches.get(c["itemSeq"]) == "DIFFERENT", -c["score"]))
    confident = matches.get(rescored[0]["itemSeq"]) == "SAME_LIKELY" and _margin(rescored) >= RERANK_MARGIN
    return rescored, not confident


def _margin(candidates: list[dict]) -> float:
    return 1.0 if len(candidates) < 2 else candidates[0]["score"] - candidates[1]["score"]
