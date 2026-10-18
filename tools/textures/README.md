# Earth and sky textures (plan P5.3)

`build_textures.ps1` turns the mirrored sources (`sources.lock`, in `local\sources\`) into KTX2 files with
the pinned tools (`tools.lock`, installed by `bootstrap.ps1`). Run it with PowerShell 7:

```
pwsh -NoProfile -File tools\textures\bootstrap.ps1
pwsh -NoProfile -File tools\textures\build_textures.ps1            # all assets
pwsh -NoProfile -Command "& tools\textures\build_textures.ps1 -Assets day,stars"
```

Outputs: `local\textures\out\astc\<name>.ktx2`, `local\textures\out\etc2\<name>.ktx2` (same names, for the
`textures#tcf_astc/` and default `textures/` folders of P5.4) and `local\textures\out\preview\<name>.png`
(ASTC level 0 decoded with astcenc, 2048 px wide). Intermediates: `local\textures\work\<asset>\`, wiped per
asset. The script checks every source against `sources.lock`, runs `ktx validate` on every output and
fails on any tool error. Output is deterministic (identical SHA-256 across runs).

## Asset pack (P5.4)

Copy a finished build into the install-time pack `:earthpack`:

```
Copy-Item local\textures\out\astc\*.ktx2 'earthpack\src\main\assets\textures#tcf_astc\'
Copy-Item local\textures\out\etc2\*.ktx2 earthpack\src\main\assets\textures\
```

- `:earthpack:checkTexturePack` (part of `check`, and run before `:app:bundleRelease`) fails when the two
  folders hold different file names or the ASTC files exceed 96 MiB. `:app:checkCredits` fails when a
  `sources.lock` licence and credit pair has no row in `app/src/main/assets/credits.tsv`.
- In installed APKs the suffix is stripped: the app reads `textures/<name>.ktx2` on every device.
- `assembleDebug` APKs carry no asset pack. On a device, install from the bundle: `bundletool build-apks`
  (bundletool 1.18.3 needs `--aapt2=<sdk>\build-tools\37.0.0\aapt2.exe`), then `bundletool install-apks`.
  `EarthpackLoadTest` in `:app` checks the installed slice.

## Runtime contract (P5.5)

Common to every file:

- KTX2, no supercompression, `KTXorientation = rd`: the first row of each image is the top (north) edge.
  Upload rows as stored; with Vulkan's top-left texture origin, v = 0 is the top row.
- Full mip chains to 1 x 1 (level n is `max(1, size >> n)`), stored level-major, every layer per level.
- Equirectangular. Column 0's left edge is 180 deg W, longitude increases eastwards, the right edge is
  180 deg E (wrap horizontally: `REPEAT` on u, `CLAMP_TO_EDGE` on v).
- Earth maps cover latitude +72 deg (top edge of row 0) to -72 deg (bottom edge of the last row), linear in
  latitude: `v = (72 - lat) / 144`. Outside that band the shader needs a fallback (the ISS never sees it).
- ETC2 files are the ASTC file without its level 0: half width and height, one level fewer, same layers,
  orientation, channel layout and latitude range.

| Name | ASTC VkFormat | ASTC size, levels | ETC2 VkFormat | ETC2 size, levels | Layers | Colour space | Channels |
|---|---|---|---|---|---|---|---|
| `day_detail` | `ASTC_8x8_SRGB_BLOCK` | 4096 x 3277, 13 | `ETC2_R8G8B8_SRGB_BLOCK` | 2048 x 1638, 12 | 8 | sRGB | RGB surface colour (July 2004), A = 1 |
| `monthly_colour` | `ASTC_6x6_SRGB_BLOCK` | 2048 x 819, 12 | `ETC2_R8G8B8_SRGB_BLOCK` | 1024 x 409, 11 | 12 | sRGB | RGB surface colour, A = 1 |
| `clouds` | `ASTC_8x8_UNORM_BLOCK` | 8192 x 3277, 14 | `ETC2_R8G8B8_UNORM_BLOCK` | 4096 x 1638, 13 | 1 (no array) | linear | R = G = B = cloud brightness/coverage 0..1 as in the source, A = 1 |
| `city_lights` | `ASTC_8x8_SRGB_BLOCK` | 8192 x 3277, 14 | `ETC2_R8G8B8_SRGB_BLOCK` | 4096 x 1638, 13 | 1 (no array) | sRGB | R = G = B = night-light radiance (sampled value is linear), A = 1 |
| `terrain_normal` | `ASTC_6x6_UNORM_BLOCK` | 4096 x 1638, 13 | `ETC2_R8G8B8A8_UNORM_BLOCK` | 2048 x 819, 12 | 1 (no array) | linear | RGB = X, A = Y; Z reconstructed |
| `stars` | `ASTC_6x6_SRGB_BLOCK` | 4096 x 2048, 13 | `ETC2_R8G8B8_SRGB_BLOCK` | 2048 x 1024, 12 | 1 (no array) | sRGB | RGB sky colour, A = 1 |

Single-layer files have `layerCount = 0` (plain 2D texture); the two arrays have `layerCount` 8 and 12.

### day_detail (8-layer array, 4 x 2 tiles)

Layer `k` covers column `k % 4` and row `k / 4` of a 4 x 2 grid over the +-72 deg band; layer order
A1 B1 C1 D1 A2 B2 C2 D2 of the BMNG tiles:

| Layer | Longitude | Latitude |
|---|---|---|
| 0 | 180 W to 90 W | 72 N to 0 |
| 1 | 90 W to 0 | 72 N to 0 |
| 2 | 0 to 90 E | 72 N to 0 |
| 3 | 90 E to 180 E | 72 N to 0 |
| 4..7 | as 0..3 | 0 to 72 S |

Within a layer: `u = (lon - lon0) / 90`, `v = (lat_top - lat) / 72`. Each layer is resampled and mipmapped
on its own, so sample with `CLAMP_TO_EDGE` and pick the layer per fragment; seams match to within one
texel of filtering. The build checks the tile order by comparing the assembled layers with the July 2004
21600 px map (mean absolute difference printed; it fails above 4 of 255; measured 0.96, versus 10.3 with A1 and B1 swapped and 45.4 with the rows swapped).

### monthly_colour (12-layer array)

Layer `m` = month `m + 1` (layer 0 = January 2004, layer 11 = December 2004), each the whole +-72 deg
band. Blend between the two layers around the current date.

### clouds

Source `cloud_combined_8192.tif` (8192 x 4096, three bands, measured identical, so R = G = B is kept). 0.8 of 4096 rows is 3276.8, so the band is resampled to 3277 rows with a
bicubic index map (output row j samples source row `409.6 + (j + 0.5) * 3276.8/3277 - 0.5`) instead of
an integer crop; the +-72 deg edges are exact. Values are the source code values, treated as linear
coverage (use as alpha / brightness directly).

### city_lights

Source `BlackMarble_2016_3km_gray.jpg` (13500 x 6750, rows 675..6074 kept). Stored as sRGB so the
hardware returns linear radiance for additive emission.

### terrain_normal

Source ETOPO 2022 60 s surface elevation (21600 x 10800 float metres, rows 1080..9719 kept).
Elevations below 0 m are clamped to 0 (oceans flat), before resampling and again per level (Lanczos
rings below 0 along coasts). Every mip level is computed from the height field resampled to that
level's size (not by filtering normals), so each level is a unit-length normal map.

- Tangent frame: X = east, Y = north, Z = up (outward). Encoding `c = round(127.5 n + 127.5)`.
- Slopes by central differences (longitude wraps, top/bottom rows repeat the edge row):
  east slope `dh/dx = (h[x+1] - h[x-1]) / (2 * dx0 * cos(lat))`, with `dx0 = 2 pi R / width` the
  equatorial texel width, i.e. east-west slopes are corrected for the cos(latitude) shrink of a texel's
  ground width; north slope `dh/dy = (h[y-1] - h[y+1]) / (2 * dy)`, `dy = 0.8 pi R / height`; R = 6371 km.
- `n = normalize(-k * dh/dx, -k * dh/dy, 1)` with strength factor `k = 10` (vertical exaggeration;
  parameter `-NormalStrength`, 1 = true slope).
- Shader: `xy = texture(...).ga * 2 - 1; z = sqrt(max(0, 1 - dot(xy, xy)))` for both ASTC (astcenc
  `-normal`, RGB = X, A = Y) and ETC2 (RGBA8 ETC2/EAC with RGB = X, A = Y; Compressonator 4.5.52 offers no
  EAC RG11 format, and the EAC alpha channel keeps Y independent of X).

### stars (full sky, no crop)

Source `eso0932a.tif` (6000 x 3000). The file carries no projection metadata (AVM spatial fields empty);
the frame is a full 2:1 rectangle without an Aitoff/Hammer ellipse, and star positions fit plate carree
in galactic coordinates (LMC, SMC and M31 within about 1 deg of their galactic l/b). No reprojection.

- Galactic coordinates: centre column = l = 0 (galactic centre), **l increases to the left** (sky seen
  from inside): `u = 0.5 - l / 360` with l in (-180, 180]; left and right edges are l = 180.
  Row 0's top edge is b = +90 (north galactic pole), the bottom edge b = -90: `v = (90 - b) / 180`.
- The runtime rotates view directions from ICRS/J2000 equatorial into galactic coordinates before
  sampling.

## Processing notes

- Cropping and resampling: `vips crop` (sequential read, so the 21600 px JPEGs stream) followed by
  `vips thumbnail --size force` (libvips Lanczos3). sRGB maps (day, monthly, lights, stars) are resampled
  in linear light (`--linear`: sRGB to scRGB, resize, back to sRGB); grey lights are expanded to RGB first
  because vips linearises only 3-band sRGB. Mips are made the same way from level 0 for every level.
  Clouds are resampled without linearisation; the normal map from heights with `vips resize`
  (Lanczos3). Resampling clamps at image edges (no horizontal wrap), a sub-texel effect at 180 deg.
- Embedded ICC profiles are dropped (the stars TIFF carries the standard 3144-byte sRGB profile).
- ASTC: astcenc 5.7.0 (AVX2 build), `-thorough`, `-cs` for sRGB / `-cl` for linear, `-esw rrr1` for the
  single-channel maps, `-normal` for the normal map; blocks packaged with `ktx create --raw` (KTX-Software
  4.4.2). ETC2: Compressonator CLI 4.5.52 (`-fd ETC2_RGB` / `ETC2_RGBA`, `-nomipmap`) per level, blocks
  taken from its KTX1 output and packaged the same way.
