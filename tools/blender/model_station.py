"""Model the ISS solar array wings from scratch for the station exterior (plan P4.6, decision D6).

Imported by export_station.py: build(scene) -> (objects, joints). No external model or texture is used; materials are
procedural. Frame: the Cupola frame of build_cupola.py (metres, +Z nadir, ISS velocity at azimuth +30 deg).
Deterministic: fixed lists, no random numbers.
"""
import math

import bmesh
import bpy
from mathutils import Matrix, Vector

KIND_ALPHA, KIND_BETA = 1, 2
# ISS joint positions in the Cupola frame (metres): SARJ alpha pivot and truss axis, BGA beta pivot and wing axis
# (pointing to the wing tip). Measured 4 November 2026 from the station geometry at the 1.55 m eye.
TRUSS = (0.5, -0.86603, 0.0)
ALPHAS = {
    "PORT_ALPHA": ((9.4184, -7.4219, -6.7394), TRUSS),
    "STBD_ALPHA": ((-8.3616, 23.3740, -6.7394), TRUSS),
}
BETAS = {
    "PORT_BETA_2A": ((14.8751, -22.3487, -7.3999), (-0.86603, -0.5, 0.0)),
    "PORT_BETA_2B": ((26.5123, -32.9633, -6.0843), (0.86603, 0.5, 0.0)),
    "PORT_BETA_4A": ((19.0065, -19.9635, -6.0787), (0.86603, 0.5, 0.0)),
    "PORT_BETA_4B": ((22.3802, -35.3478, -7.4033), (-0.86603, -0.5, 0.0)),
    "STBD_BETA_1A": ((-13.7744, 38.2514, -7.4000), (0.86603, 0.5, 0.0)),
    "STBD_BETA_1B": ((-25.4608, 48.9112, -6.0755), (-0.86603, -0.5, 0.0)),
    "STBD_BETA_3A": ((-17.9234, 35.8560, -6.0788), (-0.86603, -0.5, 0.0)),
    "STBD_BETA_3B": ((-21.3124, 51.3076, -7.3966), (0.86603, 0.5, 0.0)),
}
# Wing layout in the wing frame (a along the axis from the pivot, w across, t = w x a), metres. Envelope measured on
# the P4 2A wing: a -1.27..33.91, w -5.70..5.70, t -0.55..1.16. Two blankets of 4.57 m beside a central mast.
BLANKET_A = (1.60, 33.30)
BLANKET_W = (1.13, 5.70)
BLANKET_GAP = 0.01            # front (cells, -t) and back (+t) sheets sit this far from the blanket plane
PANEL = 0.387                 # hinge pitch along the blanket (82 panels)
CELL = (0.080, 0.0645)        # cell pitch across and along the blanket
MAST_A, MAST_T, MAST_SIDE = (0.0, 33.30), 0.30, 0.90
LONGERON, BATTEN, BAY = 0.06, 0.04, 0.80


def _mat(name, base, rough, metal=0.0, lines=None):
    """Principled material; lines = (hinge colour, grid colour or None): panel hinges and cell grid from the UVMap
    coordinates in metres (u across, v along the blanket)."""
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except AttributeError:
        pass
    nt = m.node_tree
    N, L = nt.nodes.new, nt.links.new
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Base Color"].default_value = (*base, 1.0)
    bsdf.inputs["Roughness"].default_value = rough
    bsdf.inputs["Metallic"].default_value = metal
    if not lines:
        return m
    uv = N("ShaderNodeUVMap")
    uv.uv_map = "UVMap"
    sep = N("ShaderNodeSeparateXYZ")
    L(uv.outputs["UV"], sep.inputs[0])

    def line(sock, pitch, width):
        d = N("ShaderNodeMath")
        d.operation = "DIVIDE"
        L(sock, d.inputs[0])
        d.inputs[1].default_value = pitch
        f = N("ShaderNodeMath")
        f.operation = "FRACT"
        L(d.outputs[0], f.inputs[0])
        lt = N("ShaderNodeMath")
        lt.operation = "LESS_THAN"
        L(f.outputs[0], lt.inputs[0])
        lt.inputs[1].default_value = width
        return lt.outputs[0]

    def mix(fac, a, b):
        mx = N("ShaderNodeMix")
        mx.data_type = "RGBA"
        sa = next(s for s in mx.inputs if s.name == "A" and s.type == "RGBA")
        sb = next(s for s in mx.inputs if s.name == "B" and s.type == "RGBA")
        if isinstance(a, tuple):
            sa.default_value = (*a, 1.0)
        else:
            L(a, sa)
        sb.default_value = (*b, 1.0)
        L(fac, mx.inputs["Factor"])
        return next(s for s in mx.outputs if s.type == "RGBA")

    col = base
    hinge, grid = lines
    if grid:
        g = N("ShaderNodeMath")
        g.operation = "MAXIMUM"
        L(line(sep.outputs["X"], CELL[0], 0.12), g.inputs[0])
        L(line(sep.outputs["Y"], CELL[1], 0.12), g.inputs[1])
        col = mix(g.outputs[0], col, grid)
    col = mix(line(sep.outputs["Y"], PANEL, 0.05), col, hinge)
    L(col, bsdf.inputs["Base Color"])
    return m


def _materials():
    return [
        _mat("M_Cells", (0.025, 0.035, 0.075), 0.25, lines=((0.45, 0.42, 0.36), (0.16, 0.16, 0.18))),
        _mat("M_Kapton", (0.50, 0.32, 0.11), 0.50, lines=((0.30, 0.20, 0.08), None)),
        _mat("M_MLI", (0.74, 0.73, 0.69), 0.60),
        _mat("M_Mast", (0.62, 0.62, 0.60), 0.40, metal=0.6),
    ]


def _quad(bm, uvl, corners, uvs, mat):
    f = bm.faces.new([bm.verts.new(c) for c in corners])
    f.material_index = mat
    for lp, uv in zip(f.loops, uvs):
        lp[uvl].uv = uv


def _box(bm, uvl, frame, lo, hi, mat):
    """Axis-aligned box in the wing frame (a, w, t) between lo and hi; faces outward, UV in metres."""
    a0, w0, t0 = lo
    a1, w1, t1 = hi
    p = lambda a, w, t: frame @ Vector((a, w, t))
    for (c, uv) in (
            (((a0, w0, t0), (a0, w1, t0), (a1, w1, t0), (a1, w0, t0)), ((w0, a0), (w1, a0), (w1, a1), (w0, a1))),
            (((a0, w0, t1), (a1, w0, t1), (a1, w1, t1), (a0, w1, t1)), ((w0, a0), (w0, a1), (w1, a1), (w1, a0))),
            (((a0, w0, t0), (a1, w0, t0), (a1, w0, t1), (a0, w0, t1)), ((a0, t0), (a1, t0), (a1, t1), (a0, t1))),
            (((a0, w1, t0), (a0, w1, t1), (a1, w1, t1), (a1, w1, t0)), ((a0, t0), (a0, t1), (a1, t1), (a1, t0))),
            (((a0, w0, t0), (a0, w0, t1), (a0, w1, t1), (a0, w1, t0)), ((w0, t0), (w0, t1), (w1, t1), (w1, t0))),
            (((a1, w0, t0), (a1, w1, t0), (a1, w1, t1), (a1, w0, t1)), ((w0, t0), (w1, t0), (w1, t1), (w0, t1)))):
        _quad(bm, uvl, [p(*x) for x in reversed(c)], list(reversed(uv)), mat)


def _beam(bm, uvl, frame, p0, p1, size, mat):
    """Square beam of edge size from p0 to p1 (wing frame points)."""
    p0, p1 = Vector(p0), Vector(p1)
    d = p1 - p0
    length = d.length
    x = d.normalized()
    y = x.cross(Vector((0.0, 0.0, 1.0)) if abs(x.z) < 0.9 else Vector((1.0, 0.0, 0.0))).normalized()
    z = x.cross(y)
    local = Matrix(((x.x, y.x, z.x, p0.x), (x.y, y.y, z.y, p0.y), (x.z, y.z, z.z, p0.z), (0, 0, 0, 1)))
    h = size / 2.0
    _box(bm, uvl, frame @ local, (0.0, -h, -h), (length, h, h), mat)


def _wing(name, pivot, axis):
    a = Vector(axis).normalized()
    w = a.cross(Vector(TRUSS)).normalized()
    t = w.cross(a)
    frame = Matrix(((a.x, w.x, t.x, pivot[0]), (a.y, w.y, t.y, pivot[1]), (a.z, w.z, t.z, pivot[2]), (0, 0, 0, 1)))
    bm = bmesh.new()
    uvl = bm.loops.layers.uv.new("UVMap")
    a0, a1 = BLANKET_A
    for s in (-1.0, 1.0):
        w0, w1 = sorted((s * BLANKET_W[0], s * BLANKET_W[1]))
        p = lambda aa, ww, tt: frame @ Vector((aa, ww, tt))
        g = BLANKET_GAP
        _quad(bm, uvl, [p(a0, w0, -g), p(a1, w0, -g), p(a1, w1, -g), p(a0, w1, -g)],
              [(w0, a0), (w0, a1), (w1, a1), (w1, a0)], 0)  # cells face -t
        _quad(bm, uvl, [p(a0, w0, g), p(a0, w1, g), p(a1, w1, g), p(a1, w0, g)],
              [(w0, a0), (w1, a0), (w1, a1), (w0, a1)], 1)  # kapton back faces +t
        _box(bm, uvl, frame, (0.60, w0, -0.30), (a0, w1, 0.35), 2)  # blanket box at the base
        _box(bm, uvl, frame, (a1, w0, -0.20), (33.91, w1, 0.30), 2)  # tip plate
    _box(bm, uvl, frame, (-1.27, -0.90, -0.55), (0.60, 0.90, 1.16), 2)  # mast canister and gimbal
    r = MAST_SIDE / math.sqrt(3.0)
    corners = [(0.0, r * math.sin(k * 2 * math.pi / 3), MAST_T + r * math.cos(k * 2 * math.pi / 3)) for k in range(3)]
    for c in corners:
        _beam(bm, uvl, frame, (MAST_A[0], c[1], c[2]), (MAST_A[1], c[1], c[2]), LONGERON, 3)
    n = int((MAST_A[1] - MAST_A[0]) / BAY)
    for i in range(1, n + 1):
        aa = MAST_A[0] + i * (MAST_A[1] - MAST_A[0]) / n
        for k in range(3):
            c0, c1 = corners[k], corners[(k + 1) % 3]
            _beam(bm, uvl, frame, (aa, c0[1], c0[2]), (aa, c1[1], c1[2]), BATTEN, 3)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-4)  # weld box corners; UVs stay per corner
    me = bpy.data.meshes.new("Wing_" + name)
    bm.to_mesh(me)
    bm.free()
    return me


def build(scene):
    """Create the wings under their joint empties (PORT_ALPHA_ROT, PORT_BETA_ROT_2A: export_station.joints_of drops
    "_ROT" to get the node name). Returns (objects, {node name: (pivot, axis)})."""
    mats = _materials()
    objs, joints = [], {}
    empties = {}
    for name in sorted(ALPHAS):
        e = bpy.data.objects.new(name + "_ROT", None)
        scene.collection.objects.link(e)
        empties[name] = e
        objs.append(e)
        joints[name] = ALPHAS[name]
    for name in sorted(BETAS):
        pivot, axis = BETAS[name]
        e = bpy.data.objects.new(name.replace("_BETA_", "_BETA_ROT_"), None)
        scene.collection.objects.link(e)
        e.parent = empties[name.split("_")[0] + "_ALPHA"]
        me = _wing(name, pivot, axis)
        for m in mats:
            me.materials.append(m)
        ob = bpy.data.objects.new("Wing_" + name, me)
        scene.collection.objects.link(ob)
        ob.parent = e
        objs += [e, ob]
        joints[name] = (pivot, axis)
    bpy.context.view_layer.update()
    return objs, joints
