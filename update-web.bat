@echo off
rem Double-click: copy the Cocos "Web Mobile" build into web\dist (runs web\update-dist.ps1).
rem Optional: pass the build folder name, e.g. update-web.bat web-mobile-002
cd /d "%~dp0"
if "%~1"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0web\update-dist.ps1"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0web\update-dist.ps1" -Build "%~1"
)
echo.
pause
