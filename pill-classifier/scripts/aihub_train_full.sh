#!/usr/bin/env bash
# AI Hub '경구약제 이미지 데이터'(576) Training 단일경구약제를 전부 받아 임베딩 모델을 학습한다 (GPU 서버용).
#
#   사진 zip 하나씩: 받기 → 알약 크롭(클래스당 60장) → zip 삭제   (원본 3.4TB를 쌓아 두지 않는다)
#   전부 끝나면: 학습 → 검색 색인 → ONNX 내보내기
#
# 저장 한도가 작은 서버(약 256GB)에 맞췄다. 한 번에 남는 원본은 zip 1~2개(최대 약 140GB)다.
# 준비: ~/aihubshell 로 받은 파일 목록 (./aihubshell -mode l -datasetkey 576 > ~/aihub_list.txt)
# 실행: tmux 안에서 API 키를 환경 변수로 넣고 실행한다. 키는 명령줄·로그·프로세스 목록에 남지 않는다.
#   read -rs "AIHUB_APIKEY?AI Hub API 키: "; export AIHUB_APIKEY        (zsh. bash는 read -rsp '키: ' AIHUB_APIKEY)
#   bash scripts/aihub_train_full.sh 2>&1 | tee -a ~/aihub_full.log
# 중간에 끊겨도 다시 실행하면 끝난 파일과 단계는 건너뛴다.
set -uo pipefail

LIST=${LIST:-$HOME/aihub_list.txt}
RAW=${RAW:-$HOME/aihub_raw}
VENV=${VENV:-$HOME/medimate-venv}
PER_CLASS=${PER_CLASS:-60}
EPOCHS=${EPOCHS:-20}
WORKERS=${WORKERS:-3}   # nproc는 컨테이너 CPU 제한을 모를 수 있어 직접 정한다
BIG_GB=${BIG_GB:-60}    # 이보다 큰 zip은 앞 파일 크롭이 끝나 지워진 뒤에 받는다 (저장 한도)
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT="$REPO/data/aihub_ts/parts"
URL="https://api.aihub.or.kr/down/0.6/576.do"

: "${AIHUB_APIKEY:?AIHUB_APIKEY 환경 변수가 없습니다 (위 사용법 참고)}"
[ -f "$LIST" ] || { echo "파일 목록이 없습니다: $LIST"; exit 1; }
mkdir -p "$RAW/.done" "$RAW/cur" "$OUT"
cd "$RAW" || exit 1
# shellcheck disable=SC1091
source "$VENV/bin/activate"
log() { echo "[$(date '+%m-%d %H:%M')] $*"; }
used() { du -sh "$HOME" 2>/dev/null | cut -f1; }

# 목록 줄 "├─TS_26_단일.zip | 31 GB | 66179" → "66179 TS_26_단일.zip 31"
files() { grep -E "─$1_[0-9]+_단일\.zip" "$LIST" | sed 's/.*─//' | awk '{print $NF, $1, $3}'; }
find_zip() { find "$RAW" -path "$RAW/cur" -prune -o -type f -name "$1" -print | head -1; }

# 나뉜 파일(.part<오프셋>)을 순서대로 이어 붙이면서 조각을 바로 지운다 (공간을 두 배로 쓰지 않는다).
merge_parts() {
    find . -name '*.part[0-9]*' | sed 's/\.part[0-9]*$//' | sort -u | while IFS= read -r base; do
        : > "$base" || exit 1
        find "$(dirname "$base")" -maxdepth 1 -name "$(basename "$base").part[0-9]*" | sort -V \
            | while IFS= read -r p; do cat "$p" >> "$base" && rm -f "$p" || exit 1; done || exit 1
    done
}

# 받으면서 바로 푼다 (download.tar를 디스크에 두지 않는다). 실패하면 그 파일만 처음부터 다시 받는다.
download() {
    local keys=$1 what=$2 try rc
    for try in 1 2 3 4 5; do
        curl -fsSL --retry 5 --retry-delay 30 \
             -H @<(printf 'apikey:%s\n' "$AIHUB_APIKEY") "$URL?fileSn=$keys" | tar -xf -
        rc=("${PIPESTATUS[@]}")
        if [ "${rc[0]}" -eq 0 ] && [ "${rc[1]}" -eq 0 ] && merge_parts; then
            return 0
        fi
        log "   $what 실패 (curl ${rc[0]}, tar ${rc[1]}), 다시 시도 $try/5"
        find . -name '*.part[0-9]*' -delete
        sleep 60
    done
    return 1
}

# zip 하나에서 알약 크롭을 만들고, 성공하면 zip을 지운다.
crop() {
    local key=$1 name=$2 zip=$3 cur="$RAW/cur/$1"
    rm -rf "$cur" && mkdir -p "$cur" && ln -s "$zip" "$cur/$name"
    if python "$REPO/scripts/convert_aihub.py" --images-dir "$cur" --labels-dir "$LABELS" \
            --out "$OUT/${name%.zip}" --max-per-class "$PER_CLASS" --workers "$WORKERS" > "$cur.log" 2>&1; then
        touch "$RAW/.done/crop_$key"
        rm -f "$zip"; rm -rf "$cur"
        log "   $name 크롭 완료: $(grep -E '^크롭' "$cur.log" | tail -1) (홈 사용량 $(used))"
    else
        log "!! $name 크롭 실패. 로그: $cur.log"
        tail -5 "$cur.log"
        return 1
    fi
}

# 1. 라벨(TL, 약 3GB)을 한 번에 받는다. 키가 틀리면 여기서 멈춘다.
if [ ! -f .done/TL ]; then
    keys=$(files TL | awk '{print $1}' | paste -sd, -)
    [ -n "$keys" ] || { echo "목록에서 TL_*_단일 파일을 찾지 못했습니다"; exit 1; }
    log "라벨 받는 중"
    download "$keys" "라벨" || exit 1
    touch .done/TL
fi
LABELS=$(dirname "$(find_zip 'TL_1_단일.zip')")
[ -d "$LABELS" ] || { echo "라벨 폴더를 찾지 못했습니다"; exit 1; }

# 2. 사진(TS): 받기와 앞 파일 크롭을 겹쳐서 진행한다.
total=$(files TS | wc -l); n=0; failed=0; bg=""
wait_bg() { if [ -n "$bg" ]; then wait "$bg" || failed=$((failed + 1)); bg=""; fi; }
while read -r key name size; do
    n=$((n + 1))
    [ -f ".done/crop_$key" ] && continue
    if [ ! -f ".done/$key" ] || [ -z "$(find_zip "$name")" ]; then
        [ "${size%.*}" -ge "$BIG_GB" ] && wait_bg
        log "사진 $n/$total $name (${size}GB) 받는 중 (홈 사용량 $(used))"
        t0=$(date +%s)
        if ! download "$key" "$name" || [ -z "$(find_zip "$name")" ]; then
            log "!! $name 받기 실패"; failed=$((failed + 1)); continue
        fi
        touch ".done/$key"
        log "   받음 (${size}GB, $(( ($(date +%s) - t0) / 60 ))분)"
    fi
    wait_bg
    crop "$key" "$name" "$(find_zip "$name")" &
    bg=$!
done < <(files TS)
wait_bg
if [ $failed -gt 0 ]; then
    log "!! $failed개 파일이 실패했습니다. 이 스크립트를 다시 실행하면 그 파일만 다시 합니다."
    exit 1
fi
log "사진 81개 크롭 완료 (홈 사용량 $(used))"

# 3. 학습 (식약처 참조 + AI Hub Validation 100종(평가 사진 제외) + AI Hub Training 전체)
cd "$REPO" || exit 1
MANIFESTS=(data/reference/manifest.csv)
for m in data/aihub_ts/parts/*/manifest.csv; do [ -f "$m" ] && MANIFESTS+=("$m"); done
[ -f data/aihub/manifest_train.csv ] && MANIFESTS+=(data/aihub/manifest_train.csv)
if [ ! -f models/.full_trained ]; then
    log "학습 시작 (${EPOCHS}에폭, 매니페스트 ${#MANIFESTS[@]}개)"
    python scripts/train_embedder.py --manifest "${MANIFESTS[@]}" --out models/embedder_full.pt \
        --backbone efficientnet_b0 --img-size 224 --epochs "$EPOCHS" --batch-size 128 \
        --workers "$WORKERS" --eval-every 2 || exit 1
    touch models/.full_trained
fi

# 4. 검색 색인 + ONNX
log "검색 색인 만드는 중"
python scripts/build_index.py --model models/embedder_full.pt --manifest "${MANIFESTS[@]}" \
    --out models/index_full.npz --batch-size 256 --workers "$WORKERS" || exit 1
python scripts/export_onnx.py --model models/embedder_full.pt --out models/embedder_full.onnx || exit 1
ls -l models/embedder_full.onnx models/index_full.npz
md5sum models/embedder_full.onnx models/index_full.npz
log "모두 끝났습니다."
