# 메디메이트 (Medimate)

시각장애인과 고령층을 위한 복약 도우미 안드로이드 앱입니다.
약봉투·처방전을 찍으면 약 이름과 복용법을 읽어 주고, 알약을 식별하고, 복약 시간을 알려 줍니다.

> 알파프로젝트 Ⅰ · 소프트웨어학과 정영미팀

이 문서는 앱 전체의 설계 문서입니다. 구현 범위는 네 덩어리입니다.

| | 기능 | 설계 |
|---|---|---|
| A | **약 인식** — 촬영 → OCR → GPT 정리 → 식약처 공공데이터 → 낭독 | 4 ~ 6장 |
| B | **로그인** — 전화번호, 구글, 카카오 | 7장 |
| C | **맞춤형 영양제 추천** | 8장 |
| D | **DB 연동** — Firebase Firestore | 9장 |

**개발 방식: 큰 틀 먼저.** 세부 기능을 하나씩 완성하지 않고, A → B → C → D 순서로 **각 기능이 처음부터 끝까지 한 번 돌아가는 뼈대**를 먼저 만든 뒤, 2차로 세부를 다듬습니다. 순서와 범위는 10장이 기준입니다.

---

## 1. 전체 흐름

```
[카메라 / 갤러리]
      │  ScanActivity
      ▼
[자르기 (약봉투 영역만)]
      │  Bitmap
      ▼
[OCR: ML Kit 한국어 (CLOVA는 보류)]  ocr/MlKitOcrProcessor
      │  줄 단위로 재구성한 원문 텍스트
      ▼
[GPT 정리: 약 이름·용법 구조화]       gpt/GptProcessor
      │  drugs[].name, dosage, storage
      ▼
[식약처 e약은요 조회]                 publicdata/DrugInfoRepository
      │  효능·주의사항·상호작용·보관법 (공식 데이터)
      ▼
[약 목록 → 상세 → TTS 낭독]           DrugListActivity / DrugDetailActivity
```

알약 식별(사진 → 모양·색·각인 매칭)은 **낱알식별 데이터를 로컬 DB에 캐시**해 두고 앱 안에서 매칭합니다. 외부 API를 매번 부르지 않습니다.

---

## 2. 프로젝트 현황

- 새로 만든 프로젝트입니다. 패키지는 `com.medimate`, 언어는 **Java**입니다.
- `compileSdk 37` / `targetSdk 37` / `minSdk 26`, AGP 9.x, Gradle Kotlin DSL, 버전 카탈로그(`gradle/libs.versions.toml`)
- 진행 상황은 10장의 단계 표에서 관리합니다. 단계를 끝낼 때마다 표의 상태를 갱신합니다.

패키지 구조 (전체)

```
com.medimate
├── ui/            // 화면 (Activity, Adapter, ViewModel)
├── ocr/           // OCR (ML Kit, 나중에 CLOVA)
├── gpt/           // OpenAI 호출, OCR 원문 구조화
├── publicdata/    // 식약처 e약은요, 낱알식별, 건강기능식품
├── tts/           // 음성 낭독
├── auth/          // 로그인 (전화번호, 구글, 카카오)
├── recommend/     // 영양제 추천 규칙
├── data/          // 사용자 데이터 저장소 (Firestore), 공공데이터 캐시 (Room)
└── model/         // Drug, UserProfile, Recommendation 등 공용 모델
```

### 참고 구현 (이전 버전)

`D:\Users\Medimate\Medimate` 에 이전 버전(패키지 `com.example.medimate`, ML Kit + GPT 방식)이 있습니다.
**복사하지 않고 참고만** 합니다. 새 설계(CLOVA, 공공데이터)와 다른 부분이 많습니다.

| 참고할 파일 (D:\Users\Medimate\Medimate\app\src\main\java\com\example\medimate\) | 참고할 내용 |
|---|---|
| `DrugActivity/MedimateActivity.java` | 카메라 권한, 촬영·갤러리 런처, UCrop 자르기 흐름 |
| `DrugActivity/MainViewModel.java` | OCR → GPT를 `LiveData`로 잇는 구조, 실패 시 TTS 안내 |
| `OCR/OcrProcessor.java` | 좌표(Y→X) 기준 텍스트 정렬 — CLOVA 결과 정렬에 재사용 |
| `GPT/GptProcessor.java`, `GPT/models/*` | OpenAI 호출, 코드블록 제거 후 JSON 파싱, `Drug` 모델 (화면용/낭독용 분리) |
| `OCR/DrugDetailDialog.java`, `TTS/TTSManager.java` | 항목별 낭독 버튼, TTS 초기화·해제 |
| `recommendation/api/RetrofitClient.java` | 공공데이터 Retrofit 클라이언트 패턴 |
| `Login/LoginActivity.java`, `Login/SplashActivity.java` | 로그인 화면 구성, 로그인 여부에 따른 첫 화면 분기 |
| `Login/PhoneLoginActivity.java`, `Login/PhoneRegisterActivity.java` | Firebase 전화번호 인증 흐름 |
| `Login/kakaoApplication.java` | 카카오 SDK 초기화 (키를 코드·매니페스트에 직접 적은 부분은 따라 하지 않음) |
| `Login/RegisterUserInfoActivity.java`, `Login/MyInfoActivity.java`, `Login/WithdrawActivity.java` | 첫 로그인 정보 입력, 내 정보, 탈퇴 |
| `recommendation/HealthInputActivity.java`, `recommendation/RecommendActivity.java` | 건강 정보 입력 화면, 추천 결과 화면 |
| `recommendation/api/FoodSafetyApi.java` | 식약처 건강기능식품 API 호출 |

가져오지 말아야 할 것: GPT 프롬프트의 "약 설명·주의사항을 GPT 내부 지식으로 채우라"는 부분 (5-5 참고).

---

## 3. 개발 환경 세팅

### 3-1. 요구 사항

- Android Studio (최신 안정 버전), JDK 17 이상
- 실기기 권장 — 카메라, TTS, TalkBack은 에뮬레이터로 검증되지 않습니다.
- (보류) 네이버 클라우드 플랫폼(NCP) 계정 — CLOVA OCR로 전환할 때 필요. 지금은 없어도 됩니다.

### 3-2. (보류) CLOVA OCR 발급 절차

> 지금은 진행하지 않습니다. 가입 전에 학교에 **지출증빙 방식**(개인 카드 전표 인정 여부, 법인카드, 세금계산서)을 먼저 확인합니다.

1. NCP 콘솔 → **CLOVA OCR** → **도메인 생성** → 도메인 종류 **General**, 언어 **한국어**
2. 생성한 도메인 → **Text OCR** → **API Gateway 연동**
3. **Secret Key 생성** → **자동 연동** → **Invoke URL** 확인
4. Secret Key와 Invoke URL을 `secrets.properties`에 넣습니다 (3-3). Invoke URL도 다른 사람이 알면 우리 과금으로 호출할 수 있으므로 Secret과 똑같이 취급합니다.

- 약국마다 약봉투 서식이 달라 **Template OCR은 맞지 않습니다.** General OCR을 씁니다.
- 약봉투는 표 형태가 많습니다. 도메인 설정에서 **표 추출(Table)** 옵션을 켤 수 있으면 켜고, 5주차 실측에서 켠 것과 끈 것을 비교합니다.
- 과금 방식과 무료 제공량은 NCP 요금 페이지에서 확인하고, 콘솔에서 **예산 알림**을 걸어 둡니다.

### 3-3. API 키 설정

`local.properties`는 Android Studio가 다시 생성하면서 내용을 지울 수 있습니다.
키는 프로젝트 루트의 **`secrets.properties`** 에 따로 둡니다.

```properties
# secrets.properties  (프로젝트 루트, 커밋 금지)

# 네이버 CLOVA OCR (보류 — 비워 둬도 됩니다)
CLOVA_OCR_INVOKE_URL=https://xxxxxxxx.apigw.ntruss.com/custom/v1/00000/xxxxxxxx/general
CLOVA_OCR_SECRET=...

# OpenAI (GPT 정리)
OPENAI_API_KEY=sk-...

# 공공데이터포털 일반 인증키 (64자리, 3-4 참고)
DATA_GO_KR_SERVICE_KEY=...

# 카카오 네이티브 앱 키 (7-5, 로그인 단계에서 필요)
KAKAO_NATIVE_APP_KEY=...
```

`.gitignore`에 한 줄 추가합니다.

```
secrets.properties
```

`app/build.gradle.kts`에서 읽어 `BuildConfig`로 주입합니다.

```kotlin
import java.util.Properties

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        listOf("CLOVA_OCR_INVOKE_URL", "CLOVA_OCR_SECRET", "OPENAI_API_KEY", "DATA_GO_KR_SERVICE_KEY", "KAKAO_NATIVE_APP_KEY").forEach { key ->
            buildConfigField("String", key, "\"${secrets.getProperty(key, "")}\"")
        }
    }
}
```

코드에서는 `BuildConfig.CLOVA_OCR_SECRET`처럼 읽습니다. 키를 바꾼 뒤에는 **Sync Project with Gradle Files → Rebuild**를 해야 반영됩니다.

### 3-4. 공공데이터 인증키

우리 계정의 일반 인증키는 **64자리 16진수(숫자 + a~f) 한 개**로 발급되어 있습니다 (마이페이지 → 활용신청 상세의 "일반 인증키").

- 특수문자가 없어 URL 인코딩해도 값이 바뀌지 않습니다. **브라우저 테스트와 앱 코드 모두 같은 키**를 씁니다.
- Retrofit에서는 기본 `@Query("serviceKey")`로 넘기면 됩니다 (`encoded = true` 불필요).
- 참고: 예전 방식 키는 Encoding(`%2B` 등 포함)과 Decoding(`+`, `/`, `==` 포함) 두 벌로 나왔습니다. 그런 키를 받게 되면 Retrofit에는 **Decoding 키**를 넣어야 이중 인코딩 오류(`SERVICE_KEY_IS_NOT_REGISTERED_ERROR`)가 나지 않습니다.

### 3-5. 의존성

`gradle/libs.versions.toml`에 추가하고 `app/build.gradle.kts`에서 `libs.*`로 참조합니다. 버전은 추가 시점의 최신 안정 버전을 씁니다.

| 용도 | 라이브러리 |
|---|---|
| HTTP | `com.squareup.retrofit2:retrofit`, `com.squareup.retrofit2:converter-gson` |
| HTTP 로그 | `com.squareup.okhttp3:logging-interceptor` |
| JSON | `com.google.code.gson:gson` |
| 화면 상태 | `androidx.lifecycle:lifecycle-viewmodel`, `androidx.lifecycle:lifecycle-livedata` |
| 로컬 DB (캐시) | `androidx.room:room-runtime` + `annotationProcessor` `androidx.room:room-compiler` |
| 자르기 | `com.github.yalantis:ucrop` (이전 버전에서 사용) |
| OCR | `com.google.mlkit:text-recognition-korean` |
| 로그인·DB (B, D 단계에서 추가) | `firebase-bom`, `firebase-auth`, `firebase-firestore`, `androidx.credentials`, `googleid`, `com.kakao.sdk:v2-user` |

### 3-6. 권한 (`AndroidManifest.xml`)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

카메라 권한은 런타임에 요청합니다. 거부되면 토스트만 띄우지 말고 **TTS로 "카메라 권한이 필요해요. 설정에서 허용해 주세요."** 를 읽어 줍니다.

---

## 4. OCR

> **현재 결정 (2026-10-02): CLOVA OCR은 보류하고 ML Kit으로 진행합니다.**
> 네이버 클라우드 가입·결제와 학교 지출증빙 방식이 정리될 때까지 OCR은 ML Kit(기기 내 인식, 무료, 키 불필요)을 씁니다.
> 4-1 ~ 4-3의 CLOVA 내용은 **나중에 교체할 때 쓰는 설계**이며 지금은 구현하지 않습니다.

### 4-0. 지금 구현하는 것 — ML Kit 한국어 OCR

```
com.medimate.ocr
├── OcrProcessor.java           // 인터페이스: void process(Bitmap bitmap, OcrCallback callback)
├── OcrCallback.java            // onSuccess(String text), onError(String message)
├── MlKitOcrProcessor.java      // 지금 쓰는 구현
└── TextLineSorter.java         // 좌표 기준 Y→X 정렬 (CLOVA로 바꿔도 재사용)
```

- 의존성: `com.google.mlkit:text-recognition-korean`
- `TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build())` 로 인식기를 만들고 `InputImage.fromBitmap(bitmap, 0)` 을 넘깁니다. 네트워크·키가 필요 없습니다.
- 약봉투는 세로 배치·표 형식이 많아 블록 순서가 뒤섞입니다. 블록을 **Y좌표(행) → X좌표(열)** 순으로 다시 정렬합니다. 같은 행 판정 기준은 약 40px (이전 버전 `OCR/OcrProcessor.java`의 `getSortedText()` 참고).
- 결과가 빈 문자열이면 GPT를 부르지 않고 바로 "글자를 찾지 못했어요. 약봉투를 화면 가운데에 두고 다시 찍어 주세요." 를 낭독합니다.
- 인식기는 화면이 닫힐 때 `close()` 합니다.

**교체를 쉽게 하는 규칙** — 화면과 ViewModel은 `MlKitOcrProcessor`를 직접 쓰지 않고 **`OcrProcessor` 인터페이스만** 참조합니다. 나중에 `ClovaOcrProcessor`를 만들어 생성하는 한 줄만 바꾸면 됩니다.

```java
OcrProcessor ocr = new MlKitOcrProcessor();   // 나중에: new ClovaOcrProcessor()
```

ML Kit은 CLOVA보다 한글 인식률이 낮을 수 있습니다. 그만큼 **6-2의 후보 제시 + 음성 확인** 단계가 중요해집니다. 5주차 실측 결과로 CLOVA 전환 필요성을 판단합니다.

### 4-1. (보류) CLOVA 호출 규격

```
POST {CLOVA_OCR_INVOKE_URL}
X-OCR-SECRET: {CLOVA_OCR_SECRET}
Content-Type: application/json
```

요청 본문

```json
{
  "version": "V2",
  "requestId": "UUID 문자열",
  "timestamp": 1790000000000,
  "lang": "ko",
  "images": [
    { "format": "jpg", "name": "medicine_bag", "data": "<Base64 이미지>" }
  ]
}
```

- `version`은 `V2`를 씁니다. V2 응답에만 줄바꿈 정보(`lineBreak`)가 들어 있습니다.
- `requestId`는 매 요청 `UUID.randomUUID()`, `timestamp`는 `System.currentTimeMillis()`
- 이미지는 **JSON + Base64** 방식으로 통일합니다 (`multipart/form-data` 방식도 있지만 쓰지 않습니다).

응답에서 쓰는 부분

```json
{
  "images": [{
    "inferResult": "SUCCESS",
    "message": "SUCCESS",
    "fields": [
      {
        "inferText": "아모디핀정",
        "inferConfidence": 0.9987,
        "lineBreak": false,
        "boundingPoly": { "vertices": [ {"x":10,"y":20}, {"x":90,"y":20}, {"x":90,"y":45}, {"x":10,"y":45} ] }
      }
    ]
  }]
}
```

| 필드 | 쓰는 곳 |
|---|---|
| `inferResult` | `SUCCESS`가 아니면 실패 처리 (`FAILURE`, `ERROR`) |
| `fields[].inferText` | 인식된 단어 |
| `fields[].lineBreak` | `true`면 이 단어 뒤에서 줄이 바뀜 → 줄 재구성에 사용 |
| `fields[].inferConfidence` | 신뢰도. 약품명 단어가 낮으면(예: 0.8 미만) 후보 확인 단계로 보냄 |
| `fields[].boundingPoly` | 좌표. 줄 순서가 어긋날 때 Y→X 재정렬에 사용 |
| `tables` | 표 추출을 켰을 때만. 약봉투 표를 셀 단위로 받을 수 있음 |

### 4-2. (보류) CLOVA 패키지 구조

4-0의 구조에 아래를 추가합니다.

```
com.medimate.ocr
├── ClovaOcrProcessor.java      // OcrProcessor 구현: Bitmap → CLOVA 호출 → 텍스트
├── ImageEncoder.java           // 축소 + JPEG 압축 + Base64
└── clova/
    ├── ClovaOcrApi.java        // Retrofit 인터페이스
    ├── ClovaOcrClient.java     // Retrofit 싱글턴 (타임아웃 30초)
    ├── ClovaOcrRequest.java    // version, requestId, timestamp, lang, images[]
    └── ClovaOcrResponse.java   // images[].inferResult, fields[]
```

Invoke URL은 경로가 도메인마다 달라서 `baseUrl`에 넣기 어렵습니다. `@Url`로 전체 주소를 넘깁니다.

```java
public interface ClovaOcrApi {
    @POST
    Call<ClovaOcrResponse> recognize(
            @Url String invokeUrl,                        // BuildConfig.CLOVA_OCR_INVOKE_URL
            @Header("X-OCR-SECRET") String secret,        // BuildConfig.CLOVA_OCR_SECRET
            @Body ClovaOcrRequest body
    );
}
```

`ClovaOcrClient`의 `baseUrl`은 형식상 `https://apigw.ntruss.com/`로 두면 됩니다. `@Url`에 절대 주소를 넘기면 그 주소가 우선합니다.

### 4-3. (보류) CLOVA 처리 순서

1. **이미지 줄이기** — 긴 변이 2,000px 안팎이 되도록 줄입니다. 카메라 원본(4,000px 이상)을 그대로 보내면 업로드가 느리고 인식률도 더 좋아지지 않습니다.
2. **Base64 인코딩** — `bitmap.compress(JPEG, 90, out)` → `Base64.encodeToString(bytes, Base64.NO_WRAP)`.
   `NO_WRAP`을 빼면 76자마다 줄바꿈이 들어가 요청이 실패합니다.
3. **호출** — Retrofit `enqueue`로 비동기 호출. 축소·인코딩도 메인 스레드에서 하지 않습니다.
4. **줄 재구성** — `fields`를 순서대로 이어 붙이되, `lineBreak == true`면 줄바꿈을 넣습니다.
   세로 배치 약봉투에서 순서가 어긋나면 `boundingPoly` 기준 Y→X 순으로 다시 정렬합니다 (이전 버전 `OcrProcessor.getSortedText()` 참고, 같은 행 판정 기준 약 40px).
5. **결과 전달** — `callback.onSuccess(text)`. 빈 문자열이면 GPT를 부르지 않고 바로
   "글자를 찾지 못했어요. 약봉투를 화면 가운데에 두고 다시 찍어 주세요." 를 낭독합니다.

**폴백** — CLOVA로 전환한 뒤에도 `MlKitOcrProcessor`는 남겨 둡니다. 네트워크가 없거나 CLOVA 호출이 실패(5xx, 타임아웃)하면 ML Kit으로 한 번 더 시도합니다.

### 4-4. 화면 연동 (`ScanViewModel`)

```
ScanActivity ──(Bitmap)──▶ ScanViewModel.start(bitmap)
                              │ OcrProcessor → GptProcessor → DrugInfoRepository
                              ▼
                 LiveData<Boolean> isLoading   → 로딩 다이얼로그
                 LiveData<List<Drug>> drugs    → DrugListActivity 로 이동
                 LiveData<String> error        → TTS로 실패 사유 낭독
```

- 실패는 토스트가 아니라 **TTS로 읽어 줍니다.** 화면을 못 보는 사용자에게는 이 음성이 곧 UI입니다.
- 단계별 진행도 음성으로 알립니다: "글자를 읽고 있어요" → "약 정보를 찾고 있어요".

---

## 5. 식약처 공공데이터 API

### 5-1. 사용할 API (발급 완료)

| 이름 | 용도 | End Point |
|---|---|---|
| 의약품개요정보 (e약은요) | 효능, 사용법, 주의사항, 상호작용, 부작용, 보관법 | `https://apis.data.go.kr/1471000/DrbEasyDrugInfoService` |
| 의약품 낱알식별 정보 | 모양, 색, 각인, 크기, 제형, 이미지 | `https://apis.data.go.kr/1471000/MdcinGrnIdntfcInfoService03` |

- 두 API 모두 활용신청·일반 인증키 발급 완료. 인증키는 계정당 하나라 **둘 다 같은 `DATA_GO_KR_SERVICE_KEY`** 를 씁니다.
- 엔드포인트는 비밀이 아니므로 인터페이스의 `@GET` 경로에 둡니다. 비밀은 인증키뿐입니다.
- 개발계정은 **일 1,000건** 제한입니다. 낱알식별 전량(약 2.5만 건) 적재와 실사용을 위해 **운영계정 전환 신청**을 해 둡니다.

**코드를 짜기 전에 브라우저로 먼저 호출해 봅니다.** 여기서 되면 이후 오류는 코드 문제입니다. 받은 응답 JSON은 모델 클래스 작성에 그대로 씁니다.

```
https://apis.data.go.kr/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList?serviceKey={인증키}&itemName=타이레놀&type=json
https://apis.data.go.kr/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03?serviceKey={인증키}&item_name=타이레놀&type=json
```

### 5-2. e약은요 — 약품 정보 조회

```
GET https://apis.data.go.kr/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList
```

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `serviceKey` | O | 일반 인증키 |
| `itemName` | | 제품명 (부분 일치) |
| `itemSeq` | | 품목기준코드 — 알면 정확히 1건 |
| `entpName` | | 업체명 |
| `pageNo`, `numOfRows` | | 페이지 |
| `type` | | `json` 권장 (기본은 XML) |

주요 응답 필드 (`body.items[]`)

| 필드 | 내용 | 앱에서 쓰는 곳 |
|---|---|---|
| `itemName`, `entpName`, `itemSeq` | 제품명, 업체명, 품목코드 | 후보 표시, 캐시 키 |
| `efcyQesitm` | 효능 | 약 설명 |
| `useMethodQesitm` | 사용법 | 복용방법 보조 |
| `atpnWarnQesitm`, `atpnQesitm` | 경고, 주의사항 | 주의사항 |
| `intrcQesitm` | 상호작용 | 영양제 추천 경고 |
| `seQesitm` | 부작용 | 주의사항 |
| `depositMethodQesitm` | 보관법 | 보관방법 |
| `itemImage` | 낱알 이미지 URL | 상세 화면 이미지 |

응답 본문에 `<p>`, `<sup>` 같은 HTML 태그가 섞여 있습니다. 화면·TTS 둘 다에 쓰기 전에 태그를 제거하고 공백을 정리합니다.

### 5-3. 낱알식별 — 알약 외형 정보

```
GET https://apis.data.go.kr/1471000/MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03
```

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `serviceKey` | O | 일반 인증키 |
| `item_name` | | 제품명 (부분 일치) |
| `entp_name` | | 업체명 |
| `item_seq` | | 품목기준코드 |
| `pageNo`, `numOfRows` | | 페이지 (전량 적재 시 `numOfRows=100`) |
| `type` | | `json` 권장 |

e약은요(`itemName`)와 달리 **파라미터가 스네이크 케이스**(`item_name`)입니다. 활용신청 페이지의 참고문서(기술문서)와 한 번 대조하세요.

주요 응답 필드 (대문자 필드명)

| 필드 | 내용 |
|---|---|
| `ITEM_SEQ`, `ITEM_NAME`, `ENTP_NAME` | 품목코드, 제품명, 업체명 |
| `DRUG_SHAPE` | 모양 (원형, 타원형, 장방형 …) |
| `COLOR_CLASS1`, `COLOR_CLASS2` | 앞·뒤 색 |
| `PRINT_FRONT`, `PRINT_BACK` | 앞·뒤 각인 문자 |
| `LINE_FRONT`, `LINE_BACK` | 분할선 |
| `LENG_LONG`, `LENG_SHORT`, `THICK` | 장축, 단축, 두께 (mm) |
| `FORM_CODE_NAME` | 제형 (필름코팅정, 경질캡슐 …) |
| `ITEM_IMAGE` | 낱알 사진 URL |

- **이 API는 앱 실행 중에 부르지 않습니다.** 전량을 한 번 내려받아 Room에 적재하고, 알약 매칭은 로컬에서 합니다.
- 응답의 `body.totalCount`로 필요한 페이지 수(`totalCount / 100`)를 계산합니다. 개발계정 한도로는 며칠이 걸리니 운영계정 전환 후 진행합니다.
- `ITEM_SEQ`로 e약은요와 연결됩니다.

### 5-4. 패키지 구조

```
com.medimate.publicdata
├── PublicDataClient.java        // Retrofit 싱글턴 (Gson, 타임아웃 10초), BASE_URL = https://apis.data.go.kr/1471000/
├── DrugInfoApi.java             // e약은요
├── PillIdentifyApi.java         // 낱알식별
├── DrugInfoRepository.java      // 캐시 → API 순 조회, 이름 정규화
├── model/
│   ├── DrugInfoResponse.java    // header/body/items 래퍼
│   ├── DrugInfoItem.java
│   ├── PillResponse.java
│   └── PillItem.java            // @SerializedName("ITEM_SEQ") 등 대문자 매핑
└── util/
    └── HtmlCleaner.java         // 응답 HTML 태그 제거

com.medimate.data                // Room
├── AppDatabase.java
├── DrugInfoEntity.java / DrugInfoDao.java   // e약은요 캐시 (itemSeq 기준)
└── PillEntity.java / PillDao.java           // 낱알식별 전량
```

```java
public interface DrugInfoApi {
    @GET("DrbEasyDrugInfoService/getDrbEasyDrugList")
    Call<DrugInfoResponse> search(
            @Query("serviceKey") String serviceKey,   // 일반 인증키
            @Query("itemName") String itemName,
            @Query("pageNo") int pageNo,
            @Query("numOfRows") int numOfRows,
            @Query("type") String type                // "json"
    );
}

public interface PillIdentifyApi {
    @GET("MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03")
    Call<PillResponse> getPage(
            @Query("serviceKey") String serviceKey,
            @Query("pageNo") int pageNo,
            @Query("numOfRows") int numOfRows,        // 100
            @Query("type") String type
    );

    @GET("MdcinGrnIdntfcInfoService03/getMdcinGrnIdntfcInfoList03")
    Call<PillResponse> searchByName(
            @Query("serviceKey") String serviceKey,
            @Query("item_name") String itemName,
            @Query("pageNo") int pageNo,
            @Query("numOfRows") int numOfRows,
            @Query("type") String type
    );
}
```

### 5-5. GPT의 역할 제한

GPT는 **OCR 원문에서 약 이름·용법·보관법을 구조화하는 데만** 씁니다.
효능·주의사항·상호작용·생김새를 GPT 자체 지식으로 채우면 근거 없는 건강 정보가 나가고 환각 위험이 있습니다. 이 항목들은 공공데이터로 채웁니다 (6-3).

GPT 응답 형식 (JSON 모드, `temperature` 0~0.3)

```json
{
  "drugs": [
    { "name": "아모디핀정5mg", "dosage": "1일 1회 아침 식후 30분", "storage": "실온 보관" }
  ],
  "common_instructions": "..."
}
```

---

## 6. OCR 결과와 공공데이터 연결

GPT가 뽑은 약 이름을 e약은요에서 찾는 단계가 핵심입니다. 약봉투의 표기와 허가 제품명이 정확히 같지 않기 때문입니다.

### 6-1. 이름 정규화

| OCR/GPT 결과 | 조회에 쓸 이름 |
|---|---|
| `아모디핀정5mg` | `아모디핀정` |
| `타이레놀정 500밀리그램` | `타이레놀정` |
| `(급여)록소프로펜정` | `록소프로펜정` |

- 괄호 안 문구, 용량(`숫자 + mg/밀리그램/g/mL`), 공백을 제거합니다.
- 결과가 없으면 제형 접미사(`정`, `캡슐`, `시럽`)를 떼고 한 번 더 조회합니다.

### 6-2. 결과 개수에 따른 처리

| 조회 결과 | 처리 |
|---|---|
| 1건 | 그대로 연결 |
| 여러 건 | 상위 3개를 **후보로 제시**하고 음성으로 확인 ("아모디핀정 오 밀리그램, 제일약품 제품이 맞나요?") |
| 0건 | GPT가 추출한 복용법만 표시하고, 약 설명은 "공식 정보를 찾지 못했어요"로 명시 |

OCR 정확도가 목표(90%)에 못 미칠 때의 대응이 바로 이 **후보 제시 + 음성 확인** 구조입니다. 자동 등록보다 이 흐름을 기본으로 설계합니다.

### 6-3. `Drug` 모델 채우기

각 항목은 화면용(`*Display`)과 낭독용(`*Tts`) 두 벌로 둡니다.

| 항목 | 출처 |
|---|---|
| `name` | GPT (OCR 원문) → e약은요 `itemName`으로 교정 |
| `dosage` | GPT (OCR 원문 — 약사가 적은 실제 용법) |
| `storage` | OCR 원문 우선, 없으면 e약은요 `depositMethodQesitm` |
| `description` | e약은요 `efcyQesitm` |
| `warning` | e약은요 `atpnWarnQesitm` + `atpnQesitm` + OCR 키워드(졸음, 운전 등) |
| `appearance` | 낱알식별 로컬 DB (`DRUG_SHAPE`, `COLOR_CLASS1`, `PRINT_FRONT`) |

낭독용 문장은 원문을 그대로 읽지 않고 TTS 낭독 규격(예: `500mg` → "오백 밀리그램", `1일 3회` → "하루 세 번")에 맞춰 변환합니다.

### 6-4. 캐시

- e약은요 조회 결과를 `itemSeq` 기준으로 Room에 저장하고, 같은 약은 다시 부르지 않습니다.
- 캐시 유효기간은 30일.
- 네트워크가 없을 때도 이전에 조회한 약은 설명을 읽어 줄 수 있습니다.

---

## 7. 로그인 (전화번호 · 구글 · 카카오)

### 7-1. 구조

로그인은 **Firebase Authentication**으로 통일합니다. 화면은 로그인 방식을 몰라도 되도록 `AuthRepository` 하나만 참조합니다.

```
com.medimate.auth
├── AuthRepository.java        // currentUser(), signOut(), 로그인 결과 콜백
├── GoogleSignInHelper.java    // 구글 → Firebase
├── PhoneAuthHelper.java       // 전화번호 인증 → Firebase
└── KakaoSignInHelper.java     // 카카오 SDK 로그인

com.medimate.ui
├── SplashActivity             // 로그인 여부 확인 → Login 또는 Main
├── LoginActivity              // 버튼 3개: 전화번호 / 구글 / 카카오
├── PhoneLoginActivity         // 번호 입력 → 인증번호 입력
└── ProfileSetupActivity       // 첫 로그인 때만: 이름, 출생연도, 성별
```

화면 흐름

```
Splash ─┬─ 로그인 안 됨 → Login ─ (성공) ─┬─ 첫 로그인 → ProfileSetup → Main
        └─ 로그인 됨 ─────────────────────┴─ 기존 회원 ─────────────→ Main
```

`Main`에는 큰 버튼 네 개만 둡니다: **약 찍기 / 내 약 / 영양제 추천 / 내 정보**.

### 7-2. Firebase 준비 (콘솔 작업 — 직접 해야 함)

1. Firebase 콘솔 → 프로젝트 → **Android 앱 추가**, 패키지 이름 `com.medimate`
2. `google-services.json`을 받아 `app/` 폴더에 넣습니다. **커밋하지 않습니다** (`.gitignore`에 포함).
3. **SHA-1, SHA-256 지문 등록** — 구글·전화번호 로그인에 필수입니다. 팀원마다 디버그 키가 달라서 **각자 자기 지문을 추가**해야 합니다.
   ```
   ./gradlew signingReport
   ```
4. **Authentication → 로그인 방법**에서 **전화**, **Google** 사용 설정
5. **Firestore Database** 생성 (위치: `asia-northeast3` 서울)

Gradle: 플러그인 `com.google.gms.google-services`, 의존성 `firebase-bom`, `firebase-auth`, `firebase-firestore`

### 7-3. 구글 로그인

- **Credential Manager** 방식을 씁니다 (`androidx.credentials`, `credentials-play-services-auth`, `com.google.android.libraries.identity.googleid`). 이전 버전의 `GoogleSignIn`(play-services-auth)은 지원 종료된 방식이라 쓰지 않습니다.
- 흐름: 구글 계정 선택 → ID 토큰 → `GoogleAuthProvider.getCredential(idToken, null)` → `FirebaseAuth.signInWithCredential()`
- 서버 클라이언트 ID는 `google-services.json`이 만들어 주는 `R.string.default_web_client_id`를 씁니다. 코드에 직접 적지 않습니다.

### 7-4. 전화번호 로그인

- `PhoneAuthProvider.verifyPhoneNumber()` → 문자 인증번호 → `signInWithCredential()`
- 번호는 국제 형식으로 변환합니다: `010-1234-5678` → `+821012345678`
- **개발 중에는 Firebase 콘솔의 "테스트용 전화번호"를 등록해서 씁니다.** 실제 문자는 하루 발송 한도와 요금이 있으니 콘솔의 현재 한도를 확인합니다.
- 접근성: 인증번호가 자동으로 확인되면(`onVerificationCompleted`) 입력 없이 넘어갑니다. 입력이 필요할 때는 "문자로 온 여섯 자리 숫자를 입력해 주세요"를 낭독합니다.

### 7-5. 카카오 로그인

- 카카오 SDK(`com.kakao.sdk:v2-user`)로 로그인합니다. 카카오톡이 설치돼 있으면 카카오톡으로, 없으면 카카오 계정 웹 로그인으로 넘어갑니다.
- 카카오 개발자 콘솔 준비: 앱 등록 → **네이티브 앱 키** 확인 → Android 플랫폼에 패키지 이름 `com.medimate`와 **키 해시** 등록 (팀원마다 다름)
- 네이티브 앱 키는 `secrets.properties`의 `KAKAO_NATIVE_APP_KEY`에 넣고, `BuildConfig`와 `manifestPlaceholders`로 주입합니다. 매니페스트나 코드에 직접 적지 않습니다.

**카카오와 Firebase 연결 (결정 필요)** — Firebase Auth는 카카오를 기본 지원하지 않습니다.

| 방식 | 내용 | 시점 |
|---|---|---|
| 1차 뼈대 | 카카오 SDK 로그인까지만 구현하고 카카오 회원번호를 받아 화면 전환 | 지금 |
| 정식 (택 1) | ① Firebase에 카카오를 **OIDC 제공업체**로 등록 ② 서버(Cloud Functions 등)에서 **커스텀 토큰** 발급 | 2차 다듬기 |

정식 연결 전에는 카카오 사용자가 Firebase 사용자로 인식되지 않아 **Firestore 보안 규칙(9-3)을 통과하지 못합니다.** 1차에서는 카카오 로그인 사용자의 DB 저장을 건너뛰고, 정식 방식은 요금제(무료 범위)를 확인한 뒤 팀이 정합니다.

### 7-6. 공통

- 로그아웃: `FirebaseAuth.signOut()` + 카카오 `UserApiClient.logout()`
- 탈퇴: 계정 삭제 + Firestore의 내 데이터 삭제 (2차)
- 모든 로그인 버튼은 48dp 이상, `contentDescription` 지정, 실패 사유는 TTS로 안내

---

## 8. 맞춤형 영양제 추천

### 8-1. 원칙

- **추천은 규칙 표로 결정합니다. GPT가 추천 성분을 정하지 않습니다.** 근거 없는 건강 정보가 나가지 않게 하기 위해서입니다.
- 규칙 표의 **모든 항목에 근거 출처**(식약처 기능성 원료 인정 내용 등)를 함께 적습니다.
- 결과 화면과 낭독에 면책 문구를 항상 넣습니다: "이 정보는 의학적 조언이 아닙니다. 복용 전에 의사나 약사와 상담하세요."

### 8-2. 흐름

```
[건강 정보 입력]            나이대, 성별, 건강 고민(여러 개 선택)
      │
      ▼
[규칙 표 매칭]              고민 → 추천 성분        recommend/RecommendEngine
      │
      ▼
[복용 중인 약과 대조]        내 약(9장) + e약은요 상호작용(intrcQesitm) → 주의 표시
      │
      ▼
[제품 조회]                 식약처 건강기능식품 API   publicdata/SupplementApi
      │
      ▼
[추천 결과 → 낭독 → 저장]
```

### 8-3. 구성

```
com.medimate.recommend
├── RecommendEngine.java       // 입력 → 추천 성분 목록 (순수 자바, 네트워크 없음)
├── RecommendRule.java         // concern, ingredient, reason, source
└── InteractionChecker.java    // 복용 약과 추천 성분 대조 (2차)

app/src/main/assets/supplement_rules.json   // 규칙 표 (팀이 작성, 출처 포함)

com.medimate.ui
├── HealthInputActivity        // 건강 정보 입력
└── RecommendActivity          // 추천 결과
```

규칙 표 형식

```json
[
  {
    "concern": "건강 고민 이름",
    "ingredient": "추천 성분",
    "reason": "화면·낭독에 쓸 한 문장 설명",
    "source": "근거 출처 (기관, 문서명)"
  }
]
```

### 8-4. 제품 조회 — 식약처 건강기능식품 API

- 이전 버전이 쓰던 주소: `https://apis.data.go.kr/1471000/HtfsInfoService03/getHtfsItem01`
- **공공데이터포털에서 별도 활용신청이 필요합니다.** 인증키는 같은 `DATA_GO_KR_SERVICE_KEY`를 씁니다. 신청 후 발급 화면의 End Point와 다르면 발급 화면 값이 우선입니다.
- 응답 모델은 실제 응답을 보고 작성합니다.

### 8-5. 뼈대와 다듬기 구분

| 1차 뼈대 | 2차 다듬기 |
|---|---|
| 입력 화면 (고민 5~6개) | 고민 항목 확장, 입력값 저장·불러오기 |
| 규칙 표 최소 5개 + 결과 목록 + 낭독 | 규칙 표 전체 작성 (출처 포함) |
| — | 복용 약과의 상호작용 주의 표시 |
| — | 건강기능식품 API로 실제 제품 표시 |

---

## 9. DB 연동 (Firebase Firestore)

### 9-1. 저장소 역할 구분

| 저장소 | 담는 것 | 이유 |
|---|---|---|
| **Firestore** | 사용자 데이터: 프로필, 내 약, 추천 기록 | 기기를 바꿔도 유지, 보호자 공유로 확장 가능 |
| **Room** (기기 안) | 공공데이터 캐시: e약은요 조회 결과, 낱알식별 전량 | 사용자와 무관한 공용 데이터, 오프라인 조회 |

### 9-2. 데이터 구조

```
users/{uid}
  ├─ name, birthYear, gender, provider("phone" | "google" | "kakao"), createdAt
  ├─ healthConcerns: ["...", "..."]
  │
  ├─ medications/{medicationId}        // 내 약
  │     itemSeq, name, dosage, storage, source("ocr" | "manual"), createdAt
  │
  └─ recommendations/{recommendationId} // 추천 기록
        concerns[], ingredients[], createdAt
```

- 약의 효능·주의사항 본문은 Firestore에 넣지 않습니다. `itemSeq`만 저장하고 본문은 Room 캐시나 e약은요에서 가져옵니다.
- 처방전·약봉투 **이미지는 저장하지 않습니다.**

### 9-3. 보안 규칙

테스트 모드(누구나 읽기·쓰기)로 두지 않습니다. **본인 문서만** 접근하게 합니다.

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

### 9-4. 코드 구조

화면은 Firestore를 직접 부르지 않고 저장소 인터페이스만 참조합니다.

```
com.medimate.data
├── UserRepository.java            // 프로필 저장·조회
├── MedicationRepository.java      // 내 약 추가·목록·삭제
├── RecommendationRepository.java  // 추천 기록 저장·조회
└── firestore/                     // 위 인터페이스의 Firestore 구현
```

### 9-5. 개인정보

- 복약 정보와 건강 고민은 **민감정보**입니다. 첫 저장 전에 **별도 동의 화면**을 거칩니다 (동의 문구 낭독 포함).
- 탈퇴하면 `users/{uid}` 아래 전체를 삭제합니다.
- 개인정보처리방침에 수집 항목과 처리 위탁(Google Firebase, OpenAI)을 명시합니다.

---

## 10. 구현 순서 — 큰 틀 먼저

### 10-1. 원칙

1. **1차는 뼈대만.** 각 단계는 "실행해서 눈으로 확인되는 최소 동작"까지만 만듭니다. 화면은 꾸미지 않고 기본 버튼과 목록으로 둡니다.
2. **1차에서 하지 않는 것**: 예외 상황 세부 처리, 캐시, 디자인, 후보 선택 화면, 상호작용 경고, 탈퇴. 필요해 보여도 2차 표에 적어 두고 넘어갑니다.
3. **A → B → C → D 순서**로 진행하고, 한 단계씩 빌드·실행으로 확인한 뒤 커밋합니다.
4. 접근성 최소 기준(버튼 48dp, `contentDescription`, 실패 시 음성 안내)은 1차부터 지킵니다. 나중에 덧붙이면 화면을 다시 만들게 됩니다.

### 10-2. 1차 — 뼈대

| 단계 | 내용 | 완료 기준 | 상태 |
|---|---|---|---|
| A1 | 키 주입, 의존성, 권한 (3-3, 3-5, 3-6) | 빌드 성공 | 완료 |
| A2 | e약은요 호출 (5-2) | "타이레놀" 조회 결과가 Logcat에 출력 | 완료 |
| A3 | ML Kit OCR (4-0) | 샘플 사진의 인식 글자가 Logcat에 출력 | 완료 |
| A4 | 촬영·갤러리 → OCR (4-4). 자르기는 생략 | 실기기에서 찍은 약봉투 글자가 화면에 표시 | |
| A5 | GPT 정리 (5-5) → e약은요 조회 → 약 목록 화면. 이름 정규화는 용량 제거만, 여러 건이면 첫 번째 사용 | 약 목록에 공식 효능이 표시 | |
| A6 | 약 상세 + TTS 낭독 | 항목을 누르면 읽어 줌 | |
| B1 | Firebase 연결 (7-2), Splash → Login → Main 화면 틀 | 앱 실행 시 로그인 화면이 뜨고, Main에 버튼 네 개 | |
| B2 | 구글 로그인 (7-3) | 로그인 후 Main 진입, 재실행 시 로그인 유지 | |
| B3 | 전화번호 로그인 (7-4), 테스트용 번호 | 테스트 번호로 로그인 | |
| B4 | 카카오 로그인 (7-5), SDK 로그인까지 | 카카오 계정으로 로그인 후 Main 진입 | |
| B5 | 로그아웃, 내 정보 화면 틀 | 로그아웃하면 Login으로 복귀 | |
| C1 | 건강 정보 입력 화면 (8-3) | 고민을 선택해 다음 화면으로 전달 | |
| C2 | 규칙 표(최소 5개) + 추천 엔진 + 결과 화면 + 낭독 (8-5) | 선택한 고민에 맞는 성분이 표시·낭독 | |
| D1 | Firestore 연결, 프로필 저장 (9-2), 보안 규칙 (9-3) | 첫 로그인 정보가 콘솔에 보임 | |
| D2 | 내 약 저장·목록 — A5 결과에 "내 약에 추가" | 앱을 다시 켜도 내 약이 남아 있음 | |
| D3 | 추천 기록 저장 | 추천 결과가 콘솔에 보임 | |

**1차가 끝나면** 로그인 → 약 찍기 → 내 약에 저장 → 영양제 추천까지 한 번에 시연할 수 있습니다.

### 10-3. 2차 — 다듬기

1차가 모두 끝난 뒤, 중요한 순서대로 진행합니다.

| 영역 | 내용 |
|---|---|
| A 약 인식 | 사진 자르기(UCrop) · 이름 정규화 전체 규칙 (6-1) · 여러 건일 때 후보 제시 + 음성 확인 (6-2) · e약은요 Room 캐시 (6-4) · 낭독 문장 변환 (6-3) · 낱알식별 전량 적재 (5-3) |
| B 로그인 | 카카오–Firebase 정식 연결 (7-5) · 첫 로그인 프로필 입력 · 탈퇴 |
| C 추천 | 규칙 표 전체 + 출처 · 복용 약 상호작용 주의 · 건강기능식품 API 제품 표시 (8-4) |
| D DB | 민감정보 동의 화면 (9-5) · 탈퇴 시 데이터 삭제 · 오프라인 동작 확인 |
| 공통 | 오류별 음성 안내 문구 · TalkBack 실기기 점검 · 화면 디자인 · HTTP 로그 정리 (13장) |
| 보류 | CLOVA OCR 전환 (4-1 ~ 4-3) — 결제·증빙 정리 후, ML Kit 실측 결과를 보고 결정 |

---

## 11. 테스트와 검증 (5주차)

| 항목 | 방법 | 목표 |
|---|---|---|
| ML Kit OCR 인식률 | 골드셋 약봉투 30장, 약품명 기준 정답률 | 90% (미달 시 CLOVA 전환 검토) |
| (보류) CLOVA OCR 인식률 | 같은 골드셋으로 비교. 표 추출 켬/끔 | 전환 시 측정 |
| OCR 처리 시간 | 촬영 후 결과까지 걸린 시간 (저사양 기기 포함) | 측정 후 결정 |
| GPT 정리 | OCR 원문 10건: JSON 스키마 준수율, 약품명 정확도, 건당 토큰·비용 | 스키마 100% |
| e약은요 실호출 | 응답 필드, 한글 인코딩, 인증키 인코딩, 실제 호출 한도 확인 | 명세와 일치 |
| 이름 매칭 | 골드셋 약품명 → e약은요 1건 매칭률 | 측정 후 결정 |

- 약봉투 샘플은 **환자 이름과 생년월일을 가린 뒤** 보관합니다.
- 결과는 5주차 PoC 종합 판단 회의 자료로 씁니다. 목표에 못 미치면 목표를 낮추지 않고 설계를 바꿉니다 (6-2의 후보 제시 방식).

---

## 12. 자주 나는 오류

| 증상 | 원인 | 해결 |
|---|---|---|
| CLOVA `401` / `403` | Secret 오류, API Gateway 연동 안 됨 | 도메인 → Text OCR → API Gateway 연동 다시 확인 (3-2) |
| CLOVA `400` | Base64에 줄바꿈 포함, `format` 불일치, `version` 누락 | `Base64.NO_WRAP`, `format`을 실제 인코딩(jpg)과 맞춤 |
| CLOVA `inferResult: FAILURE` | 이미지가 흐리거나 글자가 없음 | 재촬영 음성 안내 |
| CLOVA 호출이 느림 / 타임아웃 | 원본 이미지를 그대로 전송 | 긴 변 2,000px로 축소, 타임아웃 30초 |
| Logcat이 멈춤 | CLOVA 요청을 `BODY` 로그로 출력 | CLOVA 클라이언트는 `HEADERS`까지만 |
| 키를 바꿨는데 반영 안 됨 | `BuildConfig` 재생성 안 됨 | Gradle Sync → Rebuild |
| `BuildConfig` 클래스가 없음 | `buildFeatures.buildConfig` 미설정 | 3-3의 `buildConfig = true` 추가 |
| `SERVICE_KEY_IS_NOT_REGISTERED_ERROR` | 키 복사 누락·공백, API별 활용신청 미승인, 발급 직후 | 키 앞뒤 공백 확인, 활용신청 승인 확인, 발급 후 1~2시간 대기 |
| `LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR` | 개발계정 일 1,000건 초과 | 캐시 확인, 운영계정 전환 |
| JSON 파싱 실패 | `type=json` 누락 → XML 응답 | 파라미터 확인 |
| e약은요 결과 0건 | 제품명 표기 불일치 | 6-1 이름 정규화 적용 |
| GPT `401` | `Bearer ` 접두사 누락, 키 만료 | `Authorization: Bearer {키}` 형식 확인 |
| GPT 응답이 코드블록으로 감싸짐 | 모델이 ```json 으로 감쌈 | 파싱 전 코드블록 기호 제거 |

---

## 13. 보안 규칙

- `secrets.properties`, `local.properties`, `google-services.json`, 서비스 계정 JSON은 **절대 커밋하지 않습니다.**
- 실수로 올렸다면 커밋을 지워도 소용없습니다. **즉시 재발급**하세요.
- HTTP 로그: `Authorization`, `X-OCR-SECRET` 헤더를 가리고, 릴리스 빌드에서는 끕니다.
  ```java
  logging.redactHeader("Authorization");
  logging.redactHeader("X-OCR-SECRET");
  logging.setLevel(BuildConfig.DEBUG ? HttpLoggingInterceptor.Level.HEADERS
                                     : HttpLoggingInterceptor.Level.NONE);
  ```
  CLOVA 요청 본문은 약봉투 이미지(환자 정보)라서 `BODY` 수준으로 남기지 않습니다. 공공데이터도 URL에 `serviceKey`가 찍히니 릴리스에서는 끕니다.
- `BuildConfig` 값은 APK를 풀면 누구나 볼 수 있습니다. 개발 단계에서는 허용하되, 스토어 배포 전에는 **서버 경유**로 옮깁니다 (앱 → 우리 서버 → CLOVA / OpenAI / 공공데이터). NCP 예산 알림과 OpenAI 월 사용량 한도를 반드시 걸어 둡니다.
- 약봉투에서 읽은 텍스트는 OpenAI로, 사용자 데이터는 Google Firebase로 전송됩니다. **개인정보처리방침의 처리 위탁 항목에 OpenAI와 Google을 명시**합니다 (CLOVA로 전환하면 NAVER Cloud 추가). 촬영 안내에서 환자 이름 부분을 빼고 자르도록 유도합니다.

---

## 14. 참고

- 공공데이터포털: https://www.data.go.kr (마이페이지 → 데이터활용 → 개발계정에서 End Point·인증키 확인)
- (보류) 네이버 클라우드 CLOVA OCR API 가이드: https://api.ncloud-docs.com/docs/ai-application-service-ocr
- ML Kit Text Recognition v2 (현재 OCR): https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- 이전 버전 코드: `D:\Users\Medimate\Medimate` (참고용, 2장)
