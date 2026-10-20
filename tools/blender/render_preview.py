"""Render the rest view and three reference poses of local/blender/cupola.blend, plus side-by-sides.

Run: blender -b --factory-startup -P tools/blender/render_preview.py -- [BLEND] [--samples N] [--out DIR]
Writes <DIR>/<pose>.png and compare_<pose>.png (render left, dev photo right), default
local/blender/preview. Earth outside is a procedural cloud field inside the Earth cone (+Z = Earth) with
a sun through a side window; GPU (OptiX/CUDA) when present. The .blend itself is not modified.
"""
import math
import os
import sys

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


def setup_world_and_sun(scene):
    w = bpy.data.worlds.new("W_PreviewEarth")
    try:
        w.use_nodes = True
    except AttributeError:
        pass
    nt = w.node_tree
    bg = next(n for n in nt.nodes if n.type == "BACKGROUND")
    N, L = nt.nodes.new, nt.links.new
    tc = N("ShaderNodeTexCoord")
    sep = N("ShaderNodeSeparateXYZ")
    L(tc.outputs["Generated"], sep.inputs["Vector"])
    # Clouds over ocean inside the Earth cone (cos 69.74 deg = 0.3466), black space outside, thin limb.
    cloud = N("ShaderNodeTexNoise")
    cloud.inputs["Scale"].default_value = 16.0
    cloud.inputs["Detail"].default_value = 15.0
    cloud.inputs["Roughness"].default_value = 0.7
    cloud.inputs["Distortion"].default_value = 0.15
    L(tc.outputs["Generated"], cloud.inputs["Vector"])
    cn = N("ShaderNodeValToRGB")
    L(cloud.outputs["Fac"], cn.inputs["Fac"])
    cr = cn.color_ramp
    cr.elements[0].position, cr.elements[0].color = 0.4, (0.02, 0.06, 0.14, 1.0)
    cr.elements[1].position, cr.elements[1].color = 0.6, (0.82, 0.84, 0.87, 1.0)
    ramp_out = cn.outputs["Color"]
    ramp = N("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position, ramp.color_ramp.elements[0].color = 0.335, (0.0, 0.0, 0.0, 1.0)
    ramp.color_ramp.elements[1].position, ramp.color_ramp.elements[1].color = 0.37, (1.0, 1.0, 1.0, 1.0)
    e = ramp.color_ramp.elements.new(0.347)
    e.color = (0.5, 0.8, 1.6, 1.0)
    L(sep.outputs["Z"], ramp.inputs["Fac"])
    mul = N("ShaderNodeMix")
    mul.data_type, mul.blend_type = "RGBA", "MULTIPLY"
    mul.inputs["Factor"].default_value = 1.0
    L(ramp_out, next(s for s in mul.inputs if s.name == "A" and s.type == "RGBA"))
    L(ramp.outputs["Color"], next(s for s in mul.inputs if s.name == "B" and s.type == "RGBA"))
    L(next(s for s in mul.outputs if s.type == "RGBA"), bg.inputs["Color"])
    bg.inputs["Strength"].default_value = 1.0
    scene.world = w
    sun = bpy.data.objects.new("L_PreviewSun", bpy.data.lights.new("L_PreviewSun", "SUN"))
    sun.data.energy = 3.0
    # Sun from the zenith side, grazing in through side window 0 (light on Earth below, as in the
    # day photos); Node 3 light at 5 %: next to daylit Earth the cabin light is a faint fill.
    travel = Vector((-0.8, -0.2, 0.15)).normalized()
    for ob in bpy.data.collections["Lights_Cabin"].all_objects:
        ob.data.energy *= 0.05
    sun.rotation_euler = travel.to_track_quat("-Z", "Y").to_euler()
    scene.collection.objects.link(sun)


def make_camera(name, pose):
    eye, target, right, roll, cfg, _ = pose
    cd = bpy.data.cameras.new(name)
    cd.clip_start = 0.01
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
    setup_world_and_sun(scene)
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
    for name, pose in POSES.items():
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
