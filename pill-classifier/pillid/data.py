"""학습/색인용 매니페스트와 데이터셋, 증강."""
from __future__ import annotations

import csv
import io
import os
from pathlib import Path

from PIL import Image

try:  # 학습·.pt 추론에만 필요. PyTorch가 없거나 차단돼도 매니페스트 읽기와 상수는 쓸 수 있어야 한다
    import torchvision.transforms as T
    from torch.utils.data import Dataset
except (ImportError, OSError):  # OSError: DLL 차단 (Windows Smart App Control)
    T = None

    class Dataset:  # type: ignore[no-redef]
        pass

MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)
FILL = (127, 127, 127)  # imaging.BG_FILL과 동일


def read_manifest(paths) -> list[dict]:
    """CSV(path,label[,side]) 여러 개를 읽는다. 상대 경로는 CSV 위치 기준."""
    if isinstance(paths, (str, Path)):
        paths = [paths]
    rows = []
    for p in paths:
        p = Path(p)
        with open(p, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                img = Path(r["path"].replace("\\", "/"))  # Windows에서 쓴 매니페스트를 Linux(Colab)에서도 읽는다
                if not img.is_absolute():
                    img = p.parent / img
                rows.append({"path": str(img), "label": str(r["label"]), "side": r.get("side", "")})
    return rows


def write_manifest(path, rows, fields=("path", "label", "side")) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(fields)
        for r in rows:
            r = list(r)
            r[0] = os.path.relpath(r[0], path.parent)
            w.writerow(r)


def train_transform(size: int):
    _require_torch()
    return T.Compose([
        # 크롭이 장축 기준으로 정렬되어 있으므로 180° 뒤집힘이 주 변형이다.
        # 원형 알약은 방향 정보가 없으므로 일부는 전 방향 회전을 섞는다.
        # (좌우 반전은 각인을 거울상으로 만들어 실제로 없는 모습이 되므로 쓰지 않는다)
        T.RandomApply([T.RandomRotation((180, 180), fill=FILL)], p=0.5),
        T.RandomApply([T.RandomRotation(180, fill=FILL)], p=0.3),
        T.RandomRotation(8, fill=FILL),
        T.RandomResizedCrop(size, scale=(0.7, 1.0), ratio=(0.9, 1.1)),
        T.ColorJitter(brightness=0.3, contrast=0.3, saturation=0.2, hue=0.02),  # 색은 중요한 단서라 hue는 약하게
        T.RandomApply([T.GaussianBlur(5, sigma=(0.1, 1.5))], p=0.3),
        T.ToTensor(),
        T.Normalize(MEAN, STD),
    ])


def eval_transform(size: int):
    _require_torch()
    return T.Compose([T.Resize((size, size)), T.ToTensor(), T.Normalize(MEAN, STD)])


def _require_torch():
    if T is None:
        raise RuntimeError("PyTorch/torchvision을 불러올 수 없습니다. 학습은 Colab에서, 추론은 .onnx 모델로 하세요.")


class PhotoLike:
    """식약처 참조 사진(스튜디오 조명, 깨끗한 배경)을 폰으로 찍은 사진처럼 바꾼다 (학습 증강).

    참조 사진만 있는 약은 실제 사진과의 임베딩 유사도가 낮아 촬영 사진이 있는 비슷한 약에 밀렸다
    (2026-11, 평가 약의 색인을 식약처 사진만 남기면 Top-1 98.9% → 58.0%). 조명 색·밝기 기울기·그림자,
    반사광, 흐림, 해상도 저하, 잡음, JPEG 압축을 무작위로 섞어 두 사진 영역의 차이를 줄인다.
    """

    def __init__(self, p: float = 0.8, seed: int | None = None):
        import random

        self.p, self.seed = p, seed
        self.rng, self._pid = random.Random(seed), os.getpid()

    def __call__(self, img: Image.Image) -> Image.Image:
        import random

        import cv2
        import numpy as np

        if os.getpid() != self._pid:  # 데이터 워커마다 같은 난수 순서를 물려받지 않도록 다시 섞는다
            self.rng, self._pid = random.Random(None if self.seed is None else self.seed + os.getpid()), os.getpid()
        r = self.rng
        if r.random() >= self.p:
            return img
        a = np.asarray(img.convert("RGB"), np.float32)
        h, w = a.shape[:2]
        bg = np.all(np.abs(a - np.array(FILL, np.float32)) < 3, axis=2)  # 크롭 바깥(회색 채움)은 건드리지 않는다
        # 조명 색온도와 노출
        a *= np.array([r.uniform(0.85, 1.15), r.uniform(0.92, 1.08), r.uniform(0.8, 1.2)], np.float32)
        a = 255 * (np.clip(a, 0, 255) / 255) ** r.uniform(0.75, 1.35)
        # 한쪽이 어두운 조명 / 그림자
        if r.random() < 0.6:
            ang = r.uniform(0, 2 * np.pi)
            yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
            g = (np.cos(ang) * (xx / w - 0.5) + np.sin(ang) * (yy / h - 0.5)) * 2
            a *= (1 - r.uniform(0.1, 0.45) * np.clip(g, 0, 1))[..., None]
        # 반사광 (코팅정·연질캡슐)
        if r.random() < 0.35:
            cx, cy, rad = r.uniform(0.2, 0.8) * w, r.uniform(0.2, 0.8) * h, r.uniform(0.04, 0.15) * w
            yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
            a += r.uniform(40, 120) * np.exp(-((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * rad ** 2))[..., None]
        a = np.clip(a, 0, 255).astype(np.uint8)
        # 해상도 저하 (멀리서 찍은 사진)
        if r.random() < 0.5:
            s = r.uniform(0.3, 0.7)
            small = cv2.resize(a, (max(8, int(w * s)), max(8, int(h * s))), interpolation=cv2.INTER_AREA)
            a = cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)
        # 초점·손떨림
        if r.random() < 0.5:
            if r.random() < 0.5:
                k = r.choice([3, 5])
                a = cv2.GaussianBlur(a, (k, k), 0)
            else:
                k = r.choice([5, 7])
                kernel = np.zeros((k, k), np.float32)
                kernel[k // 2, :] = 1 / k
                m = cv2.getRotationMatrix2D((k / 2 - 0.5, k / 2 - 0.5), r.uniform(0, 180), 1)
                a = cv2.filter2D(a, -1, cv2.warpAffine(kernel, m, (k, k)))
        # 센서 잡음과 JPEG
        if r.random() < 0.6:
            a = np.clip(a + np.random.default_rng(r.randrange(1 << 30)).normal(0, r.uniform(2, 8), a.shape), 0, 255).astype(np.uint8)
        if r.random() < 0.7:
            ok, buf = cv2.imencode(".jpg", a[..., ::-1], [cv2.IMWRITE_JPEG_QUALITY, r.randint(35, 85)])
            if ok:
                a = cv2.imdecode(buf, cv2.IMREAD_COLOR)[..., ::-1]
        a = np.ascontiguousarray(a)
        a[bg] = FILL
        return Image.fromarray(a)


def is_reference(path: str) -> bool:
    """식약처 참조 크롭(data/reference/...)인지."""
    return "reference" in Path(path).parts


def open_rgb(path: str) -> Image.Image:
    return Image.open(path).convert("RGB")


class PackedImages:
    """scripts/pack_images.py로 묶은 이미지 파일에서 경로로 이미지를 읽는다 (open_rgb 대신 쓴다).

    NAS처럼 작은 파일을 여는 데 오래 걸리는 저장소에서 수십만 장을 매 에폭 열지 않도록 파일 하나에서 읽는다.
    파일: <pack> (이미지 바이트를 이어 붙인 것), <pack>.paths.txt (줄마다 경로), <pack>.idx.npy (오프셋, 길이)
    """

    def __init__(self, path):
        import numpy as np

        self.path = str(path)
        with open(self.path + ".paths.txt", encoding="utf-8") as f:
            self.index = {p: i for i, p in enumerate(f.read().splitlines())}
        self.idx = np.load(self.path + ".idx.npy")
        self._fd = None

    def __getstate__(self):  # 데이터 워커 프로세스마다 파일을 따로 연다
        st = self.__dict__.copy()
        st["_fd"] = None
        return st

    def __contains__(self, path: str) -> bool:
        return path in self.index

    def __len__(self):
        return len(self.index)

    def __call__(self, path: str) -> Image.Image:
        off, n = (int(v) for v in self.idx[self.index[path]])
        if self._fd is None:
            self._fd = os.open(self.path, os.O_RDONLY | getattr(os, "O_BINARY", 0))
        if hasattr(os, "pread"):
            data = os.pread(self._fd, n, off)
        else:  # Windows
            os.lseek(self._fd, off, os.SEEK_SET)
            data = os.read(self._fd, n)
        return Image.open(io.BytesIO(data)).convert("RGB")


class ManifestDataset(Dataset):
    def __init__(self, rows: list[dict], label_to_idx: dict[str, int], transform, loader=None, reference_aug=None):
        """reference_aug: 식약처 참조 크롭에만 먼저 적용할 변환 (예: PhotoLike)."""
        self.rows, self.label_to_idx, self.transform = rows, label_to_idx, transform
        self.loader = loader or open_rgb
        self.reference_aug = reference_aug
        self.is_ref = [is_reference(r["path"]) for r in rows] if reference_aug else None

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        r = self.rows[i]
        img = self.loader(r["path"])
        if self.reference_aug is not None and self.is_ref[i]:
            img = self.reference_aug(img)
        return self.transform(img), self.label_to_idx[r["label"]]


class PathDataset(Dataset):
    def __init__(self, paths: list[str], transform, loader=None):
        self.paths, self.transform = paths, transform
        self.loader = loader or open_rgb

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, i):
        return self.transform(self.loader(self.paths[i]))
