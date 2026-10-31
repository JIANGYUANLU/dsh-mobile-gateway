@echo off
chcp 65001 >nul
title DSH Mobile Gateway - stop
setlocal

echo Stopping DSH mobile gateway...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$cf = Join-Path (Split-Path -Parent '%~dp0') 'mobile\bin\cloudflared.exe';" ^
  "foreach($p in @(19399,19388)){ $c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue; if($c){ foreach($procId in $c.OwningProcess){ Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue; Write-Host ('stopped port ' + $p + ' pid ' + $procId) } } };" ^
  "Get-CimInstance Win32_Process -Filter \"Name='cloudflared.exe'\" | Where-Object { $_.ExecutablePath -eq $cf } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; Write-Host ('stopped cloudflared pid ' + $_.ProcessId) };" ^
  "Remove-Item (Join-Path '%~dp0' 'gateway\state.json') -ErrorAction SilentlyContinue;"

echo.
echo Done. Nothing is exposed to the network now.
pause
