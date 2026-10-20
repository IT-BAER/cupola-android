"""Export the ISS exterior parts visible from the Cupola eye as the runtime asset station.mesh (plan P4.6).

Run: blender -b --factory-startup -P tools/blender/export_station.py -- [--glb PATH] [--cupola BLEND]
     [--out DIR] [--budget N] [--atlas PX] [--min-hits N] [--stop-after vis] [--no-bake]
Reads the NASA IGOAL ISS glTF (local/sources/igoal) and local/blender/cupola.blend, writes DIR (default
local/blender/exterior): station.mesh, station_albedo.png, station_orm.png, station_report.json.
Deterministic: sorted iteration, fixed seeds, numpy only on sorted data. Frame definition: see README.md.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import os
import re
import struct
import sys

import bpy
import numpy as np
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree

sys.dont_write_bytecode = True
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GLB = os.path.join(ROOT, "local", "sources", "igoal", "International Space Station (ISS).glb")
CUPOLA_BLEND = os.path.join(ROOT, "local", "blender", "cupola.blend")
REGION_JSON = os.path.join(ROOT, "local", "blender", "visible_region.json")

EYE = Vector((0.0, 0.0, 0.70))  # build_cupola.py EYE (decision 22), Cupola frame, metres
INCH = 0.0254                   # IGOAL mesh data is in SSREF inches
YAW_DEG = 30.0                  # IGOAL window facets sit at 30 + 60k deg from ISS velocity, build_cupola at 60k
KIND_STATIC, KIND_ALPHA, KIND_BETA, KIND_GAMMA = 0, 1, 2, 3
# Removed from the asset by name: decals as separate meshes. Cupola and shutters are the interior model's.
EXCLUDE = re.compile(r"Logo|^Cupola")
# Small hardware on the rotating arrays (cables, handrails, boxes) is dropped: 25 to 55 m away it is sub-pixel.
DROP_ROT = re.compile("Details")
SHARP_DEG = 60.0
RAY_STEPS, RAY_GRID = 5, 48


def args():
    p = argparse.ArgumentParser()
    p.add_argument("--glb", default=GLB)
    p.add_argument("--cupola", default=CUPOLA_BLEND)
    p.add_argument("--out", default=os.path.join(ROOT, "local", "blender", "exterior"))
    p.add_argument("--budget", type=int, default=38000)
    p.add_argument("--atlas", type=int, default=1024)
    p.add_argument("--min-hits", type=int, default=8)
    p.add_argument("--min-sweep-hits", type=int, default=3)
    p.add_argument("--sweep-stride", type=int, default=12)
    p.add_argument("--stop-after", default="")
    p.add_argument("--reuse-vis", action="store_true")
    p.add_argument("--no-bake", action="store_true")
    return p.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])


def world_arrays(ob):
    me = ob.data
    n = len(me.vertices)
    co = np.empty(n * 3, np.float64)
    me.vertices.foreach_get("co", co)
    m = np.array(ob.matrix_world)
    co = co.reshape(n, 3) @ m[:3, :3].T + m[:3, 3]
    me.calc_loop_triangles()
    tri = np.empty(len(me.loop_triangles) * 3, np.int64)
    me.loop_triangles.foreach_get("vertices", tri)
    return co, tri.reshape(-1, 3)


def build_bvh(objs):
    cos, tris, owner, off = [], [], [], 0
    for i, ob in enumerate(objs):
        co, tr = world_arrays(ob)
        cos.append(co)
        tris.append(tr + off)
        owner.append(np.full(len(tr), i, np.int64))
        off += len(co)
    return (BVHTree.FromPolygons(np.concatenate(cos).tolist(), np.concatenate(tris).tolist()),
            np.concatenate(owner))


def place_station(root):
    """Set the root so world = Cupola frame (metres, +X velocity, +Y starboard, +Z nadir, yaw YAW_DEG)."""
    k = abs(root.matrix_basis.to_scale()[0])
    m = INCH / k
    cup = bpy.data.objects["Cupola"]
    co, _ = world_arrays(cup)
    zt = co[:, 2].max()
    ring = co[co[:, 2] > zt - 0.01]
    origin = Vector((ring[:, 0].mean(), ring[:, 1].mean(), zt))  # hatch plane centre in the import frame
    lin = Matrix.Rotation(math.radians(YAW_DEG), 4, "Z") @ Matrix.Scale(-m, 4)
    mat = lin @ Matrix.Translation(-origin)
    root.matrix_basis = mat @ root.matrix_basis
    bpy.context.view_layer.update()
    return mat, m, origin


def joints_of(ob):
    """Joint ancestors, nearest first: (node name, kind, empty)."""
    out, p = [], ob.parent
    while p is not None:
        n = p.name
        if "_BETA_ROT" in n:
            out.append((n.replace("_ROT", ""), KIND_BETA, p))
        elif "_ALPHA_ROT" in n:
            out.append((n.replace("_ROT", ""), KIND_ALPHA, p))
        elif "_TRRJ_GAMMA_ROT" in n:
            out.append((n.replace("_ROT", ""), KIND_GAMMA, p))
        p = p.parent
    return out


def group_of(ob):
    j = joints_of(ob)
    return (j[0][0], j[0][1], j[0][2]) if j else ("static", KIND_STATIC, None)


def joint_geometry(joints, members, lin):
    """name -> (pivot, axis) in the Cupola frame. Alpha: truss axis (import +Y, toward port). Beta, gamma:
    longest extent of the assembly (import-frame axes), pointing from the pivot to the tip."""
    res = {}
    for name, (kind, emp) in joints.items():
        pivot = np.array(emp.matrix_world.translation)
        if kind == KIND_ALPHA:
            ax = lin @ np.array([0.0, 1.0, 0.0])
        else:
            pos = np.concatenate([world_arrays(o)[0] for o in members[name]])
            ext = [np.ptp(pos @ lin[:, i]) for i in range(3)]
            ax = lin[:, int(np.argmax(ext))]
            ax = ax if (pos.mean(0) - pivot) @ ax >= 0 else -ax
        res[name] = (tuple(float(x) for x in pivot), tuple(float(x) for x in ax / np.linalg.norm(ax)))
    return res


def smooth_normals(pos, faces):
    """Per-corner normals, area weighted over neighbours within SHARP_DEG of the face normal."""
    a, b, c = pos[faces[:, 0]], pos[faces[:, 1]], pos[faces[:, 2]]
    fn = np.cross(b - a, c - a)
    area = np.linalg.norm(fn, axis=1)
    fnu = fn / np.maximum(area, 1e-20)[:, None]
    out = fnu[:, None, :].repeat(3, axis=1)
    flat = faces.ravel()
    order = np.argsort(flat, kind="stable")
    sv = flat[order]
    starts = np.flatnonzero(np.r_[True, sv[1:] != sv[:-1]])
    ends = np.r_[starts[1:], len(sv)]
    cos_t = math.cos(math.radians(SHARP_DEG))
    for s, e in zip(starts, ends):
        corners = order[s:e]
        f = corners // 3
        u = fnu[f]
        wt = u * area[f][:, None]
        near = (u @ u.T) > cos_t
        n = near.astype(np.float64) @ wt
        n /= np.maximum(np.linalg.norm(n, axis=1), 1e-20)[:, None]
        out.reshape(-1, 3)[corners] = n
    return out


def quant_unique(pos, faces, normals, uv):
    key = np.concatenate([
        faces.reshape(-1, 1).astype(np.int64),
        np.round(normals.reshape(-1, 3) * 32767).astype(np.int64),
        np.round(np.clip(uv, 0, 1) * 65535).astype(np.int64)], axis=1)
    uniq, inv = np.unique(key, axis=0, return_inverse=True)
    return uniq, inv.reshape(-1, 3)


def write_station(path, nodes):
    out = bytearray(b"CPST" + struct.pack("<II", 1, len(nodes)))
    stats = []
    for nd in nodes:
        uniq, idx = nd["uniq"], nd["idx"]
        nv, ni = len(uniq), idx.size
        name = nd["name"].encode("utf-8")
        out += struct.pack("<H", len(name)) + name
        out += struct.pack("<3f3fBII", *nd["pivot"], *nd["axis"], nd["kind"], nv, ni)
        pos = nd["pos"][uniq[:, 0]].astype("<f4")
        nrm = uniq[:, 1:4].astype("<i2")
        uvq = uniq[:, 4:6].astype("<u2")
        rec = np.zeros(nv, dtype=[("p", "<f4", 3), ("n", "<i2", 3), ("t", "<u2", 2)])
        rec["p"], rec["n"], rec["t"] = pos, nrm, uvq
        out += rec.tobytes()
        out += idx.astype("<u4" if nv > 65535 else "<u2").ravel().tobytes()
        stats.append({"name": nd["name"], "kind": nd["kind"], "vertices": nv, "triangles": ni // 3})
    with open(path, "wb") as f:
        f.write(out)
    return stats


def main():
    a = args()
    a.out = os.path.abspath(a.out)
    os.makedirs(a.out, exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=a.cupola)
    scene = bpy.context.scene
    before = set(bpy.data.objects.keys())
    bpy.ops.import_scene.gltf(filepath=a.glb)
    iss = sorted((o for o in bpy.data.objects if o.name not in before), key=lambda o: o.name)
    root = bpy.data.objects["SSREF_IGOAL"]
    mat, mpu, origin = place_station(root)
    cup = bpy.data.objects["Cupola"]
    cz = world_arrays(cup)[0][:, 2]
    print("FRAME metres/unit %.5f origin(import) %s Cupola z %.3f..%.3f" % (mpu, tuple(round(x, 4) for x in origin),
                                                                          cz.min(), cz.max()))
    # Window facets of the IGOAL Cupola must land on the build's windows (azimuth, deg).
    def az(ob):
        v = world_arrays(ob)[0].mean(0)
        return math.degrees(math.atan2(v[1], v[0])) % 360.0
    sh = sorted(az(bpy.data.objects["Cupola_Shutter%d" % k]) for k in range(1, 7))
    gl = sorted(az(o) for o in bpy.data.objects if o.name.startswith("Glass_Side_"))
    dev = max(min(abs((s - g + 180) % 360 - 180) for g in gl) for s in sh)
    print("WINDOW azimuth IGOAL shutters", [round(x, 1) for x in sh], "build glass", [round(x, 1) for x in gl],
          "max deviation %.2f deg" % dev)
    assert dev < 2.0, "IGOAL Cupola windows do not line up with build_cupola"

    # Visibility: rays from EYE through the glass meshes over the view envelope.
    spec = importlib.util.spec_from_file_location("visible_region", os.path.join(ROOT, "tools", "blender",
                                                                                  "visible_region.py"))
    vr = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vr)
    reg = json.load(open(REGION_JSON))
    dirs = np.concatenate([vr.view_directions(np.array(reg["rest_views"][k]), reg["lens_mm"], reg["sensor_mm"],
                                              vr.ASPECTS, reg["yaw_deg"], reg["pitch_deg"], RAY_GRID, RAY_STEPS)
                           for k in ("diagonal", "nadir")])
    dirs = np.unique(np.round(dirs, 3), axis=0)
    glass_obs = sorted((o for o in bpy.data.objects if o.name.startswith("Glass_")), key=lambda o: o.name)
    assert len(glass_obs) == 7, [o.name for o in glass_obs]
    glass_bvh, _ = build_bvh(glass_obs)
    cand = [o for o in iss if o.type == "MESH" and not EXCLUDE.search(o.name)]
    stat = [o for o in cand if not joints_of(o)]
    rot = [o for o in cand if joints_of(o)]
    iss_bvh, owner = build_bvh(stat)
    hits = np.zeros(len(stat), np.int64)
    dmin = np.full(len(stat), 1e9)
    eye = Vector(EYE)
    pts, dvs, dst = [], [], []  # rays through glass: glass hit point, direction, static distance behind it
    for d in dirs:
        dv = Vector(d)
        g = glass_bvh.ray_cast(eye, dv)
        if g[0] is None:
            continue
        p0 = g[0] + dv * 1e-3
        h = iss_bvh.ray_cast(p0, dv)
        pts.append(p0)
        dvs.append(dv)
        dst.append(h[3] if h[0] is not None else math.inf)
        if h[0] is not None:
            o = owner[h[2]]
            hits[o] += 1
            dmin[o] = min(dmin[o], g[3] + h[3])
    print("RAYS %d through glass %d" % (len(dirs), len(pts)))
    # Rotating assemblies swept over their joint angles: rays are rotated into the rest pose instead.
    joints = {}
    members = {}
    for o in rot:
        for name, kind, emp in joints_of(o):
            joints[name] = (kind, emp)
    for o in iss:
        if o.type == "MESH" and not EXCLUDE.search(o.name):
            for name, kind, emp in joints_of(o):
                members.setdefault(name, []).append(o)
    jgeo = joint_geometry(joints, members, np.array(mat)[:3, :3] / mpu)
    cache = os.path.join(a.out, "visible_parts.json")
    if a.reuse_vis:  # skip the ray sweep: reuse the kept parts and weights of the last run
        by = {o.name: o for o in cand}
        old = json.load(open(cache))["visible"]
        old = [v for v in old if v["group"] == "static" or not DROP_ROT.search(v["name"])]
        keep = [by[v["name"]] for v in old]
        weights = [v["weight"] for v in old]
        build_asset(a, scene, keep, weights, mat, jgeo)
        return
    sub = slice(0, None, a.sweep_stride)
    P = np.array([tuple(p) for p in pts])[sub]
    D = np.array([tuple(v) for v in dvs])[sub]
    S = np.array(dst)[sub]
    rhits = np.zeros(len(rot), np.int64)
    rposes = np.zeros(len(rot), np.int64)
    rdmin = np.full(len(rot), 1e9)
    step = {KIND_ALPHA: 30.0, KIND_BETA: 45.0, KIND_GAMMA: 30.0}
    for i, o in enumerate(rot):
        jl = joints_of(o)
        bvh, _ = build_bvh([o])
        angs = [np.arange(0.0, 360.0, step[k]) for _, k, _ in jl]
        for combo in np.array(np.meshgrid(*angs, indexing="ij")).reshape(len(jl), -1).T:
            Q, E = P.copy(), D.copy()
            for (nm, _, _), ang in zip(reversed(jl), reversed(combo)):  # outermost joint first
                pv, ax = (np.array(x) for x in jgeo[nm])
                r = np.array(Matrix.Rotation(-math.radians(ang), 3, Vector(ax)))
                Q = (Q - pv) @ r.T + pv
                E = E @ r.T
            vis = 0
            for q, e, s_ in zip(Q.tolist(), E.tolist(), S):
                h = bvh.ray_cast(Vector(q), Vector(e))
                if h[0] is not None and h[3] < s_:
                    vis += 1
                    rdmin[i] = min(rdmin[i], h[3])
            rhits[i] += vis
            rposes[i] += vis > 0
    keep_s = sorted((i for i in range(len(stat)) if hits[i] >= a.min_hits), key=lambda i: stat[i].name)
    keep_r = sorted((i for i in range(len(rot)) if rhits[i] >= a.min_sweep_hits and not DROP_ROT.search(rot[i].name)), key=lambda i: rot[i].name)
    keep = [stat[i] for i in keep_s] + [rot[i] for i in keep_r]
    weights = [int(hits[i]) for i in keep_s] + [max(1, int(rhits[i]) * a.sweep_stride // 10) for i in keep_r]
    vis = [{"name": stat[i].name, "group": "static", "hits": int(hits[i]), "min_distance_m": round(float(dmin[i]), 2),
            "triangles": len(stat[i].data.loop_triangles), "weight": int(hits[i])} for i in keep_s]
    vis += [{"name": rot[i].name, "group": group_of(rot[i])[0], "sweep_hits": int(rhits[i]),
             "poses_visible": int(rposes[i]), "min_distance_m": round(float(rdmin[i]), 2),
             "triangles": len(rot[i].data.loop_triangles),
             "weight": max(1, int(rhits[i]) * a.sweep_stride // 10)} for i in keep_r]
    for v in vis:
        print("VIS %-34s %-18s hits %6d poses %3d dmin %8.2f m  tris %6d" % (
            v["name"], v["group"], v.get("hits", v.get("sweep_hits")), v.get("poses_visible", 0),
            v["min_distance_m"], v["triangles"]))
    print("VISIBLE objects %d of %d, source triangles %d" % (len(vis), len(cand), sum(v["triangles"] for v in vis)))
    print("SWEPT-OUT objects", [rot[i].name for i in range(len(rot)) if i not in keep_r])
    json.dump({"rays": int(len(dirs)), "through_glass": len(pts), "visible": vis,
               "joints": {k: {"kind": joints[k][0], "pivot": v[0], "axis": v[1]} for k, v in jgeo.items()}},
              open(os.path.join(a.out, "visible_parts.json"), "w"), indent=1)
    if a.stop_after == "vis":
        return
    build_asset(a, scene, keep, weights, mat, jgeo)


def decimated_world_mesh(ob, target):
    """Welded, triangulated copy of ob in the Cupola frame, collapsed to about target triangles; UV layer renamed UVMap."""
    import bmesh
    me = ob.data
    bm = bmesh.new()
    bm.from_mesh(me)
    bmesh.ops.transform(bm, matrix=ob.matrix_world, verts=bm.verts)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-3)
    bmesh.ops.triangulate(bm, faces=bm.faces)
    tmp = bpy.data.meshes.new("tmp_" + ob.name)
    bm.to_mesh(tmp)
    bm.free()
    for i, s in enumerate(me.materials):
        tmp.materials.append(s)
    tmp.uv_layers[0].name = "UVMap"
    n0 = len(tmp.polygons)
    print("DEC %-34s src %6d welded %6d target %6d" % (ob.name, len(me.loop_triangles), n0, target))
    if target < n0 * 0.999:
        ratio = target / n0
        for _ in range(5):
            t = bpy.data.objects.new("tmp_" + ob.name, tmp)
            bpy.context.scene.collection.objects.link(t)
            md = t.modifiers.new("d", "DECIMATE")
            md.decimate_type = "COLLAPSE"
            md.ratio = min(1.0, ratio)
            md.use_collapse_triangulate = True
            res = bpy.data.meshes.new_from_object(t.evaluated_get(bpy.context.evaluated_depsgraph_get()))
            bpy.data.objects.remove(t)
            res.uv_layers[0].name = "UVMap"
            if len(res.polygons) <= target * 1.05:
                break
            ratio *= target / len(res.polygons) * 0.95
        bpy.data.meshes.remove(tmp)
        tmp = res
    return tmp


def merge_meshes(name, parts):
    """parts: [(mesh, materials)] -> one Blender mesh with merged material slots and the UVMap layer."""
    verts, faces, uvs, mids, mats, off = [], [], [], [], [], 0
    for me in parts:
        n = len(me.vertices)
        co = np.empty(n * 3)
        me.vertices.foreach_get("co", co)
        lv = np.empty(len(me.loops), np.int64)
        me.loops.foreach_get("vertex_index", lv)
        uv = np.empty(len(me.loops) * 2)
        me.uv_layers["UVMap"].uv.foreach_get("vector", uv)
        mi = np.empty(len(me.polygons), np.int64)
        me.polygons.foreach_get("material_index", mi)
        remap = []
        for s in me.materials:
            if s not in mats:
                mats.append(s)
            remap.append(mats.index(s))
        verts.append(co.reshape(n, 3))
        faces.append(lv.reshape(-1, 3) + off)
        uvs.append(uv.reshape(-1, 2))
        mids.append(np.array(remap, np.int64)[np.minimum(mi, len(remap) - 1)])
        off += n
    verts, faces, uvs, mids = (np.concatenate(x) for x in (verts, faces, uvs, mids))
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts.tolist(), [], faces.tolist())
    for s in mats:
        me.materials.append(s)
    me.polygons.foreach_set("material_index", mids)
    me.uv_layers.new(name="UVMap").uv.foreach_set("vector", uvs.ravel())
    return me


def allocate(tris, hits, budget):
    """Per-object triangle budget ~ hits^0.7 (screen coverage), floor 40, cap at the source count."""
    w = np.array(hits, float) ** 0.7
    lo, hi = 0.0, budget * 100.0 / w.sum()
    for _ in range(60):
        k = (lo + hi) / 2
        t = np.minimum(tris, np.maximum(40.0, k * w))
        lo, hi = (k, hi) if t.sum() < budget else (lo, k)
    return np.minimum(tris, np.maximum(40.0, lo * w))


def build_asset(a, scene, objs, hits, mat, jgeo):
    tris = np.array([len(o.data.loop_triangles) for o in objs], float)
    target = allocate(tris, hits, a.budget * 0.85)
    groups = {}
    for ob, t, tg in zip(objs, tris, target):
        g, kind, emp = group_of(ob)
        groups.setdefault(g, {"kind": kind, "empty": emp, "parts": []})["parts"].append((ob, tg))
    order = sorted(groups, key=lambda g: (groups[g]["kind"], g))
    gobs = []
    for g in order:
        me = merge_meshes(g, [decimated_world_mesh(ob, tg) for ob, tg in groups[g]["parts"]])
        ob = bpy.data.objects.new("G_" + g, me)
        scene.collection.objects.link(ob)
        gobs.append(ob)
        print("GROUP %-18s kind %d objects %3d tris %6d" % (g, groups[g]["kind"], len(groups[g]["parts"]),
                                                            len(me.polygons)))
    total = sum(len(o.data.polygons) for o in gobs)
    print("TRIANGLES %d budget %d" % (total, a.budget))
    assert total <= a.budget, "over budget"
    if a.stop_after == "decimate":
        return
    unwrap(gobs)
    if a.no_bake:
        albedo = orm = None
    else:
        albedo, orm = bake(scene, gobs, a)
        albedo.filepath_raw = os.path.join(a.out, "station_albedo.png")
        albedo.file_format = "PNG"
        albedo.save()
        orm.filepath_raw = os.path.join(a.out, "station_orm.png")
        orm.file_format = "PNG"
        orm.save()
    nodes = []
    for g, ob in zip(order, gobs):
        me = ob.data
        n = len(me.vertices)
        pos = np.empty(n * 3)
        me.vertices.foreach_get("co", pos)
        pos = pos.reshape(n, 3)
        lv = np.empty(len(me.loops), np.int64)
        me.loops.foreach_get("vertex_index", lv)
        faces = lv.reshape(-1, 3)
        uv = np.empty(len(me.loops) * 2)
        me.uv_layers["Atlas"].uv.foreach_get("vector", uv)
        uv = (uv.reshape(-1, 2) * [1.0, -1.0] + [0.0, 1.0]).reshape(-1, 3, 2)  # top-left texture origin
        area = np.linalg.norm(np.cross(pos[faces[:, 1]] - pos[faces[:, 0]], pos[faces[:, 2]] - pos[faces[:, 0]]), axis=1)
        ok = area > 1e-9  # degenerate triangles have no normal
        faces, uv = faces[ok], uv[ok].reshape(-1, 2)
        nrm = smooth_normals(pos, faces)
        bad = np.linalg.norm(nrm, axis=2) < 0.5
        nrm[bad] = [0.0, 0.0, 1.0]
        uniq, idx = quant_unique(pos, faces, nrm, uv)
        pivot, axis = jgeo[g] if g in jgeo else ((0.0, 0.0, 0.0), (0.0, 0.0, 1.0))
        nodes.append({"name": g, "kind": groups[g]["kind"], "pivot": pivot, "axis": axis, "pos": pos,
                      "uniq": uniq, "idx": idx})
    used = {n for o in objs for n, k, _ in joints_of(o) if k == KIND_ALPHA}
    for g in sorted(used - set(groups)):  # SARJ joint without visible geometry: marker node, 0 vertices
        nodes.append({"name": g, "kind": KIND_ALPHA, "pivot": jgeo[g][0], "axis": jgeo[g][1],
                      "pos": np.zeros((0, 3)), "uniq": np.zeros((0, 6), np.int64), "idx": np.zeros((0, 3), np.int64)})
    nodes.sort(key=lambda n: (n["kind"], n["name"]))
    path = os.path.join(a.out, "station.mesh")
    stats = write_station(path, nodes)
    for s, nd in zip(stats, nodes):
        s["pivot"], s["axis"] = [round(float(x), 4) for x in nd["pivot"]], [round(float(x), 5) for x in nd["axis"]]
    sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
    for st in stats:
        print("NODE %-16s kind %d vertices %6d triangles %6d" % (st["name"], st["kind"], st["vertices"], st["triangles"]))
    print("MESH %s bytes %d sha256 %s" % (path, os.path.getsize(path), sha))
    json.dump({"triangles": total, "nodes": stats, "mesh_sha256": sha}, open(os.path.join(a.out, "station_report.json"), "w"),
              indent=1)


def unwrap(gobs):
    me_layers = []
    for ob in gobs:
        lay = ob.data.uv_layers.new(name="Atlas")
        ob.data.uv_layers.active = lay
        me_layers.append(lay)
    bpy.ops.object.select_all(action="DESELECT")
    for ob in gobs:
        ob.select_set(True)
    bpy.context.view_layer.objects.active = gobs[0]
    bpy.context.scene.tool_settings.use_uv_select_sync = True
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(88.0), island_margin=0.001)
    bpy.ops.uv.pack_islands(rotate=True, margin=0.001)
    bpy.ops.object.mode_set(mode="OBJECT")
    area = 0.0
    for ob in gobs:
        me = ob.data
        uv = np.empty(len(me.loops) * 2)
        me.uv_layers["Atlas"].uv.foreach_get("vector", uv)
        t = uv.reshape(-1, 3, 2)
        area += 0.5 * np.abs((t[:, 1, 0] - t[:, 0, 0]) * (t[:, 2, 1] - t[:, 0, 1])
                             - (t[:, 2, 0] - t[:, 0, 0]) * (t[:, 1, 1] - t[:, 0, 1])).sum()
    print("UVFILL %.3f of the unit square" % area)


def bake(scene, gobs, a):
    scene.render.engine = "CYCLES"
    cy = scene.cycles
    cy.device, cy.samples, cy.seed, cy.use_animated_seed = "CPU", 8, 1, False
    cy.use_denoising = False
    bk = scene.render.bake
    bk.margin, bk.margin_type, bk.use_clear = 8, "EXTEND", True
    bk.use_pass_direct, bk.use_pass_indirect, bk.use_pass_color = False, False, True
    px = a.atlas
    mats = sorted({s for o in gobs for s in o.data.materials if s}, key=lambda m: m.name)
    tex = {}
    for m in mats:
        nt = m.node_tree
        for n in list(nt.nodes):
            if n.type == "TEX_IMAGE" and not n.inputs["Vector"].is_linked:
                uvn = nt.nodes.new("ShaderNodeUVMap")
                uvn.uv_map = "UVMap"
                nt.links.new(uvn.outputs["UV"], n.inputs["Vector"])
        t = nt.nodes.new("ShaderNodeTexImage")
        tex[m.name] = t
    imgs = {}
    for name, cs in (("albedo", "sRGB"), ("rough", "Non-Color"), ("metal", "Non-Color")):
        im = bpy.data.images.new("station_" + name, px, px, alpha=False)
        im.colorspace_settings.name = cs
        imgs[name] = im

    def run(img, btype):
        for m in mats:
            t = tex[m.name]
            t.image = img
            m.node_tree.nodes.active = t
            t.select = True  # Blender needs this order; deselecting the other nodes breaks the bake lookup
        bpy.ops.object.select_all(action="DESELECT")
        for ob in gobs:
            ob.select_set(True)
        bpy.context.view_layer.objects.active = gobs[0]
        for m in mats:
            m.node_tree.update_tag()
        bpy.context.view_layer.update()
        bpy.ops.object.bake(type=btype)

    run(imgs["albedo"], "DIFFUSE")
    run(imgs["rough"], "ROUGHNESS")
    saved = {}
    for m in mats:  # metallic through an emission shader
        nt = m.node_tree
        out = next(n for n in nt.nodes if n.type == "OUTPUT_MATERIAL" and n.is_active_output)
        pb = next((n for n in nt.nodes if n.type == "BSDF_PRINCIPLED"), None)
        em = nt.nodes.new("ShaderNodeEmission")
        if pb is not None:
            src = pb.inputs["Metallic"]
            if src.is_linked:
                nt.links.new(src.links[0].from_socket, em.inputs["Color"])
            else:
                em.inputs["Color"].default_value = [src.default_value] * 3 + [1.0]
        saved[m.name] = (out, [(l.from_socket, l.to_socket) for l in out.inputs["Surface"].links], em)
        nt.links.new(em.outputs["Emission"], out.inputs["Surface"])
    run(imgs["metal"], "EMIT")
    n = px * px * 4
    r, mt = np.empty(n, np.float32), np.empty(n, np.float32)
    imgs["rough"].pixels.foreach_get(r)
    imgs["metal"].pixels.foreach_get(mt)
    orm_px = np.ones(n, np.float32)
    orm_px[1::4], orm_px[2::4] = r[0::4], mt[0::4]
    orm = bpy.data.images.new("station_orm", px, px, alpha=False)
    orm.colorspace_settings.name = "Non-Color"
    orm.pixels.foreach_set(orm_px)
    return imgs["albedo"], orm


if __name__ == "__main__":
    main()
