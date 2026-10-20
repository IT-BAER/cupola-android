# Blender Cupola tools (plan P4.2)

All scripts run headless in Blender 5.2 from the repository root:
`blender -b --factory-startup -P tools/blender/<script>.py -- <args>`. Outputs go to the gitignored
`local/blender/`. Keep paths short: Blender cannot open paths over 260 characters.

`build_cupola.py` builds the Cupola interior from scratch (decision 23) into an empty scene and saves
`local/blender/cupola.blend`: window frames and glass, shell, open shutters, bolts, handrails,
laptop, photo cameras, cables, clutter, cabin light and the rest-view camera at the fixed eye point.
Each part has its own collection for the light-layer bakes in P4.4. Materials are procedural only.
The build is deterministic. `--out PATH` writes elsewhere; `--top-aperture M` overrides the top
window diameter, used only to produce a deliberately wrong build for the red check.

`measure_apertures.py [BLEND] [--dump PATH]` opens the .blend (default `local/blender/cupola.blend`),
measures the seven clear apertures from the glass meshes (top diameter; side height, parallel
sides, corner radius) and exits 1 if any value differs from the ESA size by more than 5 mm. It
also prints the branding inventory (text objects, images, image texture nodes, materials).
`--dump` writes all mesh vertices in world space rounded to 0.01 mm and prints their SHA-256;
two builds are deterministic when the hashes match (the .blend bytes differ between runs).

`visible_region.py [BLEND] [--json PATH] [--self-test]` (plan P4.3) computes which cube-face regions
the wallpaper can show. The envelope is both rest views of the settings: the Cam_Rest diagonal view and
NADIR_REST (looks along +Z, image up toward azimuth 30 deg), each with Cam_Rest's eye and lens (14 mm,
36 mm sensor fit AUTO, 104.3 deg on the long screen axis), at the phone aspects (A35 portrait and
landscape) and at tablet and foldable aspects, turned by yaw +-26 deg (20 look-around + 4 launcher pan
+ 2 float) about the camera's up axis and pitch +-22 deg about its right axis. It prints per face the
2048 px crop rectangle (16 px padding) and the cost in full-face equivalents, for a world-aligned cube
and for the best cube orientation of a random search, and writes `local/blender/visible_region.json`
(key `rest_views` holds both view matrices). Measured 18 October 2026: 5 faces (+X, -X, +Y, -Y, +Z),
3.80 face-equivalents for the world-aligned cube (all aspects), about 19.5 MiB against 15.4 MiB
planned; the best rotation found saves 0.10 face-equivalents. `-Z` (towards the hatch) is never visible.

`render_preview.py [BLEND] [--samples N] [--out DIR]` renders the rest view and the poses matching
iss035e010551, iss038e013587 (10.5 mm equisolid fisheye) and iss073e0025760 with Cycles (GPU when
present) into `local/blender/preview/` (or DIR), plus `compare_<pose>.png` with the render left and the dev photo
right (`compare_rest_clip.png` uses the ESA clip frame, for internal comparison only). Earth is a
1:1000 sphere (km -> m) with the BMNG 2004 monthly map and the 2002 cloud layer from `local/sources/`
(`tools/textures/sources.lock`), Black Marble city lights on the night side and a thin blue limb. Each
pose has an `EARTH` entry: iss035e010551 uses its EOL record (nadir 51.8 N 120.1 W, 394 km, sun
elevation -20 deg: night look, cabin light at 30 %); the other poses have no EOL record, so month comes
from EXIF and nadir and sun angles are assumed (day look: cabin light 5 %, +0.5 EV). Space is black.
The .blend is not modified.

Interior realism (P4.7 round 1, dev references only): `principled()` builds every material from noise
nodes: colour and roughness variation, fine bump, a Cycles Bevel normal for rounded specular edges and,
on frames and handrails, bare-metal wear where the bevel normal leaves the true normal. Frames are satin
black anodized; window tunnels are stepped bezels (`SIDE_BEZEL`, `TOP_BEZEL`: black walls, bare-metal
ledges, gasket last) outside the clear apertures. Added geometry: washers and two more bolt rows,
mullion rails with cap screws, orange tags, Velcro and plain label-like patches, shutter crank knobs,
brackets, top-plate fittings (`build_mullion_detail`); dark closeout panels with fasteners, wall bags,
a corrugated hose (`build_walls`); two clamped harness loops round the skirt and a bundle down each
mullion seam (`build_harness`); lumpy canvas pouches with straps; box connectors and handles; handrail
end fittings; laptop pads and vents; lens grips. The scatter uses `random.Random(DETAIL_SEED)` in a
fixed order, so the build stays deterministic. `L_Cabin` is a 1 m disk at the hatch plane (Node 3
light shining in), 60 W, warm white. Bevel nodes slow the bake: measured 20 October 2026, 8 min 39 s
wall time for the 5-face bake at 128 spp (P4.4 measured 137 s render time).

Round 2 (P4.7): large clutter (laptop on an articulated wall arm, three DSLR-like cameras with grips,
prisms, hoods and handrail brackets, cables floating across the windows with connectors, seeded stowed
items in the window corners); cloth bags via `Part.bag` (subdivided box on a rounded superellipsoid with
seeded crease valleys, sag and a piped seam); irregular patches via `Part.patch` (Velcro, blank label-like
patches in white, yellow and grey, no text); fasteners via a seeded `bolt()` (hex rotation and height,
socket caps, black-oxide heads, empty holes); wear patches modulated by a second low-frequency noise. The
skirt walls are racks (`build_walls`): dark backing, perforated rails, stacked avionics boxes of seeded
size and depth with connectors, backshells, jumper cables to the lower harness, handles, blank patches,
corrugated hoses, bags and the LEDs.

Round 3 (P4.7): albedos raised to measured-material levels (black anodize 3 %, shell 10 %, panels 20 %;
lower values read as unlit holes); rack boxes in off-white, beige, blue-grey and dark grey; tan bags with
straps; brighter blue anodized handrails and orange-red gaskets; white cable ties on the seam bundles and
floating cables; red and orange dust caps, pouches, tool handles and tags (own `random.Random` streams,
so the round-2 layout is unchanged); molded camera bodies (`Part.rounded`, a superellipsoid) with rear
LCD, dials, mount ring and a floating strap; a rounded laptop with keyboard, touchpad and hinge barrels;
a procedural screen (`screen_material`: brick-grid UI panels under a glossy coat, no text) and more
status LEDs (green, amber, red, blue) and small lit displays on the racks for the night interior.
Emissive strengths are set relative to `L_Cabin` (screen 8, LEDs 60).

Preview exposure chain (round 3): `render_preview.py` uses the Standard view transform (display sRGB, like
a camera JPEG and the bake's LDR step) instead of the factory AgX, scales screens and LEDs with the cabin
light (station power: 5 % day, 15 % night), puts the assumed nadirs on cloudy ocean (the Earth is the
interior's main light), and writes `mask_<pose>.png` (1-sample render with the Earth as holdout, white =
interior) and `hist.json`: mean and 5/50/95th percentiles of sRGB-encoded luminance for render and photo,
whole frame and inside the render's interior mask (the photo is only approximately aligned).

`bake_panorama.py [BLEND] [--samples N] [--seed N] [--out DIR] [--faces +X,-Y] [--crop X0 Y0 X1 Y1]`
(plan P4.4) path-traces the relightable interior panorama (decision 22) from EYE (0, 0, 0.70) with
Cycles (OptiX, else CUDA, else CPU; the device is printed and stored) into four light layers, denoises
each with OpenImageDenoise (compositor Denoise node, albedo + normal, HDR, CPU) and writes, under
`local/blender/bake/`: `exr/<layer>_<face>.exr` (float32, linear, denoised), `ldr/<layer>_<face>.png`
(8 bit), `preview_<layer>.png` (faces side by side in manifest `preview_order`, 1/4 size, v up, for
viewing only) and `manifest.json` (Blender version, device, samples, seed, eye, Earth cone angle,
per-layer scale, px rects for both grids, SHA-256 of every EXR and PNG, render time per face, light
group sum check, noise check). Default 128 samples, seed 1, no adaptive sampling, no time limit. The
`Glass` collection is hidden from all rays (reflections and dust come with P4.5); film is transparent.
One render per face uses Cycles light groups (world -> `earth`, `Lights_Cabin` -> `cabin`, `Emissive`
-> `emissive`), each Combined_<group> pass denoised separately; a second 512 px render with a uniform
world gives `ambient` (its world pass only). Measured 18 October 2026: 137 s render time for 5 faces on
the RTX 5060 Ti; 401 MiB EXR, 10.5 MiB PNG.
Checks: `--debug-directions` replaces the world with emission 0.5 * direction + 0.5, hides all
geometry, bakes to `local/blender/bake_debug/` (raw passes, since the denoiser bends a geometry-free
gradient) and exits 1 unless the decoded direction at 8 x 8 texels per face and grid matches the
contract within 0.5 texel. `--noise-check` renders two 256 x 256 tiles per face (64 x 64 on the
ambient grid, same directions) at 4x the bake samples and stores the mean absolute difference of the
LDR-normalised values in the manifest (limit 0.01 per layer); `--noise-sweep 16,32,...` compares tiles
at N against 4N without touching the bake.

Runtime contract (the P3 shader samples exactly this):
- World-aligned cube centred at EYE, faces +X, -X, +Y, -Y, +Z (never -Z), crop rects from
  `visible_region.json` `all.world_cube` `px` = [x0, y0, x1, y1], end-exclusive, on a 2048 grid.
- Face coordinates: major axis a with sign s; u and v are the two other axes in X, Y, Z order divided
  by |a| (on +X: u = Y/X, v = Z/X; on -X: u = Y/|X|; on +Z: u = X/Z, v = Y/Z), each in [-1, 1].
- Texel (x, y) of an N grid covers u in [2x/N - 1, 2(x+1)/N - 1] and v in [2y/N - 1, 2(y+1)/N - 1].
  Every image holds only its crop (width x1 - x0, height y1 - y0); its first stored row (top row of
  the PNG/EXR file) is y = y0, the lowest v; its first column is x = x0, the lowest u.
- Layers: `earth` (2048 grid, RGBA) = light from a white emitter of radiance 1 inside the Earth cone
  (angle to +Z below asin(6371 / 6791) = 69.74 deg), alpha 1 on interior surfaces and 0 where the
  view leaves through a window, RGB already weighted by alpha; the runtime multiplies RGB by the live
  Earth brightness. `cabin` (2048, RGB) = the `Lights_Cabin` lights at built strength. `emissive`
  (2048, RGB) = laptop screen and LEDs at built strength (decision 26). `ambient` (512 grid, RGB,
  rects = the 2048 rects / 4, floor for x0, y0 and ceil for x1, y1) = uniform white world of radiance
  1 over the full sphere, the low-frequency fill term. All are linear radiance.
- Decode: linear = sRGB-decode(texel) * scale[layer] (manifest `scale`, the 99.9th percentile of
  max(R, G, B) over the layer, alpha > 0.5 texels only for earth); earth alpha is stored linearly.
