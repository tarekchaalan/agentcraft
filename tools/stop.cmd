@echo off
setlocal
rem Stops what tools\launch.ps1 started: same arguments and exit codes as tools\stop.ps1
rem Uses PowerShell 7 (pwsh) when installed, otherwise Windows PowerShell 5.1.
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%SystemRoot%\System32\where.exe" pwsh >nul 2>&1 && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
exit /b %ERRORLEVEL%
