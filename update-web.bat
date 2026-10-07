@echo off
rem Double-click: copy the Cocos "Web Mobile" build into web\dist, then commit and push it (runs web\update-dist.ps1).
rem Options: update-web.bat [web-mobile-002] [-NoPush]
rem   build folder name under client\build (default web-mobile-001); -NoPush only copies, no commit / push.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0web\update-dist.ps1" %*
echo.
pause
