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
  vision.py        Claude Fable 5.1 비전 보조 (관찰, 재순위)
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

## Claude Fable 5.1 비전 보조

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
| `PILL_AI_MODEL` | 비우면 `anthropic.claude-fable-5-1`(Bedrock) 또는 `claude-fable-5-1` |
| `PILL_AI_FALLBACK_MODEL` | 안전 분류기가 거절했을 때 1회 재시도할 모델. 비우면 재시도 없음 |
| `PILL_AI_TIMEOUT` | 요청 제한 시간(초), 기본 40 |

접근 권한을 받으면 먼저 실제 호출을 확인합니다(요금 발생).

```bash
python scripts/check_claude.py
```

데모 캡슐 사진을 Claude에 보내 관찰 결과를 출력합니다. 실패하면 원인 종류가 나옵니다: `BAD_REQUEST`(모델 ID·스키마·권한), `UNAVAILABLE`(네트워크·한도), `REFUSED`(안전 분류기).

### 알아 둘 점
- **아직 실제 API로는 호출해 보지 못했습니다.** 요청 구성, 재시도 규칙, 응답 처리는 가짜 응답 테스트로만 확인했습니다. 모델 ID나 출력 스키마가 실제 환경에서 거절되면 `check_claude.py`에서 `BAD_REQUEST`로 드러납니다.
- Fable 5.1 호출 규칙(설계서 §3.3)을 따릅니다: `thinking`·`temperature`를 보내지 않고, 도구 대신 구조화 출력을 쓰며, 깊이는 effort로만 조절합니다.
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
| 임베딩 유사도 | 0.45 | - | 품목별 참조 이미지 중 최고 코사인 유사도 |
| 각인 | 0.44 | 0.80 | 편집거리 + 부분 일치. 조각 순서와 앞/뒤 배정은 무시. 영문 OCR이 못 읽는 한글 조각은 비교에서 제외 |
| 색 | 0.055 | 0.10 | 비슷한 색(하양/회색, 파랑/남색 등)은 부분 점수. 실제 사진에서 자주 틀려 비중이 작다 |
| 모양 | 0.055 | 0.10 | 헷갈리는 쌍(타원형/장방형 등)은 부분 점수 |

없는 단서는 빼고 나머지를 다시 정규화합니다. 가중치는 `pillid/pipeline.py`의 `BASE_WEIGHTS`, 각인 점수 상수는 `pillid/ocr.py`에 있습니다.

**신뢰도**: 임베딩이 없을 때는 1위 점수 0.85 이상, 1·2위 차 0.08 이상, 1위의 각인 점수 0.9 이상을 모두 만족할 때만 확신으로 봅니다. 임베딩이 있을 때는 점수 0.6 이상, 차 0.03 이상이고 **각인 점수 0.9 이상 또는 임베딩 점수 0.7 이상**이면 확신입니다(실제 사진 276쌍: 확신 표시 80%, 그중 정답 99%). 아니면 `lowConfidence = true`입니다.

**학습한 약만 확신**: 추론 서버는 위 조건을 만족해도 1위가 `models/trained_items.txt`(임베딩을 학습한 약 목록)에 없으면 `lowConfidence = true`로 냅니다. 학습하지 않은 약은 겉모습이 비슷한 다른 약을 높은 점수로 확신하는 일이 있었기 때문입니다(임베딩 끔 + Claude 켬, 90종: 확신 오답 6건 → 이 규칙 적용 시 1건). 임베딩이 꺼져 있으면 어떤 약도 확신하지 않습니다. 모델을 새로 학습하면 `python scripts/write_trained_items.py --crops <학습한 크롭 폴더>`로 목록도 다시 만드세요.

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

합성 사진 200품목(점수 조정 검증용과 같은 품목) 결과: Top-1 60%, Top-5 68%, Top-10 73%, 확신 표시 34%(그중 정답 99%). 같은 품목의 참조 사진(Top-1 68%)보다 낮은 이유는 모양 일치 90% → 70%, 주색상 일치 90% → 52%로 떨어지기 때문입니다. 색은 합성 때 넣은 색온도 변화를 어두운 배경에서는 화이트밸런스가 되돌리지 못해서이고, 모양은 그림자·흐림 때문입니다.

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
