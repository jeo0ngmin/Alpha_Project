"""참조 이미지 임베딩 색인. 5만 장 규모까지는 numpy 내적으로 충분히 빠르다."""
from __future__ import annotations

import numpy as np


class Gallery:
    def __init__(self, embs: np.ndarray, labels):
        self.embs = np.ascontiguousarray(embs, dtype=np.float32)
        self.labels = np.asarray(labels).astype(str)
        if len(self.embs) != len(self.labels):
            raise ValueError("임베딩 수와 라벨 수가 다릅니다.")

    @classmethod
    def load(cls, path) -> "Gallery":
        d = np.load(path, allow_pickle=False)
        return cls(d["embs"], d["labels"])

    def save(self, path) -> None:
        np.savez_compressed(path, embs=self.embs, labels=self.labels)
