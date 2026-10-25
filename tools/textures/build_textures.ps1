# Builds the Cupola Earth and sky textures (plan P5.3) from local\sources into
# local\textures\out\{astc,etc2}\*.ktx2 and level-0 previews into local\textures\out\preview.
# The runtime contract for every output is in README.md next to this script.
# Intermediates live in local\textures\work\<asset> and are wiped at the start of each asset.
param(
    [ValidateSet('day', 'monthly', 'clouds', 'lights', 'normal', 'water', 'stars', 'interior')]
    [string[]]$Assets = @('day', 'monthly', 'clouds', 'lights', 'normal', 'water', 'stars', 'interior'),
    # Interior panorama bake (tools/blender/bake_panorama.py output with manifest.json and ldr\).
    [string]$BakeDir = '',
    # Exaggeration of the physical terrain slope in the normal map (1 = true slope).
    [double]$NormalStrength = 10
)
$ErrorActionPreference = 'Stop'
$Inv = [Globalization.CultureInfo]::InvariantCulture

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Local = Join-Path $Root 'local'
if (-not $BakeDir) { $BakeDir = Join-Path $Local 'blender\r3\bake' }
$Work = Join-Path $Local 'textures\work'
$Out = Join-Path $Local 'textures\out'
$VipsBin = Join-Path $Local 'tools\vips\vips-dev-8.18\bin'
$Vips = Join-Path $VipsBin 'vips.exe'
$VipsHeader = Join-Path $VipsBin 'vipsheader.exe'
# One fixed SIMD build of astcenc so the output never depends on which variant runs.
$Astcenc = Join-Path $Local 'tools\astcenc\bin\astcenc-avx2.exe'
$Ktx = Join-Path $Local 'tools\ktx\bin\ktx.exe'
$Cmp = Join-Path $Local 'tools\compressonator\compressonatorcli-4.5.52-win64\compressonatorcli.exe'

# Crop: 0.8 of the rows centred on the equator, latitudes +72 to -72 degrees.
$CropLat = 72.0
$EarthRadius = 6371000.0

function F([double]$x) { $x.ToString('R', $Inv) }

function Run([string]$exe, [object[]]$argv) {
    # A leading space keeps negative numbers from being parsed as options by vips (GOption).
    $argv = @($argv | ForEach-Object { if ($_ -is [double]) { if ($_ -lt 0) { ' ' + (F $_) } else { F $_ } } else { [string]$_ } })
    $log = & $exe @argv 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$([IO.Path]::GetFileName($exe)) $($argv -join ' ') failed with exit $LASTEXITCODE`n$($log -join "`n")"
    }
    $log
}
function V { Run $Vips $args | Out-Null }
# vips image-array strings drop backslashes, so paths go in with forward slashes.
function ImageList { (@($args | ForEach-Object { $_ }) | ForEach-Object { $_.Replace('\', '/') }) -join ' ' }
function VNum { [double]::Parse(((Run $Vips $args) -join '').Trim().Replace(',', '.'), $Inv) }

function Get-Dims([string]$file) {
    @([int](Run $VipsHeader @('-f', 'width', $file)), [int](Run $VipsHeader @('-f', 'height', $file)))
}
function Assert-Dims([string]$file, [int]$w, [int]$h) {
    $d = Get-Dims $file
    if ($d[0] -ne $w -or $d[1] -ne $h) { throw "$file is $($d[0])x$($d[1]), expected ${w}x$h" }
}

# Full mip chain, Vulkan rule: level n is max(1, floor(size / 2^n)).
function Get-Levels([int]$w, [int]$h) {
    $levels = @(, @($w, $h))
    while ($w -gt 1 -or $h -gt 1) {
        $w = [Math]::Max(1, $w -shr 1); $h = [Math]::Max(1, $h -shr 1)
        $levels += , @($w, $h)
    }
    , $levels
}

# Every source is checked against sources.lock (bytes and SHA-256) before first use in a run.
$SourceLock = @{}
Get-Content (Join-Path $PSScriptRoot 'sources.lock') | Where-Object { $_ -and -not $_.StartsWith('#') } |
    Select-Object -Skip 1 | ForEach-Object {
        $p, $null, $b, $s, $null = $_ -split "`t"
        $SourceLock[$p] = @{ Bytes = [long]$b; Sha = $s }
    }
$Verified = @{}
function Get-Source([string]$rel) {
    $key = "local/sources/$rel"
    $entry = $SourceLock[$key]
    if (-not $entry) { throw "not listed in sources.lock: $key" }
    $path = Join-Path $Local ('sources\' + $rel.Replace('/', '\'))
    if (-not $Verified.ContainsKey($key)) {
        $bytes = (Get-Item -LiteralPath $path).Length
        $sha = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()
        if ($bytes -ne $entry.Bytes -or $sha -ne $entry.Sha) {
            throw "source differs from sources.lock: $key is $bytes B $sha, lock says $($entry.Bytes) B $($entry.Sha)"
        }
        $Verified[$key] = $true
    }
    $path
}

function New-AssetDir([string]$name) {
    $dir = Join-Path $Work $name
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    New-Item -ItemType Directory -Force $dir | Out-Null
    $dir
}

# Writes L<n>.png for every mip level of $master. sRGB data is resampled in linear light
# (vips thumbnail --linear: sRGB -> scRGB, Lanczos3 resize, back to sRGB).
function Write-Levels([string]$master, [string]$dir, [string]$prefix, [bool]$srgb) {
    $d = Get-Dims $master
    $levels = Get-Levels $d[0] $d[1]
    $pngs = @()
    for ($i = 0; $i -lt $levels.Count; $i++) {
        $w, $h = $levels[$i]
        $png = Join-Path $dir "$prefix.L$i.png"
        if ($i -eq 0) { V copy $master "$png[compression=1]" }
        else {
            $a = @('thumbnail', $master, "$png[compression=1]", $w, '--height', $h, '--size', 'force')
            if ($srgb) { $a += '--linear' }
            Run $Vips $a | Out-Null
        }
        Assert-Dims $png $w $h
        $pngs += $png
    }
    , $pngs
}

# Crop rows [top, top+rows) of a source, then Lanczos3-resample to w x h (linear light if sRGB).
# The source is read sequentially, so huge JPEGs stream instead of being decoded into memory.
function New-Master([string]$src, [int]$top, [int]$rows, [int]$w, [int]$h, [string]$master, [bool]$srgb, [bool]$grey = $false) {
    $dir = Split-Path $master
    $crop = Join-Path $dir 'crop.v'
    $srcW = (Get-Dims $src)[0]
    V crop "$src[access=sequential]" "$crop[keep=none]" 0 $top $srcW $rows
    if ($grey) {
        # vips only linearises 3-band sRGB, so a grey source becomes R=G=B first.
        $rgb = Join-Path $dir 'crop_rgb.v'
        V colourspace $crop $rgb srgb
        Remove-Item $crop; $crop = $rgb
    }
    $a = @('thumbnail', $crop, $master, $w, '--height', $h, '--size', 'force')
    if ($srgb) { $a += '--linear' }
    Run $Vips $a | Out-Null
    Remove-Item $crop
    Assert-Dims $master $w $h
}

function Write-Slice([string]$file, [byte[]]$bytes, [int]$offset, [int]$count) {
    $fs = [IO.File]::Create($file)
    try { $fs.Write($bytes, $offset, $count) } finally { $fs.Dispose() }
}

function Encode-Astc([string]$png, [string]$block, [string[]]$encArgs) {
    $astc = [IO.Path]::ChangeExtension($png, 'astc')
    $extra = if ($encArgs.Count -gt 1) { $encArgs[1..($encArgs.Count - 1)] } else { @() }
    Run $Astcenc (@($encArgs[0], $png, $astc, $block, '-thorough', '-silent') + $extra) | Out-Null
    $bytes = [IO.File]::ReadAllBytes($astc)
    $raw = [IO.Path]::ChangeExtension($png, 'astc.raw')
    # .astc = 16-byte header + blocks; ktx create --raw takes the bare blocks.
    Write-Slice $raw $bytes 16 ($bytes.Length - 16)
    $raw
}

function Encode-Etc2([string]$png, [string]$fd, [int]$blockBytes) {
    $ktx1 = [IO.Path]::ChangeExtension($png, "$fd.ktx")
    Run $Cmp @('-fd', $fd, '-nomipmap', '-noprogress', '-silent', $png, $ktx1) | Out-Null
    $b = [IO.File]::ReadAllBytes($ktx1)
    $w, $h = Get-Dims $png
    $levels = [BitConverter]::ToUInt32($b, 56); $kv = [BitConverter]::ToUInt32($b, 60)
    $size = [BitConverter]::ToUInt32($b, 64 + $kv)
    $expected = [Math]::Ceiling($w / 4) * [Math]::Ceiling($h / 4) * $blockBytes
    if ([BitConverter]::ToUInt32($b, 36) -ne $w -or [BitConverter]::ToUInt32($b, 40) -ne $h -or $levels -ne 1 -or $size -ne $expected) {
        throw "unexpected KTX1 from compressonator: $ktx1 (${w}x$h, levels $levels, $size B, expected $expected B)"
    }
    $raw = [IO.Path]::ChangeExtension($png, "$fd.raw")
    Write-Slice $raw $b (68 + $kv) $size
    $raw
}

function Write-Ktx2([string]$file, [string]$format, [int]$w, [int]$h, [int]$layers, [int]$levels, [string[]]$raws) {
    if (Test-Path $file) { Remove-Item $file }
    $a = @('create', '--raw', '--format', $format, '--width', $w, '--height', $h, '--levels', $levels,
        '--assign-texcoord-origin', 'top-left')
    if ($layers -gt 1) { $a += @('--layers', $layers) }
    Run $Ktx ($a + $raws + $file) | Out-Null
    Run $Ktx @('validate', $file) | Out-Null
}

# $astcPngs / $etcPngs: per layer, the level PNGs in order. Images go level-major into ktx create.
# $etcShift: ETC2 level 0 is ASTC level $etcShift (ASTC size >> $etcShift).
function Write-Outputs([string]$name, [object[]]$astcPngs, [object[]]$etcPngs, [string]$block, [string[]]$encArgs,
    [string]$astcFormat, [string]$etcFd, [string]$etcFormat, [int]$etcBlockBytes, [string]$decodeArgs, [int]$etcShift = 1) {
    $layers = $astcPngs.Count
    $astcRaws = @(); $etcRaws = @()
    for ($l = 0; $l -lt $astcPngs[0].Count; $l++) {
        foreach ($layer in $astcPngs) { $astcRaws += Encode-Astc $layer[$l] $block $encArgs }
    }
    for ($l = 0; $l -lt $etcPngs[0].Count; $l++) {
        foreach ($layer in $etcPngs) { $etcRaws += Encode-Etc2 $layer[$l] $etcFd $etcBlockBytes }
    }
    $w, $h = Get-Dims $astcPngs[0][0]
    $astcFile = Join-Path $Out "astc\$name.ktx2"
    $etcFile = Join-Path $Out "etc2\$name.ktx2"
    Write-Ktx2 $astcFile $astcFormat $w $h $layers $astcPngs[0].Count $astcRaws
    Write-Ktx2 $etcFile $etcFormat ($w -shr $etcShift) ($h -shr $etcShift) $layers $etcPngs[0].Count $etcRaws

    # Preview: level 0 decoded back from the ASTC blocks, layers as a mosaic 4 wide, 2048 px wide.
    $decoded = @()
    foreach ($layer in $astcPngs) {
        $astc = [IO.Path]::ChangeExtension($layer[0], 'astc')
        $dec = [IO.Path]::ChangeExtension($layer[0], 'decoded.png')
        Run $Astcenc (@($decodeArgs, $astc, $dec) + $(if ($name -eq 'terrain_normal') { @('-dsw', 'raz1') } else { @() })) | Out-Null
        $decoded += $dec
    }
    $dir = Split-Path $astcPngs[0][0]
    $mosaic = $decoded[0]
    if ($decoded.Count -gt 1) {
        $mosaic = Join-Path $dir 'mosaic.v'
        V arrayjoin (ImageList $decoded) $mosaic --across 4
    }
    $preview = Join-Path $Out "preview\$name.png"
    V thumbnail $mosaic $preview 2048 --height 100000
}

function Build-Day {
    $dir = New-AssetDir 'day'
    # BMNG 86400 tiles: A..D = 90-degree columns from 180 W eastwards, 1 = north half, 2 = south half.
    # Each tile becomes one 7168 x 5734 layer: north tiles keep rows 4320..21599 (72 N to 0),
    # south tiles rows 0..17279 (0 to 72 S). Layer order A1 B1 C1 D1 A2 B2 C2 D2.
    # ASTC 12x12 (0.89 bit/texel) at 7168 keeps more ground detail than 8x8 at 4096 and stays under 50 MB
    # per file (GitHub); ETC2 is ASTC level 2 (1792 px).
    $tiles = 'A1', 'B1', 'C1', 'D1', 'A2', 'B2', 'C2', 'D2'
    $layers = @(); $masters = @()
    foreach ($t in $tiles) {
        $src = Get-Source "bmng2004-200407-86400/world.topo.bathy.200407.3x21600x21600.$t.jpg"
        $master = Join-Path $dir "$t.v"
        $top = if ($t.EndsWith('1')) { 4320 } else { 0 }
        New-Master $src $top 17280 7168 5734 $master $true
        $masters += $master
        $layers += , (Write-Levels $master $dir $t $true)
    }
    # Tile-order check: the assembled layers must match the July 2004 21600 px map of the same data.
    $mosaic = Join-Path $dir 'order_mosaic.v'
    V arrayjoin (ImageList $masters) $mosaic --across 4
    $a = Join-Path $dir 'order_a.v'; $b = Join-Path $dir 'order_b.v'
    V thumbnail $mosaic $a 1024 --height 410 --size force
    $july = Join-Path $dir 'order_july.v'
    New-Master (Get-Source 'bmng2004-monthly/world.topo.bathy.200407.3x21600x10800.jpg') 1080 8640 1024 410 $july $true
    $diff = Join-Path $dir 'order_diff.v'; $abs = Join-Path $dir 'order_abs.v'
    V subtract $a $july $diff; V abs $diff $abs
    $mad = VNum avg $abs
    "day tile order check: mean |mosaic - July 21600 map| = $(F ([Math]::Round($mad, 3))) (8-bit units)"
    if ($mad -gt 4) { throw "day tiles do not match the July 2004 map (mean abs diff $mad); tile order is wrong" }
    $etc = @($layers | ForEach-Object { , ($_[2..($_.Count - 1)]) })
    Write-Outputs 'day_detail' $layers $etc '12x12' @('-cs') 'ASTC_12x12_SRGB_BLOCK' 'ETC2_RGB' 'ETC2_R8G8B8_SRGB_BLOCK' 8 '-ds' 2
}

function Build-Monthly {
    $dir = New-AssetDir 'monthly'
    $layers = @()
    foreach ($m in 1..12) {
        $mm = $m.ToString('00')
        $src = Get-Source "bmng2004-monthly/world.topo.bathy.2004$mm.3x21600x10800.jpg"
        $master = Join-Path $dir "m$mm.v"
        New-Master $src 1080 8640 2048 819 $master $true
        $layers += , (Write-Levels $master $dir "m$mm" $true)
    }
    $etc = @($layers | ForEach-Object { , ($_[1..($_.Count - 1)]) })
    Write-Outputs 'monthly_colour' $layers $etc '6x6' @('-cs') 'ASTC_6x6_SRGB_BLOCK' 'ETC2_RGB' 'ETC2_R8G8B8_SRGB_BLOCK' 8 '-ds'
}

function Build-Clouds {
    $dir = New-AssetDir 'clouds'
    $src = Get-Source 'bm2002-clouds/cloud_combined_8192.tif'
    # 0.8 of 4096 rows is 3276.8, so the +-72 degree band is resampled to 3277 rows with a bicubic
    # index map: output row j samples source row 409.6 + (j + 0.5) * s - 0.5, s = 3276.8 / 3277
    # (vips sample coordinates are pixel centres).
    $s = 3276.8 / 3277
    $xyz = Join-Path $dir 'xyz.v'; $idx = Join-Path $dir 'idx.v'; $master = Join-Path $dir 'clouds.v'
    V xyz $xyz 8192 3277
    V linear $xyz $idx "1 $(F $s)" "0 $(F (409.6 + 0.5 * $s - 0.5))"
    V mapim $src "$master[keep=none]" $idx --interpolate bicubic
    Assert-Dims $master 8192 3277
    $layer = Write-Levels $master $dir 'clouds' $false
    Write-Outputs 'clouds' @(, $layer) @(, $layer[1..($layer.Count - 1)]) '8x8' @('-cl', '-esw', 'rrr1') 'ASTC_8x8_UNORM_BLOCK' 'ETC2_RGB' 'ETC2_R8G8B8_UNORM_BLOCK' 8 '-dl'
}

function Build-Lights {
    $dir = New-AssetDir 'lights'
    $src = Get-Source 'blackmarble2016/BlackMarble_2016_3km_gray.jpg'
    $master = Join-Path $dir 'lights.v'
    New-Master $src 675 5400 8192 3277 $master $true $true
    $layer = Write-Levels $master $dir 'lights' $true
    Write-Outputs 'city_lights' @(, $layer) @(, $layer[1..($layer.Count - 1)]) '8x8' @('-cs', '-esw', 'rrr1') 'ASTC_8x8_SRGB_BLOCK' 'ETC2_RGB' 'ETC2_R8G8B8_SRGB_BLOCK' 8 '-ds'
}

function Build-Normal {
    $dir = New-AssetDir 'normal'
    $src = Get-Source 'etopo2022/ETOPO_2022_v1_60s_N90W180_surface.tif'
    $crop = Join-Path $dir 'crop.v'; $abs = Join-Path $dir 'crop_abs.v'; $sum = Join-Path $dir 'crop_sum.v'
    $height = Join-Path $dir 'height.v'
    V crop $src "$crop[keep=none]" 0 1080 21600 8640
    # max(h, 0) = (h + |h|) / 2: oceans flat at 0 m.
    V abs $crop $abs; V add $crop $abs $sum; V linear $sum $height 0.5 0
    Remove-Item $crop, $abs, $sum

    $levels = Get-Levels 4096 1638
    $rgb = @(); $xy = @()
    for ($i = 0; $i -lt $levels.Count; $i++) {
        $w, $h = $levels[$i]
        $p = { param($n) Join-Path $dir "n$i.$n.v" }
        V resize $height (& $p 'r') ($w / 21600.0) --vscale ($h / 8640.0) --kernel lanczos3
        Assert-Dims (& $p 'r') $w $h
        # Clamp again: Lanczos rings below 0 m along coasts.
        V abs (& $p 'r') (& $p 'ra'); V add (& $p 'r') (& $p 'ra') (& $p 'rs'); V linear (& $p 'rs') (& $p 'h') 0.5 0
        # Central differences; longitude wraps, latitude edges repeat the edge row.
        V embed (& $p 'h') (& $p 'ex') 1 0 ($w + 2) $h --extend repeat
        V crop (& $p 'ex') (& $p 'xp') 2 0 $w $h; V crop (& $p 'ex') (& $p 'xm') 0 0 $w $h
        V subtract (& $p 'xp') (& $p 'xm') (& $p 'gx')
        V embed (& $p 'h') (& $p 'ey') 0 1 $w ($h + 2) --extend copy
        V crop (& $p 'ey') (& $p 'yp') 0 2 $w $h; V crop (& $p 'ey') (& $p 'ym') 0 0 $w $h
        V subtract (& $p 'yp') (& $p 'ym') (& $p 'gy')
        # cos(latitude of each row centre); vips trig works in degrees.
        V xyz (& $p 'xyz') $w $h; V extract_band (& $p 'xyz') (& $p 'row') 1
        V linear (& $p 'row') (& $p 'lat') (-2 * $CropLat / $h) ($CropLat - $CropLat / $h)
        V math (& $p 'lat') (& $p 'cos') cos
        # Tangent frame X east, Y north, Z up. East slope = gx / (2 dx cos(lat)) with dx the
        # equatorial pixel width; north slope = -gy / (2 dy) because rows run southwards.
        $dx = 2 * [Math]::PI * $EarthRadius / $w
        $dy = (2 * $CropLat * [Math]::PI / 180) * $EarthRadius / $h
        V linear (& $p 'gx') (& $p 'gxs') (-$NormalStrength / (2 * $dx)) 0
        V divide (& $p 'gxs') (& $p 'cos') (& $p 'nx')
        V linear (& $p 'gy') (& $p 'ny') ($NormalStrength / (2 * $dy)) 0
        V multiply (& $p 'nx') (& $p 'nx') (& $p 'nx2'); V multiply (& $p 'ny') (& $p 'ny') (& $p 'ny2')
        V add (& $p 'nx2') (& $p 'ny2') (& $p 'sq'); V linear (& $p 'sq') (& $p 'sq1') 1 1
        V math2_const (& $p 'sq1') (& $p 'inv') pow (-0.5)
        V multiply (& $p 'nx') (& $p 'inv') (& $p 'x'); V multiply (& $p 'ny') (& $p 'inv') (& $p 'y')
        V bandjoin (ImageList (& $p 'x') (& $p 'y') (& $p 'inv')) (& $p 'xyzn')
        # [-1, 1] -> 0..255, rounded (cast truncates, hence +128 instead of +127.5).
        V linear (& $p 'xyzn') (& $p 'enc') 127.5 128
        $png = Join-Path $dir "normal.L$i.png"
        V cast (& $p 'enc') (& $p 'u') uchar
        # Tagged as RGB(A): a b-w image with extra bands is saved by pngsave as grey + alpha.
        V copy (& $p 'u') "$png[compression=1]" --interpretation srgb
        Assert-Dims $png $w $h
        $rgb += $png
        if ($i -gt 0) {
            # ETC2 has no two-channel format in Compressonator; RGBA with RGB = X, A = Y keeps the
            # same .ga sampling as the ASTC normal-mode file.
            V extract_band (& $p 'u') (& $p 'ux') 0; V extract_band (& $p 'u') (& $p 'uy') 1
            $xyPng = Join-Path $dir "normal.L$i.xy.png"
            V bandjoin (ImageList (& $p 'ux') (& $p 'ux') (& $p 'ux') (& $p 'uy')) (& $p 'uxy')
            V copy (& $p 'uxy') "$xyPng[compression=1]" --interpretation srgb
            $xy += $xyPng
        }
    }
    Write-Outputs 'terrain_normal' @(, $rgb) @(, $xy) '6x6' @('-cl', '-normal') 'ASTC_6x6_UNORM_BLOCK' 'ETC2_RGBA' 'ETC2_R8G8B8A8_UNORM_BLOCK' 16 '-dl'
}

function Build-Water {
    $dir = New-AssetDir 'water'
    $src = Get-Source 'etopo2022/ETOPO_2022_v1_60s_N90W180_surface.tif'
    $crop = Join-Path $dir 'crop.v'; $mask = Join-Path $dir 'mask.v'; $small = Join-Path $dir 'small.v'
    $master = Join-Path $dir 'water.v'
    V crop $src "$crop[keep=none]" 0 1080 21600 8640
    # Water = surface elevation below 0 m: 255, land 0. Box-filtered (linear kernel) so coasts become
    # fractional coverage without Lanczos overshoot.
    V relational_const $crop $mask less 0
    Remove-Item $crop
    V resize $mask $small (4096 / 21600.0) --vscale (1638 / 8640.0) --kernel linear
    # R = G = B = coverage; tagged sRGB so pngsave and compressonator see a plain RGB image.
    V bandjoin (ImageList $small $small $small) "$master[keep=none]"
    Remove-Item $mask, $small
    $rgbMaster = Join-Path $dir 'water_rgb.v'
    V copy $master $rgbMaster --interpretation srgb
    Assert-Dims $rgbMaster 4096 1638
    $layer = Write-Levels $rgbMaster $dir 'water' $false
    Write-Outputs 'water_mask' @(, $layer) @(, $layer[1..($layer.Count - 1)]) '8x8' @('-cl', '-esw', 'rrr1') 'ASTC_8x8_UNORM_BLOCK' 'ETC2_RGB' 'ETC2_R8G8B8_UNORM_BLOCK' 8 '-dl'
}

function Build-Stars {
    $dir = New-AssetDir 'stars'
    $src = Get-Source 'stars/eso0932a.tif'
    # Already equirectangular in galactic coordinates (verified by star positions, see README).
    $master = Join-Path $dir 'stars.v'
    New-Master $src 0 3000 4096 2048 $master $true
    $layer = Write-Levels $master $dir 'stars' $true
    Write-Outputs 'stars' @(, $layer) @(, $layer[1..($layer.Count - 1)]) '6x6' @('-cs') 'ASTC_6x6_SRGB_BLOCK' 'ETC2_RGB' 'ETC2_R8G8B8_SRGB_BLOCK' 8 '-ds'
}

# Interior atlas (README "interior_*"): the cropped faces of one layer stacked top to bottom in the order
# +X, -X, +Y, -Y, +Z, each padded (edge rows repeated) to a multiple of 96 rows on the 2048 grid (24 on the
# 512 grid), so no 6x6 or 8x8 block spans two faces. One mip level: the panorama is viewed near 1:1.
$InteriorFaces = @('+X', '-X', '+Y', '-Y', '+Z')
function Build-Interior {
    $dir = New-AssetDir 'interior'
    $manifestPath = Join-Path $BakeDir 'manifest.json'
    $manifest = Get-Content -Raw $manifestPath | ConvertFrom-Json
    foreach ($spec in @(
            @{ Layer = 'earth'; Grid = 2048; Align = 96; Block = '6x6'; Astc = 'ASTC_6x6_SRGB_BLOCK'; EtcFd = 'ETC2_RGBA'; Etc = 'ETC2_R8G8B8A8_SRGB_BLOCK'; EtcBytes = 16 },
            @{ Layer = 'cabin'; Grid = 2048; Align = 96; Block = '8x8'; Astc = 'ASTC_8x8_SRGB_BLOCK'; EtcFd = 'ETC2_RGB'; Etc = 'ETC2_R8G8B8_SRGB_BLOCK'; EtcBytes = 8 },
            @{ Layer = 'emissive'; Grid = 2048; Align = 96; Block = '8x8'; Astc = 'ASTC_8x8_SRGB_BLOCK'; EtcFd = 'ETC2_RGB'; Etc = 'ETC2_R8G8B8_SRGB_BLOCK'; EtcBytes = 8 },
            @{ Layer = 'ambient'; Grid = 512; Align = 24; Block = '8x8'; Astc = 'ASTC_8x8_SRGB_BLOCK'; EtcFd = 'ETC2_RGB'; Etc = 'ETC2_R8G8B8_SRGB_BLOCK'; EtcBytes = 8 })) {
        $parts = @()
        foreach ($face in $InteriorFaces) {
            $rel = "ldr/$($spec.Layer)_$face.png"
            $png = Join-Path $BakeDir $rel.Replace('/', '\')
            $sha = (Get-FileHash -LiteralPath $png -Algorithm SHA256).Hash.ToLower()
            if ($sha -ne $manifest.sha256.$rel) { throw "bake file differs from manifest.json: $rel is $sha" }
            $rect = $manifest.faces.$face."px_$($spec.Grid)"
            $rows = $rect[3] - $rect[1]
            Assert-Dims $png $spec.Grid $rows
            $padded = [int]([Math]::Ceiling($rows / $spec.Align) * $spec.Align)
            $part = Join-Path $dir "$($spec.Layer)_$($face.Replace('+', 'p').Replace('-', 'm')).v"
            V embed $png $part 0 0 $spec.Grid $padded --extend copy
            $parts += $part
        }
        # vips arrayjoin pads every cell to the largest image, so the faces are joined pairwise.
        $master = $parts[0]
        for ($i = 1; $i -lt $parts.Count; $i++) {
            $joined = Join-Path $dir "$($spec.Layer).join$i.v"
            V join $master $parts[$i] $joined vertical
            $master = $joined
        }
        $l0 = Join-Path $dir "$($spec.Layer).L0.png"
        V copy $master "$l0[compression=1]"
        $d = Get-Dims $l0
        $l1 = Join-Path $dir "$($spec.Layer).L1.png"
        Run $Vips @('thumbnail', $master, "$l1[compression=1]", ($d[0] -shr 1), '--height', ($d[1] -shr 1), '--size', 'force', '--linear') | Out-Null
        Write-Outputs "interior_$($spec.Layer)" @(, @($l0)) @(, @($l1)) $spec.Block @('-cs') $spec.Astc $spec.EtcFd $spec.Etc $spec.EtcBytes '-ds'
    }
}

foreach ($d in 'astc', 'etc2', 'preview') { New-Item -ItemType Directory -Force (Join-Path $Out $d) | Out-Null }
New-Item -ItemType Directory -Force $Work | Out-Null
$builders = @{ day = 'Build-Day'; monthly = 'Build-Monthly'; clouds = 'Build-Clouds'; lights = 'Build-Lights'; normal = 'Build-Normal'; water = 'Build-Water'; stars = 'Build-Stars'; interior = 'Build-Interior' }
foreach ($asset in $Assets) {
    $t = [Diagnostics.Stopwatch]::StartNew()
    & $builders[$asset]
    "built $asset in $([int]$t.Elapsed.TotalSeconds) s"
}

foreach ($d in 'astc', 'etc2') {
    $files = Get-ChildItem (Join-Path $Out $d) -Filter *.ktx2 | Sort-Object Name
    foreach ($f in $files) {
        "{0}`t{1}`t{2}`t{3}" -f $d, $f.Name, $f.Length, (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower()
    }
    $total = ($files | Measure-Object Length -Sum).Sum
    "{0} total`t{1} B`t{2} MiB" -f $d, $total, ($total / 1MB).ToString('0.000', $Inv)
}
