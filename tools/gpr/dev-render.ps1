#requires -Version 7
<#
.SYNOPSIS
  Renders the G-PR dev set (photos the owner has already judged) on the phone and builds photo|render sheets plus
  per-pair image metrics, for render tuning. Never point it at the hold-out folder.

.DESCRIPTION
  Reads <Dev>\holdout.json (gate objects) and <Dev>\gpr-map.json (pair id -> photo), pushes the gates as
  dev/gates.json, runs DevSceneRenderTest (app and test APK must be installed; the app from the bundle), pulls the
  renders to <Dev>\dev-out\pNN-render.png and calls dev_compare.py with the cropped photos in <Dev>\gpr-out.
  -Rectilinear renders every gate without its fisheye projection (same fov), as the product camera sees it.

  Usage:
    pwsh .\tools\gpr\dev-render.ps1 [-Serial 28101FDH3004AP] [-Scale 0.6] [-Rectilinear]
#>
param(
    [string]$Serial = '28101FDH3004AP',
    [string]$Adb = 'D:\Software\Android\Sdk\platform-tools\adb.exe',
    [string]$Dev = 'local/reference/dev-run1',
    [double]$Scale = 0.6,
    [switch]$Rectilinear
)
$ErrorActionPreference = 'Stop'
$Device = '/sdcard/Android/data/com.baer.cupola.debug/files/dev'
$Runner = 'com.baer.cupola.debug.test/androidx.test.runner.AndroidJUnitRunner'
$Inv = [cultureinfo]::InvariantCulture
function Adb { & $Adb -s $Serial @args }
function Instrument([switch]$Prepare) {
    $log = Adb shell am instrument -w -r -e class com.baer.cupola.DevSceneRenderTest -e w $w -e h $h -e scale ([string]::Format($Inv, '{0}', $Scale)) $Runner
    if (-not $Prepare -and -not ($log -match '^OK \(1 test\)')) { $log | Select-Object -Last 15 | Write-Host; throw 'DevSceneRenderTest failed' }
}

$doc = Get-Content -LiteralPath (Join-Path $Dev 'holdout.json') -Raw | ConvertFrom-Json -DateKind String
$map = Get-Content -LiteralPath (Join-Path $Dev 'gpr-map.json') -Raw | ConvertFrom-Json
$gates = [ordered]@{}
foreach ($m in $map) {
    $e = $doc | Where-Object { $_.nasa_id -eq $m.nasa_id } | Select-Object -First 1
    if (-not $e.gate) { throw "no gate for pair $($m.id)" }
    $g = $e.gate.PSObject.Copy()
    if ($Rectilinear) { $g.PSObject.Properties.Remove('projection'); $g.PSObject.Properties.Remove('fisheye_fov') }
    $gates[$m.id] = $g
}
$photo = Get-ChildItem -LiteralPath (Join-Path $Dev 'gpr-out') -Filter 'p01-photo.png' | Select-Object -First 1
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($photo.FullName); $w = $img.Width; $h = $img.Height; $img.Dispose()

$tmp = New-TemporaryFile
try {
    $gates | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $tmp -Encoding utf8
    # The first run makes the dev folder as the app (and skips); adb then pushes into it.
    $owner = @(Adb shell "stat -c %u $Device $Device/.. 2>/dev/null")
    if ($owner.Count -ne 2) { Instrument -Prepare }
    $owner = @(Adb shell "stat -c %u $Device $Device/.. 2>/dev/null")
    if ($owner.Count -ne 2 -or @($owner | Select-Object -Unique).Count -ne 1) { throw "$Device is missing or not owned by the app" }
    Adb push $tmp.FullName "$Device/gates.json" | Out-Null
} finally { Remove-Item -LiteralPath $tmp }
Adb shell "rm -rf $Device/out" | Out-Null
Instrument
$out = Join-Path $Dev 'dev-out'
New-Item -ItemType Directory -Force $out | Out-Null
foreach ($id in $gates.Keys) { Adb pull "$Device/out/$id.png" (Join-Path $out "$id-render.png") | Out-Null }
Adb shell "rm -f $Device/gates.json" | Out-Null
python (Join-Path $PSScriptRoot 'dev_compare.py') (Join-Path $Dev 'gpr-out') $out
