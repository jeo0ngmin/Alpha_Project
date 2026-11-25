"""알약 임베딩 모델 학습 (ArcFace 메트릭 러닝).

입력: 매니페스트 CSV(path,label) 여러 개. 보통은
  data/reference/manifest.csv  (식약처 참조 크롭, prepare_reference.py)
  data/aihub/manifest.csv      (AI Hub 촬영 크롭, convert_aihub.py)
검증: 클래스별로 일부 이미지를 떼어 두고, 나머지를 갤러리로 한 검색 Top-1/Top-5 정확도를 잰다.
"""
from __future__ import annotations

import argparse
import math
import random
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import torch
from torch.utils.data import DataLoader, WeightedRandomSampler

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pillid.data import ManifestDataset, PathDataset, eval_transform, read_manifest, train_transform  # noqa: E402
from pillid.embedding import ArcFaceHead, PillEmbedder, save_checkpoint  # noqa: E402


def split_rows(rows, val_frac: float, seed: int):
    """이미지가 4장 이상인 클래스에서만 검증용으로 일부를 떼어 낸다."""
    by_label = defaultdict(list)
    for r in rows:
        by_label[r["label"]].append(r)
    rng = random.Random(seed)
    train, val = [], []
    for items in by_label.values():
        rng.shuffle(items)
        n_val = int(len(items) * val_frac) if len(items) >= 4 else 0
        val.extend(items[:n_val])
        train.extend(items[n_val:])
    return train, val


@torch.no_grad()
def embed(model, rows, size, device, batch_size, workers):
    dl = DataLoader(PathDataset([r["path"] for r in rows], eval_transform(size)),
                    batch_size=batch_size, num_workers=workers)
    model.eval()
    return torch.cat([model(x.to(device)).float() for x in dl])


@torch.no_grad()
def retrieval_accuracy(gallery, g_labels, queries, q_labels, n_classes, ks=(1, 5), chunk=256):
    """품목별 최고 유사도로 순위를 매겨 Top-k 적중률을 계산한다."""
    hits = {k: 0 for k in ks}
    for i in range(0, len(queries), chunk):
        sims = queries[i:i + chunk] @ gallery.T
        per_class = torch.full((sims.size(0), n_classes), -2.0, device=sims.device)
        per_class.scatter_reduce_(1, g_labels.expand(sims.size(0), -1), sims, reduce="amax")
        top = per_class.topk(max(ks), dim=1).indices
        target = q_labels[i:i + chunk].unsqueeze(1)
        for k in ks:
            hits[k] += (top[:, :k] == target).any(dim=1).sum().item()
    return {k: hits[k] / max(len(queries), 1) for k in ks}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", nargs="+", required=True)
    ap.add_argument("--out", default=str(ROOT / "models" / "embedder.pt"))
    ap.add_argument("--backbone", default="efficientnet_b0", help="timm 모델명 (예: efficientnet_b3, convnext_tiny)")
    ap.add_argument("--dim", type=int, default=512)
    ap.add_argument("--img-size", type=int, default=224)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch-size", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-4, help="백본 학습률 (헤드는 10배)")
    ap.add_argument("--margin", type=float, default=0.3)
    ap.add_argument("--scale", type=float, default=30.0)
    ap.add_argument("--val-frac", type=float, default=0.1)
    ap.add_argument("--eval-every", type=int, default=2)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    device = torch.device(args.device)
    use_amp = device.type == "cuda"

    rows = read_manifest(args.manifest)
    labels = sorted({r["label"] for r in rows})
    label_to_idx = {l: i for i, l in enumerate(labels)}
    train_rows, val_rows = split_rows(rows, args.val_frac, args.seed)
    print(f"클래스 {len(labels):,}개, 학습 {len(train_rows):,}장, 검증 {len(val_rows):,}장, 장치 {device}")

    # 클래스별 이미지 수 편차(참조 2장 vs AI Hub 수십 장)를 완화하는 샘플링
    counts = Counter(r["label"] for r in train_rows)
    weights = [1.0 / math.sqrt(counts[r["label"]]) for r in train_rows]
    sampler = WeightedRandomSampler(weights, num_samples=len(train_rows), replacement=True)
    train_dl = DataLoader(ManifestDataset(train_rows, label_to_idx, train_transform(args.img_size)),
                          batch_size=args.batch_size, sampler=sampler, num_workers=args.workers,
                          drop_last=True, pin_memory=use_amp, persistent_workers=args.workers > 0)
    if len(train_dl) == 0:
        raise SystemExit("학습 이미지가 배치 크기보다 적습니다.")

    model = PillEmbedder(args.backbone, args.dim, pretrained=True).to(device)
    head = ArcFaceHead(args.dim, len(labels), s=args.scale, m=args.margin).to(device)
    opt = torch.optim.AdamW([
        {"params": model.backbone.parameters(), "lr": args.lr},
        {"params": list(model.neck.parameters()) + list(head.parameters()), "lr": args.lr * 10},
    ], weight_decay=1e-4)
    total_steps = args.epochs * len(train_dl)
    warmup = min(len(train_dl), total_steps // 10)

    def lr_lambda(step):
        if step < warmup:
            return (step + 1) / warmup
        return 0.5 * (1 + math.cos(math.pi * (step - warmup) / max(1, total_steps - warmup)))

    sched = torch.optim.lr_scheduler.LambdaLR(opt, lr_lambda)
    scaler = torch.amp.GradScaler(device.type, enabled=use_amp)
    config = {"backbone": args.backbone, "dim": args.dim, "img_size": args.img_size}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)

    best = -1.0
    for epoch in range(1, args.epochs + 1):
        model.train()
        head.train()
        t0, running = time.time(), 0.0
        for x, y in train_dl:
            x, y = x.to(device, non_blocking=True), y.to(device, non_blocking=True)
            with torch.autocast(device.type, dtype=torch.float16, enabled=use_amp):
                emb = model(x)
            loss = head(emb, y)  # 헤드는 float32로 계산
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(opt)
            scaler.update()
            sched.step()
            running += loss.item()
        msg = f"[{epoch}/{args.epochs}] loss {running / len(train_dl):.4f} ({time.time() - t0:.0f}s)"

        if val_rows and (epoch % args.eval_every == 0 or epoch == args.epochs):
            g = embed(model, train_rows, args.img_size, device, args.batch_size * 2, args.workers)
            q = embed(model, val_rows, args.img_size, device, args.batch_size * 2, args.workers)
            gl = torch.tensor([label_to_idx[r["label"]] for r in train_rows], device=device)
            ql = torch.tensor([label_to_idx[r["label"]] for r in val_rows], device=device)
            acc = retrieval_accuracy(g, gl, q, ql, len(labels))
            msg += f" | val top1 {acc[1]:.3f} top5 {acc[5]:.3f}"
            if acc[1] > best:
                best = acc[1]
                save_checkpoint(args.out, model, config)
                msg += " *저장"
        elif not val_rows:
            save_checkpoint(args.out, model, config)
        print(msg, flush=True)

    print(f"완료 → {args.out}" + (f" (최고 val top1 {best:.3f})" if best >= 0 else ""))


if __name__ == "__main__":
    main()
