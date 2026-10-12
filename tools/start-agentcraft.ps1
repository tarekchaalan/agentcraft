#Requires -Version 5.1
<#
.SYNOPSIS
  Guided start for AgentCraft on Windows: checks everything the launcher needs, offers to install
  what is missing, waits for each install, then asks which world to open and starts the game.

.DESCRIPTION
  Double-click start-agentcraft.cmd in the repository root (it runs this script), or run this
  script from any PowerShell (5.1 or 7). It never changes anything on your machine without asking
  first, and nothing it sets for the launch (JAVA_HOME, PATH) outlives the window.

  What it checks, in order, and what it offers when something is missing:
    - this checkout (mod\gradlew.bat, foreman\package.json, tools\launch.ps1)
    - winget (App Installer): used for installs; without it the portable installs below still work
    - Git: `winget install Git.Git`
    - Node.js 22+: nvm-windows if you have it, else winget (OpenJS.NodeJS.LTS), else a portable
      copy in %LOCALAPPDATA%\Programs\nodejs (added to your user PATH)
    - Java 25 JDK (Temurin): a portable copy in %LOCALAPPDATA%\Programs\Eclipse Adoptium (no admin,
      nothing else on the machine changes); JAVA_HOME is set for this launch only
    - Claude login (login mode): the `claude` CLI, installed with Anthropic's installer if missing,
      and `claude auth login` if you are not logged in
    - free disk space and internet, when something has to be downloaded
  Then: backend (your Claude login, an API key, or the free simulated team), the repo the agents
  should work in, the world (Studio or Survival), and tools\launch.ps1 does the rest.

  Every run is logged to artifacts\logs\start-<timestamp>.log.

.PARAMETER World
  studio or survival. Asked when not given.
.PARAMETER Repo
  Repository root(s) to register with the Foreman. Asked when not given (Enter = none).
.PARAMETER Backend
  claude (default), codex or sim. With claude and no login or key configured, you are asked.
.PARAMETER UseClaudeLogin
  Run the agents on your `claude` CLI login instead of an API key (personal use).
.PARAMETER Check
  Only check (and offer installs); do not launch.
.PARAMETER Yes
  Accept every install and every default without asking (unattended).
.PARAMETER NoInstall
  Never install anything: report what is missing and stop.
.PARAMETER LaunchArgs
  Extra arguments passed through to tools\launch.ps1, e.g. -LaunchArgs '-Dev','-Seed','1234'.

.EXAMPLE
  start-agentcraft.cmd                       # asks everything
  start-agentcraft.cmd survival              # survival world, asks the rest
  tools\start-agentcraft.ps1 -Check          # just report what is installed
  tools\start-agentcraft.ps1 -Yes -World studio -Backend sim   # unattended demo
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)][ValidateSet('studio', 'survival', '')][string]$World = '',
    [string[]]$Repo,
    [ValidateSet('sim', 'claude', 'codex', '')][string]$Backend = '',
    [switch]$UseClaudeLogin,
    [switch]$Check,
    [switch]$Yes,
    [switch]$NoInstall,
    [string[]]$LaunchArgs
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest's progress bar makes PS 5.1 downloads crawl

# Exit codes: 0 ok, 1 a requirement is missing, 2 you quit, 3 the launch failed, 4 unexpected error.
$script:ExitCode = 0
$script:Interactive = -not $Yes
$script:Transcript = $false

# --- paths ---------------------------------------------------------------------------------------

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$Launcher = Join-Path $Root 'tools\launch.ps1'
$Logs = Join-Path (Join-Path $Root 'artifacts') 'logs'
$UserPrograms = Join-Path $env:LOCALAPPDATA 'Programs'

# --- output helpers ------------------------------------------------------------------------------

function Write-Head([string]$Text) { Write-Host ''; Write-Host $Text -ForegroundColor Cyan }
function Write-Ok([string]$Text) { Write-Host "  + $Text" -ForegroundColor Green }
function Write-Info([string]$Text) { Write-Host "    $Text" -ForegroundColor DarkGray }
function Write-Warn2([string]$Text) { Write-Host "  ! $Text" -ForegroundColor Yellow }
function Write-Bad([string]$Text) { Write-Host "  x $Text" -ForegroundColor Red }
function Write-Step([string]$Text) { Write-Host "  > $Text" -ForegroundColor White }

function Quit([int]$Code, [string]$Message) {
    if ($Message) {
        if ($Code -eq 0) { Write-Ok $Message } elseif ($Code -eq 2) { Write-Warn2 $Message } else { Write-Bad $Message }
    }
    $script:ExitCode = $Code
    throw [System.Exception]::new('AGENTCRAFT_QUIT')
}

# --- prompts -------------------------------------------------------------------------------------

<#
  Ask a question with single-letter answers. $Choices is an ordered list of @{ key; label }, the
  first one is the default (Enter). -Yes answers the default without asking. Ctrl+C / closed
  input counts as quit when a 'q' choice exists.
#>
function Read-Choice([string]$Question, [object[]]$Choices) {
    $keys = ($Choices | ForEach-Object { $_.key }) -join '/'
    $default = $Choices[0].key
    if (-not $script:Interactive) {
        Write-Host "  ? $Question [$keys] -> $default (unattended)" -ForegroundColor DarkGray
        return $default
    }
    Write-Host ''
    Write-Host "  ? $Question" -ForegroundColor White
    foreach ($c in $Choices) {
        $mark = if ($c.key -eq $default) { ' (Enter)' } else { '' }
        Write-Host ("      {0}  {1}{2}" -f $c.key.ToUpperInvariant(), $c.label, $mark) -ForegroundColor Gray
    }
    while ($true) {
        try { $answer = Read-Host "    [$keys]" } catch { return 'q' }
        if ($null -eq $answer) { return 'q' }
        $answer = $answer.Trim().ToLowerInvariant()
        if ($answer -eq '') { return $default }
        foreach ($c in $Choices) {
            if ($answer -eq $c.key -or $answer -eq $c.label.ToLowerInvariant()) { return $c.key }
        }
        Write-Host "      please type one of: $keys" -ForegroundColor Yellow
    }
}

function Read-Text([string]$Question, [string]$Default = '') {
    if (-not $script:Interactive) { return $Default }
    Write-Host ''
    $suffix = if ($Default) { " [$Default]" } else { '' }
    try { $answer = Read-Host "  ? $Question$suffix" } catch { return $Default }
    if ($null -eq $answer -or $answer.Trim() -eq '') { return $Default }
    return $answer.Trim().Trim('"')
}

# The standard "it is missing, may I install it?" question. Returns install | skip | quit.
function Confirm-Install([string]$What, [string]$How, [string]$WhyNeeded) {
    if ($NoInstall) {
        Write-Bad "$What is missing ($WhyNeeded). -NoInstall: not installing."
        return 'skip'
    }
    Write-Warn2 "$What is not installed. $WhyNeeded"
    Write-Info "Install: $How"
    $a = Read-Choice "Install $What now?" @(
        @{ key = 'y'; label = 'yes, install it and continue' },
        @{ key = 's'; label = 'skip (continue without it; the launch may fail)' },
        @{ key = 'q'; label = 'quit' }
    )
    switch ($a) {
        'y' { return 'install' }
        's' { return 'skip' }
        default { return 'quit' }
    }
}

# --- environment helpers -------------------------------------------------------------------------

function Update-SessionPath {
    # Pick up PATH entries an installer just wrote (machine + user), keeping what this session added.
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($chunk in @($env:Path, $user, $machine)) {
        if (-not $chunk) { continue }
        foreach ($p in $chunk.Split(';')) {
            $t = $p.Trim()
            if ($t -and -not $parts.Contains($t)) { $parts.Add($t) }
        }
    }
    # fresh installs that may not be on PATH yet in this session
    foreach ($p in @("$env:ProgramFiles\Git\cmd", "$env:ProgramFiles\nodejs", (Join-Path $UserPrograms 'nodejs'),
            (Join-Path $env:USERPROFILE '.local\bin'), (Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps'))) {
        if ((Test-Path -LiteralPath $p) -and -not $parts.Contains($p)) { $parts.Add($p) }
    }
    $env:Path = ($parts -join ';')
}

function Add-UserPath([string]$Dir) {
    # Persistently (user scope, no admin) and for this session. Asked before calling.
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $have = @()
    if ($user) { $have = $user.Split(';') | ForEach-Object { $_.Trim() } }
    if ($have -notcontains $Dir) {
        $new = if ($user) { "$user;$Dir" } else { $Dir }
        [Environment]::SetEnvironmentVariable('Path', $new, 'User')
    }
    Update-SessionPath
}

function Get-Cmd([string]$Name) {
    $c = Get-Command $Name -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    return $null
}

function Invoke-Tool([string]$Exe, [string[]]$Arguments, [int]$TimeoutSec = 60) {
    # Runs a console tool, returns @{ code; out } without letting its stderr become a PowerShell error.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEndAsync()
    $err = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit($TimeoutSec * 1000)) {
        try { $p.Kill() } catch {}
        return @{ code = -1; out = "timed out after ${TimeoutSec}s" }
    }
    $p.WaitForExit()
    return @{ code = $p.ExitCode; out = (($out.Result + "`n" + $err.Result).Trim()) }
}

function Invoke-Visible([string]$Exe, [string[]]$Arguments, [string]$Cwd = $Root) {
    # Runs a tool in this window so you see its own progress (winget, npm, claude login). Returns the exit code.
    Write-Info ("running: " + $Exe + ' ' + ($Arguments -join ' '))
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location -LiteralPath $Cwd
    try {
        & $Exe @Arguments
        $code = $LASTEXITCODE
    }
    catch {
        Write-Bad "could not run $Exe : $($_.Exception.Message)"
        $code = 1
    }
    finally {
        Pop-Location
        $ErrorActionPreference = $prev
    }
    if ($null -eq $code) { $code = 0 }
    return [int]$code
}

function Test-Internet {
    foreach ($url in @('https://api.adoptium.net/v3/info/available_releases', 'https://nodejs.org/dist/index.json', 'https://github.com')) {
        try {
            $r = Invoke-WebRequest -UseBasicParsing -Uri $url -Method Head -TimeoutSec 8
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) { return $true }
        }
        catch {}
    }
    return $false
}

function Confirm-Internet([string]$For) {
    if (Test-Internet) { return $true }
    Write-Bad "No internet connection (needed to download $For)."
    $a = Read-Choice 'Check the connection and retry?' @(
        @{ key = 'r'; label = 'retry' },
        @{ key = 's'; label = 'skip this install' },
        @{ key = 'q'; label = 'quit' }
    )
    if ($a -eq 'r') { return (Confirm-Internet $For) }
    if ($a -eq 'q') { Quit 2 'Quit.' }
    return $false
}

function Get-FreeGB([string]$Path) {
    try {
        $drive = [System.IO.Path]::GetPathRoot((Resolve-Path -LiteralPath $Path).Path)
        $d = Get-PSDrive -Name $drive.Substring(0, 1) -ErrorAction Stop
        return [math]::Round($d.Free / 1GB, 1)
    }
    catch { return -1 }
}

function Save-Download([string]$Url, [string]$OutFile, [string]$What) {
    Write-Step "downloading $What ..."
    Write-Info $Url
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch {}
    $dir = Split-Path -Parent $OutFile
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    if (Test-Path -LiteralPath $OutFile) { Remove-Item -LiteralPath $OutFile -Force }
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $OutFile -TimeoutSec 1800
    }
    catch {
        throw "download failed: $($_.Exception.Message)"
    }
    $size = (Get-Item -LiteralPath $OutFile).Length
    if ($size -lt 1MB) { throw "download is only $size bytes; not a real archive (check the connection or a proxy)" }
    Write-Info ("got {0:N1} MB" -f ($size / 1MB))
}

function Expand-Portable([string]$Zip, [string]$Dest, [string]$What) {
    Write-Step "unpacking $What into $Dest ..."
    if (-not (Test-Path -LiteralPath $Dest)) { New-Item -ItemType Directory -Path $Dest -Force | Out-Null }
    $tmp = Join-Path $Dest ('.unpack-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    Expand-Archive -LiteralPath $Zip -DestinationPath $tmp -Force
    $top = @(Get-ChildItem -LiteralPath $tmp -Directory)
    if ($top.Count -ne 1) {
        Remove-Item -LiteralPath $tmp -Recurse -Force
        throw 'unexpected archive layout (expected one top-level folder)'
    }
    $final = Join-Path $Dest $top[0].Name
    if (Test-Path -LiteralPath $final) {
        # never delete what is there (it may be in use); set it aside
        $aside = "$final.old-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
        Write-Warn2 "$final exists; moving it to $aside"
        Move-Item -LiteralPath $final -Destination $aside
    }
    Move-Item -LiteralPath $top[0].FullName -Destination $final
    Remove-Item -LiteralPath $tmp -Recurse -Force
    Remove-Item -LiteralPath $Zip -Force -ErrorAction SilentlyContinue
    return $final
}

function Get-Winget {
    $w = Get-Cmd 'winget'
    if ($w) { return $w }
    $p = Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps\winget.exe'
    if (Test-Path -LiteralPath $p) { return $p }
    return $null
}

function Invoke-Winget([string]$Id, [string]$What) {
    $w = Get-Winget
    if (-not $w) { return $false }
    Write-Step "winget install $Id (a Windows 'User Account Control' prompt may appear; answer Yes) ..."
    $code = Invoke-Visible $w @('install', '--id', $Id, '-e', '--source', 'winget', '--accept-package-agreements', '--accept-source-agreements')
    # 0 = installed; -1978335189 (0x8A15002B) = nothing to do, already installed / no applicable upgrade
    if ($code -eq 0 -or $code -eq -1978335189) { Update-SessionPath; return $true }
    Write-Bad "winget exited with code $code while installing $What."
    if ($code -eq -1978335215 -or $code -eq 1223 -or $code -eq -2147023673) { Write-Info 'That looks like a declined or missing admin prompt. Run this script "as administrator", or let it use a portable install instead.' }
    return $false
}

# --- the checks ----------------------------------------------------------------------------------

function Test-Checkout {
    Write-Head 'Checking this checkout'
    $missing = @()
    foreach ($rel in @('tools\launch.ps1', 'tools\lib\procs.ps1', 'mod\gradlew.bat', 'mod\build.gradle', 'foreman\package.json', 'tools\package.json')) {
        if (Test-Path -LiteralPath (Join-Path $Root $rel)) { continue }
        $missing += $rel
    }
    if ($missing.Count) {
        Write-Bad "This does not look like a complete AgentCraft checkout ($Root). Missing: $($missing -join ', ')"
        Write-Info 'Clone the repository again (git clone ...) and run start-agentcraft.cmd from its root.'
        Quit 1 'Checkout incomplete.'
    }
    Write-Ok "checkout: $Root"
    $free = Get-FreeGB $Root
    if ($free -ge 0) {
        if ($free -lt 4) {
            Write-Warn2 ("only {0} GB free on this drive; the first launch downloads Gradle, Minecraft and Fabric (about 2 GB) and builds the mod" -f $free)
            $a = Read-Choice 'Continue anyway?' @(@{ key = 'y'; label = 'yes' }, @{ key = 'q'; label = 'quit' })
            if ($a -eq 'q') { Quit 2 'Quit.' }
        }
        else { Write-Ok ("disk: {0} GB free" -f $free) }
    }
    $ps = $PSVersionTable.PSVersion
    if ($ps.Major -ge 7) { Write-Ok "PowerShell $ps" }
    else { Write-Ok "Windows PowerShell $ps (works; PowerShell 7 is a little faster: winget install Microsoft.PowerShell)" }
}

function Test-Git {
    $git = Get-Cmd 'git'
    if ($git) {
        $v = Invoke-Tool $git @('--version') 15
        if ($v.code -eq 0) { Write-Ok "git: $($v.out) ($git)"; return $true }
    }
    return $false
}

function Resolve-Git {
    Write-Head 'Git'
    while (-not (Test-Git)) {
        $a = Confirm-Install 'Git' 'winget install Git.Git (https://git-scm.com/download/win)' 'The agents work in git worktrees of your repository and the launcher checks repos with git.'
        if ($a -eq 'quit') { Quit 2 'Quit.' }
        if ($a -eq 'skip') { Write-Warn2 'continuing without git; -Repo and the agents'' worktrees will not work'; return }
        if (-not (Confirm-Internet 'Git')) { continue }
        if (-not (Invoke-Winget 'Git.Git' 'Git')) {
            Write-Info 'Install it from https://git-scm.com/download/win (keep "Git from the command line" selected), then come back here.'
            $r = Read-Choice 'When Git is installed, check again?' @(@{ key = 'r'; label = 'check again' }, @{ key = 's'; label = 'skip' }, @{ key = 'q'; label = 'quit' })
            if ($r -eq 'q') { Quit 2 'Quit.' }
            if ($r -eq 's') { return }
            Update-SessionPath
        }
    }
}

function Get-NodeMajor {
    $node = Get-Cmd 'node'
    if (-not $node) { return @{ major = 0; version = ''; path = '' } }
    $v = Invoke-Tool $node @('--version') 15
    if ($v.code -ne 0 -or $v.out -notmatch '^v(\d+)\.') { return @{ major = 0; version = $v.out; path = $node } }
    return @{ major = [int]$Matches[1]; version = $v.out; path = $node }
}

function Install-NodePortable {
    # Portable zip from nodejs.org into %LOCALAPPDATA%\Programs\nodejs, on the user PATH.
    $index = Invoke-WebRequest -UseBasicParsing -Uri 'https://nodejs.org/dist/index.json' -TimeoutSec 30
    $releases = $index.Content | ConvertFrom-Json
    $pick = $null
    foreach ($r in $releases) {
        if ($r.version -match '^v(\d+)\.' -and [int]$Matches[1] -eq 22 -and $r.lts) { $pick = $r; break }
    }
    if (-not $pick) { foreach ($r in $releases) { if ($r.version -match '^v22\.') { $pick = $r; break } } }
    if (-not $pick) { throw 'no Node.js 22 release found in https://nodejs.org/dist/index.json' }
    $ver = $pick.version
    $zipName = "node-$ver-win-x64.zip"
    $zip = Join-Path $env:TEMP $zipName
    Save-Download "https://nodejs.org/dist/$ver/$zipName" $zip "Node.js $ver (portable)"
    $dest = Join-Path $UserPrograms 'nodejs'
    $final = Expand-Portable $zip $dest "Node.js $ver"
    # flatten: %LOCALAPPDATA%\Programs\nodejs\node.exe
    Get-ChildItem -LiteralPath $final -Force | Move-Item -Destination $dest -Force
    Remove-Item -LiteralPath $final -Recurse -Force
    Add-UserPath $dest
    Write-Ok "Node.js $ver installed in $dest (added to your user PATH)"
}

function Resolve-Node {
    Write-Head 'Node.js 22+'
    while ($true) {
        $n = Get-NodeMajor
        if ($n.major -ge 22) { Write-Ok "node $($n.version) ($($n.path))"; return }
        $why = if ($n.major -gt 0) { "Found $($n.version); the Foreman needs 22 or newer." } else { 'The Foreman (the agents'' backend) runs on Node.js.' }
        $nvm = Get-Cmd 'nvm'
        $how = if ($nvm) { 'nvm install 22 + nvm use 22 (you have nvm-windows)' } elseif (Get-Winget) { 'winget install OpenJS.NodeJS.LTS, or a portable copy in your user profile' } else { 'a portable copy in %LOCALAPPDATA%\Programs\nodejs (no admin)' }
        $a = Confirm-Install 'Node.js 22+' $how $why
        if ($a -eq 'quit') { Quit 2 'Quit.' }
        if ($a -eq 'skip') { Write-Warn2 'continuing without Node.js 22; the launch will fail at the Foreman'; return }
        if (-not (Confirm-Internet 'Node.js')) { continue }
        $done = $false
        if ($nvm) {
            Write-Step 'nvm install 22 ...'
            if ((Invoke-Visible $nvm @('install', '22')) -eq 0) {
                Write-Step 'nvm use 22 ...'
                if ((Invoke-Visible $nvm @('use', '22')) -eq 0) { Update-SessionPath; $done = ((Get-NodeMajor).major -ge 22) }
            }
            if (-not $done) { Write-Warn2 'nvm could not switch (it may need an administrator window); trying another way' }
        }
        if (-not $done -and (Get-Winget)) {
            $m = Read-Choice 'How should Node.js be installed?' @(
                @{ key = 'w'; label = 'winget (system install, asks for admin once)' },
                @{ key = 'p'; label = 'portable copy in your user profile (no admin)' },
                @{ key = 'q'; label = 'quit' }
            )
            if ($m -eq 'q') { Quit 2 'Quit.' }
            if ($m -eq 'w') { $done = Invoke-Winget 'OpenJS.NodeJS.LTS' 'Node.js' }
            if ($done) { $done = ((Get-NodeMajor).major -ge 22) }
        }
        if (-not $done) {
            try { Install-NodePortable; $done = ((Get-NodeMajor).major -ge 22) }
            catch { Write-Bad "portable install failed: $($_.Exception.Message)" }
        }
        if (-not $done) {
            Write-Info 'Install Node.js 22 or newer from https://nodejs.org, then come back here.'
            $r = Read-Choice 'Check again?' @(@{ key = 'r'; label = 'check again' }, @{ key = 's'; label = 'skip' }, @{ key = 'q'; label = 'quit' })
            if ($r -eq 'q') { Quit 2 'Quit.' }
            if ($r -eq 's') { return }
            Update-SessionPath
        }
    }
}

function Get-JdkVersion([string]$Name) {
    # "jdk-25.0.4.1+1" -> 25.0.4.1 ; "jdk-25+36" -> 25.0 ; "zulu25.30.17-ca-jdk25.0.1-win_x64" -> 25.0.1
    $v = $Name
    if ($v -match '(\d+(?:\.\d+)+)') { $v = $Matches[1] } elseif ($v -match '(\d+)') { $v = $Matches[1] } else { return [version]'0.0' }
    if ($v -notmatch '\.') { $v += '.0' }
    try { return [version]$v } catch { return [version]'0.0' }
}

function Test-JavaHome([string]$JdkHome) {
    # Returns the major version of the JDK at $JdkHome, or 0.
    if (-not $JdkHome) { return 0 }
    $exe = Join-Path $JdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $exe)) { return 0 }
    $r = Invoke-Tool $exe @('-version') 20
    if ($r.out -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

function Find-Jdk25 {
    # The JDK to use: JAVA_HOME if it is 25+, else the newest 25+ in the usual places, else java on PATH.
    if ((Test-JavaHome $env:JAVA_HOME) -ge 25) { return $env:JAVA_HOME }
    $roots = @(
        (Join-Path $UserPrograms 'Eclipse Adoptium'),
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Zulu",
        "$env:ProgramFiles\Amazon Corretto",
        (Join-Path $env:USERPROFILE '.jdks')
    )
    $found = @()
    foreach ($r in $roots) {
        if (-not (Test-Path -LiteralPath $r)) { continue }
        foreach ($d in (Get-ChildItem -LiteralPath $r -Directory -ErrorAction SilentlyContinue)) {
            if ((Get-JdkVersion $d.Name).Major -lt 25) { continue }
            if (Test-Path -LiteralPath (Join-Path $d.FullName 'bin\java.exe')) { $found += $d }
        }
    }
    foreach ($d in ($found | Sort-Object { Get-JdkVersion $_.Name } -Descending)) {
        if ((Test-JavaHome $d.FullName) -ge 25) { return $d.FullName }
    }
    $java = Get-Cmd 'java'
    if ($java) {
        $jh = Split-Path -Parent (Split-Path -Parent $java)
        if ((Test-JavaHome $jh) -ge 25) { return $jh }
    }
    return $null
}

function Install-JdkPortable {
    # Temurin 25 zip from the Adoptium API into %LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25...
    $url = 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'
    $zip = Join-Path $env:TEMP 'temurin-25-jdk-windows-x64.zip'
    Save-Download $url $zip 'Temurin 25 JDK (portable)'
    $dest = Join-Path $UserPrograms 'Eclipse Adoptium'
    $final = Expand-Portable $zip $dest 'Temurin 25 JDK'
    if ((Test-JavaHome $final) -lt 25) { throw "the unpacked JDK at $final does not run (java -version)" }
    Write-Ok "Temurin JDK installed in $final (nothing else on the machine was changed)"
    return $final
}

function Resolve-Java {
    Write-Head 'Java 25 JDK'
    while ($true) {
        $jdk = Find-Jdk25
        if ($jdk) {
            $env:JAVA_HOME = $jdk
            $env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
            Write-Ok "JAVA_HOME = $jdk (for this launch only)"
            return
        }
        $have = Test-JavaHome $env:JAVA_HOME
        $why = if ($have -gt 0) { "JAVA_HOME points at Java $have; Minecraft 26.3 needs a Java 25 JDK." } else { 'Minecraft 26.3 and the mod build need a Java 25 JDK.' }
        $a = Confirm-Install 'Java 25 JDK (Temurin)' 'a portable copy in %LOCALAPPDATA%\Programs\Eclipse Adoptium (no admin; your other Java installs and JAVA_HOME stay as they are)' $why
        if ($a -eq 'quit') { Quit 2 'Quit.' }
        if ($a -eq 'skip') { Write-Warn2 'continuing without Java 25; the launch will fail at the game build'; return }
        if (-not (Confirm-Internet 'the Temurin JDK')) { continue }
        try { Install-JdkPortable | Out-Null }
        catch {
            Write-Bad "JDK install failed: $($_.Exception.Message)"
            Write-Info 'You can also install it from https://adoptium.net (Temurin 25, Windows x64) and run this again.'
            if (Get-Winget) {
                $w = Read-Choice 'Try winget instead (system install, asks for admin)?' @(@{ key = 'y'; label = 'yes' }, @{ key = 'r'; label = 'no, check again' }, @{ key = 'q'; label = 'quit' })
                if ($w -eq 'q') { Quit 2 'Quit.' }
                if ($w -eq 'y') { Invoke-Winget 'EclipseAdoptium.Temurin.25.JDK' 'Temurin 25' | Out-Null }
            }
            else {
                $r = Read-Choice 'Check again?' @(@{ key = 'r'; label = 'check again' }, @{ key = 's'; label = 'skip' }, @{ key = 'q'; label = 'quit' })
                if ($r -eq 'q') { Quit 2 'Quit.' }
                if ($r -eq 's') { return }
            }
        }
    }
}

# --- backend and login ---------------------------------------------------------------------------

function Get-AgentConfig {
    $dir = if ($env:AGENTCRAFT_HOME) { $env:AGENTCRAFT_HOME } else { Join-Path $env:USERPROFILE '.agentcraft' }
    $file = Join-Path $dir 'config.json'
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    try { return (Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json) }
    catch { Write-Warn2 "could not read $file ($($_.Exception.Message)); ignoring it"; return $null }
}

function Test-ConfiguredLogin {
    $cfg = Get-AgentConfig
    if ($null -eq $cfg) { return $false }
    try { return [bool]$cfg.claude.useClaudeLogin } catch { return $false }
}

function Get-ClaudeLogin {
    # @{ installed; loggedIn; detail }
    $claude = Get-Cmd 'claude'
    if (-not $claude) {
        $p = Join-Path $env:USERPROFILE '.local\bin\claude.exe'
        if (Test-Path -LiteralPath $p) { $claude = $p }
    }
    if (-not $claude) { return @{ installed = $false; loggedIn = $false; detail = '' } }
    $r = Invoke-Tool $claude @('auth', 'status') 40
    $logged = $false
    $detail = $r.out
    if ($r.out -match '\{[\s\S]*\}') {
        try {
            $j = $Matches[0] | ConvertFrom-Json
            $logged = [bool]$j.loggedIn
            $detail = if ($logged) { "logged in ($($j.authMethod))" } else { 'not logged in' }
        }
        catch {}
    }
    elseif ($r.out -match 'logged in' -and $r.out -notmatch 'not logged in') { $logged = $true; $detail = 'logged in' }
    return @{ installed = $true; loggedIn = $logged; detail = $detail; path = $claude }
}

function Resolve-ClaudeLogin {
    Write-Head 'Claude login'
    while ($true) {
        $s = Get-ClaudeLogin
        if ($s.installed -and $s.loggedIn) { Write-Ok "claude CLI: $($s.detail) ($($s.path))"; return $true }
        if (-not $s.installed) {
            $a = Confirm-Install 'the Claude Code CLI (`claude`)' 'Anthropic''s installer: irm https://claude.ai/install.ps1 | iex (into your user profile)' 'Login mode uses the login of your `claude` CLI (your claude.ai plan) instead of an API key.'
            if ($a -eq 'quit') { Quit 2 'Quit.' }
            if ($a -eq 'skip') { return $false }
            if (-not (Confirm-Internet 'the Claude Code CLI')) { continue }
            Write-Step 'running the Claude Code installer ...'
            try {
                $installer = Invoke-WebRequest -UseBasicParsing -Uri 'https://claude.ai/install.ps1' -TimeoutSec 60
                Invoke-Expression $installer.Content
            }
            catch { Write-Bad "installer failed: $($_.Exception.Message)" }
            Update-SessionPath
            if (-not (Get-ClaudeLogin).installed) {
                Write-Info 'See https://docs.claude.com/en/docs/claude-code/setup for the manual install, then come back here.'
                $r = Read-Choice 'Check again?' @(@{ key = 'r'; label = 'check again' }, @{ key = 's'; label = 'skip' }, @{ key = 'q'; label = 'quit' })
                if ($r -eq 'q') { Quit 2 'Quit.' }
                if ($r -eq 's') { return $false }
            }
            continue
        }
        Write-Warn2 "claude CLI is installed but $($s.detail)."
        $a = Read-Choice 'Log in now? (opens your browser; come back here when it says you are logged in)' @(
            @{ key = 'y'; label = 'yes, run `claude auth login`' },
            @{ key = 's'; label = 'skip (use an API key or the simulated team instead)' },
            @{ key = 'q'; label = 'quit' }
        )
        if ($a -eq 'q') { Quit 2 'Quit.' }
        if ($a -eq 's') { return $false }
        $code = Invoke-Visible $s.path @('auth', 'login')
        if ($code -ne 0) {
            Write-Warn2 "login exited with code $code. You can also run `claude` and type /login."
            $r = Read-Choice 'Check again?' @(@{ key = 'r'; label = 'check again' }, @{ key = 's'; label = 'skip' }, @{ key = 'q'; label = 'quit' })
            if ($r -eq 'q') { Quit 2 'Quit.' }
            if ($r -eq 's') { return $false }
        }
    }
}

function Resolve-Backend {
    # Returns @{ backend; useLogin }
    Write-Head 'How the agents run'
    $b = $Backend
    if (-not $b) { $b = if ($env:AGENTCRAFT_BACKEND) { $env:AGENTCRAFT_BACKEND } else { 'claude' } }
    if ($b -eq 'sim') { Write-Ok 'backend: sim (scripted demo team, no API calls)'; return @{ backend = 'sim'; useLogin = $false } }
    if ($b -eq 'codex') {
        $codex = Get-Cmd 'codex'
        if ($codex) { Write-Ok "backend: codex ($codex); log in with `codex login` if you have not" }
        else {
            $a = Confirm-Install 'the Codex CLI' 'npm install -g @openai/codex' 'The codex backend runs the team on your Codex login.'
            if ($a -eq 'quit') { Quit 2 'Quit.' }
            if ($a -eq 'install') {
                $npm = Get-Cmd 'npm.cmd'; if (-not $npm) { $npm = Get-Cmd 'npm' }
                if ($npm -and (Invoke-Visible $npm @('install', '-g', '@openai/codex')) -eq 0) { Update-SessionPath; Write-Ok 'codex installed; run `codex login` once' }
                else { Write-Warn2 'could not install codex; the launch may fail' }
            }
        }
        return @{ backend = 'codex'; useLogin = $false }
    }
    $login = $UseClaudeLogin.IsPresent -or (Test-ConfiguredLogin)
    $key = [bool]$env:ANTHROPIC_API_KEY
    if (-not $login -and -not $key) {
        $a = Read-Choice 'No Claude login or API key is configured. Which do you want?' @(
            @{ key = 'l'; label = 'my Claude login (claude.ai plan, personal use)' },
            @{ key = 'k'; label = 'an Anthropic API key (paste it; kept for this launch only)' },
            @{ key = 's'; label = 'the free simulated team (no API, scripted agents)' },
            @{ key = 'q'; label = 'quit' }
        )
        switch ($a) {
            'l' { $login = $true }
            'k' {
                $k = Read-Text 'ANTHROPIC_API_KEY'
                if (-not $k) { Quit 2 'No key given.' }
                $env:ANTHROPIC_API_KEY = $k
                $key = $true
            }
            's' { Write-Ok 'backend: sim'; return @{ backend = 'sim'; useLogin = $false } }
            default { Quit 2 'Quit.' }
        }
    }
    if ($login) {
        if (Resolve-ClaudeLogin) { Write-Ok 'backend: claude, login mode'; return @{ backend = 'claude'; useLogin = $true } }
        if ($key) { Write-Ok 'backend: claude, API key'; return @{ backend = 'claude'; useLogin = $false } }
        $a = Read-Choice 'Without a login: use the simulated team for now?' @(@{ key = 'y'; label = 'yes, sim' }, @{ key = 'q'; label = 'quit' })
        if ($a -eq 'q') { Quit 2 'Quit.' }
        return @{ backend = 'sim'; useLogin = $false }
    }
    Write-Ok 'backend: claude, ANTHROPIC_API_KEY from the environment'
    return @{ backend = 'claude'; useLogin = $false }
}

# --- repo and world ------------------------------------------------------------------------------

function Resolve-Repos {
    Write-Head 'Repository for the agents'
    $out = @()
    $given = @()
    if ($Repo) { $given = $Repo }
    foreach ($r in $given) {
        $full = [System.IO.Path]::GetFullPath($r)
        if (-not (Test-Path -LiteralPath (Join-Path $full '.git'))) { Quit 1 "-Repo $full is not the root of a git repository" }
        $out += $full
        Write-Ok "repo: $full"
    }
    if (@($out).Count -or -not $script:Interactive) { if (-not @($out).Count) { Write-Info 'no repo given; the agents start with the demo repo in sandbox/' }; return $out }
    Write-Info 'The agents code in a git repository of yours (each task in its own worktree). Enter skips this and uses the demo repo in sandbox/.'
    while ($true) {
        $p = Read-Text 'Path to a repository root (Enter = none)'
        if (-not $p) { return $out }
        try { $full = (Resolve-Path -LiteralPath $p -ErrorAction Stop).Path } catch { Write-Warn2 "not found: $p"; continue }
        if (-not (Test-Path -LiteralPath (Join-Path $full '.git'))) { Write-Warn2 "$full has no .git folder; give the repository root"; continue }
        $out += $full
        Write-Ok "repo: $full"
        $more = Read-Choice 'Add another repository?' @(@{ key = 'n'; label = 'no' }, @{ key = 'y'; label = 'yes' })
        if ($more -ne 'y') { return $out }
    }
}

function Resolve-World {
    Write-Head 'Which world'
    if ($World) { Write-Ok "world: $World"; return $World }
    if ($env:AGENTCRAFT_WORLD -and (@('studio', 'survival') -contains $env:AGENTCRAFT_WORLD)) { Write-Ok "world: $($env:AGENTCRAFT_WORLD) (AGENTCRAFT_WORLD)"; return $env:AGENTCRAFT_WORLD }
    $a = Read-Choice 'Which world do you want to open?' @(
        @{ key = '1'; label = 'Studio    - the agents'' world: creative, flat meadow, nothing spawns' },
        @{ key = '2'; label = 'Survival  - a real world in survival mode, the HQ built into its terrain' },
        @{ key = 'q'; label = 'quit' }
    )
    switch ($a) {
        '1' { return 'studio' }
        '2' { return 'survival' }
        default { Quit 2 'Quit.' }
    }
}

# --- main ----------------------------------------------------------------------------------------

try {
    if (-not (Test-Path -LiteralPath $Logs)) { New-Item -ItemType Directory -Path $Logs -Force | Out-Null }
    $logFile = Join-Path $Logs ('start-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
    try { Start-Transcript -Path $logFile -Append | Out-Null; $script:Transcript = $true } catch {}

    Write-Host ''
    Write-Host '  AgentCraft - guided start' -ForegroundColor Cyan
    Write-Host '  Checks what the launcher needs, offers to install what is missing, then starts the game.' -ForegroundColor DarkGray
    if ($Check) { Write-Host '  -Check: nothing will be launched.' -ForegroundColor DarkGray }
    if ($Yes) { Write-Host '  -Yes: every question gets its default answer.' -ForegroundColor DarkGray }
    Write-Host "  log: $logFile" -ForegroundColor DarkGray

    Test-Checkout
    Update-SessionPath
    if (Get-Winget) { Write-Ok 'winget: available for installs' } else { Write-Info 'winget not found; installs use portable copies in your user profile (https://aka.ms/getwinget to add winget)' }
    Resolve-Git
    Resolve-Node
    Resolve-Java

    $ready = [bool](Get-Cmd 'git') -and ((Get-NodeMajor).major -ge 22) -and ((Test-JavaHome $env:JAVA_HOME) -ge 25)
    if (-not $ready) {
        Write-Head 'Summary'
        if (-not (Get-Cmd 'git')) { Write-Bad 'git: missing' }
        if ((Get-NodeMajor).major -lt 22) { Write-Bad 'Node.js 22+: missing' }
        if ((Test-JavaHome $env:JAVA_HOME) -lt 25) { Write-Bad 'Java 25 JDK: missing' }
        Quit 1 'Not everything is in place; see above. Run this again once it is.'
    }

    $be = Resolve-Backend
    if ($Check) {
        Write-Head 'Summary'
        Write-Ok 'everything the launcher needs is installed'
        Write-Info "launch with: tools\launch.ps1 -World studio|survival"
        Quit 0 'Check complete.'
    }
    $repos = Resolve-Repos
    $world = Resolve-World

    # named parameters for launch.ps1 (an array would be passed positionally and land in -ShowcaseAt)
    $splat = [ordered]@{ World = $world; Backend = $be.backend }
    if ($be.useLogin) { $splat.UseClaudeLogin = $true }
    if (@($repos).Count) { $splat.Repo = [string[]]@($repos) }
    if ($LaunchArgs) {
        # -LaunchArgs '-Dev','-Seed','1234': a token starting with '-' names a parameter, the next
        # token (unless it is another '-name') is its value, otherwise it is a switch. Started with
        # -File (or from the .cmd) the list arrives as one comma-joined string, so split on commas.
        $LaunchArgs = @($LaunchArgs | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        for ($i = 0; $i -lt $LaunchArgs.Count; $i++) {
            $tok = [string]$LaunchArgs[$i]
            if ($tok -notmatch '^-([A-Za-z][A-Za-z0-9]*)$') { Quit 1 "-LaunchArgs: expected a -Name before '$tok'" }
            $name = $Matches[1]
            if ($i + 1 -lt $LaunchArgs.Count -and ([string]$LaunchArgs[$i + 1]) -notmatch '^-[A-Za-z]') { $splat[$name] = $LaunchArgs[$i + 1]; $i++ }
            else { $splat[$name] = $true }
        }
    }
    $shown = foreach ($k in $splat.Keys) { if ($splat[$k] -is [bool]) { "-$k" } else { "-$k " + (@($splat[$k]) -join ',') } }

    Write-Head 'Launching'
    Write-Info ("tools\launch.ps1 " + ($shown -join ' '))
    Write-Info 'First launch: Gradle downloads Minecraft and Fabric and builds the mod (a few minutes). Later launches take under a minute.'
    # launch.ps1 manages its own errors and exit code, and reads optional JSON properties that strict
    # mode would turn into errors (both settings are inherited by the script we call)
    $ErrorActionPreference = 'Continue'
    Set-StrictMode -Off
    $code = 0
    Push-Location -LiteralPath $Root
    try {
        & $Launcher @splat
        if ($null -ne $LASTEXITCODE) { $code = $LASTEXITCODE }
        if (-not $? -and $code -eq 0) { $code = 1 }
    }
    catch {
        Write-Bad "the launcher threw: $($_.Exception.Message)"
        $code = 1
    }
    finally {
        Pop-Location
        $ErrorActionPreference = 'Stop'
    }
    if ($code -ne 0) {
        Write-Bad "launch failed (exit $code). The game log is artifacts\logs\game.log, the Foreman log artifacts\logs\foreman-*.log."
        Quit 3 'Launch failed.'
    }
    if ($splat.Contains('DryRun')) { Quit 0 'Dry run complete; nothing was started.' }
    Quit 0 'Done: the studio is running. Stop it with tools\stop.ps1 (or quit the game; the agents keep working).'
}
catch {
    if ($_.Exception.Message -ne 'AGENTCRAFT_QUIT') {
        # anything but Quit(): a bug or a surprise on this machine; the exit code says so
        Write-Host ''
        Write-Bad "Unexpected error: $($_.Exception.Message)"
        if ($_.InvocationInfo -and $_.InvocationInfo.PositionMessage) { Write-Info $_.InvocationInfo.PositionMessage.Trim() }
        Write-Info "The full log is in $logFile"
        $script:ExitCode = 4
    }
}
finally {
    if ($script:Transcript) { try { Stop-Transcript | Out-Null } catch {} }
}
exit $script:ExitCode
