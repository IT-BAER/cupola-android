# Downloads the pinned texture tools from tools.lock into <repo>/local/tools/<name>/ and refuses any
# file whose size or SHA-256 differs. The KTX installer is unpacked with 7-Zip, never run.
param(
    [string]$Lock = (Join-Path $PSScriptRoot 'tools.lock'),
    [string]$Dest = (Join-Path $PSScriptRoot '..\..\local\tools'),
    [string]$SevenZip = 'C:\Program Files\7-Zip\7z.exe'
)
$ErrorActionPreference = 'Stop'

$rows = Get-Content $Lock | Where-Object { $_ -and -not $_.StartsWith('#') } | Select-Object -Skip 1
foreach ($row in $rows) {
    $name, $version, $url, $bytes, $sha, $null = $row -split "`t"
    $dir = Join-Path $Dest $name
    $stamp = Join-Path $dir '.sha256'
    if ((Test-Path $stamp) -and (Get-Content $stamp -Raw).Trim() -eq $sha) { "ok    $name $version (cached)"; continue }

    $download = Join-Path $Dest ([IO.Path]::GetFileName($url))
    New-Item -ItemType Directory -Force $Dest | Out-Null
    curl.exe -sSfL --retry 3 -o $download $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    $actualBytes = (Get-Item $download).Length
    $actualSha = (Get-FileHash $download -Algorithm SHA256).Hash.ToLower()
    if ($actualBytes -ne [long]$bytes -or $actualSha -ne $sha) {
        Remove-Item $download
        throw "checksum mismatch for $name`: expected $bytes B $sha, got $actualBytes B $actualSha"
    }

    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    New-Item -ItemType Directory -Force $dir | Out-Null
    if ($download.EndsWith('.zip')) {
        Expand-Archive -LiteralPath $download -DestinationPath $dir
    } else {
        & $SevenZip x -y "-o$dir" $download | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "7-Zip could not unpack $download" }
    }
    Remove-Item $download
    Set-Content -LiteralPath $stamp -Value $sha -NoNewline
    "ok    $name $version"
}
