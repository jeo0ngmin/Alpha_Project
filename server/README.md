# 메디메이트 서버

영양제 챗봇의 **Claude 키를 앱(APK) 대신 서버에 보관**하고, 앱의 Claude 요청을 대신 보내 줍니다 (프로젝트 README 8-6, 10-3 C).

- 앱은 서버 주소(`SERVER_BASE_URL`)가 있으면 서버로 보내고, 비어 있으면 지금처럼 앱의 키로 직접 보냅니다. 서버를 사기 전에도 앱은 그대로 동작합니다.
- 서버는 **Firebase에 로그인한 사용자만** 받습니다(앱이 Firebase ID 토큰을 붙여 보냄). 허용한 모델(`claude-opus-5-5`)과 최대 토큰(4096) 안에서만, 한 사람당 한 시간에 60번까지 보냅니다.
- 앱이 보낸 키 자리 값은 버리고 서버의 키를 붙입니다. 대화 본문과 토큰은 로그에 남기지 않습니다.
- DB는 없습니다. 데이터는 지금처럼 Firestore에 저장합니다.
- 카카오 로그인 연결은 들어 있지 않습니다 (2026-11-22 사용자 결정으로 제외).

```
앱 ──HTTPS──▶ Caddy(443, 인증서 자동) ──▶ Spring Boot(8080) ──▶ api.anthropic.com
     Authorization: Bearer {Firebase ID 토큰}      서버가 x-api-key 붙임
```

| 주소 | 내용 |
|---|---|
| `GET /health` | 설치 확인. `{"status":"ok","firebase":true,"claudeKey":true}`처럼 **설정 여부만** 보여 줍니다 |
| `POST /claude/v1/messages` | Claude Messages API 중계 |

---

## 1. 서버 구매 — 고를 것

| 항목 | 권장 |
|---|---|
| 종류 | 리눅스 VPS 아무 곳이나 (AWS Lightsail, 네이버 클라우드, 카페24, Vultr 등) |
| 운영체제 | **Ubuntu 24.04 또는 22.04** |
| 사양 | 1 vCPU, **메모리 2GB 권장** (1GB도 되지만 첫 빌드가 느림, 설치 스크립트가 스왑 2GB를 만듦) |
| 지역 | 서울 |
| 방화벽(콘솔) | **22, 80, 443** 포트 열기 |
| 공인 IP | **고정 IP**로 받기 (바뀌면 인증서·앱 주소를 다시 바꿔야 함) |

도메인은 없어도 됩니다. 서버 IP가 `1.2.3.4`라면 `1-2-3-4.sslip.io`를 주소로 씁니다 (점을 `-`로).

## 2. 미리 준비할 파일 (PC에서)

1. **Firebase 서비스 계정 키**
   Firebase 콘솔 → 톱니바퀴(프로젝트 설정) → **서비스 계정** 탭 → **새 비공개 키 생성** → JSON 파일 저장.
   이 파일은 관리자 권한 키라서 **커밋·메신저 공유 금지**입니다.
2. **Claude API 키**: 앱의 `secrets.properties`에 있는 `CLAUDE_API_KEY`와 같은 값.

## 3. 서버에 설치 (처음 한 번)

```bash
# 서버에 접속 (콘솔이 알려 준 사용자 이름과 IP)
ssh ubuntu@1.2.3.4

# 코드 받기 (MM-JM 브랜치, 비공개 저장소면 GitHub 토큰으로 로그인)
git clone -b MM-JM https://github.com/jeo0ngmin/Alpha_Project.git Medimate
cd Medimate/server

# Docker 설치 + 방화벽 + 스왑
sudo bash deploy/setup-ubuntu.sh
exit        # 다시 접속해야 sudo 없이 docker 사용 가능
```

PC에서 서비스 계정 키를 서버로 올립니다 (PowerShell, 파일 이름은 받은 이름으로).

```powershell
ssh ubuntu@1.2.3.4 "mkdir -p ~/Medimate/server/secrets"
scp .\Downloads\medimate-xxxx-firebase-adminsdk.json ubuntu@1.2.3.4:~/Medimate/server/secrets/firebase-service-account.json
```

## 4. 설정하고 켜기

```bash
ssh ubuntu@1.2.3.4
cd ~/Medimate/server
cp .env.example .env
nano .env          # DOMAIN, CLAUDE_API_KEY 채우고 Ctrl+O, Enter, Ctrl+X
docker compose up -d --build     # 첫 빌드는 몇 분 걸림
```

확인:

```bash
curl https://1-2-3-4.sslip.io/health
# {"status":"ok","firebase":true,"claudeKey":true}  ← 둘 다 true면 성공
```

`firebase:false`면 3단계 파일 위치·이름을, `claudeKey:false`면 `.env`를 확인하고 `docker compose up -d`로 다시 켭니다.
인증서 발급이 안 되면(접속 거부) 콘솔 방화벽의 80·443 포트를 확인합니다. 로그: `docker compose logs -f`

## 5. 앱 연결

1. 프로젝트 루트 `secrets.properties`에 한 줄 추가
   ```
   SERVER_BASE_URL=https://1-2-3-4.sslip.io
   ```
2. Android Studio에서 **Rebuild** 후 설치
3. 로그인한 상태로 영양제 챗봇에 증상 입력 → 답이 오면 성공
4. 서버로 잘 되는 것을 확인한 뒤, **릴리스 빌드 전에 `secrets.properties`의 `CLAUDE_API_KEY` 값을 비웁니다.** 그래야 APK에 키가 들어가지 않습니다. (서버 주소가 있으면 앱은 이 키를 쓰지 않습니다.)

## 6. 업데이트·관리

```bash
cd ~/Medimate && git pull
cd server && docker compose up -d --build
```

| 하고 싶은 것 | 명령 |
|---|---|
| 로그 보기 | `docker compose logs -f api` |
| 끄기 / 켜기 | `docker compose down` / `docker compose up -d` |
| 키 바꾸기 | `.env` 수정 후 `docker compose up -d` |
| 횟수 제한 바꾸기 | `.env`의 `CLAUDE_REQUESTS_PER_HOUR` |

## 7. PC에서 실행해 보기 (선택)

```powershell
cd server
.\gradlew.bat bootRun     # http://localhost:8080/health
```

서비스 계정 파일과 키가 없으면 `/health`가 `false`를 보여 주고, Claude 요청은 503을 돌려줍니다.
에뮬레이터 앱은 HTTPS 주소만 쓰므로 PC 서버와 바로 연결되지 않습니다. 앱 연결 확인은 실제 서버에서 합니다.
