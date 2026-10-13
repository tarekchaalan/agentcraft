# Input and prerequisite checks shared by tools/launch.ps1, tools/stop.ps1 and
# tools/start-agentcraft.ps1 (dot-source it). Windows PowerShell 5.1 and PowerShell 7.
#
# Nothing here starts anything long-running: the probes (`java -version`, `node --version`, the
# node CLIs) run as captured child processes with a timeout, and a probe that outlives it is
# killed together with its children.

# One argument for a Windows command line (CommandLineToArgvW rules). The same rules as
# procs.ps1's ConvertTo-CmdArg, repeated so this file stands on its own.
function Format-NativeArg([string]$s) {
    if ($s -eq '') { return '""' }
    if ($s -notmatch '[\s"]') { return $s }
    $out = '"'
    $bs = 0
    foreach ($ch in $s.ToCharArray()) {
        if ($ch -eq '\') { $bs++; continue }
        if ($ch -eq '"') { $out += ('\' * (2 * $bs + 1)) + '"'; $bs = 0; continue }
        if ($bs) { $out += ('\' * $bs); $bs = 0 }
        $out += $ch
    }
    $out += ('\' * (2 * $bs)) + '"'
    return $out
}

function Stop-CapturedTree([System.Diagnostics.Process]$Proc) {
    if (-not $Proc) { return }
    try { if ($Proc.HasExited) { return } } catch { return }
    # taskkill /T takes the probe's children too (cmd.exe -> claude, node -> ...)
    try {
        $k = New-Object System.Diagnostics.ProcessStartInfo
        $k.FileName = Join-Path $env:SystemRoot 'System32\taskkill.exe'
        $k.Arguments = "/T /F /PID $($Proc.Id)"
        $k.UseShellExecute = $false
        $k.CreateNoWindow = $true
        $k.RedirectStandardOutput = $true
        $k.RedirectStandardError = $true
        $t = [System.Diagnostics.Process]::Start($k)
        [void]$t.WaitForExit(10000)
    } catch {}
    try { if (-not $Proc.HasExited) { $Proc.Kill() } } catch {}
}

<#
  Runs a console program and returns @{ code; stdout; stderr; out; timedOut } (out = both streams,
  trimmed). Its stderr never becomes a PowerShell error (Windows PowerShell 5.1 turns a native
  command's stderr into a terminating error under $ErrorActionPreference = 'Stop' when it is
  redirected), arguments are quoted for paths with spaces, a .cmd/.bat runs through cmd.exe, and it
  is killed after $TimeoutSec. It waits in short steps, so Ctrl+C stops the caller promptly, and a
  probe still running when the caller is interrupted is killed.
#>
function Invoke-Captured([string]$Exe, [string[]]$Arguments = @(), [int]$TimeoutSec = 60) {
    $argLine = (@($Arguments) | ForEach-Object { Format-NativeArg ([string]$_) }) -join ' '
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    if ($Exe -match '\.(cmd|bat)$') {
        $psi.FileName = $env:ComSpec
        $psi.Arguments = '/d /s /c "' + (Format-NativeArg $Exe) + ' ' + $argLine + '"'
    } else {
        $psi.FileName = $Exe
        $psi.Arguments = $argLine
    }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    try { $p = [System.Diagnostics.Process]::Start($psi) }
    catch { return @{ code = -1; stdout = ''; stderr = ''; out = "could not run ${Exe}: $($_.Exception.Message)"; timedOut = $false } }
    $o = $p.StandardOutput.ReadToEndAsync()
    $e = $p.StandardError.ReadToEndAsync()
    $finished = $false
    try {
        $deadline = (Get-Date).AddSeconds([Math]::Max(1, $TimeoutSec))
        while (-not $p.WaitForExit(200)) {
            if ((Get-Date) -ge $deadline) {
                Stop-CapturedTree $p
                $finished = $true
                return @{ code = -1; stdout = ''; stderr = ''; out = "timed out after $TimeoutSec s"; timedOut = $true }
            }
        }
        $p.WaitForExit()
        $finished = $true
        $so = [string]$o.Result
        $se = [string]$e.Result
        return @{ code = $p.ExitCode; stdout = $so; stderr = $se; out = ($so + "`n" + $se).Trim(); timedOut = $false }
    } finally {
        if (-not $finished) { Stop-CapturedTree $p }
    }
}

# --- versions ------------------------------------------------------------------------------------

# Major version in `java -version` output: 'version "25.0.1"' -> 25, 'version "1.8.0_392"' -> 8,
# 'version "26-ea"' -> 26; 0 when there is none.
function Get-JavaMajorFromText([string]$Text) {
    if ($Text -notmatch 'version "(\d+)(?:\.(\d+))?') { return 0 }
    $major = [int]$Matches[1]
    if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
    return $major
}

# Version in a JDK folder's name, to try the newest first: "jdk-25.0.4.1+1" -> 25.0.4.1,
# "jdk-25+36" -> 25.0, "zulu25.30.17-ca-jdk25.0.1-win_x64" -> 25.30.17; 0.0 when there is none.
function Get-JdkDirVersion([string]$Name) {
    $v = $Name
    if ($v -match '(\d+(?:\.\d+)+)') { $v = $Matches[1] } elseif ($v -match '(\d+)') { $v = $Matches[1] } else { return [version]'0.0' }
    $parts = @($v.Split('.') | Select-Object -First 4)
    if ($parts.Count -lt 2) { $parts += '0' }
    try { return [version]($parts -join '.') } catch { return [version]'0.0' }
}

# Major version of the JDK in $JdkHome (runs its bin\java.exe -version); 0 when it does not run.
function Get-JavaHomeMajor([string]$JdkHome) {
    if (-not $JdkHome) { return 0 }
    $exe = Join-Path $JdkHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { return 0 }
    $r = Invoke-Captured $exe @('-version') 20
    return (Get-JavaMajorFromText $r.out)
}

# Where JDKs are usually installed on Windows (user installs first).
function Get-JdkSearchRoots {
    $roots = @()
    if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium') }
    foreach ($pf in @(@($env:ProgramFiles, ${env:ProgramW6432}) | Select-Object -Unique)) {
        if (-not $pf) { continue }
        foreach ($vendor in @('Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft')) { $roots += (Join-Path $pf $vendor) }
    }
    if ($env:USERPROFILE) { $roots += (Join-Path $env:USERPROFILE '.jdks') }
    return @($roots | Select-Object -Unique)
}

<#
  The JDK to run Minecraft with: JAVA_HOME when it is Java $MinMajor or newer, else the newest
  $MinMajor+ JDK in the usual install folders, else the `java` on PATH when that is new enough.
  Returns @{ home; major; source } or $null. Never changes JAVA_HOME itself.
#>
function Find-Jdk([int]$MinMajor = 25, [string]$JavaHome = $env:JAVA_HOME, [string[]]$Roots = (Get-JdkSearchRoots)) {
    $jh = Get-JavaHomeMajor $JavaHome
    if ($jh -ge $MinMajor) { return @{ home = $JavaHome; major = $jh; source = 'JAVA_HOME' } }
    $found = @()
    foreach ($r in $Roots) {
        if (-not $r -or -not (Test-Path -LiteralPath $r -PathType Container)) { continue }
        foreach ($d in @(Get-ChildItem -LiteralPath $r -Directory -ErrorAction SilentlyContinue)) {
            if ((Get-JdkDirVersion $d.Name).Major -lt $MinMajor) { continue }
            if (Test-Path -LiteralPath (Join-Path $d.FullName 'bin\java.exe') -PathType Leaf) { $found += $d }
        }
    }
    foreach ($d in @($found | Sort-Object { Get-JdkDirVersion $_.Name } -Descending)) {
        $m = Get-JavaHomeMajor $d.FullName
        if ($m -ge $MinMajor) { return @{ home = $d.FullName; major = $m; source = "found in $(Split-Path -Parent $d.FullName)" } }
    }
    $java = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($java) {
        $home2 = Split-Path -Parent (Split-Path -Parent $java.Source)
        $m = Get-JavaHomeMajor $home2
        if ($m -ge $MinMajor) { return @{ home = $home2; major = $m; source = 'PATH' } }
    }
    return $null
}

# `node --version` output ("v22.18.0") -> @{ ok; major; minor; version }.
function Test-NodeVersion([string]$Text, [int]$MinMajor = 22) {
    $v = ([string]$Text).Trim()
    $major = 0
    $minor = 0
    $ok = $false
    if ($v -match '^v?(\d+)\.(\d+)\.\d+(?:[-+][0-9A-Za-z.+-]+)?$' -and
        [int]::TryParse($Matches[1], [ref]$major) -and [int]::TryParse($Matches[2], [ref]$minor)) {
        $ok = $major -ge $MinMajor
    }
    return @{ ok = $ok; major = $major; minor = $minor; version = $v }
}

# --- argument values -----------------------------------------------------------------------------

# Ports other dev servers on this machine use; the launcher never takes them.
$script:AcReservedPorts = @(3000, 5173, 8080)

# A port from a parameter or an environment variable: @{ ok; port; error }.
function Test-PortValue([string]$Value, [string]$What) {
    $v = ([string]$Value).Trim()
    $n = 0
    if ($v -notmatch '^\d{1,5}$' -or -not [int]::TryParse($v, [ref]$n) -or $n -lt 1 -or $n -gt 65535) {
        return @{ ok = $false; port = 0; error = "$What must be a port number from 1 to 65535 (got '$Value')" }
    }
    if ($script:AcReservedPorts -contains $n) {
        return @{ ok = $false; port = $n; error = "$What $n is reserved for other dev servers ($($script:AcReservedPorts -join ', ')); pick another" }
    }
    return @{ ok = $true; port = $n; error = $null }
}

# A whole number in [$Min, $Max]: @{ ok; value; error }.
function Test-IntValue([string]$Value, [string]$What, [int]$Min, [int]$Max) {
    $v = ([string]$Value).Trim()
    $n = 0
    if ($v -notmatch '^-?\d{1,9}$' -or -not [int]::TryParse($v, [ref]$n) -or $n -lt $Min -or $n -gt $Max) {
        return @{ ok = $false; value = 0; error = "$What must be a whole number from $Min to $Max (got '$Value')" }
    }
    return @{ ok = $true; value = $n; error = $null }
}

# A positive number such as a sim speed (0.5, 2): @{ ok; value; error }.
function Test-PositiveNumber([string]$Value, [string]$What, [double]$Max = 1000) {
    $v = ([string]$Value).Trim()
    $n = 0.0
    $ok = $v -match '^\d+(\.\d+)?$' -and [double]::TryParse($v, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$n)
    if (-not $ok -or $n -le 0 -or $n -gt $Max) { return @{ ok = $false; value = 0; error = "$What must be a number above 0 and at most $Max (got '$Value')" } }
    return @{ ok = $true; value = $n; error = $null }
}

# A Foreman profile name: the folder name of its state under the home.
function Test-ProfileName([string]$Name) {
    if ($Name -notmatch '^[A-Za-z0-9_-]{1,64}$') { return "bad profile name '$Name' (1 to 64 letters, digits, - and _)" }
    return $null
}

# A world seed: a number or any one-line text (hashed like the world-creation screen does).
function Test-SeedText([string]$Seed) {
    if (-not $Seed.Trim()) { return "-Seed is empty (give a number or a word)" }
    if ($Seed -match '[\x00-\x1F\x7F]') { return "-Seed must be one line of text (no control characters)" }
    return $null
}

# A folder that is used or created: it is not an existing file, and its drive exists.
function Test-FolderTarget([string]$Path, [string]$What) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) { return "$What $Path is a file, not a folder" }
    $p = $Path
    while ($p -and -not (Test-Path -LiteralPath $p)) { $p = Split-Path -Parent $p }
    if (-not $p) { return "$What $Path cannot be created (its drive does not exist)" }
    if (-not (Test-Path -LiteralPath $p -PathType Container)) { return "$What ${Path}: $p is a file, not a folder" }
    return $null
}

# A file that is written: not an existing folder, and its folder can be created.
function Test-FileTarget([string]$Path, [string]$What) {
    if (Test-Path -LiteralPath $Path -PathType Container) { return "$What $Path is a folder, not a file" }
    $dir = Split-Path -Parent $Path
    if (-not $dir) { return "$What $Path has no folder" }
    return (Test-FolderTarget $dir $What)
}
