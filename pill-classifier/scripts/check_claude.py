"""Claude Fable 5.1 접근 권한을 받은 뒤 실제 호출이 되는지 확인한다 (요금이 발생한다).

    # AWS Bedrock
    $env:PILL_AI_PROVIDER = "bedrock"; $env:AWS_REGION = "us-east-1"
    # 또는 Anthropic API 직접
    $env:PILL_AI_PROVIDER = "anthropic"; $env:ANTHROPIC_API_KEY = "..."

    python scripts/check_claude.py                       # 데모 캡슐 사진 관찰
    python scripts/check_claude.py 내사진.jpg --back 뒷면.jpg

실패하면 kind를 본다: BAD_REQUEST(모델 ID·스키마·권한), UNAVAILABLE(네트워크·한도), REFUSED(안전 분류기).
"""
import argparse
import json
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.imaging import read_image  # noqa: E402
from pillid.vision import AiError, PillVision, claude_from_env  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("photo", nargs="?", default=str(ROOT / "data" / "demo" / "photos" / "red_white_capsule.jpg"))
    ap.add_argument("--back")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    claude = claude_from_env()
    if claude is None:
        sys.exit("PILL_AI_PROVIDER가 설정되지 않았습니다 (bedrock 또는 anthropic).")
    vision = PillVision(claude)
    front = read_image(args.photo)
    back = read_image(args.back) if args.back else None
    print(f"모델: {claude.model} / 사진: {args.photo}")
    try:
        result = vision.observe(front, back, strict=True)  # strict: 실패 원인을 숨기지 않고 그대로 본다
    except AiError as e:
        sys.exit(f"실패 ({e.kind}): {e}\n원인: {e.__cause__}")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("\n성공. 이제 서버에 같은 환경변수를 주면 /v1/identify가 Claude를 함께 씁니다.")


if __name__ == "__main__":
    main()
