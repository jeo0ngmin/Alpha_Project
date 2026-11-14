#!/usr/bin/env bash
# AI Hub '경구약제 이미지 데이터'(576) Training 단일경구약제를 전부 받아 임베딩 모델을 학습한다 (GPU 서버용).
#
#   받기 → 알약 크롭 만들기(클래스당 60장) → 학습 → 검색 색인 → ONNX 내보내기
#
# 준비: ~/aihubshell 로 받은 파일 목록 (./aihubshell -mode l -datasetkey 576 > ~/aihub_list.txt)
# 실행: tmux 안에서 API 키를 환경 변수로 넣고 실행한다. 키는 명령줄·로그·프로세스 목록에 남지 않는다.
#   read -rs "AIHUB_APIKEY?AI Hub API 키: "; export AIHUB_APIKEY        (zsh. bash는 read -rsp '키: ' AIHUB_APIKEY)
#   bash scripts/aihub_train_full.sh 2>&1 | tee ~/aihub_full.log
# 중간에 끊겨도 다시 실행하면 이미 받은 파일과 끝난 단계는 건너뛴다.
# 원본 zip(약 3.4TB)은 ~/aihub_raw 에 남는다. 학습 결과를 확인한 뒤 지워도 된다.
set -uo pipefail

LIST=${LIST:-$HOME/aihub_list.txt}
RAW=${RAW:-$HOME/aihub_raw}
VENV=${VENV:-$HOME/medimate-venv}
PER_CLASS=${PER_CLASS:-60}
EPOCHS=${EPOCHS:-20}
WORKERS=${WORKERS:-3}  # nproc는 컨테이너 CPU 제한을 모를 수 있어 직접 정한다
REPO=$(cd "$(dirname "$0")/.." && pwd)
URL="https://api.aihub.or.kr/down/0.6/576.do"

: "${AIHUB_APIKEY:?AIHUB_APIKEY 환경 변수가 없습니다 (위 사용법 참고)}"
[ -f "$LIST" ] || { echo "파일 목록이 없습니다: $LIST"; exit 1; }
mkdir -p "$RAW/.done"
cd "$RAW" || exit 1
log() { echo "[$(date '+%m-%d %H:%M')] $*"; }

# 목록 줄 "├─TS_26_단일.zip | 31 GB | 66179" → "66179 TS_26_단일.zip"
files() { grep -E "─$1_[0-9]+_단일\.zip" "$LIST" | sed 's/.*─//' | awk '{print $NF, $1}'; }

# 파일키(쉼표로 여러 개)를 받아 풀고, 나뉜 파일(.part0, .part1 …)을 합친다.
fetch() {
    local keys=$1 what=$2 tries=0 rc
    while true; do
        curl -fL -C - --retry 5 --retry-delay 30 -o download.tar \
             -H @<(printf 'apikey:%s\n' "$AIHUB_APIKEY") "$URL?fileSn=$keys"
        rc=$?
        [ $rc -eq 0 ] && break
        tries=$((tries + 1))
        [ $rc -eq 33 ] && rm -f download.tar  # 이어받기를 지원하지 않으면 처음부터
        if [ $tries -ge 10 ]; then
            log "!! $what 다운로드 실패 (curl 종료 코드 $rc)"; rm -f download.tar; return 1
        fi
        log "   $what 다시 시도 $tries/10 (curl 종료 코드 $rc)"; sleep 60
    done
    if ! tar -xf download.tar; then
        log "!! $what 압축 해제 실패"; rm -f download.tar; return 1
    fi
    rm -f download.tar
    find . -name '*.part[0-9]*' | sed 's/\.part[0-9]*$//' | sort -u | while IFS= read -r base; do
        find "$(dirname "$base")" -maxdepth 1 -name "$(basename "$base").part[0-9]*" | sort -V \
            | xargs -d '\n' cat > "$base" \
            && find "$(dirname "$base")" -maxdepth 1 -name "$(basename "$base").part[0-9]*" -delete
    done
}

# 1. 라벨(TL, 약 3GB)을 먼저 한 번에 받는다. 키가 틀리면 여기서 멈춘다.
if [ ! -f .done/TL ]; then
    keys=$(files TL | awk '{print $1}' | paste -sd, -)
    [ -n "$keys" ] || { echo "목록에서 TL_*_단일 파일을 찾지 못했습니다"; exit 1; }
    log "라벨 $(echo "$keys" | tr ',' '\n' | wc -l)개 받는 중"
    fetch "$keys" "라벨" || exit 1
    touch .done/TL
fi

# 2. 사진(TS)은 파일마다 받는다 (한 파일이 최대 95GB라 한꺼번에 받으면 끊겼을 때 손실이 크다).
total=$(files TS | wc -l); n=0; failed=0
while read -r key name; do
    n=$((n + 1))
    [ -f ".done/$key" ] && continue
    log "사진 $n/$total $name 받는 중 (남은 공간 $(df -h . | awk 'NR==2{print $4}'))"
    if fetch "$key" "$name" && [ -n "$(find . -name "$name" -size +0)" ]; then
        touch ".done/$key"
    else
        failed=$((failed + 1))
    fi
done < <(files TS)
if [ $failed -gt 0 ]; then
    log "!! 사진 $failed개를 받지 못했습니다. 이 스크립트를 다시 실행하면 그 파일만 다시 받습니다."
    exit 1
fi
log "다운로드 완료: $(du -sh "$RAW" | cut -f1)"

# 3. 알약 크롭 (클래스당 PER_CLASS장, 촬영 조건이 고르게 섞이게 무작위)
TRAIN_DIR=$(find "$RAW" -type d -name '1.Training' | head -1)
[ -n "$TRAIN_DIR" ] || { echo "1.Training 폴더를 찾지 못했습니다"; exit 1; }
cd "$REPO" || exit 1
# shellcheck disable=SC1091
source "$VENV/bin/activate"
if [ ! -f data/aihub_ts/manifest.csv ]; then
    log "알약 크롭 만드는 중 (수 시간 걸릴 수 있음)"
    python scripts/convert_aihub.py --images-dir "$TRAIN_DIR" --labels-dir "$TRAIN_DIR" \
        --out data/aihub_ts --max-per-class "$PER_CLASS" --workers "$WORKERS" || exit 1
fi

# 4. 학습 (식약처 참조 + AI Hub Validation 100종(평가 사진 제외) + AI Hub Training 전체)
MANIFESTS=(data/reference/manifest.csv data/aihub_ts/manifest.csv)
[ -f data/aihub/manifest_train.csv ] && MANIFESTS+=(data/aihub/manifest_train.csv)
if [ ! -f models/embedder_full.pt ] || [ ! -f models/.full_trained ]; then
    log "학습 시작 (${EPOCHS}에폭)"
    python scripts/train_embedder.py --manifest "${MANIFESTS[@]}" --out models/embedder_full.pt \
        --backbone efficientnet_b0 --img-size 224 --epochs "$EPOCHS" --batch-size 128 \
        --workers "$WORKERS" --eval-every 2 || exit 1
    touch models/.full_trained
fi

# 5. 검색 색인 + ONNX
log "검색 색인 만드는 중"
python scripts/build_index.py --model models/embedder_full.pt --manifest "${MANIFESTS[@]}" \
    --out models/index_full.npz --batch-size 256 --workers "$WORKERS" || exit 1
python scripts/export_onnx.py --model models/embedder_full.pt --out models/embedder_full.onnx || exit 1
ls -l models/embedder_full.onnx models/index_full.npz
md5sum models/embedder_full.onnx models/index_full.npz
log "모두 끝났습니다."
