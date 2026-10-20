"""Render the station exterior (read back from station.mesh and the atlas PNGs) through the Cupola windows.

Run: blender -b --factory-startup -P tools/blender/render_exterior_preview.py -- [--dir DIR] [--out DIR]
     [--samples N]
Reads DIR/station.mesh, station_albedo.png, station_orm.png (default local/blender/exterior), writes
OUT/rest_diagonal.png, rest_nadir.png, rest_nadir_arrays.png (default DIR/preview). CPU Cycles, from the
eye point of Cam_Rest. Earth is a flat blue cone below, the sun a plain directional light; this checks
geometry, UVs and materials, not final lighting.
"""
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from station_mesh import read_station  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
W, H = 960, 640


def args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    d = {"--dir": os.path.join(ROOT, "local", "blender", "exterior"), "--out": None, "--samples": "48"}
    for i in range(0, len(argv), 2):
        d[argv[i]] = argv[i + 1]
    d["--dir"] = os.path.abspath(d["--dir"])
    d["--out"] = os.path.abspath(d["--out"] or os.path.join(d["--dir"], "preview"))
    return d


def material(dirname):
    m = bpy.data.materials.new("M_Station")
    m.use_nodes = True
    nt = m.node_tree
    pb = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    alb = nt.nodes.new("ShaderNodeTexImage")
    alb.image = bpy.data.images.load(os.path.join(dirname, "station_albedo.png"))
    alb.image.colorspace_settings.name = "sRGB"
    orm = nt.nodes.new("ShaderNodeTexImage")
    orm.image = bpy.data.images.load(os.path.join(dirname, "station_orm.png"))
    orm.image.colorspace_settings.name = "Non-Color"
    sep = nt.nodes.new("ShaderNodeSeparateColor")
    nt.links.new(alb.outputs["Color"], pb.inputs["Base Color"])
    nt.links.new(orm.outputs["Color"], sep.inputs["Color"])
    nt.links.new(sep.outputs["Green"], pb.inputs["Roughness"])
    nt.links.new(sep.outputs["Blue"], pb.inputs["Metallic"])
    return m


def build_station(dirname, scene):
    nodes = read_station(open(os.path.join(dirname, "station.mesh"), "rb").read())
    mat = material(dirname)
    for nd in nodes:
        if not nd["vertices"]:
            continue
        v = nd["vertices"]
        idx = nd["indices"]
        faces = [tuple(idx[i:i + 3]) for i in range(0, len(idx), 3)]
        me = bpy.data.meshes.new(nd["name"])
        me.from_pydata([r[:3] for r in v], [], faces)
        uvs = me.uv_layers.new(name="UVMap")
        flat = []
        for f in faces:
            for i in f:
                flat += [v[i][6] / 65535.0, 1.0 - v[i][7] / 65535.0]
        uvs.uv.foreach_set("vector", flat)
        me.polygons.foreach_set("use_smooth", [True] * len(faces))
        me.materials.append(mat)
        ob = bpy.data.objects.new("X_" + nd["name"], me)
        scene.collection.objects.link(ob)
    return nodes


def world(scene):
    w = bpy.data.worlds.new("W_Ext")
    w.use_nodes = True
    nt = w.node_tree
    bg = next(n for n in nt.nodes if n.type == "BACKGROUND")
    tc = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    nt.links.new(tc.outputs["Generated"], sep.inputs["Vector"])
    nt.links.new(sep.outputs["Z"], ramp.inputs["Fac"])
    e = ramp.color_ramp.elements
    e[0].position, e[0].color = 0.335, (0.0, 0.0, 0.0, 1.0)
    e[1].position, e[1].color = 0.37, (0.25, 0.45, 0.8, 1.0)
    nt.links.new(ramp.outputs["Color"], bg.inputs["Color"])
    bg.inputs["Strength"].default_value = 1.0
    scene.world = w
    sun = bpy.data.objects.new("L_Sun", bpy.data.lights.new("L_Sun", "SUN"))
    sun.data.energy = 4.0
    sun.rotation_euler = Vector((0.45, -0.35, 0.82)).normalized().to_track_quat("-Z", "Y").to_euler()
    scene.collection.objects.link(sun)


def main():
    a = args()
    bpy.ops.wm.open_mainfile(filepath=os.path.join(ROOT, "local", "blender", "cupola.blend"))
    scene = bpy.context.scene
    build_station(a["--dir"], scene)
    world(scene)
    for ob in bpy.data.objects:
        if ob.name.startswith("X_"):
            cs = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
            print("XOBJ", ob.name, len(ob.data.polygons), [round(min(c[i] for c in cs), 1) for i in range(3)],
                  [round(max(c[i] for c in cs), 1) for i in range(3)])
    for ob in bpy.data.collections["Lights_Cabin"].all_objects:
        ob.data.energy *= 0.05
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = int(a["--samples"])
    scene.cycles.seed = 0
    scene.cycles.use_denoising = True
    scene.render.resolution_x, scene.render.resolution_y = W, H
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    os.makedirs(a["--out"], exist_ok=True)
    rest = bpy.data.objects["Cam_Rest"]
    c30, s30 = math.cos(math.radians(30)), math.sin(math.radians(30))
    nadir = Matrix(((-s30, c30, 0.0), (c30, s30, 0.0), (0.0, 0.0, -1.0))).transposed()
    # The station sits at the edge of the envelope: each view is the rest view turned by the yaw/pitch
    # (|yaw| <= 26, |pitch| <= 22 deg, visible_region.py convention) that looks closest at the target.
    def aimed(rest_m, target):
        best = None
        for yi in range(-5, 6):
            for pi in range(-5, 6):
                r = rest_m @ Matrix.Rotation(math.radians(yi * 5.2), 3, "Y") @ Matrix.Rotation(math.radians(pi * 4.4), 3, "X")
                d = (r @ Vector((0, 0, -1))).dot(target.normalized())
                if best is None or d > best[0]:
                    best = (d, r)
        return best[1]

    def centroid(prefix):
        pts = [ob.matrix_world @ Vector(c) for ob in bpy.data.objects if ob.name.startswith(prefix) for c in ob.bound_box]
        return sum(pts, Vector()) / len(pts) - rest.matrix_world.translation

    static_dir = centroid("X_static")
    # Port arrays swung by 180 deg about the SARJ axis (nadir side) to check pivot and axis of the markers.
    marker = next(n for n in read_station(open(os.path.join(a["--dir"], "station.mesh"), "rb").read())
                  if n["name"] == "PORT_ALPHA")
    swing = Matrix.Translation(Vector(marker["pivot"])) @ Matrix.Rotation(math.pi, 4, Vector(marker["axis"]))         @ Matrix.Translation(-Vector(marker["pivot"]))
    for ob in bpy.data.objects:
        if ob.name.startswith("X_PORT_BETA"):
            ob.matrix_world = swing @ ob.matrix_world
    bpy.context.view_layer.update()
    views = {"rest_diagonal": aimed(rest.matrix_world.to_3x3(), static_dir),
             "rest_nadir": aimed(nadir, static_dir),
             "rest_nadir_arrays": aimed(nadir, centroid("X_PORT_BETA"))}
    cd = rest.data.copy()
    cd.clip_end = 500.0  # the station is 8 to 60 m away
    cam = bpy.data.objects.new("Cam_Ext", cd)
    scene.collection.objects.link(cam)
    scene.camera = cam
    for name, rot in views.items():
        m = rot.normalized().to_4x4()
        m.translation = rest.matrix_world.translation
        cam.matrix_world = m
        scene.render.filepath = os.path.join(a["--out"], name + ".png")
        bpy.ops.render.render(write_still=True)
        print("PREVIEW_OK", scene.render.filepath)


main()
