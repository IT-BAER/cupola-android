#requires -Version 7
<#
.SYNOPSIS
  Builds the G-PR blind-run set (plan G-PR): renders the debug build at each hold-out pose, crops the NASA photo to the
  same size, pushes both and trials.json to the phone. Debug build only; the wallpaper must be active on the phone.

.DESCRIPTION
  Reads <Holdout>\holdout.json (an array, or {"photos": [...]}). Each entry has the photo file name in `file` (relative to
  <Holdout>; if absent, `nasa_id` plus .jpg/.jpeg/.png) and optionally a `gate` object
  {time, lat, lon, alt_km, fov, crop?} plus either az, el (Earth only) or heading_deg / pass (asc|desc; the Cupola
  interior, optional rest nadir|diagonal, yaw, pitch), and optional roll, projection (fisheye), fisheye_fov
  (`crop` = [x,y,w,h] in photo pixels). Entries without `gate` are skipped
  and counted. Pairs are numbered p01.. in shuffled order; the id map exists only in <Holdout>\gpr-map.json. Console
  output holds counts and opaque pair ids only. Needs python with Pillow, else System.Drawing is used.

  Usage:
    pwsh .\tools\gpr\prepare-gpr.ps1 -DryRun                    # validate holdout.json, counts only
    pwsh .\tools\gpr\prepare-gpr.ps1 [-Serial 10.10.70.101:5555] # build and push the set
  Run the viewer (no ids on screen):
    adb -s <serial> shell am start -n com.baer.cupola.debug/com.baer.cupola.gpr.BlindRunActivity
  Pull the results (CSV + summary line; logcat tag CupolaGpr), then remove the photos from the phone:
    $d = '/sdcard/Android/data/com.baer.cupola.debug/files/gpr'
    & $adb -s $s shell "ls $d/results-*.csv" | ForEach-Object { & $adb -s $s pull $_ .\local\reference\ }
    & $adb -s $s shell "rm -r $d"
#>
param(
    [string]$Serial = '10.10.70.101:5555',
    [string]$Adb = 'D:\Software\Android\Sdk\platform-tools\adb.exe',
    [string]$Holdout = 'local/reference/holdout',
    # Exposure adapts over several seconds; an early capture keeps the previous scene's exposure.
    [int]$SettleSeconds = 12,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$Device = '/sdcard/Android/data/com.baer.cupola.debug/files'
$Gate = 'com.baer.cupola.debug/com.baer.cupola.wallpaper.GateReceiver'
$Inv = [cultureinfo]::InvariantCulture

function Fail([string]$msg) { Write-Error $msg -ErrorAction Stop }
function Num($v) { [string]::Format($Inv, '{0}', [double]$v) }
function PhotoPath($e) {
    $names = if ($e.file) { @($e.file) } elseif ($e.nasa_id) { '.jpg', '.jpeg', '.png' | ForEach-Object { "$($e.nasa_id)$_" } } else { @() }
    foreach ($n in $names) { $p = Join-Path $Holdout $n; if (Test-Path -LiteralPath $p) { return $p } }
    $null
}

$jsonPath = Join-Path $Holdout 'holdout.json'
if (-not (Test-Path -LiteralPath $jsonPath)) { Fail 'holdout.json not found' }
# DateKind String: a DateTime would reach the device shell as a locale string with a space.
try { $doc = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json -DateKind String } catch { Fail 'holdout.json is not valid JSON' }
$entries = @(if ($doc -is [array]) { $doc } else { $doc.photos })
$gated = @($entries | Where-Object { $_.gate })
$required = 'time', 'lat', 'lon', 'alt_km', 'fov'
$enums = @{ pass = 'asc', 'desc'; rest = 'nadir', 'diagonal'; projection = 'rectilinear', 'fisheye' }
$numeric = 'lat', 'lon', 'alt_km', 'az', 'el', 'fov', 'heading_deg', 'roll', 'yaw', 'pitch', 'fisheye_fov'
function Test-Gate($g) {
    $view = ($null -ne $g.az -and $null -ne $g.el) -or $null -ne $g.heading_deg -or $null -ne $g.pass
    if (($required | Where-Object { $null -eq $g.$_ }) -or -not $view) { return $false }
    if ($g.time -cnotmatch '^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z$') { return $false }
    foreach ($k in $enums.Keys) { if ($null -ne $g.$k -and $g.$k -cnotin $enums[$k]) { return $false } }
    foreach ($k in $numeric) { if ($null -ne $g.$k -and $g.$k -isnot [double] -and $g.$k -isnot [long] -and $g.$k -isnot [int]) { return $false } }
    $true
}
$bad = 0; $missingPhoto = 0
foreach ($e in $gated) {
    if (-not (Test-Gate $e.gate)) { $bad++ }
    if (-not (PhotoPath $e)) { $missingPhoto++ }
}
$python = Get-Command python -ErrorAction SilentlyContinue
$pillow = $false
if ($python) { & $python.Source -I -c 'import PIL' 2>$null; $pillow = $LASTEXITCODE -eq 0 }
Write-Host "entries=$($entries.Count) gated=$($gated.Count) skipped_no_gate=$($entries.Count - $gated.Count) incomplete_gate=$bad photo_missing=$missingPhoto pillow=$pillow"
if ($bad -or $missingPhoto -or -not $gated.Count) { Fail 'holdout.json is not ready' }
if ($DryRun) { Write-Host 'dry run: nothing rendered, nothing pushed'; return }

function Adb { & $Adb -s $Serial @args }
$size = (Adb shell wm size | Select-String '(\d+)x(\d+)' | Select-Object -Last 1).Matches[0]
$long = [math]::Max([int]$size.Groups[1].Value, [int]$size.Groups[2].Value)
$short = [math]::Min([int]$size.Groups[1].Value, [int]$size.Groups[2].Value)
$w = [int]($long / 2); $h = $short
Write-Host "target size ${w}x$h"

$py = @'
import sys
from PIL import Image
src, dst, w, h, crop = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
im = Image.open(src).convert("RGB")
if crop:
    x, y, cw, ch = map(int, crop.split(","))
else:
    cw, ch = (im.width, int(im.width * h / w)) if im.width * h <= im.height * w else (int(im.height * w / h), im.height)
    x, y = (im.width - cw) // 2, (im.height - ch) // 2
im.crop((x, y, x + cw, y + ch)).resize((w, h), Image.LANCZOS).save(dst)
'@

function Save-Photo([string]$src, [string]$dst, $crop) {
    $box = if ($crop) { ($crop | ForEach-Object { [int]$_ }) -join ',' } else { '' }
    if ($pillow) { & $python.Source -I -c $py $src $dst $w $h $box; if ($LASTEXITCODE) { Fail 'photo crop failed' }; return }
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $src).Path)
    try {
        if ($crop) { $x, $y, $cw, $ch = $crop | ForEach-Object { [int]$_ } }
        else {
            if ($img.Width * $h -le $img.Height * $w) { $cw = $img.Width; $ch = [int]($img.Width * $h / $w) } else { $cw = [int]($img.Height * $w / $h); $ch = $img.Height }
            $x = [int](($img.Width - $cw) / 2); $y = [int](($img.Height - $ch) / 2)
        }
        $bmp = [System.Drawing.Bitmap]::new($w, $h)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = 'HighQualityBicubic'
        $g.DrawImage($img, [System.Drawing.Rectangle]::new(0, 0, $w, $h), [System.Drawing.Rectangle]::new($x, $y, $cw, $ch), 'Pixel')
        $bmp.Save([System.IO.Path]::GetFullPath($dst), [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose()
    } finally { $img.Dispose() }
}

$out = Join-Path $Holdout 'gpr-out'
if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory $out | Out-Null
$order = 0..($gated.Count - 1) | Get-Random -Count $gated.Count
$pairs = @(); $map = @()
try {
    for ($i = 0; $i -lt $gated.Count; $i++) {
        $e = $gated[$order[$i]]; $g = $e.gate
        $id = 'p{0:d2}' -f ($i + 1)
        $scene = @('--es', 'cmd', 'scene', '--es', 'time', $g.time)
        foreach ($k in $numeric) { if ($null -ne $g.$k) { $scene += '--ef', $k, (Num $g.$k) } }
        foreach ($k in $enums.Keys) { if ($null -ne $g.$k) { $scene += '--es', $k, $g.$k } }
        $reply = Adb shell am broadcast -n $Gate @scene 2>&1
        if (-not ($reply -match 'Broadcast completed')) { Fail "scene broadcast failed for $id" }
        Start-Sleep $SettleSeconds
        Adb shell am broadcast -n $Gate --es cmd capture --es arg "gpr-$id" --ei w $w --ei h $h | Out-Null
        Start-Sleep 2
        $remote = @(Adb shell "ls $Device/captures/gpr-$id-*.png" 2>$null | Where-Object { $_ -like '*.png' }) | Select-Object -First 1
        if (-not $remote) { Fail "capture missing for $id (is the Cupola wallpaper active?)" }
        Adb pull $remote (Join-Path $out "$id-render.png") | Out-Null
        Adb shell rm $remote | Out-Null
        Save-Photo (PhotoPath $e) (Join-Path $out "$id-photo.png") $g.crop
        $pairs += [ordered]@{ id = $id; photo = "$id-photo.png"; render = "$id-render.png" }
        $map += [ordered]@{ id = $id; nasa_id = $e.nasa_id; file = $e.file }
        Write-Host "pair $id done"
    }
} finally {
    Adb shell am broadcast -n $Gate --es cmd scene --es arg clear | Out-Null
}
$map | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Holdout 'gpr-map.json') -Encoding utf8
@{ pairs = $pairs } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $out 'trials.json') -Encoding utf8
# The folder must belong to the app: one made by the adb shell (uid 2000) is unreadable for it (EACCES).
Adb shell "run-as com.baer.cupola.debug mkdir -p $Device/gpr" | Out-Null
# run-as cannot reach /sdcard on some builds (Pixel, Android 17), so the mkdir can fail; a missing folder must fail here.
$owner = @(Adb shell "stat -c %u $Device/gpr $Device 2>/dev/null")
if ($owner.Count -ne 2 -or @($owner | Select-Object -Unique).Count -ne 1) { Fail "$Device/gpr is missing or not owned by the app: pull any results-*.csv, remove the folder, and if run-as cannot create it, move the app-made captures folder (empty it first) to gpr; then run again" }
foreach ($f in Get-ChildItem -LiteralPath $out) { Adb push $f.FullName "$Device/gpr/$($f.Name)" | Out-Null }
Write-Host "pushed $($pairs.Count) pairs, $($pairs.Count * 2) trials; scene override cleared"
