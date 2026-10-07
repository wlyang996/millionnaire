@echo off
rem Double-click: copy the Cocos "Web Mobile" build into web\dist, then commit and push it (runs web\update-dist.ps1).
rem Options: update-web.bat [web-mobile-002] [-NoPush] [-Force]
rem   build folder under client\build (default: the most recently built one); -NoPush only copies;
rem   -Force allows a build older than the latest client code.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0web\update-dist.ps1" %*
echo.
pause
