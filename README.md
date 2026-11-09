# MediMate

| 폴더 | 내용 | 언어 |
|---|---|---|
| [app/](app/) | Android 앱. 서버 호출 공용 코드와 디버그 빌드 전용 시험 화면(알약 식별, 약봉투 인식, 기기에 저장하는 시험용 복약 일정) | Java |
| [pill-classifier/](pill-classifier/) | **AI 파트.** 알약 식별 알고리즘, Claude 비전 보조, 내부 추론 서버 — [설명](pill-classifier/README.md) | Python |
| [backend/](backend/) | **알약·약봉투 백엔드.** 사진 업로드, 알약 식별, 약봉투 인식(일정 초안), 약 상세 정보 — [설명](backend/README.md) | Java |
| [docs/](docs/) | 요구사항·설계·API 명세 | |

```
[app] ──HTTPS──▶ [백엔드 API 서버] ──내부망──▶ [pill-classifier 추론 서버 :8000] ──▶ Claude Fable 5.1
```

이 저장소의 백엔드는 알약 식별, 약봉투 인식, 약 상세 API를 제공합니다. 로그인은 Firebase 토큰을 확인만 하고(설정했을 때), 실제 앱 화면과 알람은 팀원 담당이라 여기에는 시험용 화면만 있습니다. 백엔드가 추론 서버를 부르는 방법은 [pill-classifier/README.md](pill-classifier/README.md)의 "백엔드 연동"에 있습니다.

## 데이터는 어디에 저장하나

| 저장할 것 | 어디에 |
|---|---|
| 프로필, 내 약, 복약 일정, 복용 기록 (사용자 데이터) | **Firestore** (앱이 기기 DB에 먼저 저장하고 동기화) |
| 식약처 약 설명 캐시, 알약 DB (모든 사용자 공용) | 백엔드 DB / 추론 서버 |
| 약봉투 인식 중간 결과, 올린 사진 (임시) | 백엔드 (사진은 7일 뒤 자동 삭제) |

백엔드는 복약 일정을 저장하지 않습니다. 약봉투 확정은 일정 초안(`plans`)만 돌려주고, 앱이 Firestore에 저장합니다. "이미 먹는 약" 확인이 필요한 요청(알약 식별, 약봉투 확정)에는 앱이 Firestore의 복용 중인 약을 `currentMedications`로 함께 보냅니다. 결정 배경은 [기술설계서 ADR-10](docs/02_기술설계서.md)에 있습니다.

## 앱의 알약 식별 흐름

```
홈 ─▶ 촬영 화면 ─────────▶ 결과 화면 ─────────────────▶ 선택 완료
      앞면(필수)·뒷면(선택)    업로드 → 식별 요청 → 폴링        약 정보 + 복용 계획 초안
      카메라 또는 앨범          후보 목록 (일치도, 내 약 표시)
      힌트: 모양·색·글자        ├ 다시 찍기 (RETAKE)
                               └ 직접 찾기 (MANUAL_SEARCH) ─▶ 검색 화면
```

| 파일 | 역할 |
|---|---|
| `pill/PillCaptureActivity` | 촬영·앨범 선택. 고르는 즉시 회전 보정과 1600px 축소를 거쳐 JPEG로 저장 |
| `pill/PillResultActivity`, `PillResultViewModel` | 업로드 → `POST /pill-identification` → `pollAfterMs` 간격으로 폴링 → 후보 선택 |
| `pill/PillSearchActivity` | `GET /pill-identification/search` (커서로 더 보기) |
| `net/JobPoller`, `HandlerScheduler` | 폴링 로직 (시간 초과, 일시적 네트워크 오류 재시도, 취소). 알약·약봉투 공용 |
| `net/ApiClient`, `MedimateApi` | Retrofit. 공통 래퍼(`success/data/error`)를 풀고 에러는 `ApiException`으로 |
| `ui/Speaker` | 음성 안내. TalkBack이 켜져 있으면 TTS 대신 live region으로 읽게 해서 목소리가 겹치지 않음 |

## 화면 담당이 쓰는 공용 코드 (`app/src/main`)

화면은 `MedimateApi`를 직접 부르지 말고 아래 저장소 클래스를 씁니다. 모든 메서드는 네트워크를 쓰므로 백그라운드 스레드에서 부르고, 결과 기다리기는 `JobPoller`로 합니다.

| 클래스 | 하는 일 | 쓰는 화면 (MM-JE) |
|---|---|---|
| `pill/PillRepository` | 알약 사진 올리기 + 식별 요청(`requestIdentification`), 결과 조회(`getJob`), 후보 선택(`select`), 직접 찾기(`search`), **약 상세(`getMedication`)** | 알약 촬영 → 분석 중 → (결과 화면) → 약 상세 |
| `bag/BagRepository` | 약봉투 사진 올리기 + 인식 요청(`requestRecognition`), 결과 조회(`getJob`), 약 고치기(`patchItem`), 확정해서 일정 초안 받기(`confirm`) | 약봉투 촬영 → 분석 결과 목록 → 약 상세 |
| `plan/model/PlanDraft` | 일정 초안. 앱(JM)이 이것을 자기 일정 모델로 바꿔 Firestore에 저장합니다 | (일정 저장) |
| `plan/model/CurrentMedication` | 요청에 함께 보내는 "지금 먹는 약" (`itemSeq` 또는 `name`) | |
| `bag/model`, `drug/model`, `pill/model` | 서버 응답 모델. 필드 이름은 `backend/src/main/resources/static/openapi.json`과 같습니다 | |

JE 화면에 연결하는 예:

| 화면 요소 | 값 |
|---|---|
| 분석 결과 목록: 약 이름 / 한 줄 정보 | `job.visibleItems()`의 `item.displayName()` / `item.displayText` |
| 분석 결과: 총 N개 | `job.visibleItems().size()` |
| 약 상세 카드 (약 설명·주의사항·복용방법·보관방법) | 약봉투: `item.sections`, 알약: `getMedication(itemSeq).sections`. 카드 제목은 `section.title`, 본문은 `section.text`(없으면 `section.ttsText`의 안내 문장), 읽어 줄 문장은 `section.ttsText` |
| 분석 중 → 실패 | 약봉투: `job.status == FAILED`이면 `job.error.ttsMessage`. 알약: `job.fallbackAction`(RETAKE / MANUAL_SEARCH) |
| 분석 취소 버튼 | `poller.cancel()` |
| 일정으로 저장 | `confirm(jobId, BagConfirmRequest.acceptAll(Firestore의 복용 중인 약))` → `result.plans`를 Firestore에 저장. "알람으로 설정할까요?"에 아니요면 `notificationEnabled=false`로 저장 |

```java
// 약봉투 예 (ViewModel 안)
BagRepository repo = new BagRepository(ApiClient.get());
JobPoller<BagJob> poller = new JobPoller<>(repo::getJob, BagRepository::isFinished, io, new HandlerScheduler(), 120_000);
io.execute(() -> {
    try {
        JobAccepted accepted = repo.requestRecognition(jpeg);   // jpeg: ImageCompressor로 줄인 사진
        main.post(() -> poller.start(accepted.jobId, accepted.pollAfterMs, listener)); // onDone(BagJob job)
    } catch (ApiException e) {
        main.post(() -> speaker.say(statusView, e.error().ttsMessage)); // 업로드 실패, 흐린 사진 등
    }
});
```

**접근성:** 버튼은 최소 56dp, 본문은 18sp 이상입니다. 모든 버튼과 이미지에 설명이 있고, 제목은 heading으로 지정했습니다. 서버의 `ttsScript`와 `ttsMessage`를 그대로 읽어 줍니다.

## 앱 실행하기

**시험용 화면:** 지금 앱의 화면(홈, 알약 식별·검색, 약봉투 시험, 내 복약 일정)과 시험용 일정 저장소(`debugtools/LocalPlans`, 기기 안에 저장 — 실제 앱에서는 Firestore가 대신함)는 모두 디버그 빌드 전용 폴더 `app/src/debug`(테스트는 `app/src/testDebug`)에 있습니다. 실제 화면은 화면 담당 팀원이 만들고, 합칠 때는 이 두 폴더를 빼면 됩니다. 단, `src/debug/AndroidManifest.xml`의 `ACCESS_LOCAL_NETWORK` 권한과 `src/debug/res/xml/network_security_config.xml`(개발 서버용 평문 HTTP 허용)은 남겨야 debug 빌드가 PC의 백엔드에 접속합니다. 서버 호출(`net`), 저장소(`PillRepository`, `BagRepository`), 데이터 모델, 사진 압축, 음성 안내 같은 공용 코드는 `app/src/main`에 있습니다.

앱은 백엔드가 떠 있어야 동작합니다. `pill-classifier/run_server.bat`과 `backend/run_backend.bat`을 각각 켜 둡니다.

**에뮬레이터**
1. Android Studio에서 `D:\medimate`를 열고, Device Manager에서 가상 기기를 하나 만듭니다.
2. ▶ Run을 누릅니다. 에뮬레이터에서 `10.0.2.2:8080`이 PC의 백엔드로 연결됩니다.
3. 시험용 사진: `pill-classifier/data/demo/photos/*.jpg`(저장소에는 없음. `python scripts/make_demo_data.py`로 만듭니다)를 에뮬레이터 창에 끌어다 놓으면 기기에 저장됩니다. 앱에서 **사진 고르기**로 선택하면 됩니다.

**실제 휴대폰** (PC와 같은 와이파이)
1. `gradle.properties`에 `medimate.apiBaseUrl=http://<PC의 IP>:8080/`을 추가합니다. 끝의 `/`는 필수입니다. PC의 IP는 `ipconfig`로 확인합니다.
2. Windows 방화벽이 8080 포트(Java) 수신을 허용해야 합니다.
3. 휴대폰에서 USB 디버깅을 켜고 ▶ Run을 누릅니다.

평문 HTTP는 **debug 빌드에서만** 허용됩니다(`src/debug/res/xml/network_security_config.xml`). 릴리스 빌드는 HTTPS만 씁니다.

**Android 17 이상 기기(에뮬레이터 포함):** targetSdk 37 앱이 사설 주소(10.x, 192.168.x 등)에 접속하려면 `ACCESS_LOCAL_NETWORK` 런타임 권한이 필요합니다. 권한이 없으면 연결이 시간 초과로 실패합니다. debug 빌드는 첫 실행 때 "근처 기기" 권한을 요청하니 **허용**을 누르세요. 이 권한은 debug 매니페스트(`src/debug/AndroidManifest.xml`)에만 있고, 공인 주소의 운영 서버를 쓰는 릴리스 빌드에는 들어가지 않습니다.

## 테스트

```powershell
$env:JAVA_HOME = "D:\AndroidStudio\jbr"
.\gradlew.bat :app:testDebugUnitTest
```

- `ApiContractTest`: 저장해 둔 응답 예시(`app/src/test/resources/fixtures`)로 요청 형식과 응답 해석을 검증합니다. 백엔드 응답 형식이 바뀌면 예시도 함께 바꿔야 합니다.
- `BagApiContractTest`: 약봉투 인식(확정 결과의 일정 초안, `currentMedications`로 뺀 약, 이미 먹는 약 409 포함)과 약 상세 API를 같은 방식으로 검증합니다. 응답 예시(`bag_*`, `medication_200.json`)는 백엔드 `RecognitionApiTest` 환경(가짜 추론 서버)에서 받은 실제 응답입니다. 이 테스트는 debug 화면 코드에 의존하지 않아서 `src/test`에 있습니다.
- `JobPollerTest`: 폴링 종료, 시간 초과, 재시도, 취소를 검증합니다.
- `LiveBackendTest`: 실행 중인 서버에 실제로 요청합니다. `$env:MEDIMATE_LIVE_URL = "http://localhost:8080/"`를 설정했을 때만 실행됩니다.

## 남은 일

- 로그인(Firebase) 연동: `ApiClient`에 `Authorization: Bearer <ID 토큰>` 헤더 추가 (`TODO(인증)`)
- 일정 초안(`PlanDraft`, 알약 선택의 `draftPlan`)을 JM의 일정 모델로 바꿔 Firestore에 저장하는 코드 (앱 담당과 합칠 때)
- 접근성 설정(`/users/me/accessibility`)의 TTS 속도·음높이 반영 (`TODO(설정)`)
- 촬영 중 실시간 안내(흔들림, 알약 위치): 지금은 기본 카메라 앱을 쓰며, CameraX로 바꾸면 가능

## 데이터 출처

AI 허브 「경구약제 이미지 데이터」, 식약처 공공데이터, 미국 NLM 알약 사진을 썼습니다. 출처 표기는 [docs/README.md](docs/README.md#데이터-출처)에 있습니다.
