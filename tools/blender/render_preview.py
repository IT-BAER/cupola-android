"""Render the rest view and three reference poses of local/blender/cupola.blend, plus side-by-sides.

Run: blender -b --factory-startup -P tools/blender/render_preview.py -- [BLEND] [--samples N] [--out DIR]
Writes <DIR>/<pose>.png and compare_<pose>.png (render left, dev photo right), default
local/blender/preview. Earth outside is a 1:1000 sphere with the BMNG 2004 monthly map and the 2002
cloud layer from local/sources/ (tools/textures/sources.lock), placed by the pose's nadir and sun angles
(EARTH below); GPU (OptiX/CUDA) when present. The .blend itself is not modified.
"""
import math
import os
import sys

import bmesh
import bpy
import numpy as np
from mathutils import Matrix, Vector

sys.dont_write_bytecode = True  # no __pycache__ in tools/blender
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bake_panorama import pick_device  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
W, H = 960, 640
Z_TOP = 1.3405  # top plate height printed by build_cupola.py (z_top)

# name: (eye, target, right_hint, roll_deg, camera settings, photo for comparison)
POSES = {
    "rest": None,  # Cam_Rest from the .blend (fixed eye point, decision 22)
    # Interior view from the hatch, on axis, side windows left and right.
    "iss035e010551": ((0.0, -0.05, 0.40), (0.0, 0.0, Z_TOP), (1, 0, 0), 0.0,
                      {"lens": 15.9, "sensor": 36.0}, "dev/iss035e010551.jpg"),
    # Nikon D2Xs 10.5 mm fisheye (EXIF), on axis below the top window.
    "iss038e013587": ((0.0, 0.0, 0.33), (0.0, 0.0, Z_TOP), (1, 0, 0), 0.0,
                      {"fisheye_mm": 10.5, "sensor": 23.7}, "dev/iss038e013587.jpg"),
    # Nikon Z 9 at 24 mm (EXIF), looking across at a mullion, Earth (+Z) down in the frame.
    "iss073e0025760": ((-0.35 * math.cos(math.radians(30)), -0.35 * math.sin(math.radians(30)), 0.85),
                       (0.70 * math.cos(math.radians(30)), 0.70 * math.sin(math.radians(30)), 1.25),
                       (-math.sin(math.radians(30)), math.cos(math.radians(30)), 0), 0.0,
                       {"lens": 24.0, "sensor": 36.0, "up": (0, 0, -1)}, "dev/iss073e0025760.jpg"),
}


def args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    blend, samples = os.path.join(ROOT, "local", "blender", "cupola.blend"), 32
    out = os.path.join(ROOT, "local", "blender", "preview")
    i = 0
    while i < len(argv):
        if argv[i] == "--samples":
            samples = int(argv[i + 1])
            i += 2
        elif argv[i] == "--out":
            out = os.path.abspath(argv[i + 1])
            i += 2
        else:
            blend = argv[i]
            i += 1
    return blend, samples, out


def look(eye, target, right_hint, roll_deg, up=None):
    f = (Vector(target) - Vector(eye)).normalized()
    if up is not None:
        r = f.cross(Vector(up)).normalized()
    else:
        r = (Vector(right_hint) - f * Vector(right_hint).dot(f)).normalized()
    u = r.cross(f)
    m = (Matrix((r, u, -f)).transposed() @ Matrix.Rotation(math.radians(roll_deg), 3, "Z")).to_4x4()
    m.translation = Vector(eye)
    return m


# Preview Earth per pose (1:1000 scale sphere, metres). iss035e010551 uses its EOL record
# (reference/dev-set.json: nadir, time, sun angles, altitude). The others have no EOL record
# ("not found"): month from EXIF, nadir and sun ASSUMED (cloudy ocean, sun high, as in the photos).
EARTH = {
    "rest": {"month": 7, "lat": 10.0, "lon": 60.0, "alt_km": 410.0, "sun_el": 50.0, "sun_az": 120.0},
    "iss035e010551": {"month": 3, "lat": 51.8, "lon": -120.1, "alt_km": 394.0, "sun_el": -20.0, "sun_az": 56.0},
    "iss038e013587": {"month": 12, "lat": -20.0, "lon": -30.0, "alt_km": 410.0, "sun_el": 65.0, "sun_az": 180.0},
    "iss073e0025760": {"month": 5, "lat": 35.0, "lon": -40.0, "alt_km": 415.0, "sun_el": 45.0, "sun_az": 220.0},
}
R_EARTH = 6371.0  # km; 1 km -> 1 m in the preview scene
SRC = os.path.join(ROOT, "local", "sources")


def load_image(path):
    for im in bpy.data.images:
        if bpy.path.abspath(im.filepath) == path:
            return im
    return bpy.data.images.load(path)


def setup_world(scene):
    w = bpy.data.worlds.new("W_PreviewSpace")
    w.color = (0.0, 0.0, 0.0)
    try:
        w.use_nodes = True
    except AttributeError:
        pass
    bg = next(n for n in w.node_tree.nodes if n.type == "BACKGROUND")
    bg.inputs["Strength"].default_value = 0.0
    scene.world = w
    for ob in bpy.data.collections["Lights_Cabin"].all_objects:
        ob["base_energy"] = ob.data.energy


def setup_earth(scene, cfg):
    """BMNG month + 2002 cloud layer on a sphere; city lights on the night side; thin blue limb."""
    for ob in [o for o in scene.objects if o.name.startswith("PreviewEarth") or o.name == "L_PreviewSun"]:
        bpy.data.objects.remove(ob)
    lat, lon = math.radians(cfg["lat"]), math.radians(cfg["lon"])
    up = Vector((math.cos(lat) * math.cos(lon), math.cos(lat) * math.sin(lon), math.sin(lat)))
    east = Vector((-math.sin(lon), math.cos(lon), 0.0))
    north = up.cross(east)
    # +Z of the Cupola frame points at the nadir; map east -> -X, north -> +Y, up -> -Z.
    rot = Matrix.Diagonal((-1.0, 1.0, -1.0)) @ Matrix((east, north, up))
    me = bpy.data.meshes.new("PreviewEarth")
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=512, v_segments=256, radius=R_EARTH)
    bm.to_mesh(me)
    bm.free()
    for poly in me.polygons:
        poly.use_smooth = True
    ob = bpy.data.objects.new("PreviewEarth", me)
    ob.matrix_world = Matrix.Translation((0.0, 0.0, R_EARTH + cfg["alt_km"])) @ rot.to_4x4()
    scene.collection.objects.link(ob)
    m = bpy.data.materials.new("M_PreviewEarth")
    try:
        m.use_nodes = True
    except AttributeError:
        pass
    nt = m.node_tree
    N, L = nt.nodes.new, nt.links.new
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    out = next(n for n in nt.nodes if n.type == "OUTPUT_MATERIAL")
    flip = N("ShaderNodeVectorMath")
    flip.operation = "MULTIPLY"
    flip.inputs[1].default_value = (1.0, -1.0, 1.0)  # outside view: east toward +u
    L(N("ShaderNodeTexCoord").outputs["Object"], flip.inputs[0])

    def env(path):
        t = N("ShaderNodeTexEnvironment")
        t.image = load_image(path)
        L(flip.outputs["Vector"], t.inputs["Vector"])
        return t

    day = env(os.path.join(SRC, "bmng2004-monthly", "world.topo.bathy.2004%02d.3x21600x10800.jpg" % cfg["month"]))
    cloud = env(os.path.join(SRC, "bm2002-clouds", "cloud_combined_8192.tif"))
    cloud.image.colorspace_settings.name = "Non-Color"
    mix = N("ShaderNodeMix")
    mix.data_type = "RGBA"
    L(cloud.outputs["Color"], mix.inputs["Factor"])
    L(day.outputs["Color"], next(s for s in mix.inputs if s.name == "A" and s.type == "RGBA"))
    next(s for s in mix.inputs if s.name == "B" and s.type == "RGBA").default_value = (0.85, 0.86, 0.88, 1.0)
    L(next(s for s in mix.outputs if s.type == "RGBA"), bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.85
    night = cfg["sun_el"] < 0.0
    lights = env(os.path.join(SRC, "blackmarble2016", "BlackMarble_2016_3km.jpg"))
    L(lights.outputs["Color"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = 0.02 if night else 0.0
    lw = N("ShaderNodeLayerWeight")
    lw.inputs["Blend"].default_value = 0.08
    em = N("ShaderNodeEmission")
    em.inputs["Color"].default_value = (0.25, 0.5, 1.0, 1.0)
    pw = N("ShaderNodeMath")
    pw.operation = "MULTIPLY"
    pw.inputs[1].default_value = 0.02 if night else 0.6
    L(lw.outputs["Facing"], pw.inputs[0])
    L(pw.outputs["Value"], em.inputs["Strength"])
    add = N("ShaderNodeAddShader")
    L(bsdf.outputs["BSDF"], add.inputs[0])
    L(em.outputs["Emission"], add.inputs[1])
    L(add.outputs["Shader"], out.inputs["Surface"])
    me.materials.append(m)
    # Sun from the pose's elevation/azimuth at the nadir point; Earth itself shadows it at night.
    sun = bpy.data.objects.new("L_PreviewSun", bpy.data.lights.new("L_PreviewSun", "SUN"))
    sun.data.energy = 3.0
    sun.data.angle = math.radians(0.53)
    e, a = math.radians(cfg["sun_el"]), math.radians(cfg["sun_az"])
    to_sun = Vector((-math.sin(a) * math.cos(e), math.cos(a) * math.cos(e), -math.sin(e)))
    sun.rotation_euler = (-to_sun).to_track_quat("-Z", "Y").to_euler()
    scene.collection.objects.link(sun)
    # Daylit Earth dwarfs the Node 3 light (5 %); on the night side it is the main light, dimmed to
    # the photos' level (30 %). Day exposure +0.5 EV: the photos expose for the bright Earth.
    for ob in bpy.data.collections["Lights_Cabin"].all_objects:
        ob.data.energy = ob["base_energy"] * (0.3 if night else 0.05)
    scene.view_settings.exposure = 0.0 if night else 0.5


def make_camera(name, pose):
    eye, target, right, roll, cfg, _ = pose
    cd = bpy.data.cameras.new(name)
    cd.clip_start = 0.01
    cd.clip_end = 30000.0
    cd.sensor_fit = "HORIZONTAL"
    cd.sensor_width = cfg["sensor"]
    if "fisheye_mm" in cfg:
        cd.type = "PANO"
        cd.panorama_type = "FISHEYE_EQUISOLID"
        cd.fisheye_lens = cfg["fisheye_mm"]
        cd.fisheye_fov = math.pi
    else:
        cd.lens = cfg["lens"]
    ob = bpy.data.objects.new(name, cd)
    ob.matrix_world = look(eye, target, right, roll, cfg.get("up"))
    bpy.context.scene.collection.objects.link(ob)
    return ob


def pixels(img):
    a = np.empty(img.size[0] * img.size[1] * 4, dtype=np.float32)
    img.pixels.foreach_get(a)
    return a.reshape(img.size[1], img.size[0], 4)


def compare(render_png, photo, out_png):
    r = bpy.data.images.load(render_png)
    p = bpy.data.images.load(photo)
    p.scale(W, H)
    arr = np.concatenate([pixels(r), pixels(p)], axis=1)
    arr[..., 3] = 1.0
    img = bpy.data.images.new(os.path.basename(out_png), 2 * W, H, alpha=False)
    img.pixels.foreach_set(arr.ravel())
    img.filepath_raw = out_png
    img.file_format = "PNG"
    img.save()


def main():
    blend, samples, out_dir = args()
    bpy.ops.wm.open_mainfile(filepath=blend)
    scene = bpy.context.scene
    setup_world(scene)
    scene.render.engine = "CYCLES"
    print("DEVICE", pick_device(scene))
    scene.cycles.samples = samples
    scene.cycles.seed = 0
    scene.cycles.use_denoising = True
    scene.render.resolution_x, scene.render.resolution_y = W, H
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    os.makedirs(out_dir, exist_ok=True)
    rest = bpy.data.objects["Cam_Rest"]
    rest.data.clip_end = 30000.0
    for name, pose in POSES.items():
        setup_earth(scene, EARTH[name])
        scene.camera = rest if pose is None else make_camera("Cam_" + name, pose)
        path = os.path.join(out_dir, name + ".png")
        scene.render.filepath = path
        bpy.ops.render.render(write_still=True)
        if pose is not None:
            compare(path, os.path.join(ROOT, "local", "reference", pose[5]),
                    os.path.join(out_dir, "compare_" + name + ".png"))
        else:
            compare(path, os.path.join(ROOT, "local", "reference", "clip", "clip_f120.png"),
                    os.path.join(out_dir, "compare_rest_clip.png"))
        print("PREVIEW_OK", name, path)


main()
