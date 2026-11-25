# 알약·약봉투 백엔드

앱이 찍은 사진을 받아 [pill-classifier 추론 서버](../pill-classifier/README.md)에 넘기고, 결과를 API 명세 형식으로 돌려줍니다.
SwaggerHub `kookmin-c88/medimate-api 1.0.0` 중 **알약 식별**, **약봉투 인식**, **약 상세 정보**를 구현했습니다.

## 데이터는 어디에 저장하나

| 저장할 것 | 어디에 |
|---|---|
| 프로필, 내 약, 복약 일정, 복용 기록 (사용자 데이터) | **Firestore** (앱이 기기 DB에 먼저 저장하고 동기화) |
| 식약처 약 설명 캐시, 알약 DB (모든 사용자 공용) | 이 서버의 DB / 추론 서버 |
| 약봉투 인식 중간 결과, 올린 사진 (임시) | 이 서버 (작업은 메모리에 24시간, 사진은 7일 뒤 자동 삭제) |

서버는 **복약 일정을 저장하지 않습니다.** 약봉투를 확정하면 일정 초안(`plans`)만 돌려주고, 앱이 그것을 Firestore에 저장하고 알람을 만듭니다.
그래서 "이미 먹는 약"인지는 서버가 알 수 없고, 앱이 요청에 `currentMedications`(지금 먹는 약의 `itemSeq` 또는 `name`)를 함께 보냅니다.

```
[앱] ──▶ [이 서버 :8080] ──▶ [추론 서버 :8000] ──▶ CLOVA OCR (약봉투 글자)
              │                     │               └▶ Claude (알약 관찰, 약봉투 정리)
              │                     └ 식약처 DB (후보, 품목 대조)
              └──▶ 식약처 e약은요 (약 설명)
```

## 실행

창 두 개에서 각각 켭니다. Java 21 이상이 필요합니다.

```
pill-classifier\run_server.bat
backend\run_backend.bat
```

`http://localhost:8080/docs`에서 API를 바로 시험할 수 있습니다.
에뮬레이터의 앱은 설정 없이 `10.0.2.2:8080`으로 연결됩니다.

**키 파일** (모두 `pill-classifier` 폴더, Git에 올라가지 않음)

| 파일 | 내용 | 없으면 |
|---|---|---|
| `.anthropic_key` | Claude API 키 한 줄 | 알약은 CV만으로 식별, 약봉투는 인식 불가 |
| `.clova_ocr` | `CLOVA_OCR_INVOKE_URL=...`과 `CLOVA_OCR_SECRET=...` 두 줄 | 앱이 글자(`ocrRawText`)를 보낼 때만 약봉투 인식 가능 |
| `.mfds_service_key` | 공공데이터포털 인증키 | 약 설명(e약은요) 없이 응답 |

## API

| 요청 | 하는 일 |
|---|---|
| `POST /files/images` | 사진 업로드. 방향을 바로잡고 위치 정보를 지운 뒤 품질을 검사합니다. |
| `GET /files/images/{id}/content` | 10분짜리 서명 링크로 사진을 받습니다. |
| `POST /pill-identification` | 알약 식별을 시작합니다. 바로 `jobId`를 돌려줍니다. `currentMedications`를 보내면 그 약인 후보에 `isInMyMedications`가 켜집니다. |
| `GET /pill-identification/{jobId}` | 식별 결과를 조회합니다. |
| `POST /pill-identification/{jobId}/select` | 사용자가 고른 약을 확정하고 약 정보와 일정 초안(`draftPlan`)을 돌려줍니다. 내 약에 추가는 앱이 Firestore에 합니다. |
| `GET /pill-identification/search` | 모양, 색, 각인, 이름으로 직접 찾습니다. |
| `POST /recognition/medication-bags` | 약봉투 인식을 시작합니다. |
| `GET /recognition/jobs/{jobId}` | 약봉투에서 읽은 약 목록을 조회합니다. |
| `PATCH /recognition/jobs/{jobId}/items/{itemId}` | 사용자가 약을 확인하거나 고칩니다. |
| `POST /recognition/jobs/{jobId}/confirm` | 목록을 확정하고, 약마다 복약 일정 초안을 돌려줍니다(저장하지 않음). |
| `GET /medications/{itemSeq}` | 약 상세 정보. 약 설명·주의사항·복용방법·보관방법(있으면 함께 먹으면 안 되는 것·부작용) 버튼용 `sections`를 돌려줍니다. |
| `GET /medications/{itemSeq}/tts-script` | 약 정보를 읽어 줄 문장 |

## 약봉투 기능의 앱 흐름 (화면·알람 담당용)

1. **촬영과 업로드:** 약봉투를 찍어 `POST /files/images`(`purpose=MEDICATION_BAG`)로 올립니다.
2. **인식 요청:** `POST /recognition/medication-bags`에 `fileId`를 보내고, `jobId`를 받습니다.
3. **결과 기다리기:** `GET /recognition/jobs/{jobId}`를 2초마다 부릅니다. 보통 10~30초 걸립니다.
   - `status`가 `FAILED`이면 `error.ttsMessage`를 읽어 주고 다시 찍게 합니다.
   - `COMPLETED`나 `PARTIAL`이면 `summaryTtsScript`를 읽어 주고 `items`를 목록으로 보여 줍니다.
4. **확인과 수정 (선택):** 약마다 확인받지 않으려면 이 단계를 건너뛰고 5번에서 `acceptAll`을 씁니다. 확인받는 경우 `needsReview: true`인 약은 `reviewReasons`를 보여 주고 확인을 받습니다.
   - 맞게 들었으면 `PATCH .../items/{itemId}`에 빈 본문 `{}`을 보냅니다.
   - 다른 약이면 `candidates`나 수동 검색에서 고른 `itemSeq`를 보냅니다.
   - 시간·용량·일수를 고치거나, `excluded: true`로 목록에서 뺄 수 있습니다.
5. **확정:** `POST .../confirm`을 보냅니다. Firestore에 있는 복용 중인 약을 `currentMedications`로 함께 보냅니다. 서버는 약마다 일정 초안(`plans`)과 화면용 요약(`summaries`, 같은 순서, `itemId`로 목록과 연결)을 돌려주며 **아무것도 저장하지 않습니다.**
   - **한꺼번에 확정 (`{"acceptAll": true}`, 시험 화면이 쓰는 방식):** 확인 없이 확정합니다. 복용량·횟수·일수를 읽지 못한 약, `currentMedications`에 있는 약, 봉투 안에서 두 번 나온 약은 빼며, 뺀 약은 `skippedItems`(이름과 이유)에 있습니다. 남는 약이 없으면 422(`MM-RCG-4223`)이고 `error.details`에 약별 이유가 있습니다.
   - 아래 두 가지는 `acceptAll` 없이 보냈을 때입니다.
   - 확인이 필요한 약이 남아 있으면 409(`MM-RCG-4091`)이고, `error.details`에 약별 이유가 있습니다.
   - `currentMedications`에 있는 약(또는 봉투 안에서 같은 약이 두 번)이 있으면 409(`MM-RCG-4094`)이고, `error.details[].field`가 그 약의 `itemId`입니다. 기존 일정을 끝내거나 그 약을 목록에서 빼고 다시 확정합니다.
6. **알람 묻기와 저장:** 확정 응답의 `ttsScript`를 읽어 줍니다. 마지막 문장은 `alarmQuestion`("알람으로 설정할까요?")입니다.
   - 답과 상관없이 `plans`를 Firestore 일정으로 저장합니다. "아니요"면 `notificationEnabled`를 false로 바꿔 저장합니다.
   - "예"면 약마다 `startDate`~`endDate` 동안 `timeSlots`의 시각에 알람을 설정합니다.
7. **다시 설치하거나 폰을 바꿨을 때:** 앱이 Firestore에서 일정을 받아 알람을 다시 만듭니다(서버와 무관).

**기본 알림 시각:** 아침 08:00, 점심 12:30, 저녁 18:30, 자기 전 22:00입니다. 확정할 때 `timeSlotOverrides`로 바꿀 수 있습니다.

**안전 원칙:** 약봉투를 잘못 읽으면 잘못된 시간과 용량으로 알림이 갑니다. 그래서 서버는 근거가 부족한 값을 비웁니다. 기본 확정은 사용자가 확인하기 전에는 하지 않고, `acceptAll` 확정은 값이 빈 약을 일정에서 뺍니다. 다만 `acceptAll`에서는 약 이름을 잘못 읽은 경우까지 걸러지지는 않습니다.

## 로그인 (Firebase)

`FIREBASE_PROJECT_ID`를 설정하면 위 API는 Firebase 로그인 토큰이 있어야 쓸 수 있습니다. 서명 링크로 여는 사진과 `/docs`는 예외입니다.

- **앱:** 요청마다 `Authorization: Bearer <ID 토큰>` 헤더를 붙입니다. 토큰은 `FirebaseUser.getIdToken(false)`로 받습니다.
- **서버:** 토큰을 Google 공개키로 검증하고, 다른 사용자의 작업 결과는 403으로 막습니다.
- **설정하지 않으면:** 로그인 확인을 하지 않습니다. 혼자 시험할 때 씁니다.

## 환경변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `PILL_BASE_URL` | `http://localhost:8000` | 추론 서버 주소 |
| `PILL_INTERNAL_KEY` | 없음 | 추론 서버에 같은 값을 설정했을 때만 필요합니다. |
| `FIREBASE_PROJECT_ID` | 없음 | 설정하면 로그인 토큰을 확인합니다. |
| `MFDS_SERVICE_KEY` | 없음 | 비우면 `../pill-classifier/.mfds_service_key`를 읽습니다. |
| `MEDIMATE_STORAGE_DIR` | `./data/files` | 사진 보관 폴더. 7일 뒤 자동 삭제합니다. |
| `MEDIMATE_DB_URL` | `./data/medimate` 파일 H2 | 공용 데이터 DB(식약처 약 설명 캐시). 운영은 `jdbc:postgresql://...`을 넣습니다. |
| `MEDIMATE_DB_USER`, `MEDIMATE_DB_PASSWORD` | `sa`, 없음 | DB 계정 |
| `MEDIMATE_BIND` | `127.0.0.1` | 실제 폰으로 시험할 때만 `0.0.0.0`으로 엽니다. |
| `MEDIMATE_DAILY_PILL` | `50` | 사용자(또는 IP)당 하루 알약 식별 횟수 |
| `MEDIMATE_DAILY_RECOGNITION` | `30` | 사용자(또는 IP)당 하루 약봉투 인식 횟수 |
| `PORT` | `8080` | 포트 |

## 한계

- **서버 한 대 기준입니다.** 작업과 사용량 제한은 메모리에 있어 다시 켜면 사라집니다. 사진은 디스크에 남습니다.
- **사용자 데이터는 보관하지 않습니다.** 일정·복용 기록은 앱(Firestore)에 있으므로 `/medication-plans`, `/doses`, `/schedules` API가 없습니다. 예전에 만든 일정 표(`medication_plans`)는 `V3__drop_medication_plans.sql`이 지웁니다.
- **"이미 먹는 약" 확인은 앱이 보낸 목록만큼만 정확합니다.** `currentMedications`를 보내지 않으면 확인하지 않습니다.
- **PostgreSQL은 시험하지 못했습니다.** 이 PC에 없어서 H2(PostgreSQL 호환 모드)로만 시험했습니다.
- **알약 선택의 `draftPlan`은 고정된 기본값입니다.** 하루 1번 아침 식후 1정이며, 처방과 무관합니다.
- **약봉투 약 대조는 낱알식별 DB 기준입니다.** 시럽, 가루약처럼 DB에 없는 약은 "확인 필요"로 나오며, 사용자가 확인하면 봉투의 이름 그대로 일정을 만듭니다.
- **사진 소유자는 확인하지 않습니다.** `fileId`는 추측할 수 없는 값이지만, 로그인을 켜도 다른 사람의 `fileId`로 식별을 요청하는 것은 막지 않습니다.
- **흐림 검사는 심하게 흐린 사진만 거절합니다.**

## 테스트

```
gradlew.bat test
```

- `PillApiTest`, `RecognitionApiTest`: 가짜 추론 서버로 전체 API를 검사하고, 응답을 `static/openapi.json`과 대조합니다.
- `FirebaseAuthTest`: 테스트용 키로 만든 토큰으로 로그인 확인을 검사합니다.
- `ImageQualityPhotosTest`: `pill-classifier/data`의 실제 사진으로 품질 기준을 검증합니다. 사진이 없으면 건너뜁니다.
