#!/usr/bin/env bash
# AI Hub '경구약제 이미지 데이터'(576) Training 단일경구약제를 전부 받아 임베딩 모델을 학습한다 (GPU 서버용).
#
#   사진 zip 하나씩: 받기 → 알약 크롭(클래스당 60장) → zip 삭제   (원본 3.4TB를 쌓아 두지 않는다)
#   전부 끝나면: 학습 → 검색 색인 → ONNX 내보내기
#
# 저장 한도가 작은 서버(약 256GB)에 맞췄다. 한 번에 남는 원본은 zip PARALLEL개(2개면 최대 약 190GB)다.
# 준비: ~/aihubshell 로 받은 파일 목록 (./aihubshell -mode l -datasetkey 576 > ~/aihub_list.txt)
# 실행: tmux 안에서 API 키를 환경 변수로 넣고 실행한다. 키는 명령줄·로그·프로세스 목록에 남지 않는다.
#   read -rs "AIHUB_APIKEY?AI Hub API 키: "; export AIHUB_APIKEY        (zsh. bash는 read -rsp '키: ' AIHUB_APIKEY)
#   bash scripts/aihub_train_full.sh 2>&1 | tee -a ~/aihub_full.log
# 중간에 끊겨도 다시 실행하면 끝난 파일과 단계는 건너뛴다. 받다 만 파일은 처음부터 다시 받는다 (AI Hub는 이어받기를 지원하지 않는다).
set -uo pipefail

LIST=${LIST:-$HOME/aihub_list.txt}
RAW=${RAW:-$HOME/aihub_raw}
VENV=${VENV:-$HOME/medimate-venv}
PER_CLASS=${PER_CLASS:-60}
EPOCHS=${EPOCHS:-20}
WORKERS=${WORKERS:-3}   # nproc는 컨테이너 CPU 제한을 모를 수 있어 직접 정한다
# 동시에 받는 파일 수. AI Hub는 연결 하나의 속도가 오래 받을수록 떨어진다 (2026-11-18: 기존 연결 6MB/s, 새 연결 33MB/s).
# 파일마다 zip을 크롭이 끝날 때까지 들고 있으므로 3 이상이면 저장 한도(256GB)를 넘을 수 있다.
PARALLEL=${PARALLEL:-2}
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT="$REPO/data/aihub_ts/parts"
URL="https://api.aihub.or.kr/down/0.6/576.do"

: "${AIHUB_APIKEY:?AIHUB_APIKEY 환경 변수가 없습니다 (위 사용법 참고)}"
[ -f "$LIST" ] || { echo "파일 목록이 없습니다: $LIST"; exit 1; }
mkdir -p "$RAW/.done" "$RAW/cur" "$RAW/dl" "$OUT"
cd "$RAW" || exit 1
# 두 개가 동시에 돌면 서로의 조각 파일을 지운다
exec 9>"$RAW/.lock"
if command -v flock >/dev/null; then
    flock -n 9 || { echo "이 스크립트가 이미 실행 중입니다 (tmux ls 로 확인하세요)"; exit 1; }
fi
# 지난 실행에서 받다 만 조각은 이어받을 수 없으니 지운다
find "$RAW" -name '*.part[0-9]*' -delete
# shellcheck disable=SC1091
source "$VENV/bin/activate"
log() { echo "[$(date '+%m-%d %H:%M')] $*"; }
used() { du -sh "$HOME" 2>/dev/null | cut -f1; }

# 목록 줄 "├─TS_26_단일.zip | 31 GB | 66179" → "66179 TS_26_단일.zip 31"
files() { grep -E "─$1_[0-9]+_단일\.zip" "$LIST" | sed 's/.*─//' | awk '{print $NF, $1, $3}'; }
find_zip() { find "$RAW" -path "$RAW/cur" -prune -o -type f -name "$1" -print | head -1; }

# 폴더 $1 안의 나뉜 파일(.part<오프셋>)을 순서대로 이어 붙이면서 조각을 바로 지운다 (공간을 두 배로 쓰지 않는다).
merge_parts() {
    find "$1" -name '*.part[0-9]*' | sed 's/\.part[0-9]*$//' | sort -u | while IFS= read -r base; do
        : > "$base" || exit 1
        find "$(dirname "$base")" -maxdepth 1 -name "$(basename "$base").part[0-9]*" | sort -V \
            | while IFS= read -r p; do cat "$p" >> "$base" && rm -f "$p" || exit 1; done || exit 1
    done
}

# AI Hub API는 정상 응답에도 HTTP 502를 돌려줄 때가 있어(2026-11-15 확인) 상태 코드가 아니라 내용으로 판단한다.
# 파일 목록이 안 오면 API 서버가 살아날 때까지 5분마다 확인하며 기다린다 (재시도 횟수를 쓰지 않는다).
wait_api() {
    local warned=""
    while true; do
        if curl -s -m 60 "https://api.aihub.or.kr/info/576.do" | grep -q "TS_1_단일"; then
            [ -n "$warned" ] && log "   AI Hub 서버 응답 정상, 다시 받습니다"
            return 0
        fi
        [ -z "$warned" ] && log "   AI Hub 파일 목록이 오지 않습니다. 5분마다 다시 확인합니다"
        warned=1
        sleep 300
    done
}

# 받으면서 바로 푼다 (download.tar를 디스크에 두지 않는다). 파일마다 따로 폴더($RAW/dl/<이름>)에 푼다.
# 실패하면 그 파일만 처음부터 다시 받는다. 성공 여부는 tar가 풀리는지로 판단한다.
# 인증 실패(키·데이터 승인 문제)면 2를 돌려주고 더 시도하지 않는다.
# 연결이 끊긴 채 멈추면(10분 동안 초당 100KB 미만) curl을 끝내고 다시 받는다 (2026-11-15 TS_14에서 5시간 멈춤).
download() {
    local keys=$1 what=$2 dir="$RAW/dl/${2%.zip}" try rc msg
    mkdir -p "$dir" || return 1
    for try in 1 2 3 4 5; do
        wait_api
        curl -sSL --speed-limit 102400 --speed-time 600 \
            -H @<(printf 'apikey:%s\n' "$AIHUB_APIKEY") "$URL?fileSn=$keys" | tar -C "$dir" -xf -
        rc=("${PIPESTATUS[@]}")
        if [ "${rc[0]}" -eq 0 ] && [ "${rc[1]}" -eq 0 ] && merge_parts "$dir"; then
            return 0
        fi
        find "$dir" -name '*.part[0-9]*' -delete
        # 응답이 짧은 글이면(오류 안내) 무슨 내용인지 남긴다. 큰 파일이면 내려받지 않는다.
        msg=$(curl -s -m 60 --max-filesize 65536 -H @<(printf 'apikey:%s\n' "$AIHUB_APIKEY") \
              "$URL?fileSn=$keys" | head -c 300 | tr -d '\0\r\n')
        log "   $what 실패 (curl ${rc[0]}, tar ${rc[1]})${msg:+, AI Hub 응답: $msg}, 다시 시도 $try/5"
        if [[ "$msg" == *인증* || "$msg" == *권한* ]]; then
            log "!! AI Hub 인증 실패. API 키나 데이터 사용 승인(신청 내역)을 확인하세요."
            return 2
        fi
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

# 사진 파일 하나: 받기 → 크롭 → zip 삭제. 백그라운드 작업으로 돌린다.
job() {
    local key=$1 name=$2 size=$3 n=$4 t0 drc
    if [ ! -f "$RAW/.done/$key" ] || [ -z "$(find_zip "$name")" ]; then
        log "사진 $n/$total $name (${size}GB) 받는 중 (홈 사용량 $(used))"
        # 받다 만 같은 이름의 zip(이어 붙이다 끊긴 것)이 남아 있으면 지운다. 안 지우면 크롭이 깨진 zip을 집을 수 있다.
        find "$RAW" -path "$RAW/cur" -prune -o -type f -name "$name" -exec rm -f {} +
        t0=$(date +%s)
        download "$key" "$name"; drc=$?
        [ $drc -eq 2 ] && return 2
        if [ $drc -ne 0 ] || [ -z "$(find_zip "$name")" ]; then
            log "!! $name 받기 실패"; return 1
        fi
        touch "$RAW/.done/$key"
        log "   $name 받음 (${size}GB, $(( ($(date +%s) - t0) / 60 ))분)"
    fi
    crop "$key" "$name" "$(find_zip "$name")"
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

# 2. 사진(TS): 파일 PARALLEL개를 동시에 받고 크롭한다.
mapfile -t ITEMS < <(files TS)
total=${#ITEMS[@]}; n=0; failed=0; auth=""; pids=()
# 끝난 작업을 거둔다. $1개 미만이 될 때까지 기다린다.
reap() {
    local i rc
    while [ ${#pids[@]} -ge "$1" ]; do
        for i in "${!pids[@]}"; do
            kill -0 "${pids[$i]}" 2>/dev/null && continue
            wait "${pids[$i]}"; rc=$?
            unset 'pids[i]'
            [ $rc -eq 2 ] && auth=1
            [ $rc -ne 0 ] && failed=$((failed + 1))
        done
        pids=("${pids[@]}")
        [ ${#pids[@]} -ge "$1" ] && sleep 10
    done
}
for item in "${ITEMS[@]}"; do
    read -r key name size <<< "$item"
    n=$((n + 1))
    [ -f ".done/crop_$key" ] && continue
    reap "$PARALLEL"
    [ -n "$auth" ] && break
    job "$key" "$name" "$size" "$n" &
    pids+=($!)
    sleep 5   # 로그 순서가 섞이지 않게 조금 띄운다
done
reap 1
[ -n "$auth" ] && exit 1
if [ $failed -gt 0 ]; then
    log "!! $failed개 파일이 실패했습니다. 이 스크립트를 다시 실행하면 그 파일만 다시 합니다."
    exit 1
fi
log "사진 ${total}개 크롭 완료 (홈 사용량 $(used))"

# 3. 학습 (식약처 참조 + AI Hub Validation 100종(평가 사진 제외) + AI Hub Training 전체)
cd "$REPO" || exit 1
MANIFESTS=(data/reference/manifest.csv)
for m in data/aihub_ts/parts/*/manifest.csv; do [ -f "$m" ] && MANIFESTS+=("$m"); done
[ -f data/aihub/manifest_train.csv ] && MANIFESTS+=(data/aihub/manifest_train.csv)
# 작은 파일 27만 개를 매 에폭 NAS에서 열면 GPU가 거의 논다 (2026-11-18). 한 번 묶어 두고 거기서 읽는다.
PACK=data/packed/train_full.pack
if [ ! -f models/.full_trained ]; then
    if [ ! -f "$PACK" ]; then
        log "학습용 사진을 파일 하나로 묶는 중"
        python scripts/pack_images.py --manifest "${MANIFESTS[@]}" --out "$PACK" || exit 1
    fi
    log "학습 시작 (${EPOCHS}에폭, 매니페스트 ${#MANIFESTS[@]}개)"
    python scripts/train_embedder.py --manifest "${MANIFESTS[@]}" --pack "$PACK" --out models/embedder_full.pt \
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
