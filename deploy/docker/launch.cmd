@echo off
setlocal
chcp 65001 >nul
set "TICKET_POWERSHELL=powershell.exe"
where pwsh.exe >nul 2>nul
if not errorlevel 1 set "TICKET_POWERSHELL=pwsh.exe"
"%TICKET_POWERSHELL%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch.ps1" -Action "%~1"
set "TICKET_EXIT_CODE=%errorlevel%"
echo.
if not "%TICKET_EXIT_CODE%"=="0" echo Operation failed. Please read the error above.
if /i not "%~2"=="--no-pause" pause
exit /b %TICKET_EXIT_CODE%
