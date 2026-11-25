"""서비스키 없이 시험할 수 있는 데모 DB(가짜 품목 7개)와 합성 알약 사진을 만든다.

    python scripts/make_demo_data.py
    → data/demo/pills.db, data/demo/photos/*.jpg
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.demo import build_demo  # noqa: E402

if __name__ == "__main__":
    db_path, photos_dir = build_demo(ROOT / "data" / "demo")
    print(f"데모 DB: {db_path}")
    print(f"데모 사진: {photos_dir}")
    for p in sorted(photos_dir.iterdir()):
        print(f"  {p.name}")
