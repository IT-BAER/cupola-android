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

`render_preview.py [BLEND] [--samples N]` renders the rest view and the poses matching
iss035e010551, iss038e013587 (10.5 mm equisolid fisheye) and iss073e0025760 with Cycles on the CPU
into `local/blender/preview/`, plus `compare_<pose>.png` with the render left and the dev photo
right (`compare_rest_clip.png` uses the ESA clip frame, for internal comparison only). Earth is
a plain direction gradient; the .blend is not modified.
