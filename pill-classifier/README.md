# 사진 기반 알약 식별 (pill-classifier)

사진 한 장(또는 앞·뒷면 두 장)으로 알약 후보 Top-K를 찾는 파이프라인입니다.

```
사진 → 전처리(화이트밸런스) → 알약 분할(배경 분리 또는 YOLO)
     → 정규화 크롭(배경 회색, 장축 수평 정렬)
     → ┬ 모양 추정   (방사 프로파일 꼭짓점 수 + 종횡비/면적비)
       ├ 색상 추정   (k-means 2색, 식약처 색상 분류명으로 매핑)
       ├ 각인 OCR    (CLAHE + RapidOCR, 혼동 문자 통일 후 퍼지 매칭)
       └ 임베딩      (EfficientNet + ArcFace, 참조 이미지 색인 검색)
     → 식약처 DB 전 품목과 가중합 점수 → Top-K + 신뢰도 경고
```

**학습 없이도 동작합니다.** DB만 구축하면 모양·색·각인으로 식별합니다. 임베딩 모델을 학습해 색인을 만들면 자동으로 함께 쓰고, 정확도가 크게 오릅니다.

## 폴더 구조

```
pillid/            라이브러리
  imaging.py       분할, 정규화 크롭, 화이트밸런스
  attributes.py    모양/색 추정 + 유사도 표
  ocr.py           각인 인식, 정규화, 퍼지 매칭
  embedding.py     PillEmbedder, ArcFaceHead, 추론용 Embedder
  pipeline.py      PillIdentifier (전체 식별 흐름과 점수 계산)
  db.py            식약처 데이터 SQLite
  vision.py        Claude Opus 5.5 비전 보조 (관찰, 재순위)
  prompts/         Claude 시스템 프롬프트 (버전별 파일)
  enums.py         식약처 한글 분류값 ↔ MediMate API enum 변환
  demo.py          서비스키 없이 시험하는 데모 DB·합성 사진
server/app.py      추론 서버 (FastAPI). MediMate 백엔드가 내부망에서 호출
scripts/
  build_db.py          ① 식약처 API → DB + 참조 이미지
  prepare_reference.py ② 참조 이미지 → 앞/뒷면 크롭 (+ 모양/색 추정 일치율 보고)
  convert_aihub.py     ③ AI Hub 경구약제 데이터 → 학습 크롭 / YOLO 데이터셋
  train_embedder.py    ④ 임베딩 모델 학습
  build_index.py       ⑤ 참조 크롭 임베딩 색인
  export_onnx.py       학습한 .pt → .onnx (PyTorch 없는 PC에서 추론)
  identify.py          식별 실행
  make_demo_data.py    데모 DB·사진 생성
  check_claude.py      Claude 접근 권한을 받은 뒤 실제 호출 확인
  eval_reference.py    식약처 참조 사진으로 정확도 평가
  tune_scoring.py      점수 상수·신뢰도 기준 조정 (조정용/검증용 표본 분리)
  build_color_refs.py  식약처 참조 사진 → 색 기준표 (pillid/color_refs.npz)
  eval_photos.py       실제로 찍은 사진 폴더로 Top-1/3/5/10 평가
  make_synthetic_photos.py  참조 사진 → 폰 사진 비슷한 합성 사진 (동작 확인용)
  aihub_eval_set.py    AI Hub Validation 사진 → 실제 사진 평가셋 (zip에서 바로 읽음)
  eval_nih_photos.py   미국 NIH 소비자 사진으로 분할·모양·색·OCR 검증
  pack_for_colab.py    Colab 학습용 코드·데이터 zip 묶기
notebooks/train_embedder_colab.ipynb   Colab(GPU)에서 임베딩 학습 → 색인 생성
tests/                 합성 도형 기반 테스트 + 추론 서버 API 테스트 + Claude 호출 규칙 테스트
```

## 추론 서버

가장 쉬운 방법: `run_server.bat`을 더블클릭합니다. 실제 DB가 없으면 데모 DB로 켜지고, 브라우저에서 http://localhost:8000/docs 를 열면 사진을 올려 바로 시험해 볼 수 있습니다. 끄려면 창을 닫습니다.

직접 실행하려면:

```powershell
.venv\Scripts\python scripts\make_demo_data.py                  # 데모 데이터 (실제 DB가 있으면 생략)
$env:PILL_DB = "data\demo\pills.db"; $env:PILL_USE_OCR = "0"
.venv\Scripts\python -m uvicorn server.app:app --port 8000
```

| 엔드포인트 | 용도 |
|---|---|
| `POST /v1/identify` | multipart: `front`(필수), `back`, `hints`(JSON), `topk` → 특징 + 후보 |
| `GET /v1/items/{itemSeq}` | 품목 상세 |
| `GET /v1/items/{itemSeq}/image` | 식약처 참조 사진 (`data/images/{품목기준코드}.jpg`, 없으면 404) |
| `GET /v1/items` | 수동 검색 (drugShape, colorClass, printText, lineFront, itemName) |
| `GET /health` | 상태 (`ocr`, `embedding`, `vision`이 켜져 있는지 포함) |

입출력은 모두 MediMate API enum(`ROUND`, `WHITE` 등)을 씁니다. 사진은 저장하지 않습니다.

## Claude Opus 5.5 비전 보조

`docs/03_AI_OCR_연동설계서.md` §6의 관찰과 재순위를 이 서버가 직접 수행합니다 ([pillid/vision.py](pillid/vision.py), 프롬프트는 [pillid/prompts/](pillid/prompts/)).

1. **관찰** (`pill-observe-v1`, effort low): Claude가 사진을 보고 모양·색·각인·사진 문제를 기록합니다. 알약이 없거나 여러 개면 여기서 바로 `NOT_FOUND`를 돌려줍니다.
2. **후보 점수**: Claude의 관찰과 CV 추정을 합칩니다. 모양·색이 서로 다르면 두 유사도를 평균하고, 각인은 OCR과 관찰 중 더 잘 맞는 쪽을 씁니다. Claude가 각인을 읽기 어렵다고 하면(`LOW`) 각인은 쓰지 않습니다. 사용자가 입력한 힌트가 있는 항목은 힌트가 우선합니다.
3. **재순위** (`pill-rerank-v1`, effort medium): 결과가 불확실할 때만(저신뢰이거나 1·2위 차이 0.1 미만) 상위 5개 후보를 Claude가 사용자 사진·참조 사진과 다시 비교합니다. 최종 점수 = 0.5 × 기존 점수 + 0.5 × 판정값(같아 보임 1.0, 가능 0.5, 다름 0). "다름" 판정은 목록 뒤로 갑니다.

Claude가 꺼져 있거나 호출이 실패하면 2번만으로 동작합니다. **기본값은 꺼짐**입니다.

### 켜는 방법

키는 코드나 파일에 넣지 않고 환경변수로만 줍니다.

```powershell
# AWS Bedrock (설계서 기준). 자격 증명은 AWS 기본 체인(IAM Role, AWS_PROFILE, AWS_ACCESS_KEY_ID 등)
$env:PILL_AI_PROVIDER = "bedrock"; $env:AWS_REGION = "us-east-1"
# 또는 Anthropic API 직접
$env:PILL_AI_PROVIDER = "anthropic"; $env:ANTHROPIC_API_KEY = "..."
```

| 환경변수 | 설명 |
|---|---|
| `PILL_AI_PROVIDER` | `none`(기본) / `bedrock` / `anthropic` |
| `PILL_AI_MODEL` | 비우면 `anthropic.claude-opus-5-5`(Bedrock) 또는 `claude-opus-5-5`. 2026-11-22 Fable 5.1에서 바꿈(요금 2.5배 차이) |
| `PILL_AI_FALLBACK_MODEL` | 안전 분류기가 거절했을 때 1회 재시도할 모델. 비우면 재시도 없음 |
| `PILL_AI_TIMEOUT` | 요청 제한 시간(초), 기본 40 |

접근 권한을 받으면 먼저 실제 호출을 확인합니다(요금 발생).

```bash
python scripts/check_claude.py
```

데모 캡슐 사진을 Claude에 보내 관찰 결과를 출력합니다. 실패하면 원인 종류가 나옵니다: `BAD_REQUEST`(모델 ID·스키마·권한), `UNAVAILABLE`(네트워크·한도), `REFUSED`(안전 분류기).

### 알아 둘 점
- 이 README의 Claude 보조 평가 수치는 모두 Fable 5.1(Anthropic API 직접)로 잰 것입니다. **Opus 5.5로는 아직 실제로 호출해 보지 못했습니다**(2026-11-22 크레딧 소진). 모델 ID나 출력 스키마가 거절되면 `check_claude.py`에서 `BAD_REQUEST`로 드러납니다.
- 설계서 §3.3의 호출 규칙(Fable 5.1 기준, Opus 5.5도 같음)을 따릅니다: `thinking`·`temperature`를 보내지 않고, 도구 대신 구조화 출력을 쓰며, 깊이는 effort로만 조절합니다.
- 사진은 긴 변 1,024px JPEG로 줄여서 보냅니다(이미지 토큰 ≈ 가로×세로÷750).
- 켜면 응답이 느려집니다(관찰 한 번 + 필요 시 재순위 한 번, 각각 최대 40초).
- 사용자의 알약 사진이 외부(해외 리전일 수 있음)로 전송됩니다. 설계서 02 §8의 국외 이전 동의가 필요합니다.
- 설계서 §6.2의 "복용 중인 약 +0.05 가산"은 아직 없습니다. 백엔드가 사용자의 복용 약 목록을 넘겨 줘야 합니다.

## 약봉투 인식

`POST /v1/bag/recognize`(사진 `image` 또는 앱이 읽은 글자 `ocrText`)는 약봉투를 복약 정보 초안으로 바꿉니다. 흐름은 설계서(docs/03 §1.2)를 따릅니다.

1. **글자 읽기:** CLOVA OCR로 글자와 위치를 읽고, 줄과 표 칸(` | `)을 재구성합니다. 글자가 너무 적거나 흐리면 다시 찍게 합니다.
2. **개인정보 가림:** 환자 이름, 주민번호, 전화번호, 교부번호를 가린 글자만 Claude에 보냅니다. 환자 이름은 `홍O동`처럼 가려서 돌려줍니다.
3. **정리:** Claude가 약마다 이름, 1회량, 횟수, 시점, 식사 관계, 일수와 근거 줄을 JSON으로 냅니다(`pillid/prompts/bag-extract-v1.md`).
4. **검증:** 근거 줄에 없는 이름과 숫자, 범위를 벗어난 값은 비우고 "확인 필요"로 표시합니다. 시점이 없으면 기본 시점(1회 아침, 2회 아침·저녁, 3회 아침·점심·저녁)으로 채우되 확인을 요구합니다.
5. **재확인:** 문제가 남으면 사진을 함께 보내 Claude에 한 번 더 확인합니다(`bag-recheck-v1`). 이때는 사진에 보이는 이름도 전송됩니다.
6. **품목 대조:** 식약처 DB 품목명과 비교해 일치, 후보, 없음으로 나눕니다.

**설정:** `pill-classifier\.clova_ocr` 파일에 아래 두 줄을 넣습니다. 값은 네이버 클라우드 콘솔의 CLOVA OCR > Domain > API Gateway 연동에 있습니다. 도메인은 General이어야 합니다.

```
CLOVA_OCR_INVOKE_URL=https://...
CLOVA_OCR_SECRET=...
```

`/health`의 `bagOcr`, `bagAi`로 설정 상태를 확인할 수 있습니다. 비용은 봉투 한 장에 Claude 호출 1~2번입니다.

## 백엔드 연동

백엔드는 사진 파일을 그대로 `POST /v1/identify`에 넘기면 됩니다. Claude 설정은 백엔드에 필요 없습니다.

```
POST /v1/identify            (multipart/form-data, 내부망 전용)
  front   필수  앞면 사진 (JPEG/PNG)
  back    선택  같은 알약의 뒷면 사진
  hints   선택  사용자가 입력한 값 JSON: {"drugShape":"ROUND","colorClass":["WHITE"],"printText":"DW"}
  topk    선택  후보 수 (기본 10, 최대 50)
  헤더 X-Internal-Key: 서버에 PILL_INTERNAL_KEY를 설정한 경우 같은 값
```

```json
{
  "status": "COMPLETED",              // 또는 NOT_FOUND
  "reason": null,                     // NOT_FOUND일 때 NO_PILL | MULTIPLE_PILLS
  "features": {"drugShape": "ROUND", "colorClass1": "YELLOW", "colorClass2": null,
               "printFront": "YH", "printBack": null, "featureConfidence": 0.8, "...": null},
  "candidates": [
    {"itemSeq": "200000001", "itemName": "...", "entpName": "...", "className": "...",
     "etcOtcName": "...", "itemImageUrl": "https://...", "score": 0.93,
     "scoreBreakdown": {"shape": 1.0, "color": 1.0, "imprint": 1.0, "rerank": 1.0},
     "description": "모양 원형, 색 노랑, 앞면 각인 YH, 뒷면 각인 없음, 제형 필름코팅정", "hasImage": true}
  ],
  "lowConfidence": false,             // true면 "약사에게 확인" 안내와 수동 검색을 권한다
  "imageIssues": [],                  // BLURRY | GLARE | BAD_BACKGROUND | CROPPED | NO_PILL (Claude가 본 사진 문제)
  "observationUsed": true,            // Claude 관찰을 썼는지
  "reranked": false,                  // Claude 재순위를 거쳤는지
  "sidesUsed": 1, "pillCount": 1, "version": "0.1.0"
}
```

API 명세의 `PillIdentificationJob`으로 바꿀 때:

| 추론 서버 응답 | 명세 필드 |
|---|---|
| `status`, `candidates[].score` | `status`, `candidates[].matchScore` (순서대로 `rank` 부여) |
| `features` | `extractedFeatures` (필드 이름 동일) |
| `NOT_FOUND` | `fallbackAction = RETAKE` |
| `lowConfidence = true` | `imageIssues`가 있으면 `RETAKE`, 없으면 `MANUAL_SEARCH`. `requiresUserSelection = true` |
| 1·2위 `score` 차이 < 0.15 | `requiresUserSelection = true` (명세 기준) |

`ttsScript`, `isInMyMedications`, 파일 업로드, 잡(202 → 폴링), 인증은 백엔드 몫입니다. 수동 검색(`GET /pill-identification/search`)과 후보 선택 후 약 정보는 `GET /v1/items`, `GET /v1/items/{itemSeq}`를 쓰면 됩니다.

## 설치

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python -m pytest tests -q
```

GPU로 학습하려면 [pytorch.org](https://pytorch.org)에서 CUDA 버전 torch를 먼저 설치하세요.

## 1단계: DB 구축 (학습 없이 바로 쓰기)

1. [공공데이터포털](https://www.data.go.kr/data/15057639/openapi.do)에서 **식품의약품안전처_의약품 낱알식별 정보**를 활용신청하고 서비스키를 받습니다. 승인 직후에는 키가 동기화되기까지 시간이 걸릴 수 있습니다.
2. `build_db.bat`을 더블클릭합니다. 메모장이 열리면 받은 "일반 인증키"를 한 줄로 붙여 넣고 저장한 뒤 닫습니다. 키는 `.mfds_service_key` 파일에 저장되고 Git에는 올라가지 않습니다. 전체(약 2.5만 품목)와 참조 사진을 받는 데 30분~1시간 걸리고, 끊겨도 다시 실행하면 이어서 받습니다.

직접 실행하려면:

```powershell
python scripts/build_db.py --max-pages 2          # 먼저 200건으로 동작 확인
python scripts/build_db.py --download-images      # 전체 + 참조 사진
```

키는 `.mfds_service_key` 파일, 환경변수 `MFDS_SERVICE_KEY`, `--service-key` 순으로 찾습니다. "등록되지 않은 서비스키" 오류가 나면 활용신청 승인 여부를 확인하세요. 발급 직후에는 반영까지 1~2시간 걸릴 수 있습니다.

3. 식별합니다.

```bash
python scripts/identify.py photo.jpg
python scripts/identify.py front.jpg --back back.jpg
```

## 2단계: 임베딩 모델 학습 (정확도 향상)

```bash
# 식약처 참조 이미지 → 앞/뒷면 크롭. 모양/색 추정 일치율도 출력되니 attributes.py 임계값 조정에 쓰세요
python scripts/prepare_reference.py

# AI Hub '경구약제 이미지 데이터'(aihub.or.kr, 신청 필요)를 받은 뒤
python scripts/convert_aihub.py --images-dir D:/aihub/원천데이터 --labels-dir D:/aihub/라벨링데이터 --limit 200   # 먼저 확인
python scripts/convert_aihub.py --images-dir D:/aihub/원천데이터 --labels-dir D:/aihub/라벨링데이터

# 학습 (GPU 권장)
python scripts/train_embedder.py --manifest data/reference/manifest.csv data/aihub/manifest.csv --epochs 20

# 색인 생성. AI Hub 크롭까지 넣으면 품목당 참조 시점이 늘어납니다
python scripts/build_index.py --manifest data/reference/manifest.csv data/aihub/manifest.csv
```

**Colab에서 학습하기 (권장)**: [notebooks/train_embedder_colab.ipynb](notebooks/train_embedder_colab.ipynb)

1. PC에서 `python scripts/pack_for_colab.py` → `colab/pill-classifier-code.zip`, `colab/pill-classifier-data.zip`(학습 크롭, 약 1.7GB)
2. 두 파일을 Google Drive의 `MediMate/` 폴더에 올리고, 노트북을 Colab에서 열어 T4 GPU로 셀을 차례로 실행 (20에폭 1~2시간)
3. Drive에 생긴 `MediMate/models/embedder.pt`를 PC의 `models/embedder.pt`로, `index.npz`를 `data/index.npz`로 복사
4. `python scripts/eval_photos.py data/aihub/eval_photos4`로 임베딩 유무 효과를 비교 (`--no-embedding`)

노트북은 평가에 쓰는 AI Hub 사진을 학습 크롭에서 자동으로 뺍니다. 마지막 셀이 모델을 **ONNX**(`embedder.onnx`)로도 내보냅니다. 추론은 `.onnx`를 쓰면 PyTorch 없이 onnxruntime만으로 돌아갑니다. 이 PC처럼 Windows Smart App Control이 PyTorch DLL을 (간헐적으로) 차단하는 환경에서는 `.pt`를 읽을 수 없으므로 `models/embedder.onnx`를 두세요. `find_default_model`이 `.onnx`를 먼저 찾고, PyTorch가 될 때만 `.pt`도 씁니다. 학습 → 색인 → 식별 경로는 이 PC에서 600품목·1에폭으로 끝까지 도는 것을 확인했습니다(CPU 6분, 성능 측정용은 아님). **이 PC(GPU 없음)에서는 본 학습이 현실적이지 않습니다.** CPU로는 참조 크롭 5만 장 1에폭에 수 시간이 걸립니다. 학습은 Google Colab(무료 T4)이나 GPU가 있는 PC에서 하세요. Colab에서는 저장소와 `data/reference/`, `data/aihub/`를 Drive에 올린 뒤 위 명령을 그대로 실행하면 됩니다(`--workers 2`, `--batch-size 128`). 학습 결과 `models/embedder.pt`만 이 PC로 가져와 `build_index.py`를 돌리면 됩니다.

AI Hub 데이터 없이 참조 크롭만으로 학습하면 품목당 사진이 앞·뒤 2장뿐이라 "다른 각도·조명에서도 같은 약"을 배울 수 없습니다. AI Hub '경구약제 이미지 데이터'는 신청 승인 후 내려받을 수 있고, 용량이 수백 GB라 필요한 부분만 받는 것이 좋습니다.

`models/embedder.onnx`(또는 `.pt`)와 `data/index.npz`가 있으면 `identify.py`와 서버가 자동으로 사용합니다. 새 약이 추가되면 ①→②→⑤만 다시 실행하면 되고, 재학습은 필요 없습니다.

## 3단계(선택): YOLO 탐지기

여러 알약이 붙어 있거나 배경이 복잡하면 배경 분리 방식이 실패합니다. 이때 탐지기를 학습해 씁니다.

```bash
python scripts/convert_aihub.py --images-dir ... --labels-dir ... --yolo --no-crops
yolo detect train data=data/aihub/yolo/data.yaml model=yolov8n.pt imgsz=960 epochs=50
python scripts/identify.py photo.jpg --detector runs/detect/train/weights/best.pt
```

## 촬영 가이드 (정확도에 가장 큰 영향)

- **어두운 단색 배경**(검정·남색 천이나 종이) 위에서 찍으세요. 흰 알약을 흰 종이 위에 두면 분할에 실패합니다.
- 알약이 화면의 1/4~1/2를 차지하게 가까이서 찍으세요. 너무 멀면 각인이 작아 읽지 못합니다.
- 알약끼리 겹치거나 붙지 않게 놓으세요.
- 그림자가 적은 확산광이 좋습니다. 형광등 아래 직접 조명보다 창가 간접광이 낫습니다.
- 각인이 있는 면을 위로 두고, 가능하면 앞·뒷면을 모두 찍으세요 (`--back`).

## 점수 계산

| 단서 | 가중치 (임베딩 있을 때) | 임베딩 없을 때 | 비고 |
|---|---|---|---|
| 임베딩 유사도 | 0.55 | - | 품목별 참조 이미지 중 최고 코사인 유사도 |
| 각인 | 0.36 | 0.80 | 편집거리 + 부분 일치(읽은 4글자 이상이 정답 안에 그대로 있으면 더 인정). 조각 순서와 앞/뒤 배정은 무시. 영문 OCR이 못 읽는 한글 조각은 비교에서 제외 |
| 색 | 0.045 | 0.10 | 비슷한 색(하양/회색, 파랑/남색 등)은 부분 점수. 실제 사진에서 자주 틀려 비중이 작다 |
| 모양 | 0.045 | 0.10 | 헷갈리는 쌍(타원형/장방형 등)은 부분 점수 |

없는 단서는 빼고 나머지를 다시 정규화합니다. 가중치는 `pillid/pipeline.py`의 `BASE_WEIGHTS`, 각인 점수 상수는 `pillid/ocr.py`에 있습니다.

**신뢰도**: 임베딩이 없을 때는 1위 점수 0.85 이상, 1·2위 차 0.08 이상, 1위의 각인 점수 0.9 이상을 모두 만족할 때만 확신으로 봅니다. 임베딩이 있을 때는 점수 0.6 이상, 차 0.03 이상이고 **각인 점수 0.9 이상 또는 임베딩 점수 0.7 이상**이면 확신입니다(실제 사진 276쌍: 확신 표시 80%, 그중 정답 99%). 아니면 `lowConfidence = true`입니다.

**학습한 약만 확신**: 추론 서버는 위 조건을 만족해도 1위가 `models/trained_items.txt`(임베딩을 학습한 약 목록)에 없으면 `lowConfidence = true`로 냅니다. 학습하지 않은 약은 겉모습이 비슷한 다른 약을 높은 점수로 확신하는 일이 있었기 때문입니다(임베딩 끔 + Claude 켬, 90종: 확신 오답 6건 → 이 규칙 적용 시 1건). 임베딩이 꺼져 있으면 어떤 약도 확신하지 않습니다. 모델을 새로 학습하면 `python scripts/write_trained_items.py --crops <학습한 크롭 폴더>`로 목록도 다시 만드세요. 크롭 폴더가 서버에만 있으면 학습 때 만든 전체 색인으로 `--index models/index_full.npz`를 씁니다.

## 평가와 점수 조정

식약처 참조 사진(앞·뒷면이 나란히 찍힌 스튜디오 사진)으로 전체 흐름을 평가합니다. 폰 사진보다 조건이 좋으므로 **상한에 가까운 수치**입니다.

```powershell
python scripts/eval_reference.py --n 400 --seed 1 --save-obs data/eval/obs.json   # 관측 (OCR 포함, 품목당 3~4초)
python scripts/eval_reference.py --load-obs data/eval/obs.json --part validation  # 저장한 관측으로 점수만 다시 계산
python scripts/tune_scoring.py --obs data/eval/obs.json                           # 점수 상수·신뢰도 기준 조정
```

표본의 앞 절반은 조정용, 뒤 절반은 검증용입니다. `tune_scoring.py`는 조정용에서 값을 고르고 검증용 결과를 함께 보여 줍니다. 값은 자동으로 반영되지 않으니 검증용에서도 나아졌을 때만 상수를 고치세요.

2026-10 결과 (DB 25,439품목, RapidOCR, 임베딩·Claude 없음, 검증용 200품목):

| | 처음 | 각인 비교·점수 조정 후 | 모양·색·분할 보정 후 |
|---|---|---|---|
| Top-1 | 44.5% | 57.0% | **68.0%** |
| Top-5 / Top-10 | 54.0% / 61.0% | 65.0% / 70.0% | **78.0% / 80.5%** |
| 확신 표시의 정답률 | 77% (100건 중 오답 23건) | 93% (70건 중 오답 5건) | **98%** (96건 중 오답 2건) |

(가중치를 실제 사진에 맞춰 80:10:10으로 바꾼 뒤에도 이 검증용에서 Top-1 68.0%, Top-5 75.5%, 확신 표시 정답률 99%로 유지됩니다.)
| 모양 / 주색상 일치 | 78% / 78% | 78% / 78% | 90% / 90% |
| 각인을 양면 모두 정확히 읽음 | 53% | 53% | 61% |

- 모양·색만으로는 Top-1이 1%입니다. 식별은 사실상 각인에 달려 있습니다.
- 모양·색·각인이 완전히 같은 품목이 DB의 4%(1,097품목)라서 Top-1은 최대 98% 정도입니다.
- OCR 엔진: 같은 400품목에서 각인 정확 판독률이 RapidOCR 56%, EasyOCR 29%였고 속도도 RapidOCR이 두 배 빨라서 RapidOCR을 먼저 씁니다.

### 모양·색 추정

모양 임계값과 색 기준표는 평가용과 겹치지 않는 참조 사진으로 맞췄습니다. 조정에 쓰지 않은 1,000품목에서 모양 일치 78.2% → 90.2%, 주색상 일치 73.0% → 88.0%입니다.

- **색 기준표** (`pillid/color_refs.npz`): 식약처 사진 8,000품목에서 뽑은 (실제 색, DB 색 이름) 약 16,000쌍입니다. 가까운 기준색 5개의 다수결로 색 이름을 정하고, 가까운 기준색이 없으면 색상·채도·명도 규칙을 씁니다. 비교할 때 밝기(L)는 거의 보지 않습니다(가중치 0.15). 실제 사진은 노출·조명에 따라 밝기가 크게 흔들리기 때문이며, 이렇게 해도 참조 사진 일치율은 그대로이고 실제 사진(AI Hub 25% → 31%, NIH 34% → 41%)에서는 오릅니다.
- `build_color_refs.py --aihub-base ...`로 AI Hub 실제 사진의 색을 기준표에 넣을 수도 있습니다. AI Hub 촬영 장비 사진에서는 크게 올랐지만(31% → 45%) 폰 사진(NIH)에서는 오히려 떨어져서(41% → 37%) **기본 기준표에는 넣지 않았습니다.** 식약처의 색 이름은 눈으로 보는 것과 다릅니다(살구색은 "분홍", 밝은 초록은 "연두").
- 기준표를 다시 만들 때는 평가용 품목을 빼야 합니다. 넣으면 평가에서 자기 자신과 비교하게 됩니다.

```powershell
python scripts/build_color_refs.py --exclude scripts/heldout_items.txt
```

- **참조 사진 분할** (`imaging.segment_reference`): 식약처 사진은 배경이 밝아서 Otsu 분할이 흰 알약의 그림자 쪽이나 두 색 캡슐의 한쪽만 잡습니다. 고정 색차 기준과 볼록 껍질로 알약 전체를 잡습니다. 폰 사진 경로(`segment_pills`)는 그대로입니다.

### 실제 사진으로 평가

위 수치는 식약처 스튜디오 사진 기준입니다. 서비스 품질은 **폰으로 찍은 사진**으로 재야 합니다. 사진이 모이면 아래 명령 하나로 Top-1/3/5/10과 신뢰도 표시 정확도가 나옵니다.

```powershell
python scripts/eval_photos.py data/photos                       # 기본 (RapidOCR)
python scripts/eval_photos.py data/photos --ocr-engine easyocr  # OCR 엔진 비교
python scripts/eval_photos.py data/photos --no-ocr              # 모양·색만
```

**사진 모으는 방법**

- 파일 이름: `<품목기준코드>_<번호>.jpg` (앞면), `<품목기준코드>_<번호>_back.jpg` (같은 알약의 뒷면). 예: `200808876_1.jpg`, `200808876_1_back.jpg`. 품목기준코드는 약 포장이나 [의약품안전나라](https://nedrug.mfds.go.kr)에서 찾을 수 있고, `GET /v1/items?itemName=...`로도 검색됩니다.
- 또는 폴더에 `labels.csv`(`front,back,item_seq`)를 두면 파일 이름은 자유입니다.
- 종류가 많을수록 좋습니다. 같은 약을 여러 번 찍는 것보다 **다른 약 30~50종**을 각각 앞·뒷면 한 번씩 찍는 편이 낫습니다. 흰 원형 정제, 캡슐, 각인이 작은 약을 꼭 섞으세요.
- 촬영 조건은 아래 "촬영 가이드"대로 하되, 일부는 일부러 흐리게·형광등 아래서·밝은 배경에서 찍어 두면 실패 조건을 알 수 있습니다.
- 결과 CSV(`eval_result.csv`)에 건별로 읽은 각인·추정 모양·색·순위가 남으니 어디서 틀리는지 바로 볼 수 있습니다.

**실제 사진이 모이기 전까지의 기준선**: `scripts/make_synthetic_photos.py`가 참조 사진에서 알약만 오려 어두운 배경·그림자·흐림·색온도 변화를 입힌 **합성 사진**을 만듭니다. 서비스 경로(화이트밸런스 → 배경 분리 분할 → OCR)가 돌아가는지 확인하는 용도이며, 알약 자체는 스튜디오 조명이라 실제 사진의 대체물이 아닙니다.

```powershell
python scripts/make_synthetic_photos.py --items data/eval/scoring_validation_items.txt --n 200
python scripts/eval_photos.py data/synthetic_photos
```

**실제 사진 기준선 (미국 NIH C3PI 소비자 사진 300장, 2026-10)**: 미국 국립의학도서관이 공개한, 일반인이 폰·웹캠으로 찍은 알약 사진입니다([data.lhncbc.nlm.nih.gov/public/Pills](https://data.lhncbc.nlm.nih.gov/public/Pills/)). 미국 약이라 식약처 DB와 대조(Top-1)는 못 하지만 사진마다 각인·모양·색 정답이 있어 서비스 경로를 실제 사진 조건에서 검증할 수 있습니다. `scripts/eval_nih_photos.py`로 재현합니다.

| NIH 소비자 사진 300장 | 결과 | 비고 |
|---|---|---|
| 알약 분할 | 80% | 밝은 배경(흰 종이·책상) 68%, 그 외 89%. 촬영 가이드가 어두운 배경을 권하는 이유 |
| 모양 일치 | 76% | 타원형/장방형은 같은 것으로 침 |
| 주색상 일치 | 37% | 조명·노출 때문에 흰색이 회색으로, 어두운 사진의 주황이 갈색으로 |
| 각인 OCR (RapidOCR) | 정확 10%, 부분 41%, 틀림 7%, 못 읽음 41% | 사진에서 각인이 또렷한 경우(Clear)만 보면 정확 30% |
| 각인 OCR (EasyOCR) | 정확 3%, 부분 20%, 틀림 21%, 못 읽음 56% | 실제 사진에서도 RapidOCR이 낫고, 틀리게 읽는 비율이 3배 |

참조 사진(정확 56%)보다 훨씬 낮습니다. NIH 사진은 알약이 화면의 몇 %밖에 안 되는 먼 거리 사진이 대부분이라 각인 해상도가 부족합니다. **가까이서 찍는 것**이 실제 서비스에서 가장 중요합니다. 부분 판독(41%)도 순위에는 도움이 됩니다(부분 일치 점수).

**AI Hub 실제 카메라 사진 (90종 276쌍, 2026-10)**: AI Hub 경구약제 Validation 사진에서 식약처 DB에 있는 90종을 골라 조건(배경·조명·각도)이 다른 앞·뒷면 쌍을 뽑았습니다(`scripts/aihub_eval_set.py`, zip에서 바로 읽음). 검정·남색 배경에 전구색·주광색 조명, 비스듬한 각도의 **진짜 카메라 사진**이라 폰 사진에 가장 가깝습니다. 약 종류로 반을 나눠 조정용/검증용으로 썼습니다.

| AI Hub 실제 사진 | 조정용 137쌍 | 검증용 139쌍 |
|---|---|---|
| Top-1 / Top-5 / Top-10 | 53% / 68% / 71% | **60% / 71% / 78%** |
| 확신 표시의 정답률 | 85% (34건 중 오답 5건) | 95% (38건 중 오답 2건) |
| 알약 분할 / 모양 일치 / 주색상 일치 | 99% / 86% / 29% | |

처음 돌렸을 때(색 밝기 비중 0.75, 각인 비중 75%)는 180쌍 기준 Top-1 48%였고, 색 판정의 밝기 비중을 낮추고 각인 비중을 80%로 올린 뒤 같은 180쌍에서 50%, 276쌍 전체에서 57%입니다. 각인을 양면 모두 정확히 읽은 경우가 49%이고 그중 78%가 1위입니다. 나머지는 각인을 못 읽거나 틀리게 읽은 경우라 OCR 자체 또는 Claude 비전·임베딩이 필요합니다.

**임베딩 모델을 켠 뒤 (AI Hub 100종으로 Colab 학습, 2026-10)**: 같은 276쌍에서 Top-1 **95%**, Top-5 97%, Top-10 99%. 임베딩 없이 맞히던 131쌍은 그대로 맞히고 132쌍을 새로 맞혔습니다(임베딩 때문에 틀린 경우 0). 단, 이 90종은 학습 때 다른 사진으로 본 약이라 **"학습 사진이 있는 약"에 대한 수치**입니다. AI Hub에 사진이 없는 나머지 약은 임베딩 없이 잰 수치(검증용 60%)에 가깝다고 봐야 합니다. 학습 사진이 없는 약에 대한 효과는 노트북의 `HOLDOUT_FRAC`로 따로 잴 수 있습니다.

**AI Hub 전체로 다시 학습한 뒤 (학교 GPU 서버, 2026-11-18, `scripts/aihub_train_full.sh`)**: 25,787종(그중 실제 촬영 사진이 있는 약 4,120종), 20에폭, 학습 중 검증 Top-1 98.0%. 같은 276쌍 결과:

| 모델 + 색인 | Top-1 | Top-5 | 확신했는데 오답 |
|---|---|---|---|
| 이전 모델 + 이전 색인 (5.7만 장) | 95% | 98% | 1~2건 |
| 새 모델 + 이전과 같은 구성 색인 | 98% | 99.6% | 2건 |
| 새 모델 + 전체 색인 (29.9만 장) | 93.5% | 99.3% | 2건 |
| 새 모델 + 품목당 20장 색인 (12.6만 장) | 94.6% | 99.6% | 2건 |
| 위 + 임베딩 비중 0.45 → 0.55 | 96.4% | 100% | 2건 |
| 위, 평가 라벨 2건 수정 후 (아래) | 97.1% | 100% | 0건 |
| 위 + 각인 포함 점수 | 97.8% | 100% | 0건 |
| 위 + 질의 회전 평균 | 98.6% | 100% | 0건 |
| **위 + 한글 각인 판독 (기본값)** | **98.9%** | **100%** | **0건** (확신 표시 82%) |

이전 색인에서는 평가 약만 촬영 사진이 있고 겉모습이 같은 복제약은 식약처 사진 2장뿐이라 정답 쪽이 유리했습니다. 전체 색인에서는 복제약도 촬영 사진을 가져서 실제 사용에 가까운 수치는 **94% 안팎**입니다. 오답을 보면 임베딩은 정답을 가리키는데 잘못 읽은 각인이 다른 약과 우연히 맞아 뒤집힌 경우가 많아서, 임베딩 비중을 0.55로 올렸습니다(`pillid/pipeline.py`의 `BASE_WEIGHTS`, 각인·색·모양 비율 80:10:10은 그대로). 품목 기준 반반으로 나눠 조정용에서 고르고 검증용에서 확인했습니다(검증용 Top-1 93.4% → 95.6%). 신뢰도 기준은 바꾸지 않았습니다(확신 표시 78%, 그중 정답 99%). 느슨하게 하면 이 사진들에서는 확신 표시가 늘지만 실제 폰 사진으로 확인한 뒤에 정합니다. 남은 오답은 대부분 성분·용량이 같은 다른 회사 약이나 같은 약의 다른 용량이라, 사진에서 가를 단서는 각인입니다. 확신 오답이던 2건(빔스크정 150mg → 200mg)은 사진을 보니 실제로 200mg(파란색, `L20`)이었습니다. AI Hub의 150mg(K-040340) 폴더에 200mg 사진이 섞여 있던 것이라 `eval_photos4/labels.csv`의 라벨을 고쳤습니다(학습 데이터에도 이렇게 섞인 사진이 있을 수 있습니다). 또 각인을 일부만 읽은 경우(`MM PRG 75` → `PRG 75`) 한 글자 틀린 다른 약(`PRC 75`)이 더 높은 점수를 받아서, 읽은 글자(4글자 이상)가 정답 각인 안에 그대로 있으면 0.4 + 0.6 × 읽은 비율을 주도록 했습니다(`pillid/ocr.py`의 `CONTAIN_BASE`). 참조 사진(임베딩 없음)에서도 조정용 Top-1 111 → 115, 검증용 110 → 110 / 200으로 나빠지지 않았습니다. 질의 크롭은 90°씩 돌려 4번 임베딩하고 평균합니다(`pillid/pipeline.py`의 `EMBED_ROTATIONS`, 확신 표시 178 → 186, 사진 한 장 임베딩 33ms → 113ms). 한글 각인 캡슐(덴티골드, 투와이스P)은 영문 OCR이 `8`, `VLOO`로 읽어 틀렸는데, 한국어 글자 인식 모델을 더해 해결했습니다(아래 "한글 각인"). 기본 색인(`data/index.npz`)은 `python scripts/cap_index.py models/index_full.npz --per-class 20 --out data/index.npz`로 만든 것(품목당 식약처 사진 2장 + 촬영 사진 18장)이고, 확신 목록은 `write_trained_items.py --index models/index_full.npz`로 만들었습니다.

합성 사진 200품목(점수 조정 검증용과 같은 품목) 결과: Top-1 60%, Top-5 68%, Top-10 73%, 확신 표시 34%(그중 정답 99%). 같은 품목의 참조 사진(Top-1 68%)보다 낮은 이유는 모양 일치 90% → 70%, 주색상 일치 90% → 52%로 떨어지기 때문입니다. 색은 합성 때 넣은 색온도 변화를 어두운 배경에서는 화이트밸런스가 되돌리지 못해서이고, 모양은 그림자·흐림 때문입니다.

### AI Hub에 사진이 없는 약

임베딩은 AI Hub 촬영 사진이 있는 4,120종에서만 잘 맞습니다. 나머지(DB의 대부분)는 색인에 식약처 스튜디오 사진 2장뿐이라 폰 사진과의 유사도가 낮게 나와, 촬영 사진이 있는 비슷한 약에 밀립니다. 평가 약의 색인을 식약처 사진 2장만 남겨 보면 Top-1이 98.9% → **58.0%**로, 임베딩 없이 각인·색·모양만 쓸 때(65.6%)보다도 낮았습니다. 그래서 식약처 사진만 있는 품목의 임베딩 점수에 0.15를 더합니다(`pillid/pipeline.py`의 `REFERENCE_ONLY_BONUS`). 이 경우 58.0% → **80.1%**, 촬영 사진이 있는 약은 98.9% 그대로입니다(0.20부터 떨어짐). 평가 약은 학습 때 촬영 사진을 본 약이라 실제보다 낙관적인 추정입니다.

재학습도 해 봤지만 **채택하지 않았습니다**(`scripts/aihub_train_v2.sh`). 식약처 크롭을 폰 사진처럼 바꾸는 증강(`pillid/data.py`의 `PhotoLike`: 조명 색·그림자·반사광·흐림·저해상도·잡음·JPEG)을 넣고, `scripts/holdout_items_v2.txt`의 200종(평가 사진 품목 45종 포함)은 촬영 사진을 학습에서 빼서 '처음 보는 약'을 정직하게 쟀습니다. 증강 효과만 보려고 같은 홀드아웃으로 증강 없이도 학습했습니다(`TAG=v2noaug PHOTO_AUG=0`, 지금 모델과 같은 방식). 평가 사진 중 홀드아웃 45종 135쌍, 색인에는 식약처 사진만:

| 처음 보는 약 135쌍 | 증강 없음 | 증강 (v2) |
|---|---|---|
| 임베딩만 Top-1 | 7.4% | 15.6% |
| 사진 분석 Top-1 (보정 0.15) | 63.0% | 68.1% |
| **+ Claude 보조 (서비스 경로)** | **89.6% / 87.4%** (두 번) | **88.1% / 85.9%** (두 번) |
| 학습한 약 141쌍 Top-1 | 99.3% | 99.3% |

증강은 사진 분석만 볼 때 +5%p였지만 Claude 보조를 거치면 차이가 없어졌고(Claude 판정이 실행마다 2~3%p 흔들림), v2는 홀드아웃 200종의 촬영 사진을 학습하지 않은 모델이라 지금 모델을 그대로 씁니다. 지금 모델의 '처음 보는 약' 정확도는 증강 없음 모델로 추정하면 사진 분석 63%, Claude 보조 88% 안팎입니다(위 80%·92%는 촬영 사진을 학습한 약이라 낙관적).

### Claude 비전 보조 (서비스 경로 전체)

`scripts/eval_identify_api.py`는 서버의 `/v1/identify`를 같은 프로세스에서 불러(서버를 띄우지 않음) 서비스와 똑같은 경로로 잽니다: 사진 분석이 확신하면 그대로 내고, 확신하지 못하면 Claude가 사진을 보고(관찰) 필요하면 상위 후보와 참조 사진을 비교(재비교)합니다.

| AI Hub 실제 사진 276쌍 | Top-1 | 확신 표시 | 확신 오답 | Claude 호출 |
|---|---|---|---|---|
| 사진 분석만 | 270 (97.8%) | 221 (80%) | 0 | 0 |
| + Claude 보조 (각인 규칙 전) | 275 (99.6%) | 269 (97%) | 0 | 관찰 55장(20%), 재비교 35 |
| **+ Claude 보조 (지금)** | **272 (98.6%)** | **264 (96%)** | **0** | 관찰 60장(22%), 재비교 38 |

(사진 분석만에서 4장은 `NOT_FOUND`(알약을 하나로 찾지 못함)였고 Claude 보조에서는 모두 풀렸습니다. `eval_photos.py`로 잰 98.9%와 다른 것은 이 4장 때문입니다. 각인 규칙 전후 차이는 아래 '각인이 정확히 맞는 다른 약' 참고.)

재비교에서 Claude가 1~5위를 모두 '다른 약'으로 판정하면, 판정받지 않은 6위가 1위로 올라오던 문제를 고쳤습니다(`pillid/vision.py`의 `apply_rerank`: 이때는 순서를 그대로 두고 저신뢰). 같은 평가에서 7건이 이렇게 틀렸습니다(정답인 아스피린 `AA`를 '다른 약'으로 보자 각인 `CTS`인 약이 1위).

평가 약의 색인을 식약처 사진 2장만 남긴 경우(AI Hub에 사진이 없는 약과 같은 처지):

| 식약처 사진만 있는 약 (276쌍) | Top-1 | Claude 호출 |
|---|---|---|
| 사진 분석만 (점수 보정 포함) | 219 (79.3%) | 0 |
| **+ Claude 보조** | **253 (91.7%)** | 관찰 149장, 재비교 116 |

다만 이 경우 1위로 확신한 오답이 6건 있었고, 모두 1위가 **촬영 사진이 있는(학습한) 다른 약**이었습니다. 실제 서비스에서도 확신으로 나갈 수 있는 경우입니다. 6건 중 5건은 성분·함량이 같은 다른 회사 제품(리세드론산 150mg, 로수바스타틴 5mg, 프레가발린 75mg)이고, 1건(유로케어정 → 비타민D 제제)은 다른 약입니다. OCR도 각인을 틀리게 읽은 경우라(`A150` ← `R150`) 근거끼리 엇갈리지 않아 확신 규칙으로 거르기 어렵습니다(식약처 사진만 있는 약이 근소하게 뒤따르면 확신하지 않는 규칙은 학습한 약의 확신 표시를 12% 줄여야 했습니다). 근본 해결은 v2 재학습입니다. 그동안 1위와 성분·함량·모양·색이 같은 다른 제품 수를 `sameIngredientCount`로 내고(성분명은 품목명 끝 괄호에서 읽으며 25,439품목 중 11,186품목에 있음), 백엔드는 0보다 크면 "같은 성분과 함량으로 모양과 색까지 같은 다른 회사 약도 있어서, 제품 이름은 다를 수 있습니다"라고 읽어 줍니다. 학습한 약 기준 확신 답변의 31%가 이런 경우입니다.

**각인이 정확히 맞는 다른 약이 있으면 확신하지 않음** (`pillid/pipeline.py`의 `imprint_rival`, `RIVAL_MIN_LEN`): 읽은 각인(앞면 3글자 이상)이 학습하지 않은 같은 모양의 약과 정확히 맞는데(각인 점수 0.9 이상) 1위 약의 각인은 맞지 않으면, 재비교 뒤의 1위까지 다시 확인해 저신뢰로 냅니다. 처음 보는 약 135쌍에서 확신 오답 3건(각인 `GGZ`를 정확히 읽었는데 학습한 `ggr` 약, 프레가발린 `PG 75` → 다른 회사 `PG75`)이 모두 이 경우였고 모두 걸러집니다. 또 읽은 각인이 정확히 맞는 약은 순위가 낮아도 Claude 재비교에 3개까지 더 넣습니다(`imprint_candidates`, `IMPRINT_EXTRA`). 처음 보는 약 오답 17건 중 5건이 각인을 정확히 읽었는데 정답이 2~8위라 재비교(1~5위)에서 정답을 보지 못한 경우였습니다.

| 서비스 경로 + Claude | 처음 보는 약 135쌍 (증강 없음 모델) | 학습한 약 276쌍 (지금 모델) |
|---|---|---|
| 두 규칙 전 | Top-1 118 (87.4%), 확신 오답 3 | Top-1 275 (99.6%), 확신 269, 오답 0 |
| **두 규칙 후** | **Top-1 123 (91.1%), 확신 오답 0** | **Top-1 272 (98.6%), 확신 264, 오답 0** |

학습한 약에서 줄어든 3장은 모두 같은 약(아스피린 장용정, 각인 `AA`)입니다. OCR이 `AST`로 읽어 각인이 정확히 맞는 다른 약(`AST`)이 재비교에 들어갔고, Claude가 정답을 '다른 약'으로 봐서 7위로 밀렸습니다. 확신 표시가 아니라 '확실하지 않음'으로 나가는 오답입니다. DB 대부분이 AI Hub 사진이 없는 약이라 이 교환을 택했습니다.

이어서 **1위가 학습한 약이고 임베딩 0.6 이상, 2위와 0.05 이상 벌어져 있으면 추가 후보를 넣지 않게** 했습니다(`EXTRA_SKIP_MARGIN`). 사진 분석만으로 기록해 보니 아스피린 3장은 2위와 0.06~0.10, 추가 후보로 정답을 찾은 처음 보는 약 사진은 0.01~0.02 차이였습니다. Claude 보조 평가는 도중에 API 크레딧이 떨어져 앞 118장만 됐고, 그 안에서 아스피린 3장은 다시 1위(각인 `AST`인 학습 안 된 약이 있어 '확실하지 않음')였습니다. **처음 보는 약 135쌍은 아직 재지 못했습니다**(같은 시점에 기본 모델을 Opus 5.5로 바꿔서, 다음 평가는 두 변경을 함께 잽니다). `eval_identify_api.py`는 이제 끝에 Claude 토큰 수와 요금을 출력합니다.

각인 판독 방식도 실제 폰 사진(NIH 239장)으로 비교했지만 원본 해상도 크롭, 크롭 크기 480/720/960, 대비 강화 끄기·음각 반전·선명화 모두 정확 판독 23~26장으로 차이가 없었습니다. 폰 사진의 각인은 흐림과 조명이 한계이고, 어려운 경우는 Claude 보조가 맡습니다. 원본 해상도 크롭(`region_in_original`)은 해가 없어 남겨 두었습니다(긴 변 1600px 넘는 사진만 해당).

### 한글 각인

식약처 DB 25,439품목 중 825품목(3.2%, 주로 연질캡슐·일반의약품)은 각인이 한글입니다("덴티골드", "그린노즈S"). 패키지에 든 RapidOCR 모델은 한글을 못 읽어서 PP-OCRv5 한국어 인식 모델을 함께 씁니다(`pillid/ocr.py`의 `rec="hybrid"`). 글자 위치는 한 번만 찾고, 찾은 글자를 두 모델로 읽어 평소에는 영문 모델 판독을, 한국어 모델이 한글을 신뢰도 0.8 이상으로 읽었을 때는 그 판독을 씁니다. 한국어 모델만 쓰면 영문·숫자 각인을 영문 모델보다 못 읽어서 섞어 씁니다.

| 글자 인식 | 식약처 참조 사진 400품목 Top-1 | 확신 오답 | AI Hub 실제 사진 276쌍 Top-1 | 사진 한 장 각인 판독 |
|---|---|---|---|---|
| 영문 모델 | 263 (66%) | 3 | 272 | 1.5초 |
| 한국어 모델 | 251 (63%) | 5 | 273 | |
| **함께 (기본값)** | **270 (68%)** | **3** | **273** | 1.8초 |

모델 파일은 Git에 올리지 않습니다(라이선스 Apache-2.0, 출처 ModelScope `RapidAI/RapidOCR`). 새 PC에서는 아래 두 파일을 `models/ocr/`에 받으세요. 없으면 예전처럼 영문 모델만 씁니다(`PILL_OCR_REC=en|ko|hybrid`로 고를 수 있음).

```bash
mkdir -p models/ocr && cd models/ocr
curl -LO https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/rec/korean_PP-OCRv5_rec_mobile.onnx
curl -LO https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/paddle/PP-OCRv5/rec/korean_PP-OCRv5_rec_mobile/ppocrv5_korean_dict.txt
```

받은 파일 크기: `korean_PP-OCRv5_rec_mobile.onnx` 13,488,748바이트, `ppocrv5_korean_dict.txt` 47,451바이트.

### 약봉투 인식 평가

실제 약봉투 사진이 모이기 전에는 가상 약봉투로 잽니다. 양식 3가지(표형 복약안내문, 시간 칸 체크형 봉투, 번호 목록형)에 식약처 실제 품목명을 넣고 폰 사진처럼 기울기·그림자·흐림을 더합니다. 환자 정보는 가짜입니다.

```bash
python scripts/make_synthetic_bags.py --out data/eval/bags_synth --n 12   # 사진 + truth.json
python scripts/eval_bags.py data/eval/bags_synth                          # CLOVA 1건 + Claude 1~2번 / 장
```

실제 약봉투도 같은 폴더 구조(사진 + `truth.json`)로 두면 그대로 잴 수 있습니다. 가장 중요한 수치는 "조용한 오답"(값이 틀렸는데 '확인 필요'가 없음)입니다.

가상 약봉투 12장, 약 38개 (2026-11): 약 찾기 38/38(없는 약 지어냄 0), 품목 일치 38/38, 하루 횟수·복용 시점·단위 38/38, 1회량 36/38(1 비움, 1 틀림), 일수 37/38(1 비움, 확인 필요 표시), 모든 항목이 맞은 약 32/38(84%). 기운 봉투에서 오른쪽 용량 칸이 한 줄 밀려 다른 약의 1회량이 들어간 조용한 오답 1건이 있어, 한 약의 1회량·횟수·일수를 비우면 같은 봉투 다른 약의 같은 칸도 확인을 요구하도록 했습니다(`pillid/bag.py`의 `flag_shared_columns`). 고친 뒤 조용한 오답 0건. 한 장에 15~60초 걸리고 12장 중 8장이 재확인을 거쳤습니다.

원인은 줄 재구성이었습니다. 사진이 3°만 기울어도 오른쪽 용량 칸이 한 줄 아래 약과 묶였고, 9° 기운 사진은 표가 칸마다 흩어져 교부번호가 이름표와 떨어지는 바람에 가려지지도 않았습니다. CLOVA 글자 상자의 기울기 중앙값으로 사진을 바로 세운 좌표에서 줄을 나누도록 고쳤습니다(`assemble_lines`, `page_skew`). 또 Claude가 인쇄된 제품명을 성분명처럼 고쳐("레비티라세브이정500mg" → "레비티라세탐정500mg") 다른 회사 제품이 근소하게 1위로 '일치' 처리된 일이 있어, 인쇄된 이름으로도 찾아 더 높은 쪽을 쓰고, 이름이 정확히 같지 않은데 1·2위 차이가 0.05보다 작으면 사용자가 고르게 했습니다(`MATCHED_MARGIN`).

| 가상 약봉투 12장, 약 38개 | 고치기 전 | 고친 뒤 |
|---|---|---|
| 모든 항목이 맞은 약 | 32 (84%) | **35 (92%)** (나머지 3개는 식사 관계가 적혀 있지 않아 비운 것으로 정상) |
| 1회량 / 일수 | 36 / 37 | **38 / 38** |
| 품목 일치 | 38 | **38** (잘못된 '일치' 0) |
| 조용한 오답 | 1 | **0** |
| 재확인(Claude 2번째 호출) | 8장 | 5장 |

**어려운 가상 약봉투 24장** (`make_synthetic_bags.py --hard`: 약 3~7개, 처방전형 표 추가, 사진 상태 L3 = 구겨짐·반사광·손가락 가림·어두운 잡음)에서 추가로 찾은 문제와 고친 점:

- 기운 사진에서 교부번호가 이름표와 다른 줄로 떨어져 **가려지지 않고 Claude로 갔습니다.** 이제 10자리 넘는 숫자는 위치와 관계없이 가립니다(`_LONG_NUMBER`).
- "삼시타플러스정50/500mg"이 함량 가산점 때문에 "시타플러스정50/500mg"과 같은 1.0점이 되어 잘못 '일치'했습니다. 유사도가 아니라 이름이 글자 그대로 같을 때만 바로 정합니다.
- 짧은 글자 상자("1회", "1/2캡슐")의 기울기가 부정확해 원근이 있는 사진에서 줄이 어긋났습니다. 상자 넓이로 가중한 중앙값을 씁니다. 이름과 1회량이 같은 줄에 오는 비율: 보정 없음 56/84, 투영 방식 78/84, 넓이 가중 80/84.
- 재확인 7번 중 6번이 Claude가 낯선 제품명을 의심한 경우였는데, 그 이름은 식약처 품목명과 글자 그대로 같았습니다. 이런 경우(근거 검증 통과, 줄 OCR 신뢰도 0.6 이상)는 재확인하지 않습니다(`RECHECK_SKIP_MIN_OCR`). 재확인 이유는 결과의 `recheckReasons`에 남습니다.

| 가상 약봉투 | 모든 항목이 맞은 약 | 조용한 오답 | 재확인 | 봉투 한 장 평균 |
|---|---|---|---|---|
| 기본 12장 (약 38개) | **38/38 (100%)** | 0 | 1/12 | 17초 (전 25초) |
| 어려운 24장 (약 119개) | **116/119 (97%)** | 0* | 5/24 | 28초 (전 39초) |

\* 남은 3개: 후보 목록에서 고르게 한 것 2개(정답이 후보에 있음), 생성기 정답표 오류 1개(스팬슐 제형을 정제로 적음). 하루 횟수·복용 시점·식사 관계·일수는 모두 119/119입니다.

가상 봉투는 실제보다 깨끗하고 양식이 단순해 상한에 가까운 수치입니다.

## 알려진 한계

- 폰 사진 분할(`segment_pills`)은 배경을 완만한 곡면으로 추정해 조명 기울기를 걷어내고, 배경과 색조가 같으면서 어두운 픽셀(그림자)은 배경으로 봅니다. 미국 NIH 소비자 사진으로 확인했지만(아래), 어두운 알약이 밝은 배경에 있으면 놓칠 수 있습니다.
- 폰 사진에서는 색이 조명과 노출에 크게 흔들립니다(전구색 조명에서 흰 알약이 노랑·분홍으로, 어두운 사진의 주황이 갈색으로). 실제 사진 주색상 일치율은 AI Hub 촬영 장비 사진 약 30%, NIH 폰 사진 약 40%입니다. 색은 점수 비중이 작고(12.5%), 신뢰도 판정은 각인에 달려 있어 치명적이지는 않지만 색만으로 거르는 기능은 넣지 마세요.
- 타원형과 장방형은 제조사마다 기준이 달라 윤곽만으로는 87% 정도까지만 구별됩니다. 남은 모양 오류의 대부분입니다.
- 색 기준표는 식약처 스튜디오 사진의 색입니다. 폰 사진은 조명과 노출이 달라 색 일치율이 더 낮을 수 있습니다.
- 화이트밸런스는 배경이 밝을 때만(밝기 80 이상) 적용합니다. 어두운 배경은 색 기운이 조금만 있어도 흰 알약이 미색으로 틀어집니다. 실제 폰 사진으로는 검증하지 못했습니다.
- 한두 글자짜리 각인, 음각, 로고는 OCR이 자주 틀립니다. Claude 비전 관찰이나 임베딩으로 보완해야 하는 부분입니다.
- 임베딩이 있을 때의 가중치(임베딩 45%)는 아직 조정하지 않은 초기값입니다. 폰으로 직접 찍은 사진에서의 성능은 아직 재지 못했습니다(`eval_photos.py`로 잴 수 있습니다).
- 분할선(LINE_FRONT/BACK)과 크기(LENG_LONG 등)는 아직 점수에 쓰지 않습니다. 크기는 기준 물체(동전 등)가 있어야 추정할 수 있습니다.
- AI Hub JSON 필드명은 배포 버전마다 다를 수 있습니다. `convert_aihub.py --limit`로 먼저 확인하세요.

## 데이터 출처

- **AI 허브 「경구약제 이미지 데이터」**: 임베딩 모델 학습과 실제 사진 평가에 썼고, `docs/figures`의 그림 일부(`07_처리예시.png`의 ① 실제 촬영 사진 등)에 사진이 들어 있습니다. 원본 데이터는 이 저장소에 없으며, AI 허브에서 신청해 받아야 합니다.
  > 이 프로젝트는 과학기술정보통신부의 재원으로 한국지능정보사회진흥원의 지원을 받아 구축된 "경구약제 이미지 데이터"를 활용하여 수행되었습니다. 이 프로젝트에 활용된 데이터는 AI 허브([aihub.or.kr](https://aihub.or.kr))에서 내려받을 수 있습니다.
- **식품의약품안전처 「의약품 낱알식별 정보」, 「의약품개요정보(e약은요)」** (공공데이터포털 [data.go.kr](https://www.data.go.kr)): 알약 참조 사진·모양·색·각인 DB와 약 설명에 썼습니다. 그림의 ③ 식약처 참조 사진도 여기서 나왔습니다.
- **미국 국립의학도서관(NLM) C3PI 알약 사진** ([data.lhncbc.nlm.nih.gov/public/Pills](https://data.lhncbc.nlm.nih.gov/public/Pills/)): 폰 사진 조건의 평가에만 썼습니다.

## 안전 고지

이 도구는 **식별 보조용**입니다. Top-1 결과를 단정하지 말고, 후보 이미지와 직접 비교하게 하세요. 신뢰도 경고가 뜨거나 확실하지 않으면 약사에게 확인하도록 안내해야 합니다.
