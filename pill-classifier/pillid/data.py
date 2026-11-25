"""학습/색인용 매니페스트와 데이터셋, 증강."""
from __future__ import annotations

import csv
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


class ManifestDataset(Dataset):
    def __init__(self, rows: list[dict], label_to_idx: dict[str, int], transform):
        self.rows, self.label_to_idx, self.transform = rows, label_to_idx, transform

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, i):
        r = self.rows[i]
        img = Image.open(r["path"]).convert("RGB")
        return self.transform(img), self.label_to_idx[r["label"]]


class PathDataset(Dataset):
    def __init__(self, paths: list[str], transform):
        self.paths, self.transform = paths, transform

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, i):
        return self.transform(Image.open(self.paths[i]).convert("RGB"))
