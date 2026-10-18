#requires -Version 7
<#
.SYNOPSIS
  P2.6 skeleton gate, host side: SurfaceFlinger frame pacing of the Cupola wallpaper layer.
.EXAMPLE
  ./skeleton_gate.ps1 -Serial <adb serial> -Layer 'cupola' -Seconds 60
  ./skeleton_gate.ps1 -Serial <adb serial> -Layer 'cupola' -Seconds 30 -Mode still
  ./skeleton_gate.ps1 -SelfTest
#>
[CmdletBinding(DefaultParameterSetName = 'Run')]
param(
    [Parameter(ParameterSetName = 'Run', Mandatory)][string]$Serial,
    [Parameter(ParameterSetName = 'Run', Mandatory)][string]$Layer,
    [Parameter(ParameterSetName = 'Run')][int]$Seconds = 60,
    [Parameter(ParameterSetName = 'Run')][ValidateSet('trace', 'still')][string]$Mode = 'trace',
    [Parameter(ParameterSetName = 'Run')][string]$Adb = 'adb',
    [Parameter(ParameterSetName = 'Run')][string]$OutDir = '.',
    [Parameter(ParameterSetName = 'SelfTest', Mandatory)][switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$EmptySlot = [long]::MaxValue

function Read-LatencyText([string[]]$Lines) {
    foreach ($line in $Lines) {
        $f = $line.Trim() -split '\s+'
        if ($f.Count -ne 3) { continue }
        $d = 0L; $a = 0L; $r = 0L
        if (-not ([long]::TryParse($f[0], [ref]$d) -and [long]::TryParse($f[1], [ref]$a) -and [long]::TryParse($f[2], [ref]$r))) { continue }
        if ($a -le 0 -or $a -eq $EmptySlot -or $d -le 0) { continue }
        [pscustomobject]@{ Desired = $d; Actual = $a; Ready = $r }
    }
}

# Rows: objects with Desired/Actual/Ready in ns. Duplicates (same Desired) are dropped.
function Get-GateStats {
    param([object[]]$Rows, [ValidateSet('trace', 'still')][string]$Mode = 'trace')
    $unique = @($Rows | Sort-Object Desired -Unique | Sort-Object Actual)
    $stats = [ordered]@{ Frames = $unique.Count; Intervals = 0; WithinPct = 0.0; Above33 = 0; MaxMs = 0.0; MeanFps = 0.0; Pass = $false }
    if ($unique.Count -lt 2) { return [pscustomobject]$stats }
    $ms = @(1..($unique.Count - 1) | ForEach-Object { ($unique[$_].Actual - $unique[$_ - 1].Actual) / 1e6 })
    $within = @($ms | Where-Object { [math]::Abs($_ - 16.7) -le 2.0 }).Count
    $spanS = ($unique[-1].Actual - $unique[0].Actual) / 1e9
    $stats.Intervals = $ms.Count
    $stats.WithinPct = [math]::Round(100.0 * $within / $ms.Count, 2)
    $stats.Above33 = @($ms | Where-Object { $_ -gt 33.4 }).Count
    $stats.MaxMs = [math]::Round(($ms | Measure-Object -Maximum).Maximum, 3)
    $stats.MeanFps = [math]::Round($ms.Count / $spanS, 2)
    $stats.Pass = if ($Mode -eq 'still') { [math]::Abs($stats.MeanFps - 15.0) -le 1.0 } else { $stats.WithinPct -ge 99.0 -and $stats.Above33 -eq 0 }
    return [pscustomobject]$stats
}

function Invoke-Adb([string[]]$AdbArgs) {
    $out = & $Adb -s $Serial @AdbArgs
    if ($LASTEXITCODE -ne 0) { throw "adb $($AdbArgs -join ' ') failed with exit code $LASTEXITCODE" }
    $out
}

function Resolve-Layer {
    # Android 16 lists `RequestedLayerState{<name> parentId=..}`; older releases list the bare name.
    $names = @(Invoke-Adb @('shell', 'dumpsys', 'SurfaceFlinger', '--list') |
        ForEach-Object { $_.Trim() -replace '^RequestedLayerState\{(.+?)( parentId=.*)?\}$', '$1' } |
        Where-Object { $_ -match $Layer })
    if ($names.Count -eq 0) { throw "No SurfaceFlinger layer matches '$Layer'" }
    if ($names.Count -gt 1) { Write-Warning "Layers matching '$Layer': $($names -join ' | '); using the first. Narrow -Layer." }
    $names[0]
}

function Invoke-SelfTest {
    $dir = Join-Path $PSScriptRoot 'fixtures'
    $cases = @(
        @{ File = 'pass.txt'; Mode = 'trace'; Expect = $true },
        @{ File = 'gap40.txt'; Mode = 'trace'; Expect = $false },
        @{ File = 'still15.txt'; Mode = 'still'; Expect = $true },
        @{ File = 'pass.txt'; Mode = 'still'; Expect = $false }
    )
    $bad = 0
    foreach ($c in $cases) {
        $s = Get-GateStats -Rows @(Read-LatencyText (Get-Content (Join-Path $dir $c.File))) -Mode $c.Mode
        $ok = $s.Pass -eq $c.Expect
        if (-not $ok) { $bad++ }
        '{0} {1} mode={2} expect={3} got={4}' -f ($(if ($ok) { 'OK  ' } else { 'FAIL' })), $c.File, $c.Mode, $(if ($c.Expect) { 'PASS' } else { 'FAIL' }), $(if ($s.Pass) { 'PASS' } else { 'FAIL' })
    }
    if ($bad) { exit 1 }
    'selftest ok'
    exit 0
}

if ($SelfTest) { Invoke-SelfTest }

$layerName = Resolve-Layer
Write-Host "layer: $layerName"
$byDesired = @{}
$clock = [Diagnostics.Stopwatch]::StartNew()
while ($true) {
    # The command line is parsed by the device shell, so the layer name is single-quoted there.
    foreach ($row in Read-LatencyText (Invoke-Adb @('shell', "dumpsys SurfaceFlinger --latency '$layerName'") | Select-Object -Skip 1)) {
        $byDesired[$row.Desired] = $row
    }
    if ($clock.Elapsed.TotalSeconds -ge $Seconds) { break }
    Start-Sleep -Milliseconds 1000
}
$all = @($byDesired.Values | Sort-Object Actual)
if ($all.Count -eq 0) { throw "No frames for layer '$layerName' (is it on screen?)" }
# Device clock rows only: keep the last $Seconds before the newest frame (older slots predate the run).
$cutoff = $all[-1].Actual - [long]($Seconds * 1e9)
$rows = @($all | Where-Object { $_.Actual -ge $cutoff })

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$csv = Join-Path $OutDir "gate-$Mode-$stamp.csv"
$rows | Select-Object Desired, Actual, Ready | Export-Csv -Path $csv -NoTypeInformation
$s = Get-GateStats -Rows $rows -Mode $Mode
$verdict = if ($s.Pass) { 'PASS' } else { 'FAIL' }
if ($Mode -eq 'still') {
    "{0} still frames={1} mean_fps={2} (rule 15 +- 1) csv={3}" -f $verdict, $s.Frames, $s.MeanFps, $csv
} else {
    "{0} trace frames={1} intervals={2} within_16.7+-2ms={3}% above_33.4ms={4} max_interval_ms={5} mean_fps={6} csv={7}" -f $verdict, $s.Frames, $s.Intervals, $s.WithinPct, $s.Above33, $s.MaxMs, $s.MeanFps, $csv
}
exit ($s.Pass ? 0 : 1)
