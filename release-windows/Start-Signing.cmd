@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0sign-release.ps1"
set "rike_exit=%errorlevel%"
echo.
pause
exit /b %rike_exit%
