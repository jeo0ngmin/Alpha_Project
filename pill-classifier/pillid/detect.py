"""알약 탐지: 학습된 YOLO 가중치가 있으면 사용하고, 없으면 배경 분리 기반으로 분할한다."""
from __future__ import annotations

import numpy as np

from .imaging import PillRegion, region_from_box, segment_pills


class PillDetector:
    def __init__(self, weights: str | None = None, conf: float = 0.35):
        self.conf = conf
        self.model = None
        if weights:
            from ultralytics import YOLO
            self.model = YOLO(weights)

    def __call__(self, img: np.ndarray) -> list[PillRegion]:
        if self.model is None:
            return segment_pills(img)
        # ultralytics는 numpy 입력을 BGR로 해석한다
        res = self.model.predict(img, conf=self.conf, verbose=False)[0]
        boxes = res.boxes.xyxy.cpu().numpy()
        regions = [region_from_box(img, b) for b in boxes]
        return sorted(regions, key=lambda r: r.area, reverse=True)
