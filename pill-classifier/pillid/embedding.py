"""메트릭 러닝 임베딩 모델 (백본 + ArcFace).

학습과 .pt 추론에는 PyTorch가 필요하다. 추론만 할 때는 scripts/export_onnx.py로 내보낸 .onnx를 쓰면
onnxruntime만으로 동작한다 (Windows Smart App Control처럼 PyTorch DLL이 차단되는 PC에서도 된다).
"""
from __future__ import annotations

import math
from pathlib import Path

import cv2
import numpy as np

from .data import MEAN, STD

try:  # PyTorch는 학습·.pt 추론에만 필요하다. 없거나 차단돼도 .onnx 추론은 된다
    import timm
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    TORCH_ERROR = None
except (ImportError, OSError) as e:  # OSError: DLL 차단
    torch = F = timm = None
    TORCH_ERROR = e

    class nn:  # type: ignore[no-redef]  # 아래 클래스 정의가 깨지지 않도록 하는 자리표시자
        class Module:
            pass


def find_default_model(root) -> tuple[str | None, str | None]:
    """지정이 없을 때 쓸 (모델, 색인). models/embedder.onnx 를 먼저, 없으면 embedder.pt. 색인(data/index.npz)이 있어야 한다."""
    root = Path(root)
    index = root / "data" / "index.npz"
    if not index.exists():
        return None, None
    candidates = ["embedder.onnx"] + (["embedder.pt"] if torch is not None else [])  # .pt는 PyTorch가 될 때만
    for name in candidates:
        m = root / "models" / name
        if m.exists():
            return str(m), str(index)
    return None, None


class PillEmbedder(nn.Module):
    """이미지 → L2 정규화된 임베딩. 새 약은 재학습 없이 참조 이미지만 색인에 추가하면 된다."""

    def __init__(self, backbone: str = "efficientnet_b0", dim: int = 512, pretrained: bool = True):
        super().__init__()
        self.backbone = timm.create_model(backbone, pretrained=pretrained, num_classes=0)
        self.neck = nn.Sequential(nn.Linear(self.backbone.num_features, dim), nn.BatchNorm1d(dim))

    def forward(self, x):
        return F.normalize(self.neck(self.backbone(x)), dim=-1)


class ArcFaceHead(nn.Module):
    """Additive angular margin loss. 학습할 때만 쓰고 추론에는 필요 없다."""

    def __init__(self, dim: int, n_classes: int, s: float = 30.0, m: float = 0.3):
        super().__init__()
        self.weight = nn.Parameter(torch.empty(n_classes, dim))
        nn.init.xavier_uniform_(self.weight)
        self.s, self.m = s, m
        self.cos_m, self.sin_m = math.cos(m), math.sin(m)
        self.th = math.cos(math.pi - m)
        self.mm = math.sin(math.pi - m) * m

    def forward(self, emb, labels):
        cos = F.linear(F.normalize(emb.float()), F.normalize(self.weight.float()))
        cos = cos.clamp(-1 + 1e-7, 1 - 1e-7)
        sin = torch.sqrt(1.0 - cos * cos)
        phi = cos * self.cos_m - sin * self.sin_m
        phi = torch.where(cos > self.th, phi, cos - self.mm)  # θ+m이 π를 넘는 구간의 단조성 보정
        onehot = F.one_hot(labels, cos.size(1)).to(cos.dtype)
        logits = self.s * (onehot * phi + (1 - onehot) * cos)
        return F.cross_entropy(logits, labels)


def save_checkpoint(path, model: PillEmbedder, config: dict) -> None:
    torch.save({"state_dict": model.state_dict(), "config": config}, path)


def load_embedder(path, device="cpu") -> tuple[PillEmbedder, dict]:
    ck = torch.load(path, map_location="cpu", weights_only=True)
    cfg = ck["config"]
    model = PillEmbedder(cfg["backbone"], cfg["dim"], pretrained=False)
    model.load_state_dict(ck["state_dict"])
    return model.to(device).eval(), cfg


class Embedder:
    """추론용. .onnx(onnxruntime) 또는 .pt(PyTorch) 체크포인트를 읽는다."""

    def __init__(self, ckpt_path, device: str | None = None):
        self.path = str(ckpt_path)
        self.session = None
        if self.path.lower().endswith(".onnx"):
            import onnxruntime as ort
            self.session = ort.InferenceSession(self.path, providers=["CPUExecutionProvider"])
            meta = self.session.get_modelmeta().custom_metadata_map
            self.img_size, self.dim = int(meta.get("img_size", 224)), int(meta.get("dim", 512))
            self.input_name = self.session.get_inputs()[0].name
            self.device = "onnxruntime"
            return
        if torch is None:
            raise RuntimeError(f"PyTorch를 불러올 수 없어 .pt 모델을 쓸 수 없습니다 ({TORCH_ERROR}). "
                               "scripts/export_onnx.py로 내보낸 .onnx 모델을 쓰세요.")
        from .data import eval_transform
        self.device = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
        self.model, cfg = load_embedder(ckpt_path, self.device)
        self.img_size = cfg["img_size"]
        self.dim = cfg["dim"]
        self.tf = eval_transform(self.img_size)

    def _preprocess(self, crops: list[np.ndarray]) -> np.ndarray:
        """BGR 크롭 → (N, 3, S, S) float32. eval_transform(Resize → ToTensor → Normalize)과 같은 계산."""
        mean, std = np.array(MEAN, np.float32), np.array(STD, np.float32)
        out = []
        for c in crops:
            rgb = cv2.cvtColor(cv2.resize(c, (self.img_size, self.img_size), interpolation=cv2.INTER_LINEAR),
                               cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
            out.append(((rgb - mean) / std).transpose(2, 0, 1))
        return np.stack(out)

    def embed_bgr(self, crops: list[np.ndarray], batch_size: int = 64) -> np.ndarray:
        if not crops:
            return np.zeros((0, self.dim), np.float32)
        out = []
        for i in range(0, len(crops), batch_size):
            batch = crops[i:i + batch_size]
            if self.session is not None:
                out.append(self.session.run(None, {self.input_name: self._preprocess(batch)})[0])
            else:
                from PIL import Image
                with torch.no_grad():
                    x = torch.stack([self.tf(Image.fromarray(cv2.cvtColor(c, cv2.COLOR_BGR2RGB))) for c in batch])
                    out.append(self.model(x.to(self.device)).float().cpu().numpy())
        return np.concatenate(out).astype(np.float32)

    def embed_paths(self, paths: list[str], batch_size: int = 64, num_workers: int = 0) -> np.ndarray:
        if self.session is not None:
            out = []
            for i in range(0, len(paths), batch_size):
                crops = [cv2.imdecode(np.fromfile(p, np.uint8), cv2.IMREAD_COLOR) for p in paths[i:i + batch_size]]
                out.append(self.embed_bgr(crops, batch_size))
            return np.concatenate(out) if out else np.zeros((0, self.dim), np.float32)
        from torch.utils.data import DataLoader
        from .data import PathDataset
        dl = DataLoader(PathDataset(paths, self.tf), batch_size=batch_size, num_workers=num_workers)
        with torch.no_grad():
            out = [self.model(x.to(self.device)).float().cpu().numpy() for x in dl]
        return np.concatenate(out) if out else np.zeros((0, self.dim), np.float32)
