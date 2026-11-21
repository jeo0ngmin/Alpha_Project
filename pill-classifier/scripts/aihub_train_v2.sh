#!/usr/bin/env bash
# v2 학습: 식약처 사진만 있는 약(AI Hub에 사진이 없는 약, DB의 대부분)의 정확도를 올리려는 재학습 (GPU 서버용).
#
# aihub_train_full.sh로 사진을 모두 받아 크롭하고 묶음 파일(data/packed/train_full.pack)까지 만든 서버에서 실행한다.
# 다시 받지 않는다. 기존 모델(embedder_full.*)은 그대로 두고 embedder_${TAG}.*, index_${TAG}.npz를 새로 만든다.
#
#   달라진 점
#   - 식약처 참조 크롭을 폰 사진처럼 바꾸는 증강 (--photo-aug, pillid/data.py PhotoLike)
#   - scripts/holdout_items_v2.txt의 200종은 촬영 사진을 학습에서 빼고, 2에폭마다 '처음 보는 약' 정확도를 같이 출력한다
#     (지금 모델은 이 수치를 잴 수 없다. 로컬 추정: 식약처 사진만 있으면 Top-1 58%, 점수 보정 후 80%)
#
# 실행 (tmux 안에서):
#   cd ~/Alpha_Project && git pull
#   bash pill-classifier/scripts/aihub_train_v2.sh 2>&1 | tee -a ~/aihub_v2.log
# 끊기면 같은 명령을 다시 실행한다. 끝난 에폭부터 이어서 학습한다.
set -uo pipefail

VENV=${VENV:-$HOME/medimate-venv}
EPOCHS=${EPOCHS:-20}
WORKERS=${WORKERS:-3}
PHOTO_AUG=${PHOTO_AUG:-0.8}
TAG=${TAG:-v2}  # 비교 실험: TAG=v2noaug PHOTO_AUG=0 (같은 홀드아웃, 증강 없이)
REPO=$(cd "$(dirname "$0")/.." && pwd)
source "$VENV/bin/activate"
log() { echo "[$(date '+%m-%d %H:%M')] $*"; }

cd "$REPO" || exit 1
MANIFESTS=(data/reference/manifest.csv)
for m in data/aihub_ts/parts/*/manifest.csv; do [ -f "$m" ] && MANIFESTS+=("$m"); done
[ -f data/aihub/manifest_train.csv ] && MANIFESTS+=(data/aihub/manifest_train.csv)
PACK=data/packed/train_full.pack
[ -f "$PACK" ] || { echo "묶음 파일이 없습니다: $PACK (aihub_train_full.sh를 먼저 끝까지 실행하세요)"; exit 1; }
[ ${#MANIFESTS[@]} -gt 2 ] || { echo "AI Hub 크롭 매니페스트가 없습니다 (data/aihub_ts/parts)"; exit 1; }

if [ ! -f models/.${TAG}_trained ]; then
    log "${TAG} 학습 시작 (${EPOCHS}에폭, 매니페스트 ${#MANIFESTS[@]}개, 참조 사진 증강 ${PHOTO_AUG})"
    python scripts/train_embedder.py --manifest "${MANIFESTS[@]}" --pack "$PACK" --out models/embedder_${TAG}.pt \
        --backbone efficientnet_b0 --img-size 224 --epochs "$EPOCHS" --batch-size 128 \
        --workers "$WORKERS" --eval-every 2 --holdout scripts/holdout_items_v2.txt --photo-aug "$PHOTO_AUG" || exit 1
    touch models/.${TAG}_trained
fi

# 색인은 모든 사진으로 만든다 (서비스용). 처음 보는 약 평가는 PC에서 홀드아웃 품목의 촬영 사진을 빼고 한다.
log "${TAG} 검색 색인 만드는 중"
python scripts/build_index.py --model models/embedder_${TAG}.pt --manifest "${MANIFESTS[@]}" \
    --out models/index_${TAG}.npz --batch-size 256 --workers "$WORKERS" || exit 1
python scripts/export_onnx.py --model models/embedder_${TAG}.pt --out models/embedder_${TAG}.onnx || exit 1
ls -l models/embedder_${TAG}.onnx models/index_${TAG}.npz
md5sum models/embedder_${TAG}.onnx models/index_${TAG}.npz
log "${TAG} 모두 끝났습니다."
