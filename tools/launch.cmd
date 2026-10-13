@echo off
setlocal
rem AgentCraft launcher for cmd.exe / Explorer: same arguments and exit codes as tools\launch.ps1
rem Uses PowerShell 7 (pwsh) when installed, otherwise Windows PowerShell 5.1.
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%SystemRoot%\System32\where.exe" pwsh >nul 2>&1 && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch.ps1" %*
exit /b %ERRORLEVEL%
