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

# Anthropic Claude (영양제 챗봇의 증상 파악, 8-6)
CLAUDE_API_KEY=sk-ant-...

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
        listOf("CLOVA_OCR_INVOKE_URL", "CLOVA_OCR_SECRET", "OPENAI_API_KEY", "DATA_GO_KR_SERVICE_KEY", "KAKAO_NATIVE_APP_KEY", "CLAUDE_API_KEY").forEach { key ->
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
| Claude (영양제 챗봇) | `com.anthropic:anthropic-java` (공식 Java SDK) |
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

- **추천은 규칙 표로 결정합니다. AI가 추천 성분을 정하지 않습니다.** 근거 없는 건강 정보가 나가지 않게 하기 위해서입니다.
- AI(Claude, 8-6)는 **사용자 정보를 파악하는 데만** 씁니다.
  - 사용자가 말한 내용을 정리해 규칙 표의 고민 항목으로 바꿉니다.
  - 그 정보로 항목을 고를 수 있는지 판단합니다.
  - 정보가 부족하면 증상을 묻는 추가 질문을 만듭니다.
- 규칙 표에 없는 항목은 만들지 않습니다. 성분·설명·조언도 AI가 쓰지 않습니다. 추가 질문도 증상을 묻는 질문만 합니다.
- 규칙 표의 **모든 항목에 근거 출처**(식약처 기능성 원료 인정 내용 등)를 함께 적습니다. 출처가 빈 규칙은 엔진이 읽을 때 버립니다.
- **규칙 표 확장** (2026-10-30 결정): 식약처 기능성 원료 공공데이터 API에서 공식 "주된 기능성" 문구를 받아 규칙 표 후보를 만들고, 팀이 검토해 넣습니다. AI가 문구를 쓰거나 학습으로 항목을 정하지 않습니다. 활용신청 후 실제 응답 JSON을 받으면 만듭니다 (대상 후보: 공공데이터포털 "식품의약품안전처_건강기능식품 개별인정형 정보" 등).
- 결과 화면과 낭독에 면책 문구를 항상 넣습니다: "이 정보는 의학적 조언이 아닙니다. 복용 전에 의사나 약사와 상담하세요."

### 8-2. 흐름 (챗봇형, 2026-10-06 변경 · 2026-10-28 Claude로 교체)

```
[채팅 입력]                 "요즘 좀 안 좋아요"                ui/recommend/RecommendChatActivity
      │
      ▼
[증상 파악]                 Claude: 지금까지의 대화 → 판단      recommend/ClaudeSymptomAnalyzer
      │                     { 고민 항목, 정보 부족 여부, 추가 질문, 표 밖 증상 여부, 증상 요약,
      │                       지병, 복용 약, 병원 안내, 응급·진료 분류, 약·질환 연결 }
      │
      ├─ 응급 (8-8) ──▶ [119 안내]  문진·추천 없이 바로 안내하고 상담 종료
      │
      ├─ 정보 부족 ──▶ [추가 질문]  "어디가 어떻게 불편하세요?"  → 사용자 답 → 다시 [증상 파악]
      │                (최대 2번. 넘으면 그때까지 고른 항목으로 진행)
      ▼
[지병·복용 약 질문]          추천할 항목이 정해지면 상담마다 한 번   ui/recommend/RecommendChatViewModel
      │                     "평소 앓고 계신 병이 있거나 드시고 있는 약이 있으세요?"
      │                     → 사용자 답 → 다시 [증상 파악] (지병·약을 뽑아 냄)
      ▼
[약·질환 연결 확인] (8-8)    규칙에 걸릴 수 있는 연결만 "○○: 레보티록신, 맞나요?"로 확인
      ▼
[규칙 표 매칭]              고민 → 추천 성분·설명·출처·섭취 시 주의   recommend/RecommendEngine
      │                     + 근거 규칙 확인 사항 (8-8, 디버그 빌드)
      │                     지병·약이 있으면 답변 맨 앞에 상담 안내, 병원 안내(8-7)는 맨 끝
      ▼
[제품 조회]                 식약처 건강기능식품 API            publicdata/SupplementApi (8-4)
      │
      ▼
[챗봇 답변 → 낭독 → 저장]
```

입력은 텍스트 채팅입니다. 음성 입력은 2차에 검토합니다.

- 추천을 한 번 내면 상담 하나가 끝납니다. 다음 입력은 새 상담으로 보고 대화 기록을 비웁니다.
- 추가 질문을 2번 했는데도 고를 항목이 없으면, 규칙 표의 고민 목록을 읽어 주고 그중에서 말해 달라고 안내합니다.
- 증상은 분명한데 규칙 표에 맞는 항목이 없으면(예: 무릎 통증인데 관절 항목이 없음) 가까운 항목으로 억지로 추천하지 않습니다. "아직 추천할 수 있는 항목이 없어요. 증상이 계속되거나 심하면 의사나 약사와 상담하세요"라고 안내하고, 표에 있는 증상만 추천합니다.
- **추천 전에 지병·복용 약을 묻습니다** (2026-10-30). 증상이 지병 때문일 수 있기 때문입니다(예: 손발 저림 → 당뇨병성 신경병증). 지병이나 약이 있다고 하면 답변 맨 앞에 고정 문구로 안내합니다: "말씀하신 지병(당뇨)과 드시는 약(메트포르민)이 있으시네요. 지금 증상이 지병 때문일 수도 있고, 영양제가 드시는 약과 맞지 않을 수도 있어요. 영양제를 드시기 전에 꼭 진료받는 의사나 약사에게 지금 증상을 말씀하고 상담하세요." 지병과 증상의 관련성은 AI가 판단하지 않습니다(진단 금지).
- 추천할 항목이 없으면(표 밖 증상만 있거나 건강과 관계없는 말) 지병·약은 묻지 않습니다.

### 8-3. 구성

```
com.medimate.recommend
├── RecommendEngine.java          // 고민 → 추천 성분 목록 (순수 자바, 네트워크 없음)
├── RecommendRule.java            // concern, ingredient, reason, source, caution, ingredientIds
├── SymptomAnalyzer.java          // 증상 파악 인터페이스 + Vocabulary(고를 수 있는 이름들). ViewModel은 이것만 참조한다
├── SymptomAnalysis.java          // 파악 결과 (8-6 받는 것)
├── ClaudeSymptomAnalyzer.java    // SymptomAnalyzer의 Claude 구현 (8-6)
├── SymptomWarning.java           // 병원 진료 안내 표 한 줄 (8-7)
├── KnowledgeRule.java            // 근거 규칙 한 줄 (8-8)
├── KnowledgeBase.java            // 근거 규칙·출처·용어 조회 (8-8)
└── KnowledgeMessages.java        // 근거 규칙으로 만드는 고정 틀 안내 문장 (8-8)

app/src/main/assets/supplement_rules.json   // 규칙 표 (팀이 작성, 출처 포함)
app/src/main/assets/symptom_warnings.json   // 병원 진료 안내 표 (8-7, 출처 포함)
app/src/main/assets/knowledge_rules.jsonl   // 근거 규칙 (8-8, 시작 자료 사본, 전문가 검토 전)
app/src/main/assets/knowledge_sources.json  // 근거 규칙의 출처 (8-8, 시작 자료 sources.json 사본)
app/src/main/assets/knowledge_terms.json    // 규칙 대상 id → 한국어 이름 (8-8)

com.medimate.ui.recommend
├── RecommendChatActivity      // 채팅 화면 (말풍선 목록 + 입력창), 봇 답변 낭독
├── RecommendChatViewModel     // 대화 상태 유지, 다음 행동 결정 (응급 → 추가 질문 → 지병·약 → 연결 확인 → 답변)
├── RecommendReply             // 답변 문장 조립 (순수 자바, 단위 테스트 대상)
└── ChatAdapter / ChatMessage
```

규칙 표 형식

```json
[
  {
    "concern": "건강 고민 이름",
    "ingredient": "추천 성분",
    "reason": "화면·낭독에 쓸 한 문장 설명",
    "source": "근거 출처 (기관, 문서명)",
    "caution": "섭취 시 주의사항 (공식 원문, 과다 섭취·상한섭취량 포함)",
    "cautionSource": "주의사항 근거 출처",
    "ingredientIds": ["근거 규칙(8-8)의 성분 id"]
  }
]
```

- `ingredientIds`: 근거 규칙과 맞춰 볼 성분 id입니다. 제형을 모르면 가능한 제형도 넣습니다(예: 칼슘 → `calcium`, `calcium_carbonate`. 탄산칼슘 규칙도 함께 봅니다).

**섭취 시 주의사항** (2026-10-30 추가): 추천 성분마다 답변에 "섭취 시 주의"로 보여 주고 읽어 줍니다. `cautionSource`가 비면 주의사항을 보여 주지 않습니다(규칙 자체는 유지).

| 출처 | 쓰는 내용 |
|---|---|
| 식약처 「건강기능식품의 기준 및 규격」 (제2026-43호, 식품안전나라 원료별 정보) | 원료별 "섭취 시 주의사항" 원문 |
| 보건복지부·한국영양학회 「2025 한국인 영양소 섭취기준」 | 성인 상한섭취량 (과다 섭취 기준) |
| 질병관리청 국가건강정보포털 「영양제」 | 과잉 섭취 시 증상 |

검토할 점:
- 루테인, 오메가3, 프로바이오틱스의 문구는 공전 PDF에서 텍스트를 추출한 것입니다. 괄호와 쉼표 위치를 손으로 맞췄으니 원본 PDF와 한 번 대조해야 합니다.
- 비타민B1은 상한섭취량이 정해져 있지 않아서 공전의 주의 문구만 넣었습니다.
- "비타민C를 빈속에 먹으면 속쓰림이 생길 수 있다"는 식약처·질병관리청·약학정보원에서 공식 문구를 찾지 못해 넣지 않았습니다. 하이닥 같은 2차 출처에만 있습니다.
- 스페인감초추출물의 글리시리진(고혈압 등) 주의는 언론 보도에만 있습니다. 식약처 인정 주의사항에는 없어서 넣지 않았습니다.

### 8-4. 제품 조회 — 식약처 건강기능식품 API

- 이전 버전이 쓰던 주소: `https://apis.data.go.kr/1471000/HtfsInfoService03/getHtfsItem01`
- **공공데이터포털에서 별도 활용신청이 필요합니다.** 인증키는 같은 `DATA_GO_KR_SERVICE_KEY`를 씁니다. 신청 후 발급 화면의 End Point와 다르면 발급 화면 값이 우선입니다.
- 응답 모델은 실제 응답을 보고 작성합니다.

### 8-5. 뼈대와 다듬기 구분

| 1차 뼈대 | 2차 다듬기 |
|---|---|
| 채팅 입력 + Claude 증상 파악·추가 질문 (고민 5~6개) | 고민 항목 확장, 대화 기록 저장, 음성 입력 |
| 규칙 표 최소 5개 + 챗봇 답변 + 낭독 | 규칙 표 전체 작성 (출처 포함) |
| — | 복용 약과의 상호작용 주의 표시 (8-8에서 시작 자료 규칙으로 디버그 빌드 시연 중, 전문가 검토 필요) |
| — | 건강기능식품 API로 실제 제품 표시 (활용신청 후 실제 응답으로 모델 작성) |
| — | Claude 호출을 서버(Firebase Functions)로 옮겨 앱에서 키 제거 (8-6) |
| — | 응급 증상 안내 (8-8에서 시작 자료 규칙으로 디버그 빌드 시연 중, 전문가 검토 필요) |

### 8-6. Claude 증상 파악 규격

**호출**

| 항목 | 값 |
|---|---|
| 라이브러리 | 공식 Java SDK `com.anthropic:anthropic-java` |
| 모델 | `claude-opus-5-5` |
| 생각 깊이 | `effort: low` (분류 작업이라 빠른 응답 우선) |
| 출력 | 구조화된 출력(`output_config.format`, JSON 스키마). 고민 이름·병원 안내·응급 분류·연결 대상은 목록을 `enum`으로 넣어 **표에 없는 이름은 나올 수 없게** 합니다 |
| 거절 대비 | `fallbacks: "default"` (베타 `server-side-fallback-2026-07-01`). 그래도 거절되면 "다시 말씀해 주세요"로 안내합니다 |
| 키 | `BuildConfig.CLAUDE_API_KEY` (3-3) |

**보내는 것**: 시스템 프롬프트(역할·규칙·목록) + 이번 상담의 대화. 고민 목록에는 이름과 함께 **그 고민이 다루는 범위**(규칙 표 `reason`을 이어 붙인 것, `RecommendEngine.concernScopes()`)를 넣어, 이름만 보고 넓혀 고르지 않게 합니다. 사용자 말은 `user`, 봇의 질문은 `assistant`로 넣습니다.

**받는 것**

```json
{
  "concerns": ["눈 건강", "피로"],
  "needs_more_info": false,
  "follow_up_question": "",
  "unmatched": false,
  "symptom_summary": "눈이 침침하고 오후에 쉽게 피곤함",
  "conditions": ["당뇨"],
  "medications": ["메트포르민"],
  "warnings": [],
  "triage": [],
  "links": [{ "raw_name": "메트포르민", "target_id": "metformin", "user_confirmed": false }]
}
```

| 필드 | 뜻 |
|---|---|
| `concerns` | 규칙 표의 고민 이름 중 해당하는 것 (없으면 빈 배열) |
| `needs_more_info` | 항목을 고르기에 정보가 부족하면 `true` |
| `follow_up_question` | `needs_more_info`가 `true`일 때 할 질문 한 개. 고령층이 알아듣기 쉬운 짧은 존댓말 |
| `unmatched` | 규칙 표 범위에 들지 않는 증상이 하나라도 있으면 `true`. 앱이 상담 안내 문구를 붙입니다 |
| `symptom_summary` | 사용자 말을 정리한 한 줄. 추천 기록 저장(D3)에 씁니다 |
| `conditions` | 사용자가 말한 지병 (말한 그대로, 추측 금지. 없거나 아직 안 물었으면 빈 배열) |
| `medications` | 사용자가 말한 복용 중인 약 (위와 같음). 나중에 내 약(D2)과 합칠 수 있습니다 |
| `warnings` | 병원 진료 안내 표(8-7)의 id |
| `triage` | 응급·진료 분류 id (8-8) |
| `links` | 사용자가 말한 약·지병 등과 근거 규칙 대상 id의 연결 (8-8). 모르면 `unclear`, 목록 밖이면 `not_listed` |

**시스템 프롬프트 규칙**

- 매 턴 가장 먼저 응급·진료 확인 목록에 해당하는지 봅니다.
- 고민 이름은 목록에 있는 것만 씁니다.
- 증상이 그 항목의 범위에 실제로 들어갈 때만 고릅니다. 부위가 가깝다고 넓혀 고르지 않습니다 (2026-10-28 수정: 속쓰림·위경련이 "장 건강"으로 분류되던 문제).
- 증상은 분명한데 맞는 항목이 없으면 더 묻지 않고 `unmatched: true`로 둡니다.
- 한 항목이라도 분명히 해당하면 더 묻지 않고 바로 고릅니다.
- 추가 질문은 증상(부위, 느낌, 언제부터)만 묻습니다. 진단·성분·제품·조언은 쓰지 않습니다.
- 지병·약 질문은 앱이 고정 문구로 합니다. Claude는 답에서 지병·약을 뽑기만 하고, "있다"고만 했으면 무엇인지 한 번 묻습니다.
- 약·지병 연결은 성분이 분명할 때만 합니다. 색·모양·브랜드만으로 짐작하지 않고, 정정하면 최신 내용을 따릅니다.

**보안**

- 앱에 키가 들어 있으면 APK를 분석해 꺼낼 수 있습니다. 1차는 시연용으로 앱에서 바로 부르고, 배포 전에 서버로 옮깁니다(8-5).
- 대화 본문은 로그에 남기지 않습니다. 실패 시 HTTP 코드와 오류 종류만 남깁니다.

### 8-7. 병원 진료 안내 (2026-10-30)

위경련처럼 심하거나 반복·지속되는 증상은 **영양제를 추천하되, 답변 맨 끝에 병원 진료 안내를 붙입니다.**

- 안내 문구에는 질환명이 들어가므로 **AI가 쓰지 않고**, `assets/symptom_warnings.json`의 고정 문구만 씁니다. 모든 줄에 근거 출처가 있어야 하고, 출처가 빈 줄은 읽을 때 버립니다.
- Claude는 사용자 증상이 표의 `symptoms`에 해당하는지만 판단해 `warnings`에 id를 넣습니다 (스키마 `enum`으로 표에 있는 id만 나옴).
- 가벼운 증상(가끔 식후 속쓰림, 더부룩함)에는 안내를 붙이지 않습니다.

```json
[
  {
    "id": "stomach_pain",
    "symptoms": "위경련, 반복되거나 계속되는 속쓰림, 반복되거나 계속되는 명치·윗배 통증",
    "message": "위경련이나 반복되는 속쓰림, 명치 통증은 위염, 위·십이지장 궤양, 위식도역류질환 같은 질환일 수 있으니 병원에 내원하여 진료를 받으시기 바랍니다.",
    "source": "질병관리청 국가건강정보포털 「위염」, 「위십이지장 궤양」, 「위식도역류질환」"
  }
]
```

**위 건강 규칙 (2026-10-30 추가)**: 매스틱검("위 불편감 개선에 도움을 줄 수 있음"), 스페인감초추출물("위 점막 내 헬리코박터균 증식을 억제하고 위 점막을 보호하여 위 건강에 도움을 줄 수 있음"). 둘 다 개별인정형 원료이고 **2026년 식약처 재평가 대상**(결과 연말 발표 예정)입니다. 문구는 언론 보도 인용이므로 원료 API(8-1)를 받으면 식약처 원문과 대조하고, 재평가에서 인정이 취소되면 규칙 표에서 뺍니다. 스페인감초추출물은 글리시리진 때문에 고혈압 등 주의가 필요해, 복용 약 대조를 확장할 때 우선 반영합니다.

### 8-8. 응급 안내와 상호작용·질환 확인 (2026-10-30, 디버그 빌드)

시작 자료(`supplement_chatbot_starter_v1/`, 합성 개발 자료)의 근거 규칙 24개를 써서, **매 턴 응급 신호를 먼저 확인**하고 **추천 성분을 사용자의 약·질환과 대조**합니다.

**사용 범위 (정책 P10)**

- 규칙은 모두 약사·의사 검토 전(`production_enabled=false`)입니다. 그 값은 바꾸지 않았고, **디버그 빌드에서만** 읽습니다(`BuildConfig.DEBUG`). 릴리스 빌드에서는 규칙을 읽지 않아 8-7까지의 동작만 합니다.
- 규칙을 쓴 답변에는 "약사·의사 검토 전 규칙(starter-2026-10-30)으로 만든 시험용 안내"라고 표시합니다.
- 앱에는 규칙·출처만 복사했습니다. 학습 대화·평가 정답(`eval_test_labels.jsonl` 등)은 앱에 넣지 않습니다.

**판단 순서 (정책 P09: 응급 > 진료 필요 > 알려진 위험 > 정보 부족 > 조건부 후보)**

| 단계 | 조건 | 앱 동작 |
|---|---|---|
| 응급 | `triage`가 R022(지속 흉통+숨참·식은땀), R023(말하기 힘든 호흡곤란)에 해당 | 추가 질문·추천 없이 바로 "지금 바로 119에 전화하세요…" 안내, 상담 종료 |
| 진료 우선 | `triage`가 R024(원인 모를 피로가 수주 지속)에 해당 | 영양제 추천 대신 진료 안내 |
| 연결 확인 | 추천 후보 성분에 규칙이 걸릴 수 있는 약·질환으로 연결됨 | "○○: 레보티록신(갑상선 호르몬제) — 맞나요?"로 한 번 확인 (정책 P02) |
| 확인 사항 | 확인된 연결 + 후보 성분에 규칙이 걸림 | 성분 아래에 "⚠ 성분 — 대상: 근거 요약 + 적용 한계 + 의사·약사 확인 또는 복용 간격 확인 (근거: 출처)" |
| 모름 | 성분을 모르는 약(`unclear`), 또는 확인받지 못한 연결 | "성분을 확인하지 못해 함께 드셔도 되는지 알 수 없어요. 약 봉투나 처방전을 약사에게…" (정책 P03) |
| 규칙 없음 | 목록 밖이거나 후보 성분과 관계없는 규칙만 있음 | "규칙이 없다는 것은 '함께 드셔도 안전하다'는 뜻이 아니에요" (정책 P03) |

- 안내 문장은 고정 틀에 규칙의 `evidence_summary_ko`, `applicability_limits_ko`, 출처 이름만 넣어 만듭니다(`KnowledgeMessages`). AI가 상호작용을 판단하거나 문장을 쓰지 않습니다.
- 약 이름 → 성분 연결은 Claude가 규칙 대상 목록 안에서만 하고(스키마 `enum`), 사용자 확인을 받은 연결만 규칙에 씁니다. 나중에 식약처 의약품 허가정보 API(시작 자료 S18)로 바꿀 수 있습니다(실제 응답 JSON을 받은 뒤).
- 현재 규칙 표 성분 중 규칙이 걸리는 것: 오메가3(와파린 R012), 마그네슘(신장기능 저하 R004, 일부 항생제 R005), 칼슘(탄산칼슘일 때 레보티록신 R003), 비타민D(티아지드계 이뇨제 R018), 프로바이오틱스(면역저하·중증질환 R016). 철분·칼륨·B12 등의 규칙은 규칙 표에 그 성분이 없어 아직 쓰이지 않습니다.

**확인한 것 (2026-10-30)**

- 단위 테스트: `RecommendReplyTest`(응급 → 119, 진료 우선 시 추천 없음, 와파린 확인 → 오메가3 확인 사항, 미확인 연결 → 모름, 레보티록신 → 칼슘 복용 간격, 규칙 없음 ≠ 안전, 릴리스 빌드에서 규칙 미사용), `ClaudeSymptomAnalyzerParseTest`(목록 밖 id는 `unclear`로).
- 실제 Claude 호출(PC, 앱과 같은 프롬프트·스키마): 흉통+숨참+식은땀 → 응급, 한 달째 원인 모를 피로 → 진료 우선, 와파린 → 확인 → 오메가3 R012, 씬지로이드 → 레보티록신 확인 → 칼슘 R003, "혈압약인데 이름 모름" → `unclear`.

**남은 일 (시작 자료 지시문 기준)**

- 문진 확장: 나이·임신수유·기존 영양제·알레르기·식사 제한, "없음/모름/미응답/확인됨" 구분, 2~3개씩 묻기, 임신·수유·소아는 전문가 확인으로 (정책 P02, P06)
- 확인한 건강정보를 사용자가 보고 수정하는 화면, 오류 시 다시 보내기
- Claude 키를 서버로 옮기기 (8-5)
- 시작 자료 평가 세트(`eval_test_inputs.jsonl` + `eval_test_labels.jsonl`)로 채점. 정답은 채점에만 씁니다
- 규칙별 약사·의사 검토 후 `production_enabled` 갱신 (검토자·검토일·재검토 기한 기록)

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
- 개인정보처리방침에 수집 항목과 처리 위탁(Google Firebase, OpenAI, Anthropic)을 명시합니다.

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
| A4 | 촬영·갤러리 → OCR (4-4). 자르기는 생략 | 실기기에서 찍은 약봉투 글자가 화면에 표시 | 실기기 확인 대기 |
| A5 | GPT 정리 (5-5) → e약은요 조회 → 약 목록 화면. 이름 정규화는 용량 제거만, 여러 건이면 첫 번째 사용 | 약 목록에 공식 효능이 표시 | 코드 완료, OpenAI 키 넣고 확인 대기 |
| A6 | 약 상세 + TTS 낭독 | 항목을 누르면 읽어 줌 | 완료 |
| B1 | Firebase 연결 (7-2), Splash → Login → Main 화면 틀 | 앱 실행 시 로그인 화면이 뜨고, Main에 버튼 네 개 | 완료 |
| B2 | 구글 로그인 (7-3) | 로그인 후 Main 진입, 재실행 시 로그인 유지 | 내부 기능 + Login 버튼 연결, 실기기 확인 대기 |
| B3 | 전화번호 로그인 (7-4), 테스트용 번호 | 테스트 번호로 로그인 | 내부 기능만 완료(테스트 번호 확인됨), 번호·인증번호 입력 화면 없음 |
| B4 | 카카오 로그인 (7-5), SDK 로그인까지 | 카카오 계정으로 로그인 후 Main 진입 | SDK 로그인 구현, 카카오 콘솔에 키 해시 등록 후 실기기 확인 대기 |
| B5 | 로그아웃, 내 정보 화면 틀 | 로그아웃하면 Login으로 복귀 | |
| C1 | 챗봇 입력 화면 (8-3) — 증상을 채팅으로 입력, Claude가 증상 파악·추가 질문 (8-6) | 모호한 증상엔 추가 질문, 답하면 규칙 표의 고민으로 분류됨 | 코드 완료, Claude 요청 PC에서 확인됨, 에뮬레이터 확인 대기 |
| C2 | 규칙 표(최소 5개) + 추천 엔진 + 챗봇 답변 + 낭독 (8-5) | 분류된 고민에 맞는 성분이 표시·낭독 | 완료 (규칙 표 12개 + 섭취 시 주의사항, 출처는 팀 검토 필요) |
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
| C 추천 | 규칙 표 전체 + 출처 · 복용 약 상호작용 주의 · 건강기능식품 API 제품 표시 (8-4) · Claude 호출 서버 이전 · 응급 증상 안내 (8-5) |
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
