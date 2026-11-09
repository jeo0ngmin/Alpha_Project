@echo off
rem 알약 식별 백엔드를 켠다. 끄려면 이 창에서 Ctrl+C 를 누르거나 창을 닫는다.
rem 사진 식별까지 쓰려면 pill-classifier\run_server.bat 도 함께 켜 둔다.
chcp 65001 >nul
cd /d "%~dp0"

if not defined JAVA_HOME (
    if exist "D:\AndroidStudio\jbr\bin\java.exe" set "JAVA_HOME=D:\AndroidStudio\jbr"
    if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
if not defined JAVA_HOME (
    echo [오류] JDK를 찾지 못했습니다. JAVA_HOME을 설정하세요. 21 이상이면 됩니다.
    pause
    exit /b 1
)

echo.
echo   테스트 화면:  http://localhost:8080/docs
echo.
call gradlew.bat bootRun --console=plain
pause
