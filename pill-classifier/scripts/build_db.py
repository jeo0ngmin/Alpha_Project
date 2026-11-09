"""식약처 '의약품 낱알식별 정보' OpenAPI 전체를 받아 SQLite DB로 저장하고, 참조 이미지를 내려받는다.

서비스키: 공공데이터포털(data.go.kr)에서 '식품의약품안전처_의약품 낱알식별 정보' 활용신청 후 발급.
키는 pill-classifier/.mfds_service_key 파일에 한 줄로 저장한다 (환경변수 MFDS_SERVICE_KEY도 가능).
    python scripts/build_db.py --download-images
"""
from __future__ import annotations

import argparse
import math
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.parse import unquote

import requests
from tqdm import tqdm

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.db import PillDB  # noqa: E402

API_URL = "https://apis.data.go.kr/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03"
IMAGE_MAGIC = (b"\xff\xd8", b"\x89PNG", b"GIF8")
# 서비스키를 한 줄로 저장해 두는 파일. Git에 올리지 않는다 (.gitignore)
KEY_FILE = ROOT / ".mfds_service_key"


class ApiError(RuntimeError):
    pass


def read_key_file(text: str) -> str:
    """키 파일 내용에서 키만 꺼낸다. .env의 한 줄(NAME=값, 따옴표 포함)을 그대로 붙여 넣은 경우도 받는다."""
    line = next((ln.strip() for ln in text.splitlines() if ln.strip() and not ln.strip().startswith("#")), "")
    name, sep, value = line.partition("=")
    # 포털의 디코딩 키는 '='로 끝나는 base64라서, 앞부분이 변수 이름 모양일 때만 NAME=값으로 본다
    if sep and name.replace("_", "").isalnum() and name.upper() == name and not name.isdigit() and value:
        line = value
    return line.strip().strip("'\"")


def parse_response(data: dict) -> tuple[int, list[dict]]:
    # 키 오류는 정상 응답과 다른 모양으로 온다: {"OpenAPI_ServiceResponse": {"cmmMsgHeader": {...}}}
    gateway_error = (data.get("OpenAPI_ServiceResponse") or {}).get("cmmMsgHeader")
    if gateway_error:
        raise ApiError(f"{gateway_error.get('returnAuthMsg')} ({gateway_error.get('errMsg')}). "
                       "서비스키가 맞는지, 활용신청이 승인됐는지 확인하세요. 발급 직후에는 반영까지 1~2시간 걸릴 수 있습니다.")
    root = data.get("response", data)
    header, body = root.get("header") or {}, root.get("body") or {}
    code = str(header.get("resultCode", "00"))
    if code not in ("00", "0"):
        raise ApiError(f"API 오류 {code}: {header.get('resultMsg')}")
    items = body.get("items") or []
    if isinstance(items, dict):
        items = items.get("item") or []
    if isinstance(items, dict):
        items = [items]
    return int(body.get("totalCount") or 0), items


def fetch_page(session: requests.Session, key: str, page: int, rows: int, retries: int = 4):
    params = {"serviceKey": key, "pageNo": page, "numOfRows": rows, "type": "json"}
    last: Exception | None = None
    for attempt in range(retries):
        try:
            r = session.get(API_URL, params=params, timeout=30)
            if r.status_code >= 500:
                raise requests.HTTPError(f"HTTP {r.status_code}")
            try:
                data = r.json()
            except ValueError:
                # 키 오류 등은 type=json이어도 XML로 올 때가 있다
                raise ApiError(f"JSON이 아닌 응답(HTTP {r.status_code}). 서비스키와 활용신청 승인 상태를 "
                               f"확인하세요:\n{r.text[:500]}") from None
            return parse_response(data)
        except requests.RequestException as e:
            last = e
            time.sleep(2 ** attempt)
    raise ApiError(f"{page}페이지 요청 실패: {last}")


_local = threading.local()


def _session() -> requests.Session:
    if not hasattr(_local, "s"):
        _local.s = requests.Session()
        _local.s.headers["User-Agent"] = "Mozilla/5.0 (pill-classifier)"
    return _local.s


class Throttle:
    """모든 다운로드 스레드가 함께 지키는 요청 간격.

    식약처 이미지 서버는 요청이 잦으면 429로 거절한다. 거절당하면 전체가 쉬고, 이후 간격을 늘린다.
    """

    def __init__(self, interval: float, max_interval: float = 3.0):
        self.interval = interval
        self.max_interval = max_interval
        self._lock = threading.Lock()
        self._next_at = 0.0

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            at = max(now, self._next_at)
            self._next_at = at + self.interval
        time.sleep(max(0.0, at - now))

    def back_off(self, seconds: float) -> None:
        with self._lock:
            self._next_at = max(self._next_at, time.monotonic() + seconds)
            self.interval = min(self.interval * 1.5, self.max_interval)


def download_image(url: str, path: Path, throttle: Throttle, retries: int = 6) -> str:
    if path.exists() and path.stat().st_size > 0:
        return "skip"
    for attempt in range(retries):
        throttle.wait()
        r = _session().get(url, timeout=30)
        if r.status_code in (429, 503):
            retry_after = r.headers.get("Retry-After", "")
            pause = float(retry_after) if retry_after.isdigit() else min(30 * 2 ** attempt, 300)
            throttle.back_off(pause)
            continue
        break
    r.raise_for_status()
    if not r.content.startswith(IMAGE_MAGIC):
        raise ValueError(f"이미지가 아닌 응답 ({r.headers.get('Content-Type')})")
    tmp = path.with_suffix(".part")
    tmp.write_bytes(r.content)
    tmp.replace(path)
    return "ok"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--service-key", default=os.environ.get("MFDS_SERVICE_KEY"),
                    help="공공데이터포털 서비스키 (기본: 환경변수 MFDS_SERVICE_KEY)")
    ap.add_argument("--db", default=str(ROOT / "data" / "pills.db"))
    ap.add_argument("--rows", type=int, default=100, help="페이지당 건수")
    ap.add_argument("--max-pages", type=int, help="테스트용: 앞쪽 N페이지만 받기")
    ap.add_argument("--download-images", action="store_true", help="ITEM_IMAGE 참조 이미지도 내려받기")
    ap.add_argument("--images-dir", default=str(ROOT / "data" / "images"))
    ap.add_argument("--images-only", action="store_true", help="DB는 그대로 두고 빠진 참조 이미지만 받기")
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--interval", type=float, default=0.25,
                    help="이미지 요청 간 최소 간격(초). 서버가 429로 거절하면 자동으로 늘어난다")
    args = ap.parse_args()

    db = PillDB(args.db)
    if not args.images_only:
        fetch_all(ap, args, db)
    if args.download_images or args.images_only:
        download_all_images(args, db)
    db.close()


def fetch_all(ap, args, db: PillDB) -> None:
    service_key = args.service_key
    if not service_key and KEY_FILE.is_file():
        service_key = read_key_file(KEY_FILE.read_text(encoding="utf-8-sig"))
    if not service_key:
        ap.error(f"서비스키가 필요합니다: {KEY_FILE.name} 파일(권장), 환경변수 MFDS_SERVICE_KEY, 또는 --service-key")
    # 포털의 '인코딩' 키를 넣어도 동작하도록 디코딩한다 (requests가 다시 인코딩함)
    key = unquote(service_key) if "%" in service_key else service_key

    session = requests.Session()
    total, items = fetch_page(session, key, 1, args.rows)
    pages = math.ceil(total / args.rows) if total else 1
    if args.max_pages:
        pages = min(pages, args.max_pages)
    if total == 0:
        raise ApiError("API가 0건을 돌려줬습니다. 서비스 버전이 바뀌었을 수 있습니다 (API_URL 확인).")
    print(f"전체 {total:,}건, {pages}페이지 수집")
    db.upsert_many(items)
    for page in tqdm(range(2, pages + 1), desc="API", initial=1, total=pages):
        _, items = fetch_page(session, key, page, args.rows)
        db.upsert_many(items)
        time.sleep(0.05)
    print(f"DB 저장 완료: {db.count():,}건 → {args.db}")


def download_all_images(args, db: PillDB) -> None:
    img_dir = Path(args.images_dir)
    img_dir.mkdir(parents=True, exist_ok=True)
    tasks = [(it["item_image"], img_dir / f"{it['item_seq']}.jpg")
             for it in db.all_items() if it.get("item_image")]
    todo = [(url, path) for url, path in tasks if not (path.exists() and path.stat().st_size > 0)]
    print(f"참조 이미지 {len(tasks):,}장 중 {len(tasks) - len(todo):,}장은 이미 있음 → {len(todo):,}장 받기")
    throttle = Throttle(args.interval)
    failures = []
    with ThreadPoolExecutor(args.workers) as ex:
        futs = {ex.submit(download_image, url, path, throttle): url for url, path in todo}
        for f in tqdm(as_completed(futs), total=len(futs), desc="이미지", mininterval=5):
            try:
                f.result()
            except Exception as e:  # noqa: BLE001 - 개별 실패는 건너뛰고 집계만 한다
                failures.append(f"{futs[f]} ({e})")
    for line in failures[:5]:
        print(f"실패: {line}")
    if len(failures) > 5:
        print(f"… 외 {len(failures) - 5:,}건")
    print(f"이미지 {len(tasks) - len(failures):,}/{len(tasks):,}장 → {img_dir}"
          + (" (다시 실행하면 빠진 것만 이어서 받습니다)" if failures else ""))


if __name__ == "__main__":
    try:
        main()
    except ApiError as e:
        sys.exit(f"[실패] {e}")
