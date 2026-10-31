@echo off
chcp 65001 >nul
title DSH Mobile Gateway - close this window to stop
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo [ERROR] node.exe not found in PATH.
  pause
  exit /b 1
)

if not exist "%~dp0bin\cloudflared.exe" (
  echo [ERROR] bin\cloudflared.exe is missing.
  pause
  exit /b 1
)

echo Starting DSH mobile gateway. This window must stay open.
echo Press Ctrl+C or close this window to stop everything.
echo.
node "%~dp0gateway\gateway.mjs"

echo.
echo Gateway stopped. All external entry points are closed.
pause
