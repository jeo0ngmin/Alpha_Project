# MediMate API 명세서

| 항목 | 내용 |
|---|---|
| 버전 | v1.0 (2026-09-27) |
| Base URL | `https://api.<도메인>/api/v1` |
| 형식 | JSON (UTF-8), 이미지 업로드는 `multipart/form-data` |
| 시간 | ISO-8601, 시간대 포함 (`2026-09-27T08:00:00+09:00`) |

---

## 1. 공통

### 1.1 인증
`/auth/*`를 뺀 모든 요청에 서버가 발급한 액세스 토큰(§2)을 붙인다. 토큰이 없거나 만료되면 `401`이며, 앱은 `POST /auth/refresh`로 토큰을 갱신한 뒤 원래 요청을 한 번 다시 보낸다.

```
Authorization: Bearer <accessToken>
X-Request-Id: <UUID, 선택 – 없으면 서버가 생성해 응답 헤더로 돌려줌>
```

### 1.2 페이지네이션
목록 API는 `?cursor=<opaque>&size=20`, 응답에 `nextCursor`(없으면 `null`).

### 1.3 멱등성
생성 API(`POST /scans/*`, `POST /dose-events/sync`, `POST /auth/phone/otp`)는 `Idempotency-Key` 헤더를 받는다. 같은 키로 24시간 안에 다시 오면 처음 응답을 돌려준다(네트워크 재전송 대비).

### 1.4 오류 응답

```json
{
  "code": "OCR_LOW_QUALITY",
  "message": "average OCR confidence below threshold",
  "speechMessage": "사진이 흐립니다. 밝은 곳에서 흔들리지 않게 다시 찍어 주세요.",
  "retryable": true,
  "requestId": "4f1c..."
}
```

`speechMessage`는 앱이 그대로 TTS로 읽는다.

| HTTP | code | 의미 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 요청 형식 오류 |
| 400 | `IMAGE_INVALID` | 이미지 형식·크기(10MB) 오류 |
| 401 | `UNAUTHORIZED` | 액세스 토큰 없음·만료 |
| 401 | `AUTH_INVALID_ID_TOKEN` | Google·카카오 ID 토큰 검증 실패 |
| 401 | `AUTH_REFRESH_INVALID` | 갱신 토큰 만료·폐기·재사용 감지 → 다시 로그인 |
| 400 | `AUTH_OTP_MISMATCH` / `AUTH_OTP_EXPIRED` | 인증번호 불일치 / 만료 |
| 429 | `AUTH_OTP_TOO_MANY` | 인증번호 발송·확인 횟수 초과 |
| 502 | `AUTH_OTP_SEND_FAILED` | 문자 발송 실패(SENS 장애) |
| 409 | `AUTH_IDENTITY_IN_USE` | 연결하려는 로그인 수단을 데이터가 있는 다른 계정이 쓰고 있음 |
| 403 | `FORBIDDEN` | 권한 없음(다른 사용자의 리소스 등) |
| 404 | `NOT_FOUND` | 리소스 없음 |
| 409 | `CONFLICT` | 상태 충돌(이미 확인된 scan 등) |
| 429 | `DAILY_LIMIT_EXCEEDED` / `RATE_LIMITED` | 일일 인식 상한 / 요청 과다 |
| 502 | `OCR_UNAVAILABLE` / `AI_UNAVAILABLE` / `EXTERNAL_API_ERROR` | 외부 API 장애 |
| 500 | `INTERNAL_ERROR` | 서버 오류 |

인식 작업 자체의 실패(`OCR_NO_TEXT`, `AI_REFUSED` 등)는 `GET /scans/{id}` 응답의 `status=FAILED`, `error` 필드로 전달한다.

---

## 2. 인증·계정

로그인 성공 응답(Google·카카오·전화번호·계정 연결 공통):
```json
{
  "accessToken": "eyJhbGciOiJFUzI1NiIs...",
  "accessTokenExpiresIn": 1800,
  "refreshToken": "rt_9f2c...",
  "refreshTokenExpiresIn": 7776000,
  "isNewUser": false,
  "user": { "id": 12, "displayName": "김할머니", "loginProviders": ["KAKAO", "PHONE"] }
}
```
`isNewUser=true`이면 앱은 "예전에 쓰신 적이 있나요?" 단계로 간다. `false`이면 `GET /sync`로 전체 데이터를 받아 복원한다.

### `POST /auth/nonce`
Google·카카오 로그인 전에 호출. 응답 `{ "nonce": "n_7Qx...", "expiresIn": 300 }` (1회용). 앱은 이 값을 로그인 요청의 nonce로 넣는다.

### `POST /auth/google`
```json
{ "idToken": "<Credential Manager가 준 Google ID 토큰>", "nonce": "n_7Qx...", "deviceName": "SM-A536N" }
```
응답 `200`: 로그인 성공 응답.

### `POST /auth/kakao`
```json
{ "idToken": "<Kakao SDK가 준 OpenID Connect ID 토큰>", "nonce": "n_7Qx...", "deviceName": "SM-A536N" }
```
응답 `200`: 로그인 성공 응답.

### `POST /auth/phone/otp` – 인증번호 발송
```json
{ "phoneNumber": "010-1234-5678", "appHash": "FA+9qCX9VSu" }
```
응답 `202`: `{ "expiresInSec": 180, "resendAfterSec": 60 }`
오류: `400 VALIDATION_FAILED`(번호 형식), `429 AUTH_OTP_TOO_MANY`.

### `POST /auth/phone/verify` – 인증번호 확인·로그인
```json
{ "phoneNumber": "010-1234-5678", "code": "123456", "deviceName": "SM-A536N" }
```
응답 `200`: 로그인 성공 응답. 오류: `AUTH_OTP_MISMATCH`(남은 횟수 `remainingAttempts` 포함), `AUTH_OTP_EXPIRED`, `AUTH_OTP_TOO_MANY`.

### `POST /auth/refresh`
```json
{ "refreshToken": "rt_9f2c..." }
```
응답 `200`: 새 `accessToken`과 새 `refreshToken`(이전 토큰은 즉시 무효). 오류: `401 AUTH_REFRESH_INVALID`.

### `POST /auth/logout`
`{ "refreshToken": "rt_9f2c..." }` → 해당 기기 토큰 폐기, `204`.

### `GET /users/me/identities`
```json
{ "identities": [
  { "id": 3, "provider": "KAKAO", "createdAt": "2026-09-27T09:00:00+09:00", "lastLoginAt": "2026-09-27T09:00:00+09:00" },
  { "id": 4, "provider": "PHONE", "maskedPhone": "010-****-5678", "createdAt": "..." }
] }
```

### `POST /users/me/identities/google` · `/kakao` · `/phone` – 로그인 수단 추가
본문은 각 로그인 API와 같다(전화번호는 먼저 `/auth/phone/otp`로 발송).
- 아무 계정에도 없는 수단 → 현재 계정에 연결, `200` + 로그인 성공 응답(같은 계정)
- 다른 계정에 있고 현재 계정이 비어 있음 → 현재 계정의 수단을 기존 계정으로 옮기고 빈 계정 삭제, `200` + **기존 계정**의 로그인 성공 응답(`"merged": true`)
- 다른 계정에 있고 현재 계정에 데이터가 있음 → `409 AUTH_IDENTITY_IN_USE`

### `DELETE /users/me/identities/{id}`
`204`. 마지막 남은 수단이면 `409 CONFLICT`.

---

## 3. 사용자

### `PUT /users/me` – 프로필 설정 (첫 로그인 후 이름·접근성 모드 저장)
```json
// 요청
{ "displayName": "김할머니", "accessMode": "VOICE" }
// 응답 200
{ "id": 12, "displayName": "김할머니", "accessMode": "VOICE",
  "ttsRate": 1.0,
  "mealTimes": { "MORNING": "08:00", "LUNCH": "12:30", "EVENING": "18:30", "BEDTIME": "22:00" },
  "consents": { "sensitive": null, "overseas": null, "photoRetention": null } }
```

### `GET /users/me` · `PATCH /users/me/settings`
`PATCH` 본문: `accessMode`, `ttsRate`(0.7–1.5), `mealTimes` 중 필요한 것만. `mealTimes`를 바꾸면 해당 시점 일정의 `timeOfDay`가 함께 바뀐다.

### `PUT /users/me/consents`
```json
{ "sensitive": true, "overseas": true, "photoRetention": false }
```
서버는 동의 시각을 기록한다. `sensitive=false`이면 건강 프로필·영양제 추천 API는 `403 CONSENT_REQUIRED`.

### `PUT /users/me/health-profile`
```json
{ "birthYear": 1948, "sex": "F", "conditions": ["HYPERTENSION", "DIABETES"],
  "allergies": ["페니실린"], "pregnant": false }
```

### `DELETE /users/me`
계정과 모든 데이터 삭제. `204`.

---

## 4. 인식 (Scan)

### `POST /scans/medication-bag`
약봉투·처방전 인식 시작.

- `multipart/form-data`
  - `image` (필수): JPEG/PNG, ≤ 10MB (앱에서 긴 변 2,000px로 축소)
  - `docTypeHint` (선택): `MEDICATION_BAG` | `PRESCRIPTION`
- 응답 `202`
```json
{ "scanId": "7b1e3c2a-...", "status": "PROCESSING", "pollAfterMs": 1500 }
```

### `POST /scans/pill`
- `front` (필수), `back` (선택): JPEG/PNG
- 응답 `202` – 위와 같음

### `GET /scans/{scanId}?waitSeconds=20`
롱폴링. `PROCESSING`이면 최대 `waitSeconds`(≤ 25)까지 기다렸다가 응답한다.

**약봉투 결과 (`status=NEEDS_CONFIRM`)**
```json
{
  "scanId": "7b1e3c2a-...",
  "kind": "MEDICATION_BAG",
  "status": "NEEDS_CONFIRM",
  "documentType": "MEDICATION_BAG",
  "pharmacyName": "○○약국",
  "dispensedDate": "2026-09-25",
  "medications": [
    {
      "tempId": "m1",
      "displayName": "타이레놀정500밀리그램",
      "itemSeq": "200000001",
      "match": "MATCHED",
      "candidates": [],
      "strength": "500mg",
      "doseAmount": 1,
      "doseUnit": "정",
      "timesPerDay": 3,
      "slots": ["MORNING", "LUNCH", "EVENING"],
      "slotsInferred": false,
      "mealRelation": "AFTER_MEAL",
      "mealOffsetMin": 30,
      "durationDays": 5,
      "confidence": "HIGH",
      "needsReview": false,
      "reviewReason": null
    },
    {
      "tempId": "m2",
      "displayName": "뮤코스타정",
      "itemSeq": null,
      "match": "CANDIDATE",
      "candidates": [
        { "itemSeq": "199000002", "itemName": "무코스타정100밀리그램", "entpName": "○○제약", "similarity": 0.78 }
      ],
      "strength": null,
      "doseAmount": 1, "doseUnit": "정", "timesPerDay": 3,
      "slots": ["MORNING", "LUNCH", "EVENING"], "slotsInferred": false,
      "mealRelation": "AFTER_MEAL", "mealOffsetMin": 30, "durationDays": 5,
      "confidence": "LOW",
      "needsReview": true,
      "reviewReason": "NAME_UNCERTAIN"
    }
  ],
  "warnings": [
    { "type": "DUR_COMBINATION", "itemSeqs": ["...", "..."], "message": "함께 먹으면 안 되는 약이 있습니다." }
  ],
  "speech": [
    "약 2가지를 찾았습니다.",
    "첫 번째 약, 타이레놀정 오백 밀리그램. 한 번에 한 알, 하루 세 번, 아침, 점심, 저녁 식후 삼십 분에 드세요. 오 일 동안 드세요.",
    "두 번째 약은 글자가 흐려 이름이 정확하지 않을 수 있습니다. 가족이나 약사에게 확인해 주세요.",
    "맞으면 '맞아요', 다시 찍으려면 '다시 찍기'를 누르세요."
  ],
  "createdAt": "2026-09-27T09:12:03+09:00"
}
```

`reviewReason`: `NAME_UNCERTAIN` | `NAME_UNMATCHED` | `DOSE_UNCERTAIN` | `SLOTS_MISMATCH` | `LOW_OCR_QUALITY`

**낱알 결과 (`status=NEEDS_CONFIRM`)**
```json
{
  "scanId": "c9d0...",
  "kind": "PILL",
  "status": "NEEDS_CONFIRM",
  "observation": {
    "form": "정제", "shape": "장방형", "colors": ["하양"],
    "imprintFront": "TY", "imprintBack": null, "lineFront": "-"
  },
  "confidence": "MEDIUM",
  "candidates": [
    { "rank": 1, "itemSeq": "200000001", "itemName": "타이레놀정500밀리그램", "entpName": "○○제약",
      "score": 0.72, "imageUrl": "https://.../ref/200000001.jpg",
      "printFront": "TY", "printBack": "", "drugShape": "장방형", "colors": "하양",
      "isMyMedication": true },
    { "rank": 2, "itemSeq": "...", "itemName": "...", "score": 0.66, "isMyMedication": false }
  ],
  "speech": [
    "비슷한 약은 타이레놀정 오백 밀리그램입니다. 지금 드시는 약 목록에 있는 약입니다.",
    "비슷한 후보가 네 개 더 있습니다. 정확한 확인은 약사에게 부탁드립니다."
  ]
}
```

**실패 (`status=FAILED`)**
```json
{ "scanId": "...", "status": "FAILED",
  "error": { "code": "OCR_NO_TEXT", "speechMessage": "글자가 거의 보이지 않습니다. 약봉투의 글씨 면이 보이게 찍어 주세요.", "retryable": true } }
```

실패 코드: `OCR_FAILED`, `OCR_NO_TEXT`, `OCR_LOW_QUALITY`, `OCR_UNAVAILABLE`, `AI_UNAVAILABLE`, `AI_REFUSED`, `AI_INVALID_OUTPUT`, `AI_TRUNCATED`, `PILL_NOT_FOUND`, `PILL_MULTIPLE`, `PILL_BAD_BACKGROUND`, `TIMEOUT`, `SERVER_RESTARTED`

### `POST /scans/{scanId}/confirm` – 약봉투 결과 확정
사용자가 확인·수정한 내용으로 약과 일정을 만든다.
```json
{
  "startDate": "2026-09-27",
  "medications": [
    { "tempId": "m1", "itemSeq": "200000001", "displayName": "타이레놀정500밀리그램",
      "doseAmount": 1, "doseUnit": "정", "slots": ["MORNING", "LUNCH", "EVENING"],
      "mealRelation": "AFTER_MEAL", "mealOffsetMin": 30, "durationDays": 5, "edited": false },
    { "tempId": "m2", "itemSeq": "199000002", "displayName": "무코스타정100밀리그램",
      "doseAmount": 1, "doseUnit": "정", "slots": ["MORNING", "LUNCH", "EVENING"],
      "mealRelation": "AFTER_MEAL", "mealOffsetMin": 30, "durationDays": 5, "edited": true }
  ]
}
```
응답 `201`: 생성된 `medications[]`(각각 `schedules[]` 포함)와 `speech`("내일 아침 여덟 시에 알려 드릴게요.").
`edited` 값은 AI 품질 지표(수정 비율) 집계에 쓴다.

### `GET /scans?kind=&cursor=&size=`
최근 인식 기록 목록(결과 요약만).

---

## 5. 약품 정보

### `GET /drugs/search?q=타이레놀&size=10`
```json
{ "items": [ { "itemSeq": "200000001", "itemName": "타이레놀정500밀리그램", "entpName": "○○제약", "etcOtc": "일반" } ] }
```

### `GET /drugs/{itemSeq}`
```json
{
  "itemSeq": "200000001",
  "itemName": "타이레놀정500밀리그램",
  "entpName": "○○제약",
  "ingredients": [ { "name": "아세트아미노펜", "amount": "500", "unit": "mg" } ],
  "easyInfo": {
    "efficacy": "...", "usage": "...", "warning": "...", "caution": "...",
    "interaction": "...", "sideEffect": "...", "storage": "..."
  },
  "imageUrl": "https://...",
  "source": { "name": "식품의약품안전처 의약품개요정보", "date": "2026-08-01" },
  "speechSummary": ["타이레놀정 오백 밀리그램은 열을 내리고 통증을 줄이는 약입니다.", "..."],
  "speechDetail": ["..."]
}
```
`speechSummary`는 30초 이내 분량(FR-DRUG-02).

---

## 6. 약·일정·복용

> **2026-11-08 변경 (기술설계서 ADR-10):** 약·일정·복용 기록은 앱이 Firestore에 저장하므로 이 장의 서버 API는 만들지 않는다. 약봉투 확정(`POST /recognition/jobs/{jobId}/confirm`)은 일정 초안만 돌려주고, 이미 먹는 약 확인은 앱이 보낸 `currentMedications`로 한다. 실제 구현된 API는 `backend/src/main/resources/static/openapi.json`이 기준이다.

### `GET /medications?active=true`
사용자의 약 목록(`schedules[]` 포함).

### `PATCH /medications/{id}`
`displayName`, `doseAmount`, `doseUnit`, `mealRelation`, `mealOffsetMin`, `endDate`, `active`, `schedules`(전체 교체) 중 필요한 것만. 일정이 바뀌면 이후 `PENDING` 이벤트를 다시 만든다.

### `DELETE /medications/{id}`
`active=false`로 바꾸고 이후 이벤트를 삭제한다. `204`.

### `GET /dose-events?date=2026-09-27`
```json
{
  "date": "2026-09-27",
  "slots": [
    {
      "slot": "MORNING", "scheduledAt": "2026-09-27T08:00:00+09:00",
      "events": [
        { "scheduleId": 31, "medicationId": 7, "displayName": "타이레놀정500밀리그램",
          "doseText": "한 알", "status": "TAKEN", "takenAt": "2026-09-27T08:04:00+09:00" }
      ]
    }
  ],
  "summary": { "total": 6, "taken": 2, "pending": 4, "missed": 0 },
  "speech": ["오늘 드실 약 여섯 번 중 두 번 드셨습니다. 다음은 점심 열두 시 삼십 분입니다."]
}
```

### `POST /dose-events/sync` – 복용 기록 일괄 업로드 (오프라인 포함)
```json
{
  "events": [
    { "scheduleId": 31, "scheduledAt": "2026-09-27T08:00:00+09:00",
      "status": "TAKEN", "takenAt": "2026-09-27T08:04:00+09:00", "clientUpdatedAt": "2026-09-27T08:04:01+09:00" }
  ]
}
```
응답 `200`: `{ "applied": 1, "conflicts": [] }`. 충돌 시 `TAKEN`이 우선한다(02 §7.3).

### `GET /sync?since=2026-09-26T00:00:00+09:00`
`since` 이후 바뀐 약·일정·이벤트(같은 계정의 다른 기기에서 바꾼 내용 포함). 앱은 받은 뒤 로컬 알람을 다시 등록한다.
`since`를 빼면 전체(활성 약·일정, 최근 90일 복용 기록, 설정)를 돌려준다 – 재설치·기기 변경 후 복원용.

---

## 7. 보호자 – v2로 이동
v1에는 보호자 API가 없다. §11.2 참조.

---

## 8. 영양제 추천

### `POST /recommendations/supplements`
```json
// 요청
{ "interests": ["BONE", "EYE"] }
// 응답 200
{
  "recommendations": [
    { "ingredientCode": "VITD", "shortName": "비타민D",
      "officialClaim": "칼슘과 인의 흡수 및 이용에 필요, 뼈의 형성과 유지에 필요",
      "explanation": "비타민D는 몸이 칼슘을 잘 흡수하도록 돕습니다. 뼈를 튼튼하게 유지하는 데 필요합니다.",
      "caution": null }
  ],
  "excluded": [
    { "ingredientCode": "VITK", "shortName": "비타민K",
      "reasonPlain": "드시는 와파린과 함께 드시면 약효가 달라질 수 있습니다." }
  ],
  "disclaimer": "영양제를 드시기 전에 의사나 약사와 상담하세요.",
  "speech": ["..."]
}
```
`interests` 값: `BONE`, `EYE`, `IMMUNE`, `BLOOD_CIRCULATION`, `FATIGUE`, `SLEEP`, `JOINT`, `GUT`

### `GET /recommendations/supplements/latest`
마지막 추천 결과.

---

## 9. FCM 메시지 형식 – v2로 이동
v1은 서버가 앱으로 푸시를 보내지 않는다(Firebase 미사용). §11.3 참조.

---

## 10. 내부 API (pill-id 서비스, 외부 비공개)

### `POST http://pill-id:8001/identify`
- `multipart/form-data`: `front`(필수), `back`(선택), `hints`(JSON 문자열, 선택), `boostItemSeqs`(JSON 배열, 선택), `topk`(기본 10)
```json
// hints
{ "imprintFront": "TY", "imprintBack": null, "imprintReadability": "HIGH",
  "shape": "장방형", "colors": ["하양"] }
// 응답
{
  "lowConfidence": false,
  "weights": { "embedding": 0.45, "imprint": 0.30, "color": 0.15, "shape": 0.10 },
  "observations": [ { "shape": "장방형", "colors": ["하양"], "imprint": "TY", "imprintConf": 0.81 } ],
  "candidates": [
    { "itemSeq": "200000001", "itemName": "...", "entpName": "...", "score": 0.72,
      "parts": { "embedding": 0.80, "imprint": 1.0, "color": 1.0, "shape": 1.0 },
      "drugShape": "장방형", "colors": "하양", "printFront": "TY", "printBack": "",
      "imageUrl": "https://...", "referenceImagePath": "data/images/200000001.jpg" }
  ]
}
```
오류: `422 PILL_NOT_FOUND`(알약 분할 실패).

### `GET http://pill-id:8001/health`
`{ "status": "ok", "items": 25123, "embedding": true, "ocr": true }`

---

## 11. 추후 업데이트 (v2) API – 보호자 기능

v2에서 추가되는 API다. v1 API는 바뀌지 않고, 로그인 성공 응답의 `user`에 `roles`가, `PUT /users/me` 본문에 `roles`가 추가된다(v1 사용자는 `["PATIENT"]`).

### 11.1 기기 등록 (FCM 토큰)

#### `POST /devices`
```json
{ "fcmToken": "e1x...", "exactAlarmGranted": true }
```
앱 시작 시와 토큰 갱신 시 호출. `204`.

### 11.2 보호자

#### `POST /guardian/invites` (피보호자)
```json
// 응답 201
{ "code": "482915", "expiresAt": "2026-09-27T09:22:00+09:00",
  "speech": ["보호자 연결 번호는 사, 팔, 이, 구, 일, 오 입니다. 십 분 안에 입력해 주세요."] }
```

#### `POST /guardian/links` (보호자)
```json
// 요청
{ "code": "482915" }
// 응답 201
{ "linkId": 5, "patient": { "id": 12, "displayName": "김할머니" }, "canEdit": false, "notifyDelayMin": 30 }
```
오류: `404 INVITE_NOT_FOUND`, `410 INVITE_EXPIRED`, `429 INVITE_TOO_MANY_TRIES`.

#### `GET /guardian/patients`
연결된 피보호자 목록과 오늘 요약(`taken`, `pending`, `missed`), 피보호자의 로그인 수단(`loginProviders`, 예: `["KAKAO"]` – 피보호자가 잊었을 때 안내용).

#### `GET /guardian/patients/{patientId}/dose-events?from=2026-09-21&to=2026-09-27`
날짜별 복용 현황. 형식은 `GET /dose-events`와 같다.

#### `GET /guardian/patients/{patientId}/medications`
피보호자 약 목록. `canEdit=true`이면 `PATCH /guardian/patients/{patientId}/medications/{id}` 사용 가능.

#### `PATCH /guardian/links/{linkId}`
피보호자: `canEdit`, 보호자: `notifyDelayMin`(15–120).

#### `DELETE /guardian/links/{linkId}`
양쪽 모두 연결 해제 가능. `204`.

### 11.3 FCM 메시지 형식 (서버 → 앱)

| type | 수신자 | data | 표시 |
|---|---|---|---|
| `DOSE_MISSED` | 보호자 | `patientId`, `scheduledAt`, `medicationNames` | "김할머니님이 아침 8시 약을 아직 드시지 않았습니다." |
| `DOSE_BACKUP` | 피보호자 | `scheduledAt`, `medicationIds` | 정확한 알람 권한이 없는 기기에 대한 백업 알림 |
| `REVIEW_REQUEST` | 보호자 | `patientId`, `scanId` | "김할머니님이 등록한 약 중 확인이 필요한 약이 있습니다." |
| `LINKED` | 피보호자 | `guardianName` | "보호자 ○○님과 연결되었습니다." |
| `SYNC` | 양쪽 | `since` | 화면 표시 없음, 앱이 `GET /sync` 호출 |
