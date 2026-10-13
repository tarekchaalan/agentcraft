<#
.SYNOPSIS
  AgentCraft one-command launcher: starts (or reuses) the Foreman, then builds and starts Minecraft.

.DESCRIPTION
  1. Checks first, before anything starts: every argument, this checkout, Node.js 22+, a Java 25 JDK
     (JAVA_HOME when it is 25+, else the newest JDK 25 in the usual install folders; used for the
     game only, your JAVA_HOME is not changed) and that the ports are free or held by our own
     Foreman / game.
  2. First run: installs foreman/ and tools/ npm dependencies if node_modules is missing or older
     than package-lock.json (the Gradle wrapper downloads Gradle, Minecraft and Fabric by itself).
  3. Foreman: if one is already running for <home>/<profile> (foreman.json pid alive + port
     answering) it is reused; otherwise it is started in the background (hidden console, log in
     artifacts/logs/, run file in artifacts/run/ so tools/stop.ps1 can stop it).
  4. Minecraft: `gradlew runClient` (builds if needed) with the Foreman/DevBridge ports passed via
     env, then waits until the HQ world is ready. Real use: sound on, window comes to front.
     -Dev: muted, never steals focus, no toasts (unattended QA / agents).
  Stop everything with tools\stop.ps1 (only what this script started).

  If the launch fails or you press Ctrl+C, what THIS run started (its Foreman, its game) is stopped
  again the way tools\stop.ps1 does it; anything it reused, and anything it did not start, is left
  alone. -KeepOnFailure leaves it running instead.

  Exit codes:
    0    ok: running (or -DryRun, -Login, -NoGame, -NoWait done)
    1    a step failed: the Foreman did not come up, the build failed, the game hung, the world or
         the showcase state was not ready in time (the log tail is printed), or an unexpected error
    2    bad arguments: an unknown parameter, a value out of range, a folder that does not exist
    3    a prerequisite is missing: Node.js 22+, a Java 25 JDK, part of this checkout, an npm install
         that failed, or (with -UseClaudeLogin) the agents' Claude Code is not signed in
    4    something else is in the way: a port is taken by a process this launcher did not start, or a
         game of this checkout was started some other way
    130  interrupted (Ctrl+C)

.EXAMPLE
  tools\launch.ps1                                     # claude backend (needs ANTHROPIC_API_KEY), ~/.agentcraft, ports 7878/7879
  tools\launch.ps1 -Repo C:\code\life-tracker          # work in this folder (a git repo, or any folder, even an empty one)
  tools\launch.ps1 -UseClaudeLogin                     # personal use: your `claude` CLI login instead of an API key
  tools\launch.ps1 -Login                              # sign the agents' Claude Code in once (then -UseClaudeLogin)
  tools\launch.ps1 -Backend codex                      # an all-Codex team on your Codex login (`codex login`)
  tools\launch.ps1 -ForemanArgs '--worker-engine','codex'   # mixed: Claude lead, Codex workers
  tools\launch.ps1 -Backend sim                        # scripted demo team, no API calls
  tools\launch.ps1 -World survival                     # play survival: a real world with the HQ built into its terrain
  tools\launch.ps1 -World survival -Seed 1234          # ... on a fixed seed
  tools\launch.ps1 -Showcase late                      # static showcase state (sim)
  tools\launch.ps1 -Backend sim -DryRun                # print what would start, start nothing
  tools\launch.ps1 -Dev -Showcase busy -Home C:\Projects\agentcraft\.agentcraft-home -Port 27878 -DevPort 7889
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    # sim | claude | codex (default AGENTCRAFT_BACKEND, else claude)
    [ArgumentCompleter({ param($c, $p, $word) @('sim', 'claude', 'codex') -like "$word*" })]
    [string]$Backend,
    [string[]]$Repo,
    [Alias('Profile')][string]$ForemanProfile,
    # which world the game opens: the agents' studio (creative, flat meadow, default) or survival (a
    # normal world in survival mode with the HQ built into its terrain, saved as "AgentCraft Survival")
    [ArgumentCompleter({ param($c, $p, $word) @('studio', 'survival') -like "$word*" })]
    [string]$World,
    # seed of a world created on this launch (number or text); unset: fixed for the studio, random for survival
    [string]$Seed,
    # -Showcase [busy|late]: a switch with an optional positional value
    [switch]$Showcase,
    [Parameter(Position = 0)][string]$ShowcaseAt,
    [switch]$Dev,
    [string]$Port,
    [string]$DevPort,
    [Alias('Home')][string]$AgentHome,
    [string]$GradleHome,
    [switch]$Reset,
    [string]$Speed,
    [switch]$Autostart,
    [string]$Goal,
    [string[]]$ForemanArgs,
    # claude backend: use your local `claude` CLI login instead of ANTHROPIC_API_KEY (personal use only)
    [switch]$UseClaudeLogin,
    [switch]$Notify,
    [switch]$NoNotify,
    [switch]$NoGame,
    [switch]$NoForeman,
    [switch]$NoWait,
    # seconds to wait for the world (default 600; 1800 on a checkout's first build)
    [string]$TimeoutSec,
    [string]$SummaryJson,
    # print what would be started (commands + game env) without starting anything
    [switch]$DryRun,
    # sign the agents' Claude Code in (needed once for -UseClaudeLogin; its login is separate from the Claude desktop app's), then exit
    [switch]$Login,
    # on a failure or Ctrl+C, leave what this run started running (default: stop it again)
    [switch]$KeepOnFailure,
    # anything else on the command line: reported as an error (exit 2)
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$UnknownArgs
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\procs.ps1')
. (Join-Path $PSScriptRoot 'lib\checks.ps1')

$EXIT_OK = 0; $EXIT_FAILED = 1; $EXIT_ARGS = 2; $EXIT_MISSING = 3; $EXIT_BUSY = 4; $EXIT_INTERRUPTED = 130

$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$L = Get-AcLayout $Root
$Main = Get-MainCheckout $Root
$t0 = Get-Date
$summary = [ordered]@{ ok = $false; checkout = $Root; dev = [bool]$Dev; foreman = $null; game = $null; error = $null; exitCode = $null }
$script:ExitCode = $null                                    # set on every way out; $null in `finally` = interrupted
$script:Started = [ordered]@{ foreman = $null; game = $null }  # what THIS run started (cleanup stops only these)
$script:NpmProc = $null                                     # an npm install in progress
$script:AcBgStarting = $null                                # a background start not yet recorded (procs.ps1 Start-Bg)
$node = $null

function Save-Summary {
    if (-not $SummaryJson) { return }
    try { Write-JsonFile $SummaryJson $summary } catch { Write-Warn2 "could not write -SummaryJson ${SummaryJson}: $($_.Exception.Message)" }
}

function Exit-Launch([int]$Code) {
    $script:ExitCode = $Code
    $summary.exitCode = $Code
    if ($Code -eq $EXIT_OK) { $summary.ok = $true }
    Save-Summary
    exit $Code
}

function Fail([string]$Message, [string[]]$Tail, [int]$Code = $EXIT_FAILED) {
    Write-Fail $Message
    if ($Tail) {
        Write-Host '  --- log tail ---' -ForegroundColor DarkGray
        foreach ($l in $Tail) { Write-Host "  $l" -ForegroundColor DarkGray }
    }
    $summary.error = $Message
    Exit-Launch $Code
}

# A tools/*.mjs CLI that prints JSON: its stdout parsed, or $null. Never throws on its stderr.
function Invoke-ToolJson([string]$Script, [string[]]$Arguments, [int]$TimeoutS) {
    $r = Invoke-Captured $node.Source (@((Join-Path $L.Tools $Script)) + $Arguments) $TimeoutS
    $json = $null
    try { if ($r.stdout.Trim()) { $json = $r.stdout | ConvertFrom-Json } } catch {}
    return @{ code = $r.code; json = $json; out = $r.out; timedOut = $r.timedOut }
}

function Get-ForemanStatus([int]$FmPort, [int]$WaitShowcaseS = 0) {
    $a = @('status', '--port', [string]$FmPort, '--timeout', '20')
    if ($WaitShowcaseS) { $a += @('--wait-showcase', [string]$WaitShowcaseS) }
    return (Invoke-ToolJson 'foreman-cli.mjs' $a (60 + $WaitShowcaseS)).json
}

# tools\stop.ps1 in this PowerShell (no command line to quote), with named parameters.
function Invoke-StopScript([hashtable]$Params) {
    $global:LASTEXITCODE = 0
    try { & (Join-Path $PSScriptRoot 'stop.ps1') @Params | Out-Host } catch { Write-Warn2 "stop.ps1 failed: $($_.Exception.Message)"; return 1 }
    return [int]$global:LASTEXITCODE
}

# On a failure or Ctrl+C: stop what this run started, nothing else (pid + start time, as stop.ps1 does).
function Stop-ThisRun([string]$Why) {
    $s = $script:Started
    if (-not ($s.foreman -or $s.game -or $script:NpmProc -or $script:AcBgStarting)) { return }
    Write-Head "Stopping what this launch started ($Why)"
    if ($script:NpmProc) {
        $k = @(Stop-OwnTree $script:NpmProc.pid $script:NpmProc.start)
        Write-Kv 'npm' "install stopped (pid(s) $($k -join ', ')); the next launch installs again" 'Yellow'
        $script:NpmProc = $null
    }
    if ($s.foreman -or $s.game) {
        $tmp = Join-Path $L.Run "launch-cleanup-$PID.json"
        $fmPart = [ordered]@{ started = [bool]$s.foreman; pid = $null }
        if ($s.foreman) { $fmPart.pid = $s.foreman.pid }
        Write-JsonFile $tmp ([ordered]@{ game = [ordered]@{ started = [bool]$s.game }; foreman = $fmPart })
        $code = Invoke-StopScript @{ FromSummary = $tmp; TimeoutSec = 20 }
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
        if ($code -ne 0) { Write-Warn2 "not everything stopped (stop.ps1 exit $code); see above" }
    }
    # a background start that was interrupted before it was recorded
    $b = $script:AcBgStarting
    if ($b -and (Test-SameProc ([int]$b.pid) $b.start)) {
        $k = @(Stop-OwnTree ([int]$b.pid) $b.start)
        Write-Kv 'stopped' "$($b.name) wrapper (pid(s) $($k -join ', '))" 'Yellow'
    }
    $script:AcBgStarting = $null
    $summary.cleanedUp = $true
}

function Show-LeftRunning {
    $s = $script:Started
    if (-not ($s.foreman -or $s.game)) { return }
    Write-Warn2 '-KeepOnFailure: left running what this launch started:'
    if ($s.foreman) { Write-Kv 'foreman' "pid $($s.foreman.pid)  (tools\stop.ps1 -Foreman -Profile $($s.foreman.profile))" 'Yellow' }
    if ($s.game) { Write-Kv 'game' "gradlew pid $($s.game.gradlePid)  (tools\stop.ps1 -Game)" 'Yellow' }
}

# npm dependencies: installed when node_modules is missing (first run), when an earlier install did
# not finish, or when it is older than the lockfile (after a pull that changed dependencies).
function Install-NodeDeps([string]$Dir, [string]$Name) {
    $lock = Join-Path $Dir 'package-lock.json'
    $stamp = Join-Path $Dir 'node_modules\.package-lock.json'
    $why = $null
    if (-not (Test-Path -LiteralPath (Join-Path $Dir 'node_modules'))) { $why = 'first run' }
    elseif ((Test-Path -LiteralPath $lock) -and -not (Test-Path -LiteralPath $stamp)) { $why = 'an earlier install did not finish' }
    elseif ((Test-Path -LiteralPath $lock) -and ((Get-Item -LiteralPath $lock).LastWriteTimeUtc -gt (Get-Item -LiteralPath $stamp).LastWriteTimeUtc.AddSeconds(2))) { $why = 'package-lock.json changed' }
    if (-not $why) { return }
    $log = Join-Path $L.Logs "npm-$Name.log"
    $verb = 'install'
    if (Test-Path -LiteralPath $lock) { $verb = 'ci' }
    if ($DryRun) { Write-Kv 'would run' "npm $verb in $Name/ ($why)" 'Yellow'; return }
    Write-Kv 'setup' "npm $verb in $Name/ ($why; log $log) ..." 'Yellow'
    $p = Start-Process -FilePath $env:ComSpec -ArgumentList "/d /s /c `"npm $verb --no-audit --no-fund`"" -WorkingDirectory $Dir -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru
    $null = $p.Handle   # keeps the exit code readable after the process ends
    $script:NpmProc = @{ pid = $p.Id; start = (Get-ProcStart $p.Id) }
    $deadline = (Get-Date).AddMinutes(15)
    while (-not $p.HasExited) {
        if ((Get-Date) -ge $deadline) {
            Stop-OwnTree $script:NpmProc.pid $script:NpmProc.start | Out-Null
            $script:NpmProc = $null
            Fail "npm $verb in $Name/ did not finish in 15 minutes (stopped it)" ((Get-LogTail $log 10) + (Get-LogTail "$log.err" 15)) $EXIT_MISSING
        }
        Start-Sleep -Milliseconds 300
    }
    $script:NpmProc = $null
    if ($p.ExitCode -ne 0) { Fail "npm $verb failed in $Name/ (exit $($p.ExitCode))" ((Get-LogTail $log 10) + (Get-LogTail "$log.err" 15)) $EXIT_MISSING }
}

# The Claude Code CLI the agent SDK runs (bundled with the SDK); its login is separate from the Claude desktop app's.
function Get-ClaudeCli {
    $arch = ([string]$env:PROCESSOR_ARCHITECTURE).ToLowerInvariant() -replace 'amd64', 'x64'
    $bundled = Join-Path $L.Foreman "node_modules\@anthropic-ai\claude-agent-sdk-win32-$arch\claude.exe"
    if (Test-Path -LiteralPath $bundled) { return $bundled }
    $c = Get-Command claude -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return $c.Source }
    return $null
}

function Test-ClaudeLoggedIn([string]$Cli) {
    $r = Invoke-Captured $Cli @('auth', 'status') 40
    $m = [regex]::Match($r.stdout, '\{[\s\S]*\}')
    if (-not $m.Success) { return $false }
    try { return [bool](($m.Value | ConvertFrom-Json).loggedIn) } catch { return $false }
}

try {
    if ($SummaryJson) {
        try { $SummaryJson = Resolve-FullPath $SummaryJson } catch {}
    }

    # --- 1. arguments: all of them checked before anything runs (exit 2) -------------------------

    $bad = New-Object System.Collections.Generic.List[string]
    foreach ($u in @($UnknownArgs | Where-Object { $null -ne $_ -and $_ -ne '' })) {
        if ($u -match '^-') { $bad.Add("unknown parameter $u") } else { $bad.Add("unexpected argument '$u'") }
    }
    if ($ShowcaseAt -and @('busy', 'late') -notcontains $ShowcaseAt) {
        if (@('studio', 'survival') -contains $ShowcaseAt) { $bad.Add("unexpected argument '$ShowcaseAt' (for the world, use -World $ShowcaseAt)") }
        else { $bad.Add("unexpected argument '$ShowcaseAt' (-Showcase takes busy or late)") }
    }
    $showcaseOn = $Showcase.IsPresent -or [bool]$ShowcaseAt
    if ($showcaseOn) {
        if ($Backend -and $Backend -ne 'sim') { $bad.Add('-Showcase needs the sim backend (it holds a scripted state).') }
        $Backend = 'sim'
        if (-not $ShowcaseAt) { $ShowcaseAt = 'busy' }
    }
    $backendFrom = '-Backend'
    if (-not $Backend) {
        if ($env:AGENTCRAFT_BACKEND) { $Backend = $env:AGENTCRAFT_BACKEND; $backendFrom = 'AGENTCRAFT_BACKEND' } else { $Backend = 'claude' }
    }
    if (@('sim', 'claude', 'codex') -notcontains $Backend) { $bad.Add("bad $backendFrom '$Backend' (sim, claude or codex)") }
    $worldFrom = '-World'
    if (-not $World) {
        if ($env:AGENTCRAFT_WORLD) { $World = $env:AGENTCRAFT_WORLD; $worldFrom = 'AGENTCRAFT_WORLD' } else { $World = 'studio' }
    }
    if (@('studio', 'survival') -notcontains $World) { $bad.Add("bad $worldFrom '$World' (studio or survival)") }
    if (-not $ForemanProfile) {
        if ($showcaseOn) { if ($ShowcaseAt -eq 'late') { $ForemanProfile = 'showcase-late' } else { $ForemanProfile = 'showcase' } }
        else { $ForemanProfile = $Backend }
    }
    $e = Test-ProfileName $ForemanProfile
    if ($e) { $bad.Add($e) }

    $portGiven = $PSBoundParameters.ContainsKey('Port')
    $portSpecs = @(
        @{ name = 'Port'; given = $Port; env = 'AGENTCRAFT_PORT'; default = '7878' },
        @{ name = 'DevPort'; given = $DevPort; env = 'AGENTCRAFT_DEV_PORT'; default = '7879' }
    )
    $ports = @{}
    foreach ($ps in $portSpecs) {
        $raw = $ps.given
        $what = "-$($ps.name)"
        if (-not $PSBoundParameters.ContainsKey($ps.name)) {
            if ([Environment]::GetEnvironmentVariable($ps.env)) { $raw = [Environment]::GetEnvironmentVariable($ps.env); $what = $ps.env } else { $raw = $ps.default }
        }
        $r = Test-PortValue $raw $what
        if (-not $r.ok) { $bad.Add($r.error) }
        $ports[$ps.name] = $r.port
    }
    [int]$Port = $ports.Port
    [int]$DevPort = $ports.DevPort
    if ($Port -and $Port -eq $DevPort) { $bad.Add("-Port and -DevPort must differ (both $Port)") }

    $timeoutGiven = $PSBoundParameters.ContainsKey('TimeoutSec')
    [int]$waitSec = 600
    if ($timeoutGiven) {
        $r = Test-IntValue $TimeoutSec '-TimeoutSec' 10 86400
        if ($r.ok) { $waitSec = $r.value } else { $bad.Add($r.error) }
    }
    $speedValue = $null
    if ($PSBoundParameters.ContainsKey('Speed')) {
        $r = Test-PositiveNumber $Speed '-Speed'
        if ($r.ok) { $speedValue = $r.value } else { $bad.Add($r.error) }
    }
    if ($PSBoundParameters.ContainsKey('Seed')) { $e = Test-SeedText $Seed; if ($e) { $bad.Add($e) } }
    if ($PSBoundParameters.ContainsKey('Goal') -and -not ([string]$Goal).Trim()) { $bad.Add('-Goal is empty') }
    if ($Notify -and $NoNotify) { $bad.Add('-Notify and -NoNotify contradict each other; pick one') }

    if (-not $AgentHome) {
        if ($env:AGENTCRAFT_HOME) { $AgentHome = $env:AGENTCRAFT_HOME }
        elseif ($Dev) { $AgentHome = Join-Path $Main '.agentcraft-home' }   # unattended runs never touch ~/.agentcraft
        else { $AgentHome = Join-Path $env:USERPROFILE '.agentcraft' }
    }
    try { $AgentHome = Resolve-FullPath $AgentHome; $e = Test-FolderTarget $AgentHome '-Home'; if ($e) { $bad.Add($e) } }
    catch { $bad.Add("bad -Home '$AgentHome': $($_.Exception.Message)") }
    if (-not $GradleHome) {
        if ($env:GRADLE_USER_HOME) { $GradleHome = $env:GRADLE_USER_HOME } else { $GradleHome = Join-Path $Main '.gradle-home' }
    }
    try { $GradleHome = Resolve-FullPath $GradleHome; $e = Test-FolderTarget $GradleHome '-GradleHome'; if ($e) { $bad.Add($e) } }
    catch { $bad.Add("bad -GradleHome '$GradleHome': $($_.Exception.Message)") }
    if ($SummaryJson) { $e = Test-FileTarget $SummaryJson '-SummaryJson'; if ($e) { $bad.Add($e); $SummaryJson = $null } }
    $repoPaths = @()
    foreach ($r in @($Repo | Where-Object { $_ })) {
        foreach ($part in ($r -split ',')) {
            if (-not $part.Trim()) { continue }
            try { $full = Resolve-FullPath $part.Trim() } catch { $bad.Add("bad -Repo '$($part.Trim())': $($_.Exception.Message)"); continue }
            if (-not (Test-Path -LiteralPath $full -PathType Container)) { $bad.Add("-Repo $full is not an existing folder"); continue }
            $repoPaths += $full
        }
    }
    if ($bad.Count) {
        foreach ($b in $bad) { Write-Fail $b }
        Write-Host '  Parameters: Get-Help tools\launch.ps1 -Detailed, or tools\README.md' -ForegroundColor DarkGray
        $summary.error = ($bad -join '; ')
        Exit-Launch $EXIT_ARGS
    }
    if ($Dev -and $Notify) { Write-Warn2 '-Notify ignored: -Dev turns desktop notifications off' }

    New-Item -ItemType Directory -Force $L.Logs, $L.Run | Out-Null

    Write-Host ''
    Write-Host 'AgentCraft' -ForegroundColor Cyan -NoNewline
    if ($Dev) { Write-Host '  (dev: muted, no focus, no toasts)' -ForegroundColor DarkGray } else { Write-Host '' }
    if ($DryRun) { Write-Kv 'dry run' 'nothing is started' 'Yellow' }
    Write-Kv 'checkout' $Root
    Write-Kv 'home' "$AgentHome  (profile $ForemanProfile)"

    # --- 2. this checkout and the prerequisites (exit 3) -------------------------------------------

    $need = @('tools\lib\bgrun.mjs', 'tools\stop.ps1', 'tools\foreman-cli.mjs', 'tools\devbridge-cli.mjs', 'tools\package.json', 'foreman\package.json', 'foreman\src\main.ts')
    if (-not $NoGame) { $need += @('mod\gradlew.bat', 'mod\build.gradle') }
    $missing = @($need | Where-Object { -not (Test-Path -LiteralPath (Join-Path $Root $_)) })
    if ($missing.Count) { Fail "this checkout is incomplete (missing $($missing -join ', ')); clone it again" @() $EXIT_MISSING }

    $node = Get-Command node -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $node) { Fail 'Node.js 22+ is required (https://nodejs.org). `node` is not on PATH.' @() $EXIT_MISSING }
    $nv = Test-NodeVersion (Invoke-Captured $node.Source @('--version') 20).stdout 22
    if (-not $nv.ok) { Fail "Node.js 22+ is required (found '$($nv.version)' at $($node.Source))" @() $EXIT_MISSING }

    if ($Login) {
        Install-NodeDeps $L.Foreman 'foreman'
        $cli = Get-ClaudeCli
        if (-not $cli) { Fail 'no Claude Code CLI found (foreman/node_modules is missing the SDK binary and `claude` is not on PATH)' @() $EXIT_MISSING }
        if ($DryRun) { Write-Kv 'would run' "$cli auth login" 'Yellow'; Exit-Launch $EXIT_OK }
        Write-Kv 'login' "$cli auth login ..."
        & $cli auth login
        if ($LASTEXITCODE -ne 0 -or -not (Test-ClaudeLoggedIn $cli)) { Fail 'Claude login did not complete' }
        Write-Kv 'login' 'ok. Now launch with -UseClaudeLogin.' 'Green'
        Exit-Launch $EXIT_OK
    }

    $jdk = $null
    if (-not $NoGame) {
        $jdk = Find-Jdk 25
        if (-not $jdk) {
            $why = 'none found in JAVA_HOME, the usual install folders or on PATH'
            $jh = Get-JavaHomeMajor $env:JAVA_HOME
            if ($env:JAVA_HOME) { $why = "JAVA_HOME ($env:JAVA_HOME) is Java $jh, and no Java 25 JDK was found in the usual install folders" }
            Fail "Java 25 is required for Minecraft 26.3: $why. Install Temurin 25 (https://adoptium.net), or run start-agentcraft.cmd, which offers a portable copy." @() $EXIT_MISSING
        }
        Write-Kv 'java' "$($jdk.home)  (Java $($jdk.major), $($jdk.source))"
        if ($jdk.source -ne 'JAVA_HOME' -and $env:JAVA_HOME) {
            Write-Kv '' "JAVA_HOME is Java $(Get-JavaHomeMajor $env:JAVA_HOME); the game uses the JDK above (JAVA_HOME itself is unchanged)" 'DarkGray'
        }
    }

    # --- 3. what is running, and the ports (exit 4) ------------------------------------------------

    $fmRunFile = Get-ForemanRunFile $L $AgentHome $ForemanProfile
    $live = Get-LiveForeman $AgentHome $ForemanProfile
    $gameRunFile = Get-GameRunFile $L
    $liveGame = $null
    if (-not $NoGame) {
        $prev = Read-JsonFile $gameRunFile
        if ($prev) {
            $gAlive = $prev.gamePid -and (Test-SameProc ([int]$prev.gamePid) $prev.gameStart)
            $wAlive = $prev.wrapperPid -and (Test-SameProc ([int]$prev.wrapperPid) $prev.wrapperStart)
            if ($gAlive -or $wAlive) { $liveGame = $prev } else { Remove-Item -LiteralPath $gameRunFile -Force -ErrorAction SilentlyContinue }
        }
        if (-not $liveGame) {
            # a game of this checkout started some other way (plain gradlew runClient) still owns mod/run
            $stray = Find-GameJvm $L
            if ($stray) { Fail "a Minecraft client of this checkout is already running ($(Get-ProcLabel $stray.ProcessId)) but was not started by launch.ps1. Quit it first (node tools/devbridge-cli.mjs quit)." @() $EXIT_BUSY }
            $owner = Get-PortOwner $DevPort
            if ($owner -or (Test-PortOpen $DevPort)) {
                $who = 'another process'
                if ($owner) { $who = Get-ProcLabel $owner }
                Fail "DevBridge port $DevPort is already in use by $who (another game?). Use -DevPort N." @() $EXIT_BUSY
            }
        }
    }
    if (-not $live -and -not $NoForeman) {
        $owner = Get-PortOwner $Port
        if ($owner -or (Test-PortOpen $Port)) {
            $who = 'another process'
            if ($owner) { $who = Get-ProcLabel $owner }
            Fail "port $Port is already in use by $who. Another Foreman (other profile/home)? Use -Port N." @() $EXIT_BUSY
        }
    }

    # --- 4. npm dependencies of tools/ (exit 3) ----------------------------------------------------

    Install-NodeDeps $L.Tools 'tools'

    if ($Backend -eq 'claude' -and $UseClaudeLogin -and -not $NoForeman -and -not $DryRun) {
        # fail fast: a Foreman started without a login only reports the failure in the banner later
        $cli = Get-ClaudeCli
        if ($cli -and -not (Test-ClaudeLoggedIn $cli)) {
            Fail "the agents' Claude Code is not signed in (the Claude desktop app login is separate). Run: tools\launch.ps1 -Login" @() $EXIT_MISSING
        }
    }

    # --- 5. Foreman --------------------------------------------------------------------------------

    $fmInfo = $null
    Write-Head 'Foreman'
    if ($live -and $Backend -eq 'claude' -and -not $NoForeman -and -not $DryRun) {
        # a Foreman we started that failed its Claude check keeps failing until restarted; reusing it would hide a fixed login
        $own0 = Read-JsonFile $fmRunFile
        $ours0 = $own0 -and ([int]$own0.pid -eq [int]$live.pid) -and (Test-SameProc ([int]$own0.pid) $own0.startTime)
        if ($ours0) {
            $st0 = Get-ForemanStatus ([int]$live.port)
            if ($st0 -and $st0.ok -and $st0.foreman.auth -eq 'failed') {
                Write-Warn2 "Foreman $($live.pid) failed its Claude auth check; restarting it"
                $code = Invoke-StopScript @{ Foreman = $true; ForemanProfile = $ForemanProfile; AgentHome = $AgentHome }
                if ($code -ne 0) { Fail "could not stop Foreman $($live.pid) (stop.ps1 exit $code); stop it with tools\stop.ps1 -Foreman -Profile $ForemanProfile" }
                $live = Get-LiveForeman $AgentHome $ForemanProfile
                if ($live) { Fail "Foreman $($live.pid) is still answering on port $($live.port) after stop.ps1; stop it by hand" }
            }
        }
    }
    if ($live) {
        $fmPort = [int]$live.port
        $own = Read-JsonFile $fmRunFile
        $startedByUs = $own -and ([int]$own.pid -eq [int]$live.pid) -and (Test-SameProc ([int]$own.pid) $own.startTime)
        $by = 'started by an earlier launch.ps1'
        if (-not $startedByUs) { $by = 'not started by launch.ps1; stop.ps1 leaves it alone' }
        Write-Kv 'reusing' "pid $($live.pid)  ws://127.0.0.1:$fmPort  backend $($live.backend)  ($by)" 'Green'
        if ($portGiven -and $fmPort -ne $Port) { Write-Warn2 "-Port $Port ignored: profile '$ForemanProfile' is already served on $fmPort" }
        if ($live.backend -and $live.backend -ne $Backend) { Write-Warn2 "profile '$ForemanProfile' runs the $($live.backend) backend (asked for $Backend); stop it first to switch" }
        if ($Reset -or $showcaseOn -or $repoPaths.Count) {
            if ($Reset) { Write-Warn2 '-Reset ignored for a running Foreman (tools\stop.ps1 -Foreman first)' }
            foreach ($rp in $repoPaths) {
                if ($DryRun) { Write-Kv 'would run' "repo.add $rp" 'Yellow'; continue }
                $r = Invoke-ToolJson 'foreman-cli.mjs' @('repo-add', $rp, '--port', [string]$fmPort) 60
                if ($r.code -eq 0) { Write-Kv 'repo' "added $rp" } else { Write-Warn2 "repo.add $rp failed: $($r.out)" }
            }
        }
        $fmInfo = [ordered]@{ started = $false; reused = $true; ownedByLauncher = [bool]$startedByUs; pid = [int]$live.pid; port = $fmPort; backend = $live.backend; profile = $ForemanProfile; home = $AgentHome; log = $null }
        if ($own -and $startedByUs) { $fmInfo.log = $own.log }
    } elseif ($NoForeman) {
        $fmPort = $Port
        Write-Kv 'skipped' "-NoForeman: the game will look for a Foreman on port $fmPort" 'DarkGray'
        $fmInfo = [ordered]@{ started = $false; reused = $false; pid = $null; port = $fmPort; profile = $ForemanProfile; home = $AgentHome }
    } else {
        $fmPort = $Port
        # checked again right before the start: a restarted Foreman, or something new, may hold it now
        $owner = Get-PortOwner $Port
        if ($owner -or (Test-PortOpen $Port)) {
            $who = 'another process'
            if ($owner) { $who = Get-ProcLabel $owner }
            Fail "port $Port is already in use by $who. Another Foreman (other profile/home)? Use -Port N." @() $EXIT_BUSY
        }
        # foreman/node_modules is replaced only when no Foreman of this checkout is running on it
        $busy = @(Get-ChildItem -LiteralPath $L.Run -Filter 'foreman-*.json' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '\.(spec|status)\.json$' } | ForEach-Object {
            $rf = Read-JsonFile $_.FullName
            if ($rf -and $rf.pid -and (Test-SameProc ([int]$rf.pid) $rf.startTime)) { $rf }
        })
        if ($busy.Count -and (Test-Path -LiteralPath (Join-Path $L.Foreman 'node_modules'))) {
            Write-Warn2 "not checking foreman/ dependencies: Foreman '$($busy[0].profile)' of this checkout is running on them"
        } else {
            Install-NodeDeps $L.Foreman 'foreman'
        }
        $fargs = @('--import', 'tsx', 'src/main.ts', '--backend', $Backend, '--profile', $ForemanProfile, '--home', $AgentHome, '--port', [string]$Port)
        if ($showcaseOn) {
            $fargs += @('--reset', '--showcase')
            if ($ShowcaseAt -eq 'late') { $fargs += 'late' }
        } elseif ($Reset) { $fargs += '--reset' }
        if ($repoPaths.Count) { $fargs += @('--repo', ($repoPaths -join ',')) }
        if ($null -ne $speedValue) { $fargs += @('--speed', $speedValue.ToString([System.Globalization.CultureInfo]::InvariantCulture)) }
        if ($Autostart) { $fargs += '--autostart' }
        if ($Goal) { $fargs += @('--goal', $Goal) }
        if ($Dev -or $NoNotify) { $fargs += '--no-notify' } elseif ($Notify) { $fargs += '--notify' }
        if ($UseClaudeLogin) { $fargs += '--use-claude-login' }
        if ($ForemanArgs) { $fargs += $ForemanArgs }
        if ($DryRun) {
            Write-Kv 'would run' ("node " + (Join-CmdArgs $fargs) + "   (in foreman/)") 'Yellow'
            $fmInfo = [ordered]@{ started = $false; reused = $false; dryRun = $true; pid = $null; port = $Port; backend = $Backend; profile = $ForemanProfile; home = $AgentHome; args = $fargs }
        } else {
            $stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
            $fmLog = Join-Path $L.Logs "foreman-$ForemanProfile.log"
            $fmErr = Join-Path $L.Logs "foreman-$ForemanProfile.err.log"
            try {
                $bg = Start-Bg $L $node.Source "foreman-$ForemanProfile" $node.Source $fargs $L.Foreman $fmLog $fmErr @{}
            } catch { Fail $_.Exception.Message ((Get-LogTail $fmLog 15) + (Get-LogTail $fmErr 15)) }
            $fmInfo = [ordered]@{ kind = 'foreman'; started = $true; reused = $false; ownedByLauncher = $true; pid = $bg.pid; startTime = $bg.start; wrapperPid = $bg.wrapperPid; wrapperStart = $bg.wrapperStart; port = $Port; backend = $Backend; profile = $ForemanProfile; home = $AgentHome; showcase = $ShowcaseAt; log = $fmLog; errLog = $fmErr; startedAt = $stamp; checkout = $Root }
            Write-JsonFile $fmRunFile $fmInfo
            $script:Started.foreman = $fmInfo
            $script:AcBgStarting = $null
            Write-Kv 'starting' "pid $($bg.pid)  backend $Backend  ws://127.0.0.1:$Port"
            $deadline = (Get-Date).AddSeconds(120)
            $up = $false
            $exited = $false
            while ((Get-Date) -lt $deadline) {
                if (-not (Test-SameProc $bg.pid $bg.start)) { $exited = $true; break }
                $rf = Read-JsonFile (Join-Path (Join-Path $AgentHome $ForemanProfile) 'foreman.json')
                if ($rf -and [int]$rf.pid -eq $bg.pid -and (Test-PortOpen $Port)) { $up = $true; break }
                Start-Sleep -Milliseconds 300
            }
            if (-not $up) {
                Remove-Item -LiteralPath $fmRunFile -Force -ErrorAction SilentlyContinue
                Stop-OwnTree $bg.wrapperPid $bg.wrapperStart | Out-Null
                Stop-OwnTree $bg.pid $bg.start | Out-Null
                $script:Started.foreman = $null
                $how = 'did not come up in 120 s'
                if ($exited) { $how = 'exited while starting' }
                Fail "the Foreman $how (see $fmLog)" ((Get-LogTail $fmLog 15) + (Get-LogTail $fmErr 15))
            }
            Write-Kv 'running' ("ws://127.0.0.1:$Port  ready in {0:N1} s" -f ((Get-Date) - $t0).TotalSeconds) 'Green'
        }
    }
    if ($fmInfo.log) { Write-Kv 'log' $fmInfo.log }
    $summary.foreman = $fmInfo
    Save-Summary

    # backend/auth banner (and, for a showcase, wait until the scripted state is held)
    if (-not ($DryRun -and -not $fmInfo.reused) -and (-not $NoForeman -or (Test-PortOpen $fmPort))) {
        $waitShowcase = 0
        if ($showcaseOn) { $waitShowcase = 240 }
        $st = Get-ForemanStatus $fmPort $waitShowcase
        if ($st -and $st.ok) {
            $f = $st.foreman
            $authColor = 'Green'
            if ($f.auth -eq 'failed') { $authColor = 'Red' } elseif ($f.auth -ne 'ok') { $authColor = 'Yellow' }
            $line = "$($f.backend), auth $($f.auth)"
            if ($f.authMode) { $line += " ($($f.authMode))" }
            if ($f.message) { $line += " - $($f.message)" }
            Write-Kv 'status' $line $authColor
            $decs = @($st.openDecisions).Count
            $what = "$($st.activeAgents)/$($st.agents) agents on shift, $($st.tasks) tasks, $decs open decisions"
            if ($f.showcase) { $what += ', showcase held' }
            Write-Kv 'state' $what
            $fmInfo.status = $f
        } else {
            $err = 'no answer'
            if ($st -and $st.error) { $err = $st.error }
            if ($showcaseOn) { Fail "the Foreman did not reach the showcase state: $err" (Get-LogTail $fmInfo.log 15) }
            Write-Warn2 "could not read the Foreman status: $err"
        }
    }
    Save-Summary

    # --- 6. Minecraft ------------------------------------------------------------------------------

    if ($NoGame) {
        if (-not $DryRun) {
            Write-Host ''
            Write-Host "Stop:  tools\stop.ps1 -Foreman" -ForegroundColor DarkGray
        }
        Exit-Launch $EXIT_OK
    }

    Write-Head 'Minecraft'
    $gameInfo = $null
    if ($liveGame) {
        Write-Kv 'running' "already running: pid $($liveGame.gamePid)  DevBridge :$($liveGame.devPort)  (one client per checkout)" 'Green'
        if ([int]$liveGame.foremanPort -ne $fmPort) { Write-Warn2 "it was launched against Foreman port $($liveGame.foremanPort), not ${fmPort}: tools\stop.ps1 -Game, then launch again" }
        if ([int]$liveGame.devPort -ne $DevPort) { Write-Warn2 "its DevBridge is on $($liveGame.devPort) (asked for $DevPort)" }
        $gameInfo = [ordered]@{ started = $false; reused = $true; gamePid = $liveGame.gamePid; wrapperPid = $liveGame.wrapperPid; devPort = [int]$liveGame.devPort; foremanPort = [int]$liveGame.foremanPort; log = $liveGame.log }
        $DevPort = [int]$liveGame.devPort
    } else {
        # the DevBridge port, checked again right before the start
        $owner = Get-PortOwner $DevPort
        if ($owner -or (Test-PortOpen $DevPort)) {
            $who = 'another process'
            if ($owner) { $who = Get-ProcLabel $owner }
            Fail "DevBridge port $DevPort is already in use by $who (another game?). Use -DevPort N." @() $EXIT_BUSY
        }
        # Gradle daemon JVM args from mod/gradle.properties + a marker naming this checkout, so this
        # checkout gets its own daemon (stop.ps1 -StopDaemon can stop exactly that one).
        $jvmArgs = '-Xmx4G -Dfile.encoding=UTF-8'
        $gp = Join-Path $L.Mod 'gradle.properties'
        if (Test-Path -LiteralPath $gp) {
            foreach ($line in (Get-Content -LiteralPath $gp)) { if ($line -match '^\s*org\.gradle\.jvmargs\s*=\s*(.+)$') { $jvmArgs = $Matches[1].Trim() } }
        }
        $jvmArgs = "$jvmArgs -XX:ErrorFile=$($L.Marker)"
        $gargs = @('runClient', '--console=plain', "-Dorg.gradle.jvmargs=$jvmArgs")
        if ($Dev) { $gargs += '-Dorg.gradle.daemon.idletimeout=1800000' }   # unattended: daemon exits after 30 idle minutes
        $gameLog = Join-Path $L.Logs 'game.log'
        $gameErr = Join-Path $L.Logs 'game.err.log'
        # passed to gradlew; the Gradle daemon hands the client's environment to the game JVM
        $gameEnv = @{
            JAVA_HOME           = $jdk.home
            GRADLE_USER_HOME    = $GradleHome
            AGENTCRAFT_PORT     = [string]$fmPort
            AGENTCRAFT_DEV_PORT = [string]$DevPort
            AGENTCRAFT_HOME     = $AgentHome
            AGENTCRAFT_PROFILE  = $ForemanProfile
            AGENTCRAFT_WORLD    = $World
            AGENTCRAFT_MUTE     = $(if ($Dev) { '1' } else { '0' })
            AGENTCRAFT_FOCUS    = $(if ($Dev) { '0' } else { '1' })
        }
        if ($Seed) { $gameEnv.AGENTCRAFT_WORLD_SEED = $Seed }
        # The mod's Foreman launcher finds the Foreman started (or reused) above and leaves it alone. -NoForeman means
        # "I start my own": the launcher stays off then, unless the caller set AGENTCRAFT_LAUNCHER (1 = let the game start it).
        if ($NoForeman) { $gameEnv.AGENTCRAFT_LAUNCHER = $(if ($env:AGENTCRAFT_LAUNCHER) { $env:AGENTCRAFT_LAUNCHER } else { '0' }) }
        # cmd.exe /s /c "<gradlew.bat> args": a .bat cannot be spawned directly; the arguments are fixed
        # (no user text), quoted per argument and passed verbatim
        $inner = Join-CmdArgs (@((Join-Path $L.Mod 'gradlew.bat')) + $gargs)
        if ($DryRun) {
            Write-Kv 'would run' "cmd /d /s /c `"$inner`"   (in mod/)" 'Yellow'
            foreach ($k in ($gameEnv.Keys | Sort-Object)) { Write-Kv '  env' "$k=$($gameEnv[$k])" }
            $summary.game = [ordered]@{ started = $false; dryRun = $true; command = $inner; env = $gameEnv; devPort = $DevPort; foremanPort = $fmPort }
            Exit-Launch $EXIT_OK
        }
        try {
            $bg = Start-Bg $L $node.Source 'game' $env:ComSpec @('/d', '/s', '/c', "`"$inner`"") $L.Mod $gameLog $gameErr $gameEnv -Verbatim
        } catch { Fail $_.Exception.Message ((Get-LogTail $gameLog 15) + (Get-LogTail $gameErr 15)) }
        $gameInfo = [ordered]@{ kind = 'game'; started = $true; reused = $false; wrapperPid = $bg.wrapperPid; wrapperStart = $bg.wrapperStart; gradlePid = $bg.pid; gradleStart = $bg.start; gamePid = $null; gameStart = $null; devPort = $DevPort; foremanPort = $fmPort; dev = [bool]$Dev; log = $gameLog; errLog = $gameErr; checkout = $Root; marker = $L.Marker; gradleHome = $GradleHome; javaHome = $jdk.home }
        Write-JsonFile $gameRunFile $gameInfo
        $script:Started.game = $gameInfo
        $script:AcBgStarting = $null
        Write-Kv 'starting' "gradlew runClient (pid $($bg.pid); builds if needed)  DevBridge :$DevPort -> Foreman :$fmPort"
        Write-Kv 'log' $gameLog
    }
    $summary.game = $gameInfo
    Save-Summary

    if ($NoWait -and $gameInfo.started) {
        Write-Kv 'note' "-NoWait: not waiting for the world (node tools\devbridge-cli.mjs wait --port $DevPort)" 'DarkGray'
        Exit-Launch $EXIT_OK
    }

    # a checkout's first build downloads Minecraft and Fabric: give it longer unless -TimeoutSec says otherwise
    if (-not $timeoutGiven -and $gameInfo.started -and -not (Test-Path -LiteralPath $L.LaunchCfg)) {
        $waitSec = 1800
        Write-Kv 'note' 'first build of this checkout: waiting up to 30 minutes for the world' 'DarkGray'
    }

    # wait for the game JVM, the DevBridge and a ready world (or an early gradle exit = build failure)
    $deadline = (Get-Date).AddSeconds($waitSec)
    $lastNote = Get-Date
    $gStartT = Get-Date
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if ($gameInfo.started) {
            if (-not $gameInfo.gamePid) {
                $jvm = Find-GameJvm $L
                if ($jvm) {
                    $gameInfo.gamePid = [int]$jvm.ProcessId
                    $gameInfo.gameStart = Get-ProcStart ([int]$jvm.ProcessId)
                    Write-JsonFile $gameRunFile $gameInfo
                    Write-Kv 'jvm' ("pid $($jvm.ProcessId)  (build done in {0:N0} s)" -f ((Get-Date) - $gStartT).TotalSeconds)
                }
            }
            if (-not (Test-SameProc $gameInfo.wrapperPid $gameInfo.wrapperStart) -and -not ($gameInfo.gamePid -and (Test-SameProc $gameInfo.gamePid $gameInfo.gameStart))) {
                Remove-Item -LiteralPath $gameRunFile -Force -ErrorAction SilentlyContinue
                $script:Started.game = $null
                Fail 'gradlew runClient exited before the world was ready (build failure or crash).' ((Get-LogTail $gameInfo.log 30) + (Get-LogTail $gameInfo.errLog 10))
            }
        }
        if (Test-PortOpen $DevPort) {
            $remaining = [int][Math]::Max(10, ($deadline - (Get-Date)).TotalSeconds)
            $ws = (Invoke-ToolJson 'devbridge-cli.mjs' @('wait', '--port', [string]$DevPort, '--timeout', [string]$remaining) ($remaining + 30)).json
            if ($ws -and $ws.ok) {
                $ready = $true
                $w = ''
                if ($ws.world) { $w = "world '$($ws.world.name)'" }
                Write-Kv 'ready' ("{0}  {1} fps  in {2:N0} s" -f $w, $ws.fps, ((Get-Date) - $t0).TotalSeconds) 'Green'
                $wantWorld = if ($World -eq 'survival') { 'AgentCraft Survival' } else { 'AgentCraft HQ' }
                if ($ws.world -and $ws.world.name -ne $wantWorld) {
                    Write-Warn2 "the game is in world '$($ws.world.name)', not '$wantWorld' (-World $World): stop it (tools\stop.ps1 -Game) and launch again"
                }
                $gameInfo.ready = $true
                break
            }
            $msg = 'not ready'
            if ($ws -and $ws.error) { $msg = $ws.error }
            if ($msg -match 'hung') { Fail "the game is hung: $msg (tools\stop.ps1 -Game, then relaunch)" (Get-LogTail $gameInfo.log 20) }
        }
        if (((Get-Date) - $lastNote).TotalSeconds -ge 15) {
            $lastNote = Get-Date
            Write-Kv '...' ("waiting for the world ({0:N0} s)" -f ((Get-Date) - $gStartT).TotalSeconds) 'DarkGray'
        }
        Start-Sleep -Milliseconds 1000
    }
    if (-not $ready) {
        $hint = ''
        if ($gameInfo.started -and -not $timeoutGiven) { $hint = ' A slow first build may need longer: -TimeoutSec 3600 (Gradle keeps what it downloaded).' }
        Fail "the world was not ready after $waitSec s (see $($gameInfo.log)).$hint" (Get-LogTail $gameInfo.log 20)
    }
    if ($gameInfo.started) { Write-JsonFile $gameRunFile $gameInfo }
    $summary.game = $gameInfo

    Write-Host ''
    Write-Host 'Running.' -ForegroundColor Green
    Write-Host "  stop all      tools\stop.ps1            (game: quits + saves; Foreman: Ctrl+Break, state saved)" -ForegroundColor DarkGray
    Write-Host "  stop game     tools\stop.ps1 -Game      (or just close the window; agents keep working)" -ForegroundColor DarkGray
    Write-Host "  terminal UI   cd foreman; npm run tui -- --port $fmPort" -ForegroundColor DarkGray
    Write-Host "  dev bridge    node tools\devbridge-cli.mjs state --port $DevPort" -ForegroundColor DarkGray
    Exit-Launch $EXIT_OK
} catch {
    # anything unexpected (a bug, or a surprise on this machine): reported, then cleaned up below
    Write-Fail "unexpected error: $($_.Exception.Message)"
    if ($_.InvocationInfo -and $_.InvocationInfo.PositionMessage) { Write-Host ($_.InvocationInfo.PositionMessage.Trim()) -ForegroundColor DarkGray }
    $summary.error = "unexpected error: $($_.Exception.Message)"
    $script:ExitCode = $EXIT_FAILED
    $summary.exitCode = $EXIT_FAILED
} finally {
    $interrupted = $null -eq $script:ExitCode
    if ($interrupted) {
        Write-Host ''
        Write-Warn2 'interrupted (Ctrl+C)'
        $summary.error = 'interrupted'
        $summary.exitCode = $EXIT_INTERRUPTED
    }
    if ($script:ExitCode -ne $EXIT_OK) {
        try {
            if ($KeepOnFailure) { Show-LeftRunning } else { Stop-ThisRun $(if ($interrupted) { 'interrupted' } else { 'the launch failed' }) }
        } catch { Write-Fail "cleanup failed: $($_.Exception.Message); check with tools\stop.ps1" }
        Save-Summary
    }
    if ($interrupted) {
        # `exit` alone does not set the process exit code once Ctrl+C is stopping the script
        try { $host.SetShouldExit($EXIT_INTERRUPTED) } catch {}
        exit $EXIT_INTERRUPTED
    }
}
exit $script:ExitCode
