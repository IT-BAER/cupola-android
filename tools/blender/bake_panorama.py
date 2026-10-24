"""Path-trace the relightable interior panorama (decision 22) into light layers (plan P4.4).

Run: blender -b --factory-startup -P tools/blender/bake_panorama.py -- [BLEND] [--samples N] [--seed N]
     [--out DIR] [--faces +X,-Y] [--crop X0 Y0 X1 Y1] [--debug-directions] [--noise-check]
     [--noise-sweep 32,64,128]
Writes DIR/exr/<layer>_<face>.exr, DIR/ldr/<layer>_<face>.png, DIR/preview_<layer>.png and
DIR/manifest.json (DIR default local/blender/bake). The cube and crop contract is in README.md.
"""
import glob
import hashlib
import json
import math
import os
import shutil
import sys
import time

import bpy
import numpy as np
import OpenImageIO as oiio
from mathutils import Matrix, Vector

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EYE = (0.0, 0.0, -0.10947)  # build_cupola.py EYE (decision 22); setup_scene checks it against Cam_Rest
EARTH_R_KM, ISS_ALT_KM = 6371.0, 420.0
EARTH_HALF_DEG = math.degrees(math.asin(EARTH_R_KM / (EARTH_R_KM + ISS_ALT_KM)))
FACE_N, AMB_N = 2048, 512
GROUPS = ("earth", "cabin", "emissive")
LAYERS = ("earth", "cabin", "emissive", "ambient")
TILE, TILE_MARGIN = 256, 32
NOISE_LIMIT = 0.01
AXES = "XYZ"


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    a = {"blend": os.path.join(ROOT, "local", "blender", "cupola.blend"),
         "region": os.path.join(ROOT, "local", "blender", "visible_region.json"),
         "out": None, "samples": 128, "seed": 1, "faces": None, "crop": None,
         "debug": False, "noise_check": False, "sweep": None}
    i = 0
    while i < len(argv):
        k = argv[i]
        if k in ("--samples", "--seed"):
            a[k[2:]] = int(argv[i + 1])
            i += 2
        elif k in ("--out", "--region"):
            a[k[2:]] = argv[i + 1]
            i += 2
        elif k == "--faces":
            a["faces"] = argv[i + 1].split(",")
            i += 2
        elif k == "--crop":
            a["crop"] = [int(v) for v in argv[i + 1:i + 5]]
            i += 5
        elif k == "--noise-sweep":
            a["sweep"] = [int(v) for v in argv[i + 1].split(",")]
            i += 2
        elif k == "--debug-directions":
            a["debug"] = True
            i += 1
        elif k == "--noise-check":
            a["noise_check"] = True
            i += 1
        elif k.startswith("--"):
            raise SystemExit("unknown argument: " + k)
        else:
            a["blend"] = k
            i += 1
    if a["out"] is None:
        a["out"] = os.path.join(ROOT, "local", "blender", "bake_debug" if a["debug"] else "bake")
    a["out"] = os.path.abspath(a["out"])  # File Output resolves relative paths against the .blend
    return a


# ---------- cube contract ----------

def face_axes(face):
    """Major axis index, sign, and the two other axes in X, Y, Z order (u, v)."""
    a = AXES.index(face[1])
    rest = [i for i in range(3) if i != a]
    return a, (1.0 if face[0] == "+" else -1.0), rest[0], rest[1]


def face_mirrored(face):
    """True when right = +u, up = +v, view = major axis is a left-handed camera frame."""
    a, s, r0, r1 = face_axes(face)
    e = np.eye(3)
    return np.linalg.det(np.stack([e[r0], e[r1], -s * e[a]], axis=1)) < 0


def camera_matrix(face):
    a, s, r0, r1 = face_axes(face)
    e = [Vector(v) for v in np.eye(3).tolist()]
    right = -e[r0] if face_mirrored(face) else e[r0]
    m = Matrix((right, e[r1], -s * e[a])).transposed().to_4x4()
    m.translation = Vector(EYE)
    return m


def texel_dirs(face, n, xs, ys):
    """Contract direction at texel centres (x, y) of an n-grid face."""
    a, s, r0, r1 = face_axes(face)
    d = np.zeros((len(xs), 3))
    d[:, a] = s
    d[:, r0] = (np.asarray(xs) + 0.5) / (n / 2.0) - 1.0
    d[:, r1] = (np.asarray(ys) + 0.5) / (n / 2.0) - 1.0
    return d


def amb_rect(r):
    return [r[0] // 4, r[1] // 4, -(-r[2] // 4), -(-r[3] // 4)]


def load_rects(path, faces, crop):
    with open(path, encoding="utf-8") as f:
        world = json.load(f)["all"]["world_cube"]["faces"]
    rects = {k: v["px"] for k, v in world.items()}
    if "-Z" in rects:
        raise SystemExit("visible_region.json lists -Z; the contract has no -Z face")
    if faces:
        rects = {k: rects[k] for k in faces}
    if crop:
        if len(rects) != 1:
            raise SystemExit("--crop needs exactly one face in --faces")
        rects = {k: crop for k in rects}
    return rects


# ---------- scene ----------

def pick_device(scene):
    prefs = bpy.context.preferences.addons["cycles"].preferences
    for kind in ("OPTIX", "CUDA"):
        try:
            prefs.compute_device_type = kind
        except TypeError:
            continue
        prefs.get_devices()
        devs = [d for d in prefs.devices if d.type == kind]
        if devs:
            for d in prefs.devices:
                d.use = d.type == kind
            scene.cycles.device = "GPU"
            return "%s %s" % (kind, devs[0].name)
    scene.cycles.device = "CPU"
    return "CPU"


def make_world(name, kind):
    w = bpy.data.worlds.new(name)
    try:
        w.use_nodes = True
    except AttributeError:
        pass
    nt = w.node_tree
    bg = next(n for n in nt.nodes if n.type == "BACKGROUND")
    bg.inputs["Strength"].default_value = 1.0
    if kind == "ambient":
        bg.inputs["Color"].default_value = (1.0, 1.0, 1.0, 1.0)
    else:
        # World "Generated" coordinates are the unit ray direction in world space.
        tc = nt.nodes.new("ShaderNodeTexCoord")
        if kind == "earth":
            sep = nt.nodes.new("ShaderNodeSeparateXYZ")
            gt = nt.nodes.new("ShaderNodeMath")
            gt.operation = "GREATER_THAN"
            gt.inputs[1].default_value = math.cos(math.radians(EARTH_HALF_DEG))
            nt.links.new(tc.outputs["Generated"], sep.inputs["Vector"])
            nt.links.new(sep.outputs["Z"], gt.inputs[0])
            nt.links.new(gt.outputs["Value"], bg.inputs["Color"])
        else:  # debug: emission = 0.5 * direction + 0.5
            ma = nt.nodes.new("ShaderNodeVectorMath")
            ma.operation = "MULTIPLY_ADD"
            ma.inputs[1].default_value = (0.5, 0.5, 0.5)
            ma.inputs[2].default_value = (0.5, 0.5, 0.5)
            nt.links.new(tc.outputs["Generated"], ma.inputs[0])
            nt.links.new(ma.outputs["Vector"], bg.inputs["Color"])
    w.lightgroup = "earth"
    return w


def setup_scene(a, tmp_dir):
    bpy.ops.wm.open_mainfile(filepath=a["blend"])
    eye = bpy.data.objects["Cam_Rest"].matrix_world.translation
    if (eye - Vector(EYE)).length > 1e-4:
        raise SystemExit("EYE %s differs from Cam_Rest at %s" % (EYE, tuple(eye)))
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    device = pick_device(scene)
    c = scene.cycles
    c.samples = a["samples"]
    c.use_adaptive_sampling = False
    c.time_limit = 0.0
    c.seed = a["seed"]
    c.use_animated_seed = False
    c.use_denoising = False
    vl = scene.view_layers[0]
    vl.cycles.denoising_store_passes = True
    for g in GROUPS:
        vl.lightgroups.add(name=g)
    for ob in bpy.data.collections["Lights_Cabin"].all_objects:
        ob.lightgroup = "cabin"
    for ob in bpy.data.collections["Emissive"].all_objects:
        ob.lightgroup = "emissive"
    bpy.data.collections["Glass"].hide_render = True
    if a["debug"]:
        for col in bpy.data.collections:
            col.hide_render = True
    r = scene.render
    r.film_transparent = not a["debug"]
    r.resolution_percentage = 100
    r.pixel_aspect_x = r.pixel_aspect_y = 1.0
    r.use_border = True
    r.use_crop_to_border = True
    r.use_compositing = True
    r.compositor_device = "CPU"
    scene.view_settings.view_transform = "Standard"
    scene.frame_set(1)
    worlds = {"main": make_world("W_BakeDebug" if a["debug"] else "W_BakeEarth", "debug" if a["debug"] else "earth"),
              "ambient": make_world("W_BakeDebugAmb", "debug") if a["debug"] else make_world("W_BakeAmbient", "ambient")}
    cd = bpy.data.cameras.new("Cam_Bake")
    cd.sensor_fit = "AUTO"
    cd.sensor_width = 36.0
    cd.lens = 18.0  # 90 deg square field
    cd.clip_start = 0.01
    cam = bpy.data.objects.new("Cam_Bake", cd)
    scene.collection.objects.link(cam)
    scene.camera = cam
    build_compositor(scene, tmp_dir)
    return scene, cam, worlds, device


def build_compositor(scene, tmp_dir):
    ng = bpy.data.node_groups.new("BakeComp", "CompositorNodeTree")
    scene.compositing_node_group = ng
    ng.interface.new_socket("Image", in_out="OUTPUT", socket_type="NodeSocketColor")
    rl = ng.nodes.new("CompositorNodeRLayers")
    out = ng.nodes.new("NodeGroupOutput")
    ng.links.new(rl.outputs["Image"], out.inputs[0])
    fo = ng.nodes.new("CompositorNodeOutputFile")
    fo.directory = tmp_dir
    fo.file_name = "pass"
    fo.format.media_type = "MULTI_LAYER_IMAGE"
    fo.format.file_format = "OPEN_EXR_MULTILAYER"
    fo.format.color_depth = "32"
    fo.format.exr_codec = "ZIP"
    fo.file_output_items.clear()

    def item(name, sock, kind="RGBA"):
        fo.file_output_items.new(kind, name)
        ng.links.new(sock, fo.inputs[name])

    for g in GROUPS:
        dn = ng.nodes.new("CompositorNodeDenoise")
        dn.inputs["HDR"].default_value = True
        dn.inputs["Prefilter"].default_value = "Accurate"
        dn.inputs["Quality"].default_value = "High"
        ng.links.new(rl.outputs["Combined_" + g], dn.inputs["Image"])
        ng.links.new(rl.outputs["Denoising Albedo"], dn.inputs["Albedo"])
        ng.links.new(rl.outputs["Denoising Normal"], dn.inputs["Normal"])
        item(g, dn.outputs["Image"])
        item("noisy_" + g, rl.outputs["Combined_" + g])
    item("image", rl.outputs["Image"])
    item("alpha", rl.outputs["Alpha"], "FLOAT")


def read_exr(path):
    """Channels grouped by layer name; rows as stored in the file (first row first)."""
    inp = oiio.ImageInput.open(path)
    if inp is None:
        raise RuntimeError(oiio.geterror())
    layers = {}
    sub = 0
    while inp.seek_subimage(sub, 0):  # Blender writes one EXR part per layer
        spec = inp.spec()
        px = inp.read_image(sub, 0, 0, spec.nchannels, "float")
        for i, name in enumerate(spec.channelnames):
            layer, _, ch = name.rpartition(".")
            layers.setdefault(layer, {})[ch] = px[:, :, i]
        sub += 1
    inp.close()
    out = {}
    for layer, chans in layers.items():
        if set("RGB") <= set(chans):
            out[layer] = np.stack([chans[c] for c in "RGB"], axis=2)
        else:
            out[layer] = next(iter(chans.values()))
    return out


def render_rect(scene, cam, face, n, rect, tmp_dir):
    """Render texels [x0, x1) x [y0, y1) of an n-grid face; arrays indexed [y - y0, x - x0]."""
    x0, y0, x1, y1 = rect
    r = scene.render
    r.resolution_x = r.resolution_y = n
    cam.matrix_world = camera_matrix(face)
    mirrored = face_mirrored(face)
    # A mirrored face renders with right = -u, so texel x sits at camera column n - 1 - x.
    bx0, bx1 = (n - x1, n - x0) if mirrored else (x0, x1)
    r.border_min_x, r.border_max_x = bx0 / n, bx1 / n
    r.border_min_y, r.border_max_y = y0 / n, y1 / n
    for f in glob.glob(os.path.join(tmp_dir, "*.exr")):
        os.remove(f)
    t = time.perf_counter()
    bpy.ops.render.render(write_still=False)
    dt = time.perf_counter() - t
    files = glob.glob(os.path.join(tmp_dir, "*.exr"))
    if len(files) != 1:
        raise RuntimeError("compositor output missing in " + tmp_dir)
    # The EXR stores the top camera row (highest v) first; the contract stores y0 (lowest v) first.
    res = {k: v[::-1, ::-1] if mirrored else v[::-1] for k, v in read_exr(files[0]).items()}
    for k, v in res.items():
        if v.shape[:2] != (y1 - y0, x1 - x0):
            raise RuntimeError("render size %s != rect %s" % (v.shape, rect))
    return res, dt


def bake_face(scene, cam, worlds, face, rect, tmp_dir, debug=False):
    scene.world = worlds["main"]
    main, t_main = render_rect(scene, cam, face, FACE_N, rect, tmp_dir)
    scene.world = worlds["ambient"]
    amb, t_amb = render_rect(scene, cam, face, AMB_N, amb_rect(rect), tmp_dir)
    # The denoiser bends a geometry-free gradient (no albedo/normal guide), so debug keeps the raw passes.
    p = "noisy_" if debug else ""
    layers = {"earth": np.concatenate([main[p + "earth"], main["alpha"][..., None]], axis=2),
              "cabin": main[p + "cabin"], "emissive": main[p + "emissive"], "ambient": amb[p + "earth"]}
    noisy_sum = main["noisy_earth"] + main["noisy_cabin"] + main["noisy_emissive"]
    image = main["image"]
    route_err = float(np.abs(image - noisy_sum).max() / max(float(np.abs(image).mean()), 1e-12))
    return layers, {"main_s": round(t_main, 2), "ambient_s": round(t_amb, 2), "group_sum_rel_err": route_err}


# ---------- LDR ----------

def srgb_encode(x):
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(x, 1.0 / 2.4) - 0.055)


def ldr_norm(rgb, scale):
    return srgb_encode(rgb / scale)


def to_u8(x):
    return np.floor(np.clip(x, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)


def layer_scale(name, faces):
    vals = []
    for arr in faces.values():
        m = arr[..., :3].max(axis=2)
        if name == "earth":
            m = m[arr[..., 3] > 0.5]
        vals.append(m.ravel())
    s = float(np.percentile(np.concatenate(vals), 99.9))
    if not s > 0.0:
        raise RuntimeError("layer %s has zero 99.9th percentile" % name)
    return s


def write_image(path, arr, fmt):
    h, w, c = arr.shape
    spec = oiio.ImageSpec(w, h, c, fmt)
    if fmt == "float":
        spec.attribute("compression", "zip")
    out = oiio.ImageOutput.create(path)
    if out is None or not out.open(path, spec):
        raise RuntimeError("cannot write %s: %s" % (path, oiio.geterror()))
    out.write_image(np.ascontiguousarray(arr))
    out.close()


def read_image(path):
    inp = oiio.ImageInput.open(path)
    if inp is None:
        raise RuntimeError(oiio.geterror())
    spec = inp.spec()
    px = inp.read_image(0, 0, 0, spec.nchannels, "float" if path.endswith(".exr") else "uint8")
    inp.close()
    return px


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def encode_layer(name, arr, scale):
    out = to_u8(ldr_norm(arr[..., :3], scale))
    if name == "earth":
        out = np.concatenate([out, to_u8(arr[..., 3:4])], axis=2)
    return out


def contact_sheet(path, name, pngs, factor=4, gap=8):
    tiles = []
    for img in pngs:
        f = img.astype(np.float32) / 255.0
        if name == "earth":  # alpha-0 texels shown dark blue
            f = f[..., :3] + (1.0 - f[..., 3:4]) * np.array([0.05, 0.05, 0.25], np.float32)
        h, w = (f.shape[0] // factor) * factor, (f.shape[1] // factor) * factor
        f = f[:h, :w].reshape(h // factor, factor, w // factor, factor, 3).mean(axis=(1, 3))
        tiles.append(f[::-1])  # preview only: v up, like a photo
    hmax = max(t.shape[0] for t in tiles)
    wsum = sum(t.shape[1] for t in tiles) + gap * (len(tiles) - 1)
    sheet = np.full((hmax, wsum, 3), 0.2, np.float32)
    x = 0
    for t in tiles:
        sheet[hmax - t.shape[0]:, x:x + t.shape[1]] = t
        x += t.shape[1] + gap
    write_image(path, to_u8(sheet), "uint8")


# ---------- modes ----------

def bake(a, rects, scene, cam, worlds, device, tmp_dir):
    out = a["out"]
    os.makedirs(os.path.join(out, "exr"), exist_ok=True)
    os.makedirs(os.path.join(out, "ldr"), exist_ok=True)
    data = {l: {} for l in LAYERS}
    info = {}
    for face, rect in rects.items():
        layers, timing = bake_face(scene, cam, worlds, face, rect, tmp_dir, a["debug"])
        for l in LAYERS:
            data[l][face] = layers[l]
        info[face] = {"px_2048": rect, "px_512": amb_rect(rect), "render": timing,
                      "alpha0_fraction": float((layers["earth"][..., 3] < 0.5).mean())}
        print("BAKE_FACE %s rect=%s main=%.1fs ambient=%.1fs group_sum_rel_err=%.2e alpha0=%.3f"
              % (face, rect, timing["main_s"], timing["ambient_s"], timing["group_sum_rel_err"],
                 info[face]["alpha0_fraction"]), flush=True)
    # Debug renders only the world: cabin and emissive are black there.
    scales = {l: 1.0 if a["debug"] and l in ("cabin", "emissive") else layer_scale(l, data[l]) for l in LAYERS}
    files = {}
    for l in LAYERS:
        pngs = []
        for face, arr in data[l].items():
            exr = os.path.join(out, "exr", "%s_%s.exr" % (l, face))
            png = os.path.join(out, "ldr", "%s_%s.png" % (l, face))
            write_image(exr, arr.astype(np.float32), "float")
            ldr = encode_layer(l, arr, scales[l])
            write_image(png, ldr, "uint8")
            pngs.append(ldr)
            files[os.path.relpath(exr, out).replace("\\", "/")] = sha256(exr)
            files[os.path.relpath(png, out).replace("\\", "/")] = sha256(png)
            info[face].setdefault("max", {})[l] = float(arr[..., :3].max())
        contact_sheet(os.path.join(out, "preview_%s.png" % l), l, pngs)
    for l in () if a["debug"] else ("earth", "cabin"):
        for face in rects:
            if not info[face]["max"][l] > 0.0:
                raise RuntimeError("face %s is entirely black in %s" % (face, l))
    manifest = {"blender": bpy.app.version_string, "device": device, "samples": a["samples"],
                "seed": a["seed"], "denoiser": "OpenImageDenoise (compositor, albedo + normal, HDR, CPU)",
                "eye": list(EYE), "earth_half_angle_deg": EARTH_HALF_DEG,
                "face_px": FACE_N, "ambient_px": AMB_N, "layers": list(LAYERS), "scale": scales,
                "preview_order": list(rects), "faces": info, "sha256": files}
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1)
    for l in LAYERS:
        print("SCALE %s %.6g" % (l, scales[l]))
    print("BAKE_OK", out, "device=" + device, "samples=%d" % a["samples"])
    return manifest


def check_directions(out, rects):
    """Decoded debug direction at 8 x 8 texels per face and grid matches the contract within 0.5 texel."""
    with open(os.path.join(out, "manifest.json"), encoding="utf-8") as f:
        man = json.load(f)
    worst, fails = 0.0, 0
    for face, rect in rects.items():
        for layer, n, r in (("earth", FACE_N, rect), ("ambient", AMB_N, amb_rect(rect))):
            x0, y0, x1, y1 = r
            exr = read_image(os.path.join(out, "exr", "%s_%s.exr" % (layer, face)))
            if exr.shape[:2] != (y1 - y0, x1 - x0):
                raise SystemExit("ORIENTATION FAIL %s %s size %s" % (layer, face, exr.shape))
            xs = np.linspace(x0, x1 - 1, 8).round().astype(int)
            ys = np.linspace(y0, y1 - 1, 8).round().astype(int)
            gx, gy = [g.ravel() for g in np.meshgrid(xs, ys)]
            want = texel_dirs(face, n, gx, gy)
            got = exr[gy - y0, gx - x0, :3].astype(np.float64) * 2.0 - 1.0
            a, s, r0, r1 = face_axes(face)
            maj = got[:, a] * s
            err = np.full(len(gx), np.inf)
            ok = maj > 0
            du = (got[ok, r0] / maj[ok] - want[ok, r0]) * n / 2.0
            dv = (got[ok, r1] / maj[ok] - want[ok, r1]) * n / 2.0
            err[ok] = np.maximum(np.abs(du), np.abs(dv))
            bad = int((err > 0.5).sum())
            fails += bad
            worst = max(worst, float(err.max()))
            print("ORIENT %s %-2s n=%d texels=%d max_err_texel=%.3f bad=%d" % (layer, face, n, len(gx), err.max(), bad))
            if bad:
                k = int(np.argmax(err))
                print("  e.g. texel (%d, %d): want dir %s got %s" % (gx[k], gy[k], np.round(want[k], 4), np.round(got[k], 4)))
            png = read_image(os.path.join(out, "ldr", "%s_%s.png" % (layer, face))).astype(int)
            ref = encode_layer(layer, exr, man["scale"][layer]).astype(int)
            pdiff = int(np.abs(png - ref).max())
            print("  png vs exr max diff %d/255" % pdiff)
            if pdiff > 1:
                fails += 1
    print("ORIENTATION_TEST=%s worst_err_texel=%.3f" % ("PASS" if fails == 0 else "FAIL", worst))
    return fails == 0


def pick_tiles(out, rects):
    tiles = {}
    for face, (x0, y0, x1, y1) in rects.items():
        earth = read_image(os.path.join(out, "exr", "earth_%s.exr" % face))
        cabin = read_image(os.path.join(out, "exr", "cabin_%s.exr" % face))
        lum = cabin[..., :3].mean(axis=2)
        cand = []
        for ty in range(-(-y0 // 4) * 4, y1 - TILE + 1, TILE):
            for tx in range(-(-x0 // 4) * 4, x1 - TILE + 1, TILE):
                sl = (slice(ty - y0, ty - y0 + TILE), slice(tx - x0, tx - x0 + TILE))
                alpha = float(earth[sl][..., 3].mean())
                detail = float(np.std(srgb_encode(lum[sl] / max(float(lum.max()), 1e-12))))
                cand.append((alpha >= 0.95, round(alpha, 3), detail, tx, ty))
        cand.sort(reverse=True)
        tiles[face] = [[c[3], c[4]] for c in cand[:2]]
    return tiles


def render_tiles(scene, cam, worlds, tiles, samples, tmp_dir):
    """Denoised tiles rendered in isolation with a margin; {layer: {(face, i): array}}."""
    scene.cycles.samples = samples
    res = {l: {} for l in LAYERS}
    for face, lst in tiles.items():
        for i, (tx, ty) in enumerate(lst):
            for world, n, f, m in (("main", FACE_N, 1, TILE_MARGIN), ("ambient", AMB_N, 4, TILE_MARGIN // 4)):
                x0, y0, t = tx // f, ty // f, TILE // f
                r = [max(0, x0 - m), max(0, y0 - m), min(n, x0 + t + m), min(n, y0 + t + m)]
                scene.world = worlds[world]
                img, _ = render_rect(scene, cam, face, n, r, tmp_dir)
                sl = (slice(y0 - r[1], y0 - r[1] + t), slice(x0 - r[0], x0 - r[0] + t))
                if world == "main":
                    for l in GROUPS:
                        res[l][(face, i)] = img[l][sl]
                else:
                    res["ambient"][(face, i)] = img["earth"][sl]
    return res


def mad(a, b, scale):
    return float(np.abs(ldr_norm(a[..., :3], scale) - ldr_norm(b[..., :3], scale)).mean())


def noise_check(a, scene, cam, worlds, tmp_dir, rects):
    out = a["out"]
    with open(os.path.join(out, "manifest.json"), encoding="utf-8") as f:
        man = json.load(f)
    tiles = pick_tiles(out, rects)
    print("TILES", json.dumps(tiles), flush=True)
    if a["sweep"]:
        for s in a["sweep"]:
            t0 = time.perf_counter()
            lo = render_tiles(scene, cam, worlds, tiles, s, tmp_dir)
            t1 = time.perf_counter()
            hi = render_tiles(scene, cam, worlds, tiles, 4 * s, tmp_dir)
            vals = {l: float(np.mean([mad(lo[l][k], hi[l][k], man["scale"][l]) for k in lo[l]])) for l in LAYERS}
            print("SWEEP samples=%d tiles_s=%.1f %s pass=%s" % (s, t1 - t0, " ".join("%s=%.4f" % kv for kv in vals.items()),
                                                                  all(v <= NOISE_LIMIT for v in vals.values())), flush=True)
        return True
    ref_s = 4 * man["samples"]
    t0 = time.perf_counter()
    ref = render_tiles(scene, cam, worlds, tiles, ref_s, tmp_dir)
    result = {"reference_samples": ref_s, "tile_px": TILE, "tiles_2048": tiles,
              "ambient_tile_px": TILE // 4, "render_s": round(time.perf_counter() - t0, 1), "mad": {}}
    ok = True
    for l in LAYERS:
        vals = []
        for (face, i), r in ref[l].items():
            tx, ty = tiles[face][i]
            f = 4 if l == "ambient" else 1
            rect = man["faces"][face]["px_512" if l == "ambient" else "px_2048"]
            bake_img = read_image(os.path.join(out, "exr", "%s_%s.exr" % (l, face)))
            x0, y0, t = tx // f - rect[0], ty // f - rect[1], TILE // f
            vals.append(mad(bake_img[y0:y0 + t, x0:x0 + t], r, man["scale"][l]))
        result["mad"][l] = {"mean": float(np.mean(vals)), "max_tile": float(np.max(vals))}
        ok &= result["mad"][l]["mean"] <= NOISE_LIMIT
        print("NOISE %s mean_mad=%.4f max_tile=%.4f" % (l, result["mad"][l]["mean"], result["mad"][l]["max_tile"]))
    result["pass"] = bool(ok)
    man["noise_check"] = result
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(man, f, indent=1)
    print("NOISE_CHECK=%s" % ("PASS" if ok else "FAIL"))
    return ok


def main():
    a = parse_args()
    rects = load_rects(a["region"], a["faces"], a["crop"])
    tmp_dir = os.path.join(a["out"], "tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    scene, cam, worlds, device = setup_scene(a, tmp_dir)
    print("DEVICE", device, flush=True)
    if a["noise_check"] or a["sweep"]:
        ok = noise_check(a, scene, cam, worlds, tmp_dir, rects)
    else:
        bake(a, rects, scene, cam, worlds, device, tmp_dir)
        ok = check_directions(a["out"], rects) if a["debug"] else True
    shutil.rmtree(tmp_dir, ignore_errors=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
