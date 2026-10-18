# Builds the small KTX2 fixtures of the native reader tests (plan P5.5) into
# core\render\src\test\cpp\fixtures with the pinned ktx (local\tools, KTX-Software 4.4.2).
# Block data is synthetic: ASTC uses constant-colour void-extent blocks, ETC2 a byte pattern
# (every ETC2/EAC bit pattern decodes). Each file is 16 x 12 with the full mip chain (5 levels).
# corrupt_truncated.ktx2 is etc2_rgb_srgb_2d.ktx2 cut inside its level 0 data.
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Ktx = Join-Path $Root 'local\tools\ktx\bin\ktx.exe'
$Out = Join-Path $Root 'core\render\src\test\cpp\fixtures'
$Work = Join-Path ([IO.Path]::GetTempPath()) "cupola-ktx2-fixtures-$PID"
$Width = 16; $Height = 12; $Levels = 5

function Run([string]$exe, [string[]]$argv) {
    $log = & $exe @argv 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$exe $($argv -join ' ') failed with exit $LASTEXITCODE`n$($log -join "`n")" }
}

function Block-Bytes([string]$kind, [int]$blockBytes, [int]$count, [int]$level, [int]$layer) {
    $bytes = New-Object byte[] ($blockBytes * $count)
    for ($b = 0; $b -lt $count; $b++) {
        $o = $b * $blockBytes
        if ($kind -eq 'astc') {
            # LDR void-extent block: 0x1FC marker, extents all ones, then RGBA as 16-bit UNORM.
            $head = 0xFC, 0xFD, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF
            for ($i = 0; $i -lt 8; $i++) { $bytes[$o + $i] = $head[$i] }
            $rgba = @((($level * 0x3000) -band 0xFFFF), (($layer * 0x5000) -band 0xFFFF), 0x8000, 0xFFFF)
            for ($c = 0; $c -lt 4; $c++) {
                $bytes[$o + 8 + 2 * $c] = $rgba[$c] -band 0xFF
                $bytes[$o + 9 + 2 * $c] = ($rgba[$c] -shr 8) -band 0xFF
            }
        } else {
            for ($i = 0; $i -lt $blockBytes; $i++) { $bytes[$o + $i] = (($o + $i) * 37 + $level * 31 + $layer * 13) -band 0xFF }
        }
    }
    $bytes
}

function Write-Fixture([string]$name, [string]$format, [string]$kind, [int]$blockW, [int]$blockH, [int]$blockBytes, [int]$layers) {
    $raws = @()
    for ($level = 0; $level -lt $Levels; $level++) {
        $w = [Math]::Max(1, $Width -shr $level); $h = [Math]::Max(1, $Height -shr $level)
        $count = [Math]::Ceiling($w / $blockW) * [Math]::Ceiling($h / $blockH)
        for ($layer = 0; $layer -lt [Math]::Max(1, $layers); $layer++) {
            $raw = Join-Path $Work "$name.$level.$layer.raw"
            [IO.File]::WriteAllBytes($raw, (Block-Bytes $kind $blockBytes $count $level $layer))
            $raws += $raw
        }
    }
    $file = Join-Path $Out "$name.ktx2"
    if (Test-Path $file) { Remove-Item $file }
    $a = @('create', '--raw', '--format', $format, '--width', $Width, '--height', $Height, '--levels', $Levels,
        '--assign-texcoord-origin', 'top-left')
    if ($layers -gt 0) { $a += @('--layers', $layers) }
    Run $Ktx ($a + $raws + $file)
    Run $Ktx @('validate', $file)
    $file
}

New-Item -ItemType Directory -Force $Work, $Out | Out-Null
try {
    Write-Fixture 'astc_6x6_srgb_2d' 'ASTC_6x6_SRGB_BLOCK' 'astc' 6 6 16 0 | Out-Null
    Write-Fixture 'astc_8x8_unorm_array3' 'ASTC_8x8_UNORM_BLOCK' 'astc' 8 8 16 3 | Out-Null
    $rgb = Write-Fixture 'etc2_rgb_srgb_2d' 'ETC2_R8G8B8_SRGB_BLOCK' 'etc2' 4 4 8 0
    Write-Fixture 'etc2_rgba_unorm_2d' 'ETC2_R8G8B8A8_UNORM_BLOCK' 'etc2' 4 4 16 0 | Out-Null
    $bytes = [IO.File]::ReadAllBytes($rgb)
    # Level 0 (4 x 3 blocks of 8 bytes) is the last level in the file; drop its final 40 bytes.
    $cut = New-Object byte[] ($bytes.Length - 40)
    [Array]::Copy($bytes, $cut, $cut.Length)
    [IO.File]::WriteAllBytes((Join-Path $Out 'corrupt_truncated.ktx2'), $cut)
} finally {
    Remove-Item -Recurse -Force $Work
}
Get-ChildItem $Out -Filter *.ktx2 | Sort-Object Name | ForEach-Object { '{0,-28} {1,6} B' -f $_.Name, $_.Length }
