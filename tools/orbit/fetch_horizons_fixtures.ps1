# Fetches the JPL Horizons reference fixtures for the :core:orbit tests (plan P1.3, P1.4).
# Run with PowerShell 7 from the repo root: pwsh tools/orbit/fetch_horizons_fixtures.ps1
# Output: core/orbit/src/test/resources/horizons/{sidereal.csv,sun_moon.csv}
$ErrorActionPreference = 'Stop'

$api = 'https://ssd.jpl.nasa.gov/api/horizons.api'
$outDir = Join-Path $PSScriptRoot '..\..\core\orbit\src\test\resources\horizons'
New-Item -ItemType Directory -Force $outDir | Out-Null
$inv = [Globalization.CultureInfo]::InvariantCulture

function Invoke-Horizons([hashtable] $params) {
    $query = ($params.GetEnumerator() | Sort-Object Name | ForEach-Object {
        "$($_.Name)=" + [Uri]::EscapeDataString("'$($_.Value)'")
    }) -join '&'
    $text = (Invoke-WebRequest -Uri "${api}?format=text&$query" -TimeoutSec 120).Content
    $lines = $text -split "`r?`n"
    $soe = [Array]::IndexOf($lines, '$$SOE')
    $eoe = [Array]::IndexOf($lines, '$$EOE')
    if ($soe -lt 0 -or $eoe -le $soe) { throw "Horizons returned no ephemeris:`n$text" }
    $eop = ($lines | Where-Object { $_ -like 'EOP file*' } | Select-Object -First 1) -replace '^EOP file\s*:\s*', ''
    [pscustomobject]@{ Rows = $lines[($soe + 1)..($eoe - 1)]; Eop = $eop.Trim() }
}

function Format-Jd([double] $jd) { $jd.ToString('F6', $inv) }

$fetched = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')

# GMST/GAST: time tags before 1962 are UT1 in Horizons, so the test needs no UT1-UTC table.
# Local apparent sidereal time exists only for a topocentric site; the site sits on the prime meridian.
$siderealJd = 0..9 | ForEach-Object { 2433282.5 + 401.37 * $_ + 0.1379 * $_ }
$sid = Invoke-Horizons @{
    COMMAND = '10'; OBJ_DATA = 'NO'; MAKE_EPHEM = 'YES'; EPHEM_TYPE = 'OBSERVER'
    CENTER = 'coord@399'; COORD_TYPE = 'GEODETIC'; SITE_COORD = '0,0,0'
    TLIST = ($siderealJd | ForEach-Object { Format-Jd $_ }) -join ' '; TLIST_TYPE = 'JD'; TIME_TYPE = 'UT'
    QUANTITIES = '7'; ANG_FORMAT = 'DEG'; EXTRA_PREC = 'YES'; CSV_FORMAT = 'YES'; CAL_FORMAT = 'JD'
}
$sidOut = @(
    "# JPL Horizons local apparent sidereal time at geodetic 0E 0N 0 m; time tags UT1 (pre-1962)."
    "# Fetched $fetched by tools/orbit/fetch_horizons_fixtures.ps1; EOP file: $($sid.Eop)"
    'jd_ut1,last_hours'
)
foreach ($row in $sid.Rows) {
    $f = $row -split ',' | ForEach-Object { $_.Trim() }
    $sidOut += "$($f[0]),$($f[3])"
}
Set-Content -Path (Join-Path $outDir 'sidereal.csv') -Value $sidOut -Encoding utf8NoBOM

# Sun (10) and Moon (301): geocentric apparent RA/Dec, true equator and equinox of date, TT tags.
$sunMoonJd = 0..49 | ForEach-Object { 2461041.5 + 73.1234 * $_ }
$tlist = ($sunMoonJd | ForEach-Object { Format-Jd $_ }) -join ' '
$rows = @{}
foreach ($body in '10', '301') {
    $res = Invoke-Horizons @{
        COMMAND = $body; OBJ_DATA = 'NO'; MAKE_EPHEM = 'YES'; EPHEM_TYPE = 'OBSERVER'
        CENTER = '500@399'; TLIST = $tlist; TLIST_TYPE = 'JD'; TIME_TYPE = 'TT'
        QUANTITIES = '2,20'; ANG_FORMAT = 'DEG'; EXTRA_PREC = 'YES'; CSV_FORMAT = 'YES'; CAL_FORMAT = 'JD'
    }
    $rows[$body] = $res.Rows
}
$smOut = @(
    "# JPL Horizons geocentric (500@399) apparent RA/Dec (true equator and equinox of date), degrees; range in km; TT tags."
    "# Fetched $fetched by tools/orbit/fetch_horizons_fixtures.ps1"
    'jd_tt,sun_ra_deg,sun_dec_deg,sun_range_km,moon_ra_deg,moon_dec_deg,moon_range_km'
)
$au = 149597870.7
for ($i = 0; $i -lt $sunMoonJd.Count; $i++) {
    $s = $rows['10'][$i] -split ',' | ForEach-Object { $_.Trim() }
    $m = $rows['301'][$i] -split ',' | ForEach-Object { $_.Trim() }
    if ($s[0] -ne $m[0]) { throw "Epoch mismatch at row ${i}: $($s[0]) vs $($m[0])" }
    $sunKm = ([double]::Parse($s[5], $inv) * $au).ToString('F1', $inv)
    $moonKm = ([double]::Parse($m[5], $inv) * $au).ToString('F1', $inv)
    $smOut += "$($s[0]),$($s[3]),$($s[4]),$sunKm,$($m[3]),$($m[4]),$moonKm"
}
Set-Content -Path (Join-Path $outDir 'sun_moon.csv') -Value $smOut -Encoding utf8NoBOM
Write-Output "Wrote $($sidOut.Count - 3) sidereal rows and $($smOut.Count - 3) sun/moon rows to $outDir"
