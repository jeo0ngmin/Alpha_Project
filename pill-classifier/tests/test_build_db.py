"""식약처 API 응답 해석 테스트 (네트워크 불필요)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from build_db import ApiError, parse_response, read_key_file  # noqa: E402


def test_key_file_formats():
    assert read_key_file("abc123\n") == "abc123"
    assert read_key_file("DATA_GO_KR_SERVICE_KEY=abc123") == "abc123"        # .env 한 줄을 그대로 붙여 넣음
    assert read_key_file('# 메모\nMFDS_SERVICE_KEY="abc123"\n') == "abc123"
    assert read_key_file("aB3+/xY==") == "aB3+/xY=="                          # base64 키 끝의 '='는 그대로
    assert read_key_file("ab%2Bcd%3D%3D") == "ab%2Bcd%3D%3D"


def test_normal_response():
    total, items = parse_response({"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                                   "body": {"pageNo": 1, "totalCount": 25537, "numOfRows": 2,
                                            "items": [{"ITEM_SEQ": "1"}, {"ITEM_SEQ": "2"}]}})
    assert total == 25537 and len(items) == 2


def test_items_wrapped_in_item_key_or_single_object():
    _, items = parse_response({"response": {"header": {"resultCode": "00"},
                                            "body": {"totalCount": 1, "items": {"item": {"ITEM_SEQ": "1"}}}}})
    assert items == [{"ITEM_SEQ": "1"}]


def test_key_error_is_not_mistaken_for_empty_result():
    # 실제로 키 없이 호출했을 때 오는 응답 (HTTP 401)
    with pytest.raises(ApiError) as e:
        parse_response({"OpenAPI_ServiceResponse": {"cmmMsgHeader": {
            "errMsg": "SERVICE_KEY_IS_NULL", "returnAuthMsg": "서비스 접근거부", "returnReasonCode": "20"}}})
    assert "SERVICE_KEY_IS_NULL" in str(e.value) and "활용신청" in str(e.value)


def test_api_error_code():
    with pytest.raises(ApiError):
        parse_response({"header": {"resultCode": "30", "resultMsg": "SERVICE KEY IS NOT REGISTERED ERROR."},
                        "body": {}})
