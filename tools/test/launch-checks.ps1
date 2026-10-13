# Test driver for tools/test/launch-ps.test.mjs: runs the pure checks of tools/lib/checks.ps1 and
# tools/launch.ps1's argument handling in this PowerShell and prints the results as one JSON object.
# Every launch.ps1 call is a -DryRun that starts nothing.
param([string]$Tools, [string]$Scratch)

$ErrorActionPreference = 'Stop'
. (Join-Path $Tools 'lib\checks.ps1')
$launch = Join-Path $Tools 'launch.ps1'
$r = [ordered]@{ psVersion = $PSVersionTable.PSVersion.ToString() }

$r.javaMajor = @(@('openjdk version "25.0.1" 2025-10-21 LTS', 'java version "17.0.10" 2024-01-16 LTS', 'java version "1.8.0_392"', 'openjdk version "26-ea" 2026-03-17', 'nothing') | ForEach-Object { Get-JavaMajorFromText $_ })
$r.jdkDirs = @(@('jdk-25.0.4.1+1', 'jdk-25+36', 'zulu25.30.17-ca-jdk25.0.1-win_x64', 'jdk1.8.0_392', 'jdk-21.0.5.11-hotspot', 'current') | ForEach-Object { (Get-JdkDirVersion $_).ToString() })
$r.node = @(@('v22.18.0', 'v24.21.0', 'v20.11.1', '22.0.0', 'garbage', '') | ForEach-Object { $n = Test-NodeVersion $_ 22; "$($n.ok)/$($n.major)" })
$r.ports = @(@('7878', ' 27900 ', '0', '65536', 'abc', '3000', '1.5') | ForEach-Object { $p = Test-PortValue $_ '-Port'; if ($p.ok) { "ok:$($p.port)" } else { "err:$($p.error)" } })
$r.ints = @(@('600', '9', '86401', 'x') | ForEach-Object { $t = Test-IntValue $_ '-TimeoutSec' 10 86400; if ($t.ok) { "ok:$($t.value)" } else { 'err' } })
$r.speeds = @(@('0.5', '2', '0', '-1', 'NaN', '1e3', '1001') | ForEach-Object { $s = Test-PositiveNumber $_ '-Speed'; if ($s.ok) { "ok:$($s.value.ToString([System.Globalization.CultureInfo]::InvariantCulture))" } else { 'err' } })
$r.profiles = @(@('claude', 'showcase-late', 'a_b-1', 'bad name', '', ('x' * 65)) | ForEach-Object { [bool](Test-ProfileName $_) })
$r.seeds = @(@('1234', 'hello world', '-5', '   ', "a`nb") | ForEach-Object { [bool](Test-SeedText $_) })
$r.quoting = @(@('plain', 'with space', 'C:\My Code\', 'say "hi"', '') | ForEach-Object { Format-NativeArg $_ })

# folders and files
$file = Join-Path $Scratch 'a-file.txt'
Set-Content -LiteralPath $file -Value 'x'
$r.folderTargets = @(
    [bool](Test-FolderTarget $Scratch '-Home'),
    [bool](Test-FolderTarget (Join-Path $Scratch 'new\deeper') '-Home'),
    [string](Test-FolderTarget $file '-Home'),
    [bool](Test-FolderTarget (Join-Path $file 'sub') '-Home'),
    [bool](Test-FileTarget (Join-Path $Scratch 'out.json') '-SummaryJson'),
    [string](Test-FileTarget $Scratch '-SummaryJson')
)

# Invoke-Captured: both streams captured, the exit code kept, stderr never an error under 'Stop',
# arguments with spaces and quotes arrive intact, a probe that outlives its timeout is killed
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
$c = Invoke-Captured $node @('-e', 'console.log(JSON.stringify(process.argv.slice(1))); console.error("to stderr"); process.exit(3)', 'a b', 'say "hi"', 'C:\My Code\') 30
$r.captured = [ordered]@{ code = $c.code; stdout = $c.stdout.Trim(); stderr = $c.stderr.Trim(); timedOut = $c.timedOut }
$t0 = Get-Date
$c = Invoke-Captured $node @('-e', 'setTimeout(() => {}, 20000)') 1
$r.timeout = [ordered]@{ code = $c.code; timedOut = $c.timedOut; seconds = [Math]::Round(((Get-Date) - $t0).TotalSeconds, 1) }

# launch.ps1 in this PowerShell, the way start-agentcraft.ps1 calls it
function Invoke-Launch([scriptblock]$Call) {
    $global:LASTEXITCODE = 0
    $out = (& $Call *>&1 | Out-String)
    return [ordered]@{ code = $global:LASTEXITCODE; out = $out }
}
$home2 = Join-Path $Scratch 'home'
$missing = Join-Path $Scratch 'no-such-folder'
$summary = Join-Path $Scratch 'summary.json'
$r.launch = [ordered]@{}
$r.launch.bad = Invoke-Launch { & $launch -Bogus survival -Port 70000 -World mars -Speed 0 -TimeoutSec abc -Seed ' ' -Repo $missing -DryRun -NoGame -NoForeman -Home $home2 -SummaryJson $summary }
$r.launch.badSummary = Get-Content -LiteralPath $summary -Raw | ConvertFrom-Json
$r.launch.showcase = Invoke-Launch { & $launch -Showcase late -Backend claude -Port 27911 -DevPort 27911 -Notify -NoNotify -Profile 'bad name' -DryRun -NoGame -NoForeman -Home $home2 }
$env:AGENTCRAFT_BACKEND = 'gpt'
$env:AGENTCRAFT_DEV_PORT = 'abc'
try { $r.launch.env = Invoke-Launch { & $launch -DryRun -NoGame -NoForeman -Home $home2 } }
finally { Remove-Item env:AGENTCRAFT_BACKEND, env:AGENTCRAFT_DEV_PORT -ErrorAction SilentlyContinue }
$r.launch.ok = Invoke-Launch { & $launch -Backend sim -World survival -Seed 1234 -Speed 0.5 -Port 27911 -DevPort 27912 -DryRun -NoGame -NoForeman -Home $home2 -SummaryJson $summary }
$r.launch.okSummary = Get-Content -LiteralPath $summary -Raw | ConvertFrom-Json
# a port held by something this launcher did not start: refused before anything starts
$listener = New-Object System.Net.Sockets.TcpListener ([System.Net.IPAddress]::Loopback), 0
$listener.Start()
try {
    $busyPort = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $r.launch.busy = Invoke-Launch { & $launch -Backend sim -Port $busyPort -DryRun -NoGame -Home $home2 }
} finally { $listener.Stop() }

$r | ConvertTo-Json -Depth 6
