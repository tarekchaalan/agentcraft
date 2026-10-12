@echo off
setlocal
rem AgentCraft guided start (Windows). Double-click this, or run it from cmd.exe.
rem Checks everything the launcher needs (git, Node.js 22, Java 25, your Claude login), offers to
rem install what is missing, asks which world to open, then starts the Foreman and the game.
rem   start-agentcraft.cmd              asks everything
rem   start-agentcraft.cmd survival     survival world, asks the rest
rem   start-agentcraft.cmd -Check       only report what is installed
rem Uses PowerShell 7 (pwsh) when installed, otherwise Windows PowerShell 5.1.

rem System tools by full path: a Git or MSYS install earlier on PATH has its own `timeout` and `where`.
set "SYS=%SystemRoot%\System32"
set "PS=powershell"
"%SYS%\where.exe" pwsh >nul 2>&1 && set "PS=pwsh"

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start-agentcraft.ps1" %*
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" (
    rem Keep the summary readable when started from Explorer; any key closes it sooner.
    echo Closing this window in 30 seconds ^(press a key to close it now^).
    "%SYS%\timeout.exe" /t 30 >nul
) else (
    if "%RC%"=="2" (
        echo Stopped at your request.
    ) else (
        echo start-agentcraft exited with code %RC% - see the messages above and artifacts\logs\start-*.log
    )
    pause
)
exit /b %RC%
