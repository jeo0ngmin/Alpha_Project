"""학습한 임베딩 모델(.pt)을 ONNX로 내보낸다. 추론 PC에 PyTorch가 없거나 차단돼도 onnxruntime만으로 쓸 수 있다.

    python scripts/export_onnx.py --model models/embedder.pt --out models/embedder.onnx

PyTorch가 있는 곳(Colab 등)에서 실행한다. 입력은 (N, 3, S, S) float32(ImageNet 정규화), 출력은 L2 정규화된 (N, dim).
img_size·dim·backbone은 ONNX 메타데이터에 넣어 두므로 추론 쪽에서 설정 파일이 따로 필요 없다.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.embedding import load_embedder  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default=str(ROOT / "models" / "embedder.pt"))
    ap.add_argument("--out", default=str(ROOT / "models" / "embedder.onnx"))
    ap.add_argument("--opset", type=int, default=17)
    args = ap.parse_args()

    model, cfg = load_embedder(args.model, "cpu")
    size = cfg["img_size"]
    dummy = torch.zeros(1, 3, size, size)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(model, dummy, args.out, opset_version=args.opset, input_names=["image"],
                      output_names=["embedding"], dynamic_axes={"image": {0: "batch"}, "embedding": {0: "batch"}},
                      dynamo=False)

    import onnx
    m = onnx.load(args.out)
    for k, v in (("img_size", size), ("dim", cfg["dim"]), ("backbone", cfg["backbone"])):
        entry = m.metadata_props.add()
        entry.key, entry.value = k, str(v)
    onnx.save(m, args.out)

    try:
        import onnxruntime as ort
        sess = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
        x = np.random.default_rng(0).standard_normal((2, 3, size, size)).astype(np.float32)
        with torch.no_grad():
            ref = model(torch.from_numpy(x)).numpy()
        out = sess.run(None, {"image": x})[0]
        print(f"검증: PyTorch와 ONNX 출력 최대 차이 {np.abs(out - ref).max():.2e}")
    except ImportError:
        print("onnxruntime이 없어 출력 비교는 건너뜁니다.")
    print(f"저장 → {args.out} ({Path(args.out).stat().st_size / 1e6:.1f} MB, img_size {size}, dim {cfg['dim']})")


if __name__ == "__main__":
    main()
