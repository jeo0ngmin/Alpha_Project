"""AI Hub '경구약제 이미지 데이터'를 (1) 임베딩 학습용 알약 크롭과 (2) YOLO 탐지 학습셋으로 변환한다.

라벨 JSON은 COCO 형식이라고 가정한다 (images[0]에 약 정보, annotations[].bbox = [x, y, w, h]).
조합 이미지는 약마다 JSON이 따로 있어서, 같은 이미지의 박스들을 모아 YOLO 라벨을 만든다.
라벨은 images[0].item_seq(품목기준코드)를 우선 쓰고, 없으면 drug_N(K-코드)을 쓴다.
식약처 DB와 연결하려면 item_seq가 있어야 한다.
데이터 배포 버전마다 필드가 조금씩 다를 수 있으니 처음에는 --limit로 결과를 확인하세요.

--images-dir, --labels-dir 에 zip 파일이 들어 있으면 압축을 풀지 않고 zip에서 바로 읽는다
(AI Hub 원천데이터는 수십 GB라 풀 자리가 없는 경우가 많다). 라벨 JSON은 사진이 있는 K코드 것만 읽는다.

    python scripts/convert_aihub.py --images-dir "D:/aihub/.../2.Validation/원천데이터/단일경구약제 5000종" ^
                                    --labels-dir "D:/aihub/.../2.Validation/라벨링데이터/단일경구약제 5000종"
"""
from __future__ import annotations

import argparse
import io
import json
import os
import random
import re
import shutil
import sys
import zipfile
import zlib
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from tqdm import tqdm

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.data import write_manifest  # noqa: E402
from pillid.imaging import canonical_crop, preprocess, region_from_box, write_image  # noqa: E402

IMAGE_EXTS = {".png", ".jpg", ".jpeg"}
CODE_RE = re.compile(r"(K-\d+)")
ZIP_SEP = "::"  # "경로.zip::멤버" 형태로 zip 안의 파일을 가리킨다


def iter_files(root: Path, exts: set[str]):
    """폴더(하위 포함)와 그 안의 zip에서 확장자가 맞는 파일을 (이름, 위치, 읽기함수) 로 돌려준다."""
    for p in sorted(root.rglob("*")):
        if p.suffix.lower() in exts:
            yield p.name, str(p), p.read_bytes
        elif p.suffix.lower() == ".zip":
            with zipfile.ZipFile(p) as z:
                names = [n for n in z.namelist() if Path(n).suffix.lower() in exts]
            for n in names:
                yield Path(n).name, f"{p}{ZIP_SEP}{n}", None


_open_zips: dict[str, zipfile.ZipFile] = {}  # 프로세스마다 zip을 한 번만 연다 (중앙 디렉터리 읽기가 느리다)


def read_bytes(location: str) -> bytes:
    if ZIP_SEP in location:
        zpath, member = location.split(ZIP_SEP, 1)
        if zpath not in _open_zips:
            _open_zips[zpath] = zipfile.ZipFile(zpath)
        return _open_zips[zpath].read(member)
    return Path(location).read_bytes()


def load_annotations(labels_dir: Path, wanted_codes: set[str] | None = None) -> tuple[dict[str, list], Counter]:
    """file_name → [(label, bbox)] 과 오류 통계. wanted_codes가 있으면 그 K코드 라벨만 읽는다."""
    by_image: dict[str, list] = defaultdict(list)
    stats: Counter = Counter()
    entries = [(n, loc) for n, loc, _ in iter_files(labels_dir, {".json"})
               if wanted_codes is None or ((m := CODE_RE.search(n)) and m.group(1) in wanted_codes)]
    for name, loc in tqdm(entries, desc="라벨 JSON"):
        try:
            data = json.loads(read_bytes(loc).decode("utf-8"))
            info = data["images"][0]
        except (ValueError, KeyError, IndexError, UnicodeDecodeError):
            stats["깨진 JSON"] += 1
            continue
        label = str(info.get("item_seq") or info.get("drug_N") or "").strip()
        if not label:
            stats["라벨 없음"] += 1
            continue
        if not info.get("item_seq"):
            stats["item_seq 없음(K-코드 사용)"] += 1
        for ann in data.get("annotations") or []:
            bbox = ann.get("bbox")
            if not (isinstance(bbox, list) and len(bbox) == 4
                    and all(isinstance(v, (int, float)) for v in bbox) and bbox[2] > 0 and bbox[3] > 0):
                stats["잘못된 bbox"] += 1
                continue
            entry = (label, tuple(float(v) for v in bbox))
            if entry not in by_image[info["file_name"]]:
                by_image[info["file_name"]].append(entry)
    return by_image, stats


def _link_or_copy(src: Path, dst: Path) -> None:
    if dst.exists():
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def process_image(task):
    img_path, anns, crops_dir, size, yolo_dir, split = task
    location, img_path = img_path, Path(img_path.split(ZIP_SEP)[-1])
    try:
        import cv2
        import numpy as np
        img = cv2.imdecode(np.frombuffer(read_bytes(location), np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise ValueError(f"이미지를 읽을 수 없습니다: {location}")
        img, _ = preprocess(img, max_side=None)  # 좌표가 바뀌지 않도록 크기는 유지
    except Exception as e:  # noqa: BLE001
        return [], str(e)
    h, w = img.shape[:2]
    rows = []
    for i, (label, (x, y, bw, bh)) in enumerate(anns):
        if crops_dir:
            region = region_from_box(img, (x, y, x + bw, y + bh))
            out = Path(crops_dir) / label / f"{img_path.stem}_{i}.png"
            write_image(out, canonical_crop(img, region, size))
            rows.append((str(out), label, ""))
    if yolo_dir:
        yolo_dir = Path(yolo_dir)
        if ZIP_SEP in location:
            (yolo_dir / "images" / split).mkdir(parents=True, exist_ok=True)
            (yolo_dir / "images" / split / img_path.name).write_bytes(read_bytes(location))
        else:
            _link_or_copy(img_path, yolo_dir / "images" / split / img_path.name)
        lines = []
        for _, (x, y, bw, bh) in anns:
            x1, y1 = max(0.0, x), max(0.0, y)
            x2, y2 = min(float(w), x + bw), min(float(h), y + bh)
            if x2 - x1 < 2 or y2 - y1 < 2:
                continue  # 이미지 밖으로 벗어난 박스
            lines.append(f"0 {(x1 + x2) / 2 / w:.6f} {(y1 + y2) / 2 / h:.6f} "
                         f"{(x2 - x1) / w:.6f} {(y2 - y1) / h:.6f}")
        label_path = yolo_dir / "labels" / split / f"{img_path.stem}.txt"
        label_path.parent.mkdir(parents=True, exist_ok=True)
        label_path.write_text("\n".join(lines), encoding="utf-8")
    return rows, None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--images-dir", required=True, help="원천 이미지 폴더 (하위 폴더 포함 검색)")
    ap.add_argument("--labels-dir", required=True, help="라벨 JSON 폴더 (하위 폴더 포함 검색)")
    ap.add_argument("--out", default=str(ROOT / "data" / "aihub"))
    ap.add_argument("--size", type=int, default=224)
    ap.add_argument("--no-crops", action="store_true", help="임베딩용 크롭을 만들지 않음")
    ap.add_argument("--yolo", action="store_true", help="YOLO 탐지 학습셋도 생성")
    ap.add_argument("--val-ratio", type=float, default=0.1, help="YOLO 검증셋 비율")
    ap.add_argument("--max-per-class", type=int, default=60, help="클래스당 최대 크롭 수 (0=제한 없음)")
    ap.add_argument("--limit", type=int, help="테스트용: 앞쪽 N장만")
    ap.add_argument("--seed", type=int, default=0, help="클래스당 제한 시 고를 사진을 섞는 시드")
    ap.add_argument("--workers", type=int, default=os.cpu_count())
    args = ap.parse_args()

    out = Path(args.out)
    files = {name: loc for name, loc, _ in iter_files(Path(args.images_dir), IMAGE_EXTS)}
    codes = {m.group(1) for m in map(CODE_RE.search, files) if m}
    print(f"이미지 파일 {len(files):,}장 (K코드 {len(codes)}개)")
    by_image, stats = load_annotations(Path(args.labels_dir), codes or None)
    print("라벨 통계:", dict(stats) or "문제 없음")
    per_class: Counter = Counter()
    tasks, missing = [], 0
    crops_dir = None if args.no_crops else str(out / "crops")
    yolo_dir = str(out / "yolo") if args.yolo else None
    # 파일 이름 순서대로 고르면 클래스당 제한이 한 배경·조명 조건에 몰린다(이름에 촬영 조건이 들어 있다).
    # 그래서 고정 시드로 섞어서 여러 촬영 조건이 고르게 들어가게 한다.
    names = sorted(by_image)
    random.Random(args.seed).shuffle(names)
    for name in names:
        if name not in files:
            missing += 1
            continue
        anns = by_image[name]
        # 클래스당 크롭 수 제한은 단일 알약 이미지에만 적용한다.
        # (조합 이미지나 YOLO 학습셋은 이미지를 건너뛰면 다른 알약의 박스까지 빠진다)
        if (crops_dir and args.max_per_class and not yolo_dir and len(anns) == 1
                and per_class[anns[0][0]] >= args.max_per_class):
            continue
        for label, _ in anns:
            per_class[label] += 1
        split = "val" if zlib.crc32(name.encode()) % 1000 < args.val_ratio * 1000 else "train"
        tasks.append((files[name], anns, crops_dir, args.size, yolo_dir, split))
        if args.limit and len(tasks) >= args.limit:
            break
    print(f"이미지 {len(tasks):,}장 처리 (이미지 파일 없음 {missing:,}건)")

    all_rows, errors = [], 0
    with ProcessPoolExecutor(args.workers) as ex:
        for rows, err in tqdm(ex.map(process_image, tasks, chunksize=8), total=len(tasks)):
            errors += bool(err)
            all_rows.extend(rows)

    if crops_dir:
        write_manifest(out / "manifest.csv", all_rows)
        print(f"크롭 {len(all_rows):,}장, 클래스 {len({r[1] for r in all_rows}):,}개 → {out / 'manifest.csv'}")
    if yolo_dir:
        (Path(yolo_dir) / "data.yaml").write_text(
            f"path: {Path(yolo_dir).resolve().as_posix()}\ntrain: images/train\nval: images/val\n"
            "names:\n  0: pill\n", encoding="utf-8")
        print(f"YOLO 데이터셋 → {Path(yolo_dir) / 'data.yaml'}")
    if errors:
        print(f"읽기 실패 이미지 {errors:,}장")


if __name__ == "__main__":
    main()
