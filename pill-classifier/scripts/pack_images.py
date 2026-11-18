"""매니페스트의 이미지를 큰 파일 하나로 묶는다 (학습 속도용). train_embedder.py --pack 으로 읽는다.

학교 GPU 서버처럼 데이터가 NAS에 있으면, 작은 파일 수십만 개를 매 에폭 여는 데 시간이 거의 다 간다
(2026-11-18: GPU 사용률 대부분 0%, 데이터 워커가 디스크 대기 상태). 한 번만 읽어 파일 하나로 묶어 둔다.
JPEG(품질 95)로 다시 저장해 크기를 줄인다 (224px PNG 평균 약 28KB → 약 10KB). 서버 메모리 캐시에 들어가는 크기다.
"""
from __future__ import annotations

import argparse
import io
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.data import read_manifest  # noqa: E402


def encode(path: str, quality: int) -> bytes:
    with Image.open(path) as im:
        buf = io.BytesIO()
        im.convert("RGB").save(buf, "JPEG", quality=quality)
        return buf.getvalue()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--manifest", nargs="+", required=True, help="train_embedder.py에 줄 매니페스트와 똑같이 준다")
    ap.add_argument("--out", required=True)
    ap.add_argument("--quality", type=int, default=95)
    ap.add_argument("--threads", type=int, default=16, help="NAS는 여러 파일을 동시에 읽어야 빠르다")
    args = ap.parse_args()

    rows = read_manifest(args.manifest)
    paths = list(dict.fromkeys(r["path"] for r in rows))  # 중복 제거, 순서 유지
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    idx = np.zeros((len(paths), 2), np.int64)
    off, t0, chunk = 0, time.time(), 2000
    print(f"{len(paths):,}장 묶는 중 → {out}", flush=True)
    with open(f"{out}.tmp", "wb") as f, ThreadPoolExecutor(args.threads) as ex:
        for s in range(0, len(paths), chunk):  # 한꺼번에 다 맡기면 결과가 메모리에 쌓인다
            for i, data in enumerate(ex.map(lambda p: encode(p, args.quality), paths[s:s + chunk]), s):
                f.write(data)
                idx[i] = (off, len(data))
                off += len(data)
            if (s // chunk) % 10 == 9:
                print(f"  {s + chunk:,}/{len(paths):,} ({time.time() - t0:.0f}s)", flush=True)
    with open(f"{out}.paths.txt.tmp", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(paths))
    with open(f"{out}.idx.npy.tmp", "wb") as f:
        np.save(f, idx)
    os.replace(f"{out}.paths.txt.tmp", f"{out}.paths.txt")
    os.replace(f"{out}.idx.npy.tmp", f"{out}.idx.npy")
    os.replace(f"{out}.tmp", out)  # 이 파일이 생기면 다 묶은 것이다
    print(f"완료: {len(paths):,}장, {off / 1e9:.1f}GB, {time.time() - t0:.0f}s → {out}")


if __name__ == "__main__":
    main()
