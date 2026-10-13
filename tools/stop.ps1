<#
.SYNOPSIS
  Stops what tools\launch.ps1 started from this checkout - and nothing else.

.DESCRIPTION
  Reads the run files launch.ps1 wrote in artifacts\run\ (pid + process start time, so a reused
  pid is never touched):
    game     DevBridge dev.quit (world saved), wait, then a force-kill of only the game JVM and the
             gradlew process tree launch.ps1 started.
    Foreman  Ctrl+Break into its hidden console (the Foreman treats it like Ctrl+C: state saved,
             foreman.json released), wait, then a force-kill of only that process tree.
  A Foreman that launch.ps1 reused but did not start has no run file and is left alone.
  -StopDaemon also stops the Gradle daemon(s) launch.ps1 started for this checkout (they carry a
  per-checkout marker in their JVM args); other checkouts' daemons are never touched.

.EXAMPLE
  tools\stop.ps1                 # game + every Foreman this checkout's launch.ps1 started
  tools\stop.ps1 -Game           # only the game (agents keep working)
  tools\stop.ps1 -Foreman -Profile showcase
  tools\stop.ps1 -FromSummary artifacts\run\qa-launch.json   # exactly what one launch started
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$Game,
    [switch]$Foreman,
    [Alias('Profile')][string]$ForemanProfile,
    [Alias('Home')][string]$AgentHome,
    [int]$Port,
    [string]$FromSummary,
    [switch]$StopDaemon,
    [int]$TimeoutSec = 30
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\procs.ps1')
$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$L = Get-AcLayout $Root
$node = Get-Command node -ErrorAction SilentlyContinue

$doGame = $Game.IsPresent -or -not $Foreman.IsPresent
$doForeman = $Foreman.IsPresent -or -not $Game.IsPresent
$onlyForemanPid = $null
if ($FromSummary) {
    $FromSummary = Resolve-FullPath $FromSummary
    $s = Read-JsonFile $FromSummary
    if (-not $s) { Write-Fail "cannot read $FromSummary"; exit 1 }
    $doGame = [bool]($s.game -and $s.game.started)
    $doForeman = [bool]($s.foreman -and $s.foreman.started)
    if ($doForeman) { $onlyForemanPid = [int]$s.foreman.pid }
}
if ($AgentHome) { $AgentHome = Resolve-FullPath $AgentHome }

$failures = 0
$stoppedAny = $false
Write-Host ''
Write-Host 'AgentCraft stop' -ForegroundColor Cyan

# --- game ------------------------------------------------------------------------------------------
if ($doGame) {
    $gf = Get-GameRunFile $L
    $g = Read-JsonFile $gf
    if (-not $g) {
        Write-Kv 'game' 'nothing to stop (no run file from launch.ps1)' 'DarkGray'
        $stray = Find-GameJvm $L
        if ($stray) { Write-Warn2 "a game of this checkout is running but was not started by launch.ps1: $(Get-ProcLabel $stray.ProcessId) (left alone; node tools\devbridge-cli.mjs quit)" }
    } else {
        $t = Get-Date
        # the tree root launch.ps1 started: the bgrun wrapper (-> cmd -> gradlew java)
        $rootPid = [int]$g.wrapperPid
        $rootStart = $g.wrapperStart
        $gradleAlive = Test-SameProc $rootPid $rootStart
        $gamePid = 0
        if ($g.gamePid) { $gamePid = [int]$g.gamePid }
        $gameStart = $g.gameStart
        if (-not $gamePid -and $gradleAlive) {
            # launched but the JVM was not recorded yet (stop during the build): look it up by checkout
            $jvm = Find-GameJvm $L
            if ($jvm) { $gamePid = [int]$jvm.ProcessId; $gameStart = Get-ProcStart $gamePid }
        }
        $gameAlive = $gamePid -and (Test-SameProc $gamePid $gameStart)
        if (-not $gradleAlive -and -not $gameAlive) {
            Write-Kv 'game' 'already stopped' 'DarkGray'
        } else {
            $stoppedAny = $true
            $how = 'force'
            $quitSkipped = $false
            if ($gameAlive) {
                # only ask the DevBridge to quit if the port really belongs to OUR game JVM; a game
                # that is still starting gets up to 20 s to open it (quitting saves a new world cleanly)
                $owner = Get-PortOwner ([int]$g.devPort)
                if ($owner -ne $gamePid) {
                    $until = (Get-Date).AddSeconds(20)
                    while ((Get-Date) -lt $until -and (Test-SameProc $gamePid $gameStart)) {
                        if (Test-PortOpen ([int]$g.devPort)) { $owner = Get-PortOwner ([int]$g.devPort); if ($owner -eq $gamePid) { break } }
                        Start-Sleep -Milliseconds 500
                    }
                }
                if ($owner -eq $gamePid -and $node) {
                    $hung = $false
                    try { $hung = [bool]((& $node.Source (Join-Path $L.Tools 'devbridge-cli.mjs') ping --port ([int]$g.devPort) --timeout 5 2>$null | Out-String | ConvertFrom-Json).stalled) } catch {}
                    if ($hung) { Write-Warn2 'the game is hung (render thread stalled): dev.quit saves the world and force-exits it after 15 s' }
                    Write-Kv 'game' "dev.quit via DevBridge :$($g.devPort) (pid $gamePid; saves the world) ..."
                    $q = (& $node.Source (Join-Path $L.Tools 'devbridge-cli.mjs') quit --port ([int]$g.devPort) --timeout 10 2>$null) | Out-String
                    if (Wait-ProcExit $gamePid $gameStart ([Math]::Max($TimeoutSec, 25))) {
                        $how = 'graceful'
                        if ($hung) { $how = 'dev.quit watchdog, world saved' }
                    }
                } else {
                    $quitSkipped = $true
                    $ownerText = 'nobody'
                    if ($owner) { $ownerText = "pid $owner" }
                    Write-Warn2 "DevBridge :$($g.devPort) is not served by game pid $gamePid (owner: $ownerText); skipping dev.quit"
                }
            }
            if ($gameAlive -and (Test-SameProc $gamePid $gameStart)) {
                $k = Stop-OwnTree $gamePid $gameStart
                if ($quitSkipped) { Write-Warn2 "force-killed the game JVM tree: pid(s) $($k -join ', ')" }
                else { Write-Warn2 "game JVM did not exit after dev.quit: force-killed pid(s) $($k -join ', ')" }
            }
            # gradlew ends by itself once the game JVM is gone; give it a moment, then force
            if ($gradleAlive -and -not (Wait-ProcExit $rootPid $rootStart 15)) {
                $k = Stop-OwnTree $rootPid $rootStart
                Write-Warn2 "gradlew (pid $($g.gradlePid), wrapper $rootPid) did not exit: force-killed pid(s) $($k -join ', ')"
                if ($how -eq 'graceful') { $how = 'graceful (gradlew forced)' }
                # stopped mid-build: the daemon may still have spawned the game JVM; it is ours if it
                # names this checkout's launch.cfg and started after our wrapper
                Start-Sleep -Seconds 2
                $late = Find-GameJvm $L
                if ($late -and $late.CreationDate -and $rootStart -and ($late.CreationDate.ToUniversalTime() -ge [DateTime]::Parse($rootStart).ToUniversalTime().AddSeconds(-2))) {
                    $k = Stop-OwnTree ([int]$late.ProcessId) (Get-ProcStart ([int]$late.ProcessId))
                    Write-Warn2 "game JVM pid $($late.ProcessId) appeared after gradlew was stopped: force-killed pid(s) $($k -join ', ')"
                }
            }
            $left = @()
            if ($gamePid -and (Test-SameProc $gamePid $gameStart)) { $left += $gamePid }
            if (Test-SameProc $rootPid $rootStart) { $left += $rootPid }
            if ($g.gradlePid -and (Test-SameProc ([int]$g.gradlePid) $g.gradleStart)) { $left += [int]$g.gradlePid }
            if ($left.Count) { Write-Fail "still running: $($left -join ', ')"; $failures++ }
            else { Write-Kv 'game' ("stopped ({0}) in {1:N1} s" -f $how, ((Get-Date) - $t).TotalSeconds) 'Green' }
        }
        if (-not ($gamePid -and (Test-SameProc $gamePid $gameStart)) -and -not (Test-SameProc $rootPid $rootStart)) {
            Remove-Item -Force $gf -ErrorAction SilentlyContinue
        }
    }
}

# --- Foreman(s) -------------------------------------------------------------------------------------
if ($doForeman) {
    $files = @(Get-ChildItem -Path $L.Run -Filter 'foreman-*.json' -ErrorAction SilentlyContinue)
    $matched = 0
    foreach ($file in $files) {
        $f = Read-JsonFile $file.FullName
        if (-not $f -or -not $f.pid) { continue }
        if ($ForemanProfile -and $f.profile -ne $ForemanProfile) { continue }
        if ($AgentHome -and ([System.IO.Path]::GetFullPath($f.home) -ne $AgentHome)) { continue }
        if ($Port -and [int]$f.port -ne $Port) { continue }
        if ($onlyForemanPid -and [int]$f.pid -ne $onlyForemanPid) { continue }
        $matched++
        $label = "Foreman '$($f.profile)' (pid $($f.pid), :$($f.port))"
        if (-not (Test-SameProc ([int]$f.pid) $f.startTime)) {
            Write-Kv 'foreman' "$label already stopped" 'DarkGray'
            Remove-Item -Force $file.FullName -ErrorAction SilentlyContinue
            continue
        }
        $stoppedAny = $true
        $t = Get-Date
        Write-Kv 'foreman' "${label}: Ctrl+Break (saves state) ..."
        # the Foreman shares the hidden console of its bgrun wrapper: signal that console
        $target = [int]$f.pid
        $wrapAlive = $f.wrapperPid -and (Test-SameProc ([int]$f.wrapperPid) $f.wrapperStart)
        if ($wrapAlive) { $target = [int]$f.wrapperPid }
        $sent = Send-CtrlBreak $target
        $how = 'graceful'
        if (-not $sent -or -not (Wait-ProcExit ([int]$f.pid) $f.startTime $TimeoutSec)) {
            $k = @(Stop-OwnTree ([int]$f.pid) $f.startTime)
            $how = 'force'
            Write-Warn2 "no clean exit within $TimeoutSec s (signal sent: $sent): force-killed pid(s) $($k -join ', ')"
        }
        if ($wrapAlive -and -not (Wait-ProcExit ([int]$f.wrapperPid) $f.wrapperStart 5)) {
            $k = @(Stop-OwnTree ([int]$f.wrapperPid) $f.wrapperStart)
            Write-Warn2 "wrapper pid $($f.wrapperPid) did not exit: force-killed pid(s) $($k -join ', ')"
        }
        if (Test-SameProc ([int]$f.pid) $f.startTime) { Write-Fail "$label is still running"; $failures++; continue }
        $rf = Read-JsonFile (Join-Path (Join-Path $f.home $f.profile) 'foreman.json')
        $released = -not ($rf -and [int]$rf.pid -eq [int]$f.pid)
        $note = ''
        if (-not $released) { $note = ' (foreman.json left behind; the next Foreman ignores a dead pid)' }
        Write-Kv 'foreman' ("{0} stopped ({1}) in {2:N1} s{3}" -f $label, $how, ((Get-Date) - $t).TotalSeconds, $note) 'Green'
        Remove-Item -Force $file.FullName -ErrorAction SilentlyContinue
    }
    if (-not $matched) { Write-Kv 'foreman' 'nothing to stop (no run file from launch.ps1)' 'DarkGray' }
}

# --- Gradle daemon(s) of this checkout ------------------------------------------------------------
if ($StopDaemon) {
    $ds = @(Find-OwnDaemons $L)
    if (-not $ds.Count) { Write-Kv 'daemon' "none running for this checkout ($($L.Marker))" 'DarkGray' }
    foreach ($d in $ds) {
        try {
            Stop-Process -Id $d.ProcessId -Force -ErrorAction Stop
            Write-Kv 'daemon' "stopped Gradle daemon pid $($d.ProcessId) ($($L.Marker))" 'Green'
            $stoppedAny = $true
        } catch { Write-Fail "could not stop Gradle daemon pid $($d.ProcessId): $($_.Exception.Message)"; $failures++ }
    }
}

if ($failures) { exit 1 }
exit 0
