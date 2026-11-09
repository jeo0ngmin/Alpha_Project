@echo off
rem 식약처 낱알식별 DB와 참조 사진을 내려받는다. 더블클릭해서 실행.
rem 약 2.5만 품목, 사진 포함 30분~1시간 걸린다. 중간에 끊겨도 다시 실행하면 이어서 받는다.
chcp 65001 >nul
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo [오류] 가상환경이 없습니다. README의 "설치"를 먼저 진행하세요.
    pause
    exit /b 1
)

if not exist ".mfds_service_key" (
    echo 서비스키 파일이 없습니다. 메모장이 열리면:
    echo   1^) 공공데이터포털^(data.go.kr^) 마이페이지에서 발급받은 "일반 인증키"를 한 줄로 붙여 넣고
    echo   2^) 저장한 뒤 메모장을 닫으세요.
    echo.
    type nul > ".mfds_service_key"
    notepad ".mfds_service_key"
)

for %%F in (".mfds_service_key") do if %%~zF==0 (
    echo [오류] 서비스키가 비어 있습니다. 다시 실행해서 키를 넣어 주세요.
    del ".mfds_service_key"
    pause
    exit /b 1
)

".venv\Scripts\python.exe" scripts\build_db.py --download-images
echo.
if errorlevel 1 (echo [실패] 위 메시지를 확인하세요.) else (echo [완료] data\pills.db 와 data\images 가 만들어졌습니다.)
pause
