@echo off
rem 알약 식별 추론 서버를 켠다. 더블클릭하거나 터미널에서 run_server.bat 실행.
rem 끄려면 이 창에서 Ctrl+C 를 누르거나 창을 닫는다.
chcp 65001 >nul
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo [오류] 가상환경이 없습니다. README의 "설치"를 먼저 진행하세요.
    pause
    exit /b 1
)

rem Claude API 키: .anthropic_key 파일 첫 줄에서 읽는다 (Git에 올라가지 않음). 파일이 없으면 Claude 없이 켠다
if not defined ANTHROPIC_API_KEY if exist ".anthropic_key" set /p ANTHROPIC_API_KEY=<".anthropic_key"
if defined ANTHROPIC_API_KEY if not defined PILL_AI_PROVIDER set "PILL_AI_PROVIDER=anthropic"

rem 실제 식약처 DB(data\pills.db)가 없으면 데모 DB로 켠다
if not defined PILL_DB (
    if not exist "data\pills.db" (
        if not exist "data\demo\pills.db" ".venv\Scripts\python.exe" scripts\make_demo_data.py
        set "PILL_DB=data\demo\pills.db"
        echo [안내] 실제 DB가 없어서 데모 DB^(가짜 약 7종^)로 실행합니다.
    )
)

echo.
echo   테스트 화면:  http://localhost:8000/docs
echo   상태 확인:    http://localhost:8000/health
echo.
".venv\Scripts\python.exe" -m uvicorn server.app:app --host 127.0.0.1 --port 8000
pause
