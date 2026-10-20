# Encodes the station atlas (plan P4.6) from local\blender\exterior\station_{albedo,orm}.png into KTX2:
# local\textures\out\station\{astc,etc2}\*.ktx2, same tools and conventions as build_textures.ps1
# (ASTC -thorough, ETC2 = ASTC file without level 0, ktx validate). Albedo ASTC_6x6_SRGB, ORM ASTC_8x8_UNORM.
$ErrorActionPreference = 'Stop'
$Inv = [Globalization.CultureInfo]::InvariantCulture
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Local = Join-Path $Root 'local'
$In = Join-Path $Local 'blender\exterior'
$Out = Join-Path $Local 'textures\out\station'
$Vips = Join-Path $Local 'tools\vips\vips-dev-8.18\bin\vips.exe'
$VipsHeader = Join-Path $Local 'tools\vips\vips-dev-8.18\bin\vipsheader.exe'
$Astcenc = Join-Path $Local 'tools\astcenc\bin\astcenc-avx2.exe'
$Ktx = Join-Path $Local 'tools\ktx\bin\ktx.exe'
$Cmp = Join-Path $Local 'tools\compressonator\compressonatorcli-4.5.52-win64\compressonatorcli.exe'

function Run([string]$exe, [object[]]$argv) {
    $o = & $exe @argv 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE): $o" }
    $o
}
function Slice([string]$file, [byte[]]$bytes, [int]$offset, [int]$count) {
    $b = New-Object byte[] $count
    [Array]::Copy($bytes, $offset, $b, 0, $count)
    [IO.File]::WriteAllBytes($file, $b)
}
function Build([string]$name, [string]$block, [bool]$srgb, [string]$astcFormat, [string]$etcFd, [string]$etcFormat, [int]$etcBlockBytes) {
    $dir = Join-Path $Out "work\$name"
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    New-Item -ItemType Directory -Force $dir | Out-Null
    $src = Join-Path $In "$name.png"
    $w = [int](Run $VipsHeader @('-f', 'width', $src))
    $levels = @(); $cur = $w
    while ($true) { $levels += $cur; if ($cur -eq 1) { break }; $cur = [Math]::Max(1, $cur -shr 1) }
    $astcRaws = @(); $etcRaws = @()
    for ($i = 0; $i -lt $levels.Count; $i++) {
        $s = $levels[$i]
        $png = Join-Path $dir "L$i.png"
        if ($i -eq 0) { Run $Vips @('copy', $src, "$png[compression=1]") | Out-Null }
        else {
            $a = @('thumbnail', $src, "$png[compression=1]", $s, '--height', $s, '--size', 'force')
            if ($srgb) { $a += '--linear' }
            Run $Vips $a | Out-Null
        }
        $astc = Join-Path $dir "L$i.astc"
        $mode = if ($srgb) { '-cs' } else { '-cl' }
        Run $Astcenc @($mode, $png, $astc, $block, '-thorough', '-silent') | Out-Null
        $bytes = [IO.File]::ReadAllBytes($astc)
        $raw = Join-Path $dir "L$i.astc.raw"
        Slice $raw $bytes 16 ($bytes.Length - 16)
        $astcRaws += $raw
        if ($i -ge 1) {
            $k1 = Join-Path $dir "L$i.ktx"
            Run $Cmp @('-fd', $etcFd, '-nomipmap', '-noprogress', '-silent', $png, $k1) | Out-Null
            $b = [IO.File]::ReadAllBytes($k1)
            $kv = [BitConverter]::ToUInt32($b, 60)
            $size = [BitConverter]::ToUInt32($b, 64 + $kv)
            $expected = [Math]::Ceiling($s / 4) * [Math]::Ceiling($s / 4) * $etcBlockBytes
            if ($size -ne $expected) { throw "unexpected ETC2 size $size, expected $expected ($png)" }
            $eraw = Join-Path $dir "L$i.etc.raw"
            Slice $eraw $b (68 + $kv) $size
            $etcRaws += $eraw
        }
    }
    foreach ($d in 'astc', 'etc2') { New-Item -ItemType Directory -Force (Join-Path $Out $d) | Out-Null }
    $targets = @(
        @{ File = (Join-Path $Out "astc\$name.ktx2"); Fmt = $astcFormat; W = $w; N = $levels.Count; Raws = $astcRaws },
        @{ File = (Join-Path $Out "etc2\$name.ktx2"); Fmt = $etcFormat; W = ($w -shr 1); N = ($levels.Count - 1); Raws = $etcRaws })
    foreach ($t in $targets) {
        if (Test-Path $t.File) { Remove-Item $t.File }
        $a = @('create', '--raw', '--format', $t.Fmt, '--width', $t.W, '--height', $t.W, '--levels', $t.N,
            '--assign-texcoord-origin', 'top-left')
        Run $Ktx ($a + $t.Raws + $t.File) | Out-Null
        Run $Ktx @('validate', $t.File) | Out-Null
        "{0}`t{1}`t{2} B`t{3}" -f (Split-Path (Split-Path $t.File) -Leaf), (Split-Path $t.File -Leaf), (Get-Item $t.File).Length, (Get-FileHash $t.File -Algorithm SHA256).Hash.ToLower()
    }
}

Build 'station_albedo' '6x6' $true 'ASTC_6x6_SRGB_BLOCK' 'ETC2_RGB' 'ETC2_R8G8B8_SRGB_BLOCK' 8
Build 'station_orm' '8x8' $false 'ASTC_8x8_UNORM_BLOCK' 'ETC2_RGB' 'ETC2_R8G8B8_UNORM_BLOCK' 8
