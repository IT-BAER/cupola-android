"""Build the Cupola interior from scratch (decision 23) and save local/blender/cupola.blend.

Run: blender -b --factory-startup -P tools/blender/build_cupola.py -- [--out PATH] [--top-aperture M]
Frame: metres; origin at the centre of the hatch plane to Node 3; +Z points out through the
round nadir window toward Earth. Deterministic: no time, fixed lists, and one random.Random with a fixed
seed (DETAIL_SEED) consumed in a fixed order for the small hardware scatter.
"""
import math
import os
import random
import sys

import bmesh
import bpy
from mathutils import Matrix, Vector

# Published clear apertures (ESA, Deloo 2009, design ledger C).
TOP_APERTURE_D = 0.706
SIDE_H = 0.405
SIDE_SHORT = 0.400
SIDE_LONG = 0.644
SIDE_CORNER_R = 0.0324

# Published envelope (ESA Cupola fact sheet): 2.95 m outer diameter, 1.5 m height. The interior
# below stays inside it: skirt 1.89 m across corners, top glass at 1.44 m.
CUPOLA_OUTER_D_PUBLISHED = 2.95
CUPOLA_HEIGHT_PUBLISHED = 1.5

# Estimated from iss038e013587 (fisheye from below): side-window inner edge sits ~1.57x the top
# aperture radius from the centre; mullions keep a constant width along their length.
HEX_APOTHEM = 0.52
# Derived: facet tilt (from the top plate plane) that makes the window legs parallel to the
# mullions, as seen in iss038e013587: tan-free form cos(tilt) = sqrt(3) * leg slope.
FACET_TILT = math.acos(math.sqrt(3.0) * ((SIDE_LONG - SIDE_SHORT) / 2.0) / SIDE_H)
SIDE_MARGIN_TOP = 0.07     # mullion band between top plate edge and window, est. iss038e013587
SIDE_MARGIN_BOTTOM = 0.10  # band below window to skirt, est. iss038e013587
FACET_LEN = SIDE_MARGIN_TOP + SIDE_H + SIDE_MARGIN_BOTTOM
Z_BASE = 0.30              # hatch ring plane, estimated from iss035e010551
SKIRT_H = 0.55             # vertical wall height, estimated from iss035e010551 / iss073e0025760
Z_TOP = Z_BASE + SKIRT_H + FACET_LEN * math.sin(FACET_TILT)
HATCH_R = 0.64             # CBM passage ~1.27 m (published CBM hatch size, approximated round)
TOP_DEPTH = 0.10           # top window tunnel to inner pane, estimated from iss035e010551
SIDE_DEPTH = 0.06          # side window tunnel, estimated from iss073e0025760
TOP_RING_IN, TOP_RING_OUT, TOP_RING_H = 0.365, 0.47, 0.015  # raised bolt ring, est. iss038e013587
TOP_BOLTS, TOP_BOLT_R = 48, 0.42
SIDE_BOLT_OFFSET, SIDE_BOLT_STEP = 0.035, 0.08
SHUTTER_OPEN_DEG = 130.0   # exterior shutters open, est. iss038e013587 (seen at window edges)
# Stepped window bezels (offset outside the clear aperture d, depth w toward the pane, material:
# 0 frame, 1 gasket, 2 bezel metal), shape est. iss050e033912. Top: d <= TOP_RING_IN - aperture radius.
# Walls black anodized, ledges bare-metal satin (the thin bright lines in the photos).
SIDE_BEZEL = [(0.024, 0.0, 0), (0.016, 0.005, 2), (0.016, 0.016, 0), (0.010, 0.018, 2), (0.010, 0.030, 0),
              (0.004, 0.032, 2), (0.004, 0.042, 0), (0.0, 0.044, 2), (0.0, 0.06, 1)]
TOP_BEZEL = [(0.012, 0.0, 0), (0.012, 0.03, 0), (0.008, 0.032, 2), (0.008, 0.06, 0), (0.004, 0.062, 2),
             (0.004, 0.078, 0), (0.0, 0.08, 2), (0.0, 0.1, 1)]
DETAIL_SEED = 4207         # random.Random seed for the small hardware scatter (deterministic)
CIRCLE_SEG = 96
FILLET_SEG = 8             # even: the arc midpoint is a vertex (used by measure_apertures.py)

EYE = Vector((0.0, 0.0, 0.70))  # fixed eye point (decision 22), estimated head position
REST_TARGET_R = 0.55            # rest view aims at this radius on the glass plane toward a mullion
REST_ROLL_DEG = 0.0             # diagonal composition like the ESA clip (decision 9)
REST_LENS_MM = 14.0

SQ3 = math.sqrt(3.0)
A2 = HEX_APOTHEM + FACET_LEN * math.cos(FACET_TILT)  # skirt apothem
Z_FB = Z_TOP - FACET_LEN * math.sin(FACET_TILT)      # facet bottom height


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    out = os.path.join(root, "local", "blender", "cupola.blend")
    top = TOP_APERTURE_D
    i = 0
    while i < len(argv):
        if argv[i] == "--out":
            out = argv[i + 1]
            i += 2
        elif argv[i] == "--top-aperture":
            top = float(argv[i + 1])
            i += 2
        else:
            raise SystemExit("unknown argument: " + argv[i])
    return out, top


# ---------- 2D helpers ----------

def ccw(poly):
    area = sum(poly[i][0] * poly[(i + 1) % len(poly)][1] - poly[(i + 1) % len(poly)][0] * poly[i][1]
               for i in range(len(poly)))
    return poly if area > 0 else list(reversed(poly))


def fillet(corners, r, seg=FILLET_SEG):
    """Rounded convex polygon: each corner replaced by an arc of seg segments (seg + 1 points)."""
    corners = ccw(corners)
    out = []
    n = len(corners)
    for i in range(n):
        p = Vector(corners[i])
        d1 = (Vector(corners[i - 1]) - p).normalized()
        d2 = (Vector(corners[(i + 1) % n]) - p).normalized()
        theta = d1.angle(d2)
        c = p + (d1 + d2).normalized() * (r / math.sin(theta / 2.0))
        t1 = p + d1 * (r / math.tan(theta / 2.0))
        t2 = p + d2 * (r / math.tan(theta / 2.0))
        a1 = math.atan2(t1.y - c.y, t1.x - c.x)
        a2 = math.atan2(t2.y - c.y, t2.x - c.x)
        while a2 < a1:
            a2 += 2 * math.pi
        for k in range(seg + 1):
            a = a1 + (a2 - a1) * k / seg
            out.append((c.x + r * math.cos(a), c.y + r * math.sin(a)))
    return out


def offset_corners(corners, d):
    corners = ccw(corners)
    n = len(corners)
    res = []
    for i in range(n):
        p = Vector(corners[i])
        d1 = (Vector(corners[i - 1]) - p).normalized()
        d2 = (Vector(corners[(i + 1) % n]) - p).normalized()
        theta = d1.angle(d2)
        res.append(tuple(p - (d1 + d2).normalized() * (d / math.sin(theta / 2.0))))
    return res


def circle(r, n=CIRCLE_SEG, phase=0.0):
    return [(r * math.cos(phase + 2 * math.pi * k / n), r * math.sin(phase + 2 * math.pi * k / n))
            for k in range(n)]


def ray_hit(poly, c, ang):
    d = Vector((math.cos(ang), math.sin(ang)))
    best = None
    for i in range(len(poly)):
        a, b = Vector(poly[i]), Vector(poly[(i + 1) % len(poly)])
        e = b - a
        den = d.x * e.y - d.y * e.x
        if abs(den) < 1e-12:
            continue
        w = a - c
        t = (w.x * e.y - w.y * e.x) / den
        s = (w.x * d.y - w.y * d.x) / den
        if t > 0 and -1e-5 <= s <= 1 + 1e-5 and (best is None or t < best):
            best = t
    return c + d * best


def ring_loops(inner, outer, c):
    """Matched inner/outer loops between two convex polygons around c (for a quad-strip ring)."""
    c = Vector(c)
    angs = sorted({round(math.atan2(p[1] - c.y, p[0] - c.x), 12) for p in inner + outer})
    return [ray_hit(inner, c, a) for a in angs], [ray_hit(outer, c, a) for a in angs]


def catmull(pts, sub, closed=False):
    """Catmull-Rom resampling with sub points per span (end point kept for open curves)."""
    P = [Vector(p) for p in pts]
    n = len(P)
    out = []
    for i in range(n if closed else n - 1):
        p0 = P[i - 1] if (closed or i > 0) else P[0]
        p1, p2 = P[i], P[(i + 1) % n]
        p3 = P[(i + 2) % n] if (closed or i + 2 < n) else P[-1]
        for s in range(sub):
            t = s / sub
            out.append(0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                              + (3 * p1 - p0 - 3 * p2 + p3) * t * t * t))
    if not closed:
        out.append(P[-1].copy())
    return out


# ---------- scene helpers ----------

COLS = {}
MATS = {}


def col(name):
    if name not in COLS:
        c = bpy.data.collections.new(name)
        bpy.context.scene.collection.children.link(c)
        COLS[name] = c
    return COLS[name]


def principled(name, color, metal=0.0, rough=0.5, noise=None, emit=None, strength=0.0,
               cvar=0.0, cscale=6.0, bump=None, bevel=0.0, wear=None, sheen=0.0):
    """Procedural PBR material; no image textures anywhere.

    noise: roughness variation (scale, low, high). cvar: +-fraction of base colour from a noise.
    bump: (scale, strength, detail) noise bump. bevel: Cycles rounded-edge radius in metres (specular
    edges on hard-edged boxes). wear: (colour, metallic, roughness) of bare metal shown on edges
    (bevel normal vs. true normal) in noise-driven patches; needs bevel.
    """
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except AttributeError:
        pass
    nt = m.node_tree
    N, L = nt.nodes.new, nt.links.new
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Base Color"].default_value = (*color, 1.0)
    bsdf.inputs["Metallic"].default_value = metal
    bsdf.inputs["Roughness"].default_value = rough
    if sheen:
        bsdf.inputs["Sheen Weight"].default_value = sheen
    if emit:
        bsdf.inputs["Emission Color"].default_value = (*emit, 1.0)
        bsdf.inputs["Emission Strength"].default_value = strength
    co = N("ShaderNodeTexCoord").outputs["Object"]

    def noise_fac(scale, detail=4.0):
        t = N("ShaderNodeTexNoise")
        t.inputs["Scale"].default_value = scale
        t.inputs["Detail"].default_value = detail
        L(co, t.inputs["Vector"])
        return t.outputs["Fac"]

    def remap(sock, f0, f1, t0, t1):
        mr = N("ShaderNodeMapRange")
        mr.inputs["From Min"].default_value, mr.inputs["From Max"].default_value = f0, f1
        for k, v in (("To Min", t0), ("To Max", t1)):
            if isinstance(v, (int, float)):
                mr.inputs[k].default_value = v
            else:
                L(v, mr.inputs[k])
        L(sock, mr.inputs["Value"])
        return mr.outputs["Result"]

    base = None
    if cvar:
        sc = N("ShaderNodeVectorMath")
        sc.operation = "SCALE"
        sc.inputs[0].default_value = color
        L(remap(noise_fac(cscale), 0.3, 0.7, 1.0 - cvar, 1.0 + cvar), sc.inputs["Scale"])
        base = sc.outputs["Vector"]
        L(base, bsdf.inputs["Base Color"])
    rsock = remap(noise_fac(noise[0]), 0.3, 0.7, noise[1], noise[2]) if noise else None
    if rsock:
        L(rsock, bsdf.inputs["Roughness"])
    nrm = None
    if bevel:
        bv = N("ShaderNodeBevel")
        bv.samples = 6
        bv.inputs["Radius"].default_value = bevel
        nrm = bv.outputs["Normal"]
    if bevel and wear:
        dot = N("ShaderNodeVectorMath")
        dot.operation = "DOT_PRODUCT"
        L(nrm, dot.inputs[0])
        L(N("ShaderNodeNewGeometry").outputs["True Normal"], dot.inputs[1])
        edge = remap(dot.outputs["Value"], 0.995, 0.9, 0.0, 1.0)
        mul = N("ShaderNodeMath")
        mul.operation = "MULTIPLY"
        L(edge, mul.inputs[0])
        L(remap(noise_fac(30.0, 8.0), 0.45, 0.62, 0.0, 1.0), mul.inputs[1])
        mask = mul.outputs["Value"]
        mix = N("ShaderNodeMix")
        mix.data_type = "RGBA"
        sa = next(s for s in mix.inputs if s.name == "A" and s.type == "RGBA")
        sb = next(s for s in mix.inputs if s.name == "B" and s.type == "RGBA")
        sa.default_value = (*color, 1.0)
        if base:
            L(base, sa)
        sb.default_value = (*wear[0], 1.0)
        L(mask, mix.inputs["Factor"])
        L(next(s for s in mix.outputs if s.type == "RGBA"), bsdf.inputs["Base Color"])
        L(remap(mask, 0.0, 1.0, metal, wear[1]), bsdf.inputs["Metallic"])
        L(remap(mask, 0.0, 1.0, rsock if rsock else rough, wear[2]), bsdf.inputs["Roughness"])
    if bump:
        bp = N("ShaderNodeBump")
        bp.inputs["Strength"].default_value = bump[1]
        bp.inputs["Distance"].default_value = 0.001
        L(noise_fac(bump[0], bump[2] if len(bump) > 2 else 6.0), bp.inputs["Height"])
        if nrm:
            L(nrm, bp.inputs["Normal"])
        nrm = bp.outputs["Normal"]
    if nrm:
        L(nrm, bsdf.inputs["Normal"])
    MATS[name] = m
    return m


def glass_material():
    m = bpy.data.materials.new("M_Glass")
    try:
        m.use_nodes = True
    except AttributeError:
        pass
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    lw = nt.nodes.new("ShaderNodeLayerWeight")
    lw.inputs["Blend"].default_value = 0.08
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    gl = nt.nodes.new("ShaderNodeBsdfGlossy")
    gl.inputs["Roughness"].default_value = 0.02
    mix = nt.nodes.new("ShaderNodeMixShader")
    nt.links.new(lw.outputs["Fresnel"], mix.inputs["Fac"])
    nt.links.new(tr.outputs["BSDF"], mix.inputs[1])
    nt.links.new(gl.outputs["BSDF"], mix.inputs[2])
    nt.links.new(mix.outputs["Shader"], out.inputs["Surface"])
    MATS["M_Glass"] = m
    return m


class Part:
    """One mesh object accumulated in a bmesh, with per-face material slots."""

    def __init__(self, name, collection, mats):
        self.name, self.collection, self.mats = name, collection, mats
        self.bm = bmesh.new()

    def face(self, pts, mat=0):
        vs = [self.bm.verts.new(p) for p in pts]
        f = self.bm.faces.new(vs)
        f.material_index = mat
        return f

    def strip(self, loop_a, loop_b, mat=0, closed=True):
        n = len(loop_a)
        va = [self.bm.verts.new(p) for p in loop_a]
        vb = [self.bm.verts.new(p) for p in loop_b]
        for i in range(n if closed else n - 1):
            j = (i + 1) % n
            self.bm.faces.new((va[i], va[j], vb[j], vb[i])).material_index = mat

    def _tag(self, geom, mat):
        for f in [g for g in geom if isinstance(g, bmesh.types.BMFace)]:
            f.material_index = mat

    def box(self, size, matrix, mat=0, bevel=0.0):
        r = bmesh.ops.create_cube(self.bm, size=1.0, matrix=matrix @ Matrix.Diagonal((*size, 1.0)))
        verts = r["verts"]
        faces = list({f for v in verts for f in v.link_faces})
        for f in faces:
            f.material_index = mat
        if bevel > 0:
            edges = list({e for v in verts for e in v.link_edges})
            res = bmesh.ops.bevel(self.bm, geom=edges, offset=bevel, offset_type="OFFSET",
                                  profile_type="SUPERELLIPSE", segments=3, profile=0.5,
                                  affect="EDGES", clamp_overlap=True)
            self._tag(res["faces"], mat)

    def cyl(self, p0, p1, r, mat=0, seg=16, r2=None):
        p0, p1 = Vector(p0), Vector(p1)
        axis = p1 - p0
        m = Matrix.Translation((p0 + p1) / 2) @ axis.to_track_quat("Z", "Y").to_matrix().to_4x4()
        res = bmesh.ops.create_cone(self.bm, cap_ends=True, cap_tris=False, segments=seg,
                                    radius1=r, radius2=r if r2 is None else r2,
                                    depth=axis.length, matrix=m)
        faces = list({f for v in res["verts"] for f in v.link_faces})
        self._tag(faces, mat)

    def tube(self, pts, r, mat=0, seg=8, closed=False, sub=6, rib=0.0):
        """Swept tube along a Catmull-Rom spline (parallel-transport frames); rib alternates the radius
        of successive rings by +-rib (corrugated hose). Returns the resampled centre line."""
        q = catmull(pts, sub, closed)
        m = len(q)
        tg = []
        for i in range(m):
            a = q[i - 1] if (closed or i > 0) else q[i]
            b = q[(i + 1) % m] if (closed or i < m - 1) else q[i]
            tg.append((b - a).normalized())
        n = tg[0].orthogonal().normalized()
        rings = []
        for i in range(m):
            if i:
                n = tg[i - 1].rotation_difference(tg[i]) @ n
                n = (n - tg[i] * n.dot(tg[i])).normalized()
            b = tg[i].cross(n)
            rr = r * (1.0 + rib * (1 if i % 2 else -1))
            rings.append([self.bm.verts.new(q[i] + (n * math.cos(a) + b * math.sin(a)) * rr)
                          for a in (2 * math.pi * s / seg for s in range(seg))])
        for i in range(m if closed else m - 1):
            ra, rb = rings[i], rings[(i + 1) % m]
            for s in range(seg):
                self.bm.faces.new((ra[s], ra[(s + 1) % seg], rb[(s + 1) % seg], rb[s])).material_index = mat
        if not closed:
            self.bm.faces.new(list(reversed(rings[0]))).material_index = mat
            self.bm.faces.new(rings[-1]).material_index = mat
        return q, tg

    def soft_box(self, size, matrix, mat=0, lump=0.006):
        """Bevelled box with a smooth deterministic lumpy displacement (soft fabric bag)."""
        before = set(self.bm.verts)
        self.box(size, matrix, mat=mat, bevel=min(size) * 0.3)
        c = matrix.translation
        for v in self.bm.verts:
            if v not in before:
                p = v.co
                d = math.sin(37.0 * p.x + 1.1) * math.sin(41.0 * p.y + 0.5) * math.sin(29.0 * p.z + 0.3)
                v.co = p + (p - c).normalized() * (lump * d)

    def build(self, props=None):
        me = bpy.data.meshes.new(self.name)
        self.bm.to_mesh(me)
        self.bm.free()
        for m in self.mats:
            me.materials.append(MATS[m])
        ob = bpy.data.objects.new(self.name, me)
        col(self.collection).objects.link(ob)
        for k, v in (props or {}).items():
            ob[k] = v
        return ob


# ---------- facet frame ----------

def facet_frame(k):
    a = math.radians(60.0 * k)
    t = FACET_TILT
    o = Vector((HEX_APOTHEM * math.cos(a), HEX_APOTHEM * math.sin(a), Z_TOP))
    u = Vector((-math.sin(a), math.cos(a), 0.0))
    v = Vector((math.cos(t) * math.cos(a), math.cos(t) * math.sin(a), -math.sin(t)))
    n = Vector((math.sin(t) * math.cos(a), math.sin(t) * math.sin(a), math.cos(t)))
    return o, u, v, n


def fmap(frame, p2, w=0.0):
    o, u, v, n = frame
    return o + u * p2[0] + v * p2[1] + n * w


def side_corners():
    m, h = SIDE_MARGIN_TOP, SIDE_H
    return [(-SIDE_SHORT / 2, m), (SIDE_SHORT / 2, m), (SIDE_LONG / 2, m + h), (-SIDE_LONG / 2, m + h)]


def facet_outline():
    w0 = HEX_APOTHEM / SQ3
    w1 = A2 / SQ3
    return [(-w0, 0.0), (w0, 0.0), (w1, FACET_LEN), (-w1, FACET_LEN)]


def hex_corners(apothem, z):
    rc = apothem / math.cos(math.radians(30))
    return [Vector((rc * math.cos(math.radians(30 + 60 * k)), rc * math.sin(math.radians(30 + 60 * k)), z))
            for k in range(6)]


# ---------- build ----------

def build(top_d):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    world = bpy.data.worlds.new("W_Black")
    world.color = (0.0, 0.0, 0.0)
    scene.world = world

    # Satin black anodized aluminium with bare-metal edge wear, as in iss050e033912/iss038e013587.
    principled("M_FrameBlack", (0.011, 0.011, 0.012), rough=0.38, noise=(60.0, 0.33, 0.45), cvar=0.15,
               cscale=9.0, bump=(900.0, 0.03, 2.0), bevel=0.0025, wear=((0.45, 0.45, 0.47), 1.0, 0.32))
    principled("M_Bezel", (0.14, 0.14, 0.15), metal=1.0, rough=0.32, noise=(45.0, 0.26, 0.4), cvar=0.15,
               bevel=0.0015)
    principled("M_Gasket", (0.32, 0.07, 0.035), rough=0.5, noise=(80.0, 0.4, 0.62), cvar=0.15)
    principled("M_Bolt", (0.6, 0.6, 0.62), metal=1.0, rough=0.3, noise=(300.0, 0.22, 0.45), bevel=0.0008)
    principled("M_Handrail", (0.02, 0.13, 0.45), metal=1.0, rough=0.3, noise=(30.0, 0.22, 0.42), cvar=0.15,
               bevel=0.002, wear=((0.55, 0.56, 0.58), 1.0, 0.3))
    principled("M_Shutter", (0.55, 0.53, 0.5), metal=0.6, rough=0.45, noise=(8.0, 0.35, 0.6),
               bump=(40.0, 0.2, 4.0))
    principled("M_ShellGrey", (0.045, 0.045, 0.05), metal=0.1, rough=0.6, noise=(25.0, 0.45, 0.7), cvar=0.3,
               bump=(300.0, 0.05, 3.0))
    principled("M_PanelGrey", (0.09, 0.09, 0.095), metal=0.1, rough=0.55, noise=(20.0, 0.4, 0.65), cvar=0.2,
               bevel=0.002, bump=(500.0, 0.03, 2.0))
    principled("M_Fabric", (0.2, 0.13, 0.07), rough=0.92, noise=(120.0, 0.85, 1.0), cvar=0.3, cscale=12.0,
               bump=(45.0, 0.35, 8.0), sheen=0.4)
    principled("M_PatchWhite", (0.7, 0.7, 0.66), rough=0.9, cvar=0.1, bevel=0.0008)
    principled("M_LaptopBody", (0.03, 0.03, 0.03), rough=0.5, bevel=0.002)
    principled("M_CameraBody", (0.02, 0.02, 0.02), rough=0.45, noise=(200.0, 0.4, 0.6), bevel=0.003,
               bump=(1500.0, 0.02, 2.0))
    principled("M_LensMetal", (0.05, 0.05, 0.05), metal=0.8, rough=0.3, bevel=0.001)
    principled("M_CableBlack", (0.015, 0.015, 0.015), rough=0.45, noise=(200.0, 0.35, 0.6))
    principled("M_CableBlue", (0.05, 0.22, 0.5), rough=0.45)
    principled("M_CableWhite", (0.62, 0.62, 0.6), rough=0.5, cvar=0.1)
    principled("M_CableGrey", (0.12, 0.12, 0.12), rough=0.5)
    principled("M_FabricBeige", (0.5, 0.44, 0.34), rough=0.95, cvar=0.2, cscale=12.0, bump=(45.0, 0.35, 8.0),
               sheen=0.5)
    principled("M_Webbing", (0.05, 0.05, 0.045), rough=0.85, bump=(600.0, 0.2, 2.0))
    principled("M_Velcro", (0.62, 0.62, 0.6), rough=1.0, bump=(2500.0, 0.4, 2.0), sheen=0.3)
    principled("M_VelcroBlack", (0.03, 0.03, 0.03), rough=1.0, bump=(2500.0, 0.4, 2.0), sheen=0.3)
    principled("M_Tag", (0.75, 0.22, 0.02), rough=0.3, cvar=0.1)
    principled("M_Plastic", (0.025, 0.025, 0.027), rough=0.45, noise=(80.0, 0.35, 0.55), bevel=0.0015)
    principled("M_Connector", (0.36, 0.34, 0.3), metal=1.0, rough=0.38, bevel=0.0008)
    principled("M_Hose", (0.55, 0.55, 0.52), rough=0.6, cvar=0.1)
    principled("M_ScreenEmit", (0.0, 0.0, 0.0), rough=0.2, emit=(0.55, 0.62, 0.7), strength=1.5)
    principled("M_LedGreen", (0.0, 0.0, 0.0), emit=(0.1, 1.0, 0.2), strength=8.0)
    principled("M_LedAmber", (0.0, 0.0, 0.0), emit=(1.0, 0.55, 0.05), strength=8.0)
    glass_material()

    # Top window: plate ring (hexagon to aperture circle), tunnel to the pane, glass.
    r_top = top_d / 2.0
    hexc = [tuple(p.xy) for p in hex_corners(HEX_APOTHEM, Z_TOP)]
    fr = Part("Frame_Top", "Frames", ["M_FrameBlack", "M_Gasket", "M_Bezel"])
    zg = Z_TOP + TOP_DEPTH
    prof = [(d, Z_TOP + w * TOP_DEPTH / 0.1, mt) for d, w, mt in TOP_BEZEL]
    inner, outer = ring_loops(circle(r_top + prof[0][0]), hexc, (0.0, 0.0))
    fr.strip([(p.x, p.y, Z_TOP) for p in inner], [(p.x, p.y, Z_TOP) for p in outer])
    for (d0, z0, _), (d1, z1, mt) in zip(prof, prof[1:]):
        fr.strip([(x, y, z0) for x, y in circle(r_top + d0)], [(x, y, z1) for x, y in circle(r_top + d1)], mat=mt)
    rz = Z_TOP - TOP_RING_H
    ci, co = circle(TOP_RING_IN), circle(TOP_RING_OUT)
    fr.strip([(x, y, rz) for x, y in ci], [(x, y, rz) for x, y in co])
    fr.strip([(x, y, Z_TOP) for x, y in ci], [(x, y, rz) for x, y in ci])
    fr.strip([(x, y, rz) for x, y in co], [(x, y, Z_TOP) for x, y in co])
    fr.build()
    g = Part("Glass_Top", "Glass", ["M_Glass"])
    g.face([(x, y, zg) for x, y in circle(r_top)])
    g.build({"cupola_aperture": "top"})

    # Side windows on six facets.
    corners = side_corners()
    win = fillet(corners, SIDE_CORNER_R)
    cen = (0.0, SIDE_MARGIN_TOP + SIDE_H / 2.0)
    for k in range(6):
        f = facet_frame(k)
        p = Part("Frame_Side_%d" % k, "Frames", ["M_FrameBlack", "M_Gasket", "M_Bezel"])
        prof = [(fillet(offset_corners(corners, d), SIDE_CORNER_R + d), w * SIDE_DEPTH / 0.06, mt)
                for d, w, mt in SIDE_BEZEL]
        inner, outer = ring_loops(prof[0][0], ccw(facet_outline()), cen)
        p.strip([fmap(f, q) for q in inner], [fmap(f, q) for q in outer])
        for (l0, w0, _), (l1, w1, mt) in zip(prof, prof[1:]):
            p.strip([fmap(f, q, w0) for q in l0], [fmap(f, q, w1) for q in l1], mat=mt)
        p.build()
        g = Part("Glass_Side_%d" % k, "Glass", ["M_Glass"])
        g.face([fmap(f, q, SIDE_DEPTH) for q in win])
        g.build({"cupola_aperture": "side_%d" % k})

    # Shell: vertical skirt, hatch ring, hatch tunnel, Node 3 cap.
    sh = Part("Shell", "Shell", ["M_ShellGrey"])
    top_l = hex_corners(A2, Z_FB)
    bot_l = hex_corners(A2, Z_BASE)
    sh.strip(top_l, bot_l)
    inner, outer = ring_loops(circle(HATCH_R), [tuple(p.xy) for p in bot_l], (0.0, 0.0))
    sh.strip([(q.x, q.y, Z_BASE) for q in inner], [(q.x, q.y, Z_BASE) for q in outer])
    hc = circle(HATCH_R)
    sh.strip([(x, y, Z_BASE) for x, y in hc], [(x, y, 0.0) for x, y in hc])
    sh.face([(x, y, -0.5) for x, y in reversed(circle(1.0, 32))])
    sh.strip([(x, y, 0.0) for x, y in hc[::3]], [(x * 1.0 / HATCH_R, y / HATCH_R, -0.5) for x, y in hc[::3]])
    sh.build()

    # Exterior shutters (open), hinged at the outer edge of each window.
    shu = Part("Shutters", "Shutters", ["M_Shutter"])
    for k in range(6):
        f = facet_frame(k)
        o, u, v, n = f
        sc = offset_corners(corners, 0.05)
        hinge_v = max(c[1] for c in sc)
        hinge = fmap(f, (0.0, hinge_v), SIDE_DEPTH + 0.05)
        rot = Matrix.Translation(hinge) @ Matrix.Rotation(math.radians(SHUTTER_OPEN_DEG), 4, u) \
            @ Matrix.Translation(-hinge)
        lo = [rot @ fmap(f, c, SIDE_DEPTH + 0.03) for c in sc]
        hi = [rot @ fmap(f, c, SIDE_DEPTH + 0.05) for c in sc]
        shu.face(lo)
        shu.face(list(reversed(hi)))
        shu.strip(lo, hi)
    hinge = Vector((0.42, 0.0, zg + 0.05))
    rot = Matrix.Translation(hinge) @ Matrix.Rotation(math.radians(SHUTTER_OPEN_DEG - 10), 4, "Y") \
        @ Matrix.Translation(-hinge)
    disc = circle(0.42, 48)
    lo = [rot @ Vector((x, y, zg + 0.03)) for x, y in disc]
    hi = [rot @ Vector((x, y, zg + 0.05)) for x, y in disc]
    shu.face(lo)
    shu.face(list(reversed(hi)))
    shu.strip(lo, hi)
    shu.build()

    # Bolts: top ring and around each side window.
    bo = Part("Bolts", "Bolts", ["M_Bolt"])
    for x, y in circle(TOP_BOLT_R, TOP_BOLTS):
        bo.cyl((x, y, rz), (x, y, rz - 0.008), 0.007, seg=8)
        bo.cyl((x, y, rz), (x, y, rz - 0.0015), 0.0105, seg=12)
    # Inner cap-screw row on the raised ring and a row along the hex plate edges.
    for x, y in circle(0.39, 60, phase=0.03):
        bo.cyl((x, y, rz), (x, y, rz - 0.004), 0.0045, seg=8)
    hx = hex_corners(HEX_APOTHEM - 0.025, Z_TOP)
    for i in range(6):
        for j in range(1, 6):
            p = hx[i].lerp(hx[(i + 1) % 6], j / 6.0)
            bo.cyl(p, p - Vector((0, 0, 0.005)), 0.005, seg=8)
    sc = ccw(offset_corners(corners, SIDE_BOLT_OFFSET))
    pts2 = []
    for i in range(4):
        a, b = Vector(sc[i]), Vector(sc[(i + 1) % 4])
        cnt = max(2, int((b - a).length / SIDE_BOLT_STEP))
        pts2 += [a.lerp(b, (j + 0.5) / cnt) for j in range(cnt)]
    for k in range(6):
        f = facet_frame(k)
        for q in pts2:
            bo.cyl(fmap(f, q, 0.0), fmap(f, q, -0.008), 0.006, seg=8)
            bo.cyl(fmap(f, q, 0.0), fmap(f, q, -0.0015), 0.009, seg=12)
    bo.build()

    # Handrails: one per mullion and one per skirt corner, with standoffs.
    hr = Part("Handrails", "Handrails", ["M_Handrail", "M_Bolt", "M_Plastic"])
    tops, bots, bases = hex_corners(HEX_APOTHEM, Z_TOP), hex_corners(A2, Z_FB), hex_corners(A2, Z_BASE)

    def rail(a, b, off):
        d = (b - a).normalized()
        a2, b2 = a + off, b + off
        hr.cyl(a2 + d * 0.018, b2 - d * 0.018, 0.011, seg=20)
        for e, s in ((a2, 1), (b2, -1)):
            # Machined end fitting with a collar, standoff and a foot pad on the wall.
            hr.cyl(e - d * s * 0.004, e + d * s * 0.024, 0.0145, mat=1, seg=20)
            hr.cyl(e + d * s * 0.024, e + d * s * 0.03, 0.012, mat=1, seg=20)
        for e in (a, b):
            hr.cyl(e + off * 0.25, e + off * 0.8, 0.008, mat=1, seg=12)
            hr.box((0.03, 0.03, 0.008), place(e + off.normalized() * 0.004, off, d), mat=2, bevel=0.002)

    for k in range(6):
        n_in = -(facet_frame(k)[3] + facet_frame((k + 1) % 6)[3]).normalized()
        rail(tops[k].lerp(bots[k], 0.22), tops[k].lerp(bots[k], 0.85), n_in * 0.05)
        rin = -Vector((bases[k].x, bases[k].y, 0.0)).normalized()
        rail(bases[k] + Vector((0, 0, 0.12)), bots[k] - Vector((0, 0, 0.10)), rin * 0.06)
    hr.build()

    build_laptop()
    build_cameras()
    build_cables()
    build_clutter()
    rng = random.Random(DETAIL_SEED)
    build_mullion_detail(rng)
    build_walls(rng)
    build_harness()
    build_lights_and_view()

    bpy.context.view_layer.update()


def place(pos, z_axis, x_hint):
    z = Vector(z_axis).normalized()
    x = (Vector(x_hint) - z * Vector(x_hint).dot(z)).normalized()
    y = z.cross(x)
    m = Matrix((x, y, z)).transposed().to_4x4()
    m.translation = Vector(pos)
    return m


def build_laptop():
    az = math.radians(200.0)
    radial = Vector((math.cos(az), math.sin(az), 0.0))
    tang = Vector((-math.sin(az), math.cos(az), 0.0))
    pos = radial * 0.50 + Vector((0, 0, 1.02))
    m = place(pos, (-radial * 0.6 + Vector((0, 0, -0.8))), tang)
    lp = Part("Laptop", "Laptop", ["M_LaptopBody", "M_PanelGrey", "M_VelcroBlack", "M_PatchWhite", "M_Plastic"])
    lp.box((0.34, 0.24, 0.025), m, bevel=0.004)
    # Underside: Velcro pads, a plain label patch, rubber feet; lid back: Velcro and a vent grille.
    for x, y, sz, mt in ((-0.12, -0.08, (0.05, 0.03), 2), (0.12, -0.08, (0.05, 0.03), 2), (0.0, 0.06, (0.07, 0.035), 3)):
        lp.box((*sz, 0.0015), m @ Matrix.Translation((x, y, -0.0132)), mat=mt)
    for x in (-0.15, 0.15):
        for y in (-0.105, 0.105):
            lp.cyl(m @ Vector((x, y, -0.0125)), m @ Vector((x, y, -0.016)), 0.006, mat=4, seg=12)
    hinge = Matrix.Translation((0, 0.12, 0.0125)) @ Matrix.Rotation(math.radians(-105), 4, "X")
    lp.box((0.34, 0.23, 0.008), m @ hinge @ Matrix.Translation((0, -0.115, 0.004)), bevel=0.002)
    wall = radial * (A2 - 0.01) + Vector((0, 0, 1.0))
    lp.cyl(m @ Vector((0, 0, -0.0125)), wall, 0.015, mat=1)
    for x, y in ((-0.1, -0.05), (0.1, -0.05)):
        lp.box((0.045, 0.03, 0.0015), m @ hinge @ Matrix.Translation((x, y - 0.115, 0.0085)), mat=2)
    for j in range(8):
        lp.box((0.004, 0.06, 0.0012), m @ Matrix.Translation((-0.1 + j * 0.008, 0.0, -0.0128)), mat=4)
    lp.build()
    sc = Part("Laptop_Screen", "Emissive", ["M_ScreenEmit"])
    mm = m @ hinge @ Matrix.Translation((0, -0.115, -0.0005))
    sc.face([mm @ Vector((x, y, 0.0)) for x, y in ((-0.155, -0.1), (-0.155, 0.1), (0.155, 0.1), (0.155, -0.1))])
    sc.build()


def build_cameras():
    cam = Part("PhotoCameras", "PhotoCameras", ["M_CameraBody", "M_LensMetal"])
    for k, back, lens_len, lens_r in ((1, 0.42, 0.30, 0.05), (4, 0.30, 0.10, 0.035)):
        f = facet_frame(k)
        n = f[3]
        p = fmap(f, (0.05, SIDE_MARGIN_TOP + 0.55 * SIDE_H), -back)
        m = place(p, n, f[1])
        cam.box((0.15, 0.11, 0.09), m, bevel=0.01)
        cam.box((0.06, 0.05, 0.05), m @ Matrix.Translation((0.0, 0.07, -0.01)), bevel=0.008)
        cam.cyl(m @ Vector((0, 0, 0.04)), m @ Vector((0, 0, 0.04 + lens_len)), lens_r, mat=1, seg=24)
        cam.cyl(m @ Vector((0, 0, 0.04 + lens_len)), m @ Vector((0, 0, 0.06 + lens_len)),
                lens_r * 1.15, mat=1, seg=24)
        # Rubber zoom/focus grips with ribs, a mount ring.
        for z0, z1 in ((0.3, 0.45), (0.6, 0.78)):
            for j in range(6):
                za = 0.04 + lens_len * (z0 + (z1 - z0) * j / 6)
                zb = za + lens_len * (z1 - z0) / 12
                cam.cyl(m @ Vector((0, 0, za)), m @ Vector((0, 0, zb)), lens_r * 1.06, mat=0, seg=24)
        cam.cyl(m @ Vector((0, 0, 0.045)), m @ Vector((0, 0, 0.052)), lens_r * 1.08, mat=1, seg=24)
        if k == 1:
            # Long-lens camera on a bracket to the mullion, as in iss035e010551.
            corner = hex_corners(HEX_APOTHEM, Z_TOP)[1].lerp(hex_corners(A2, Z_FB)[1], 0.6)
            cam.cyl(m @ Vector((0, -0.05, 0.0)), corner, 0.012, mat=1)
    cam.build()


def build_cables():
    tops, bots, bases = hex_corners(HEX_APOTHEM, Z_TOP), hex_corners(A2, Z_FB), hex_corners(A2, Z_BASE)
    cables = [
        ("M_CableBlack", 0.004, [tops[0] + Vector((0, 0, -0.03)), Vector((0.35, 0.3, 1.1)),
                                 Vector((0.5, 0.55, 0.95)), bots[1] + Vector((-0.05, -0.05, -0.2))]),
        ("M_CableBlack", 0.003, [tops[2] + Vector((0, 0, -0.03)), Vector((-0.2, 0.45, 1.15)),
                                 Vector((-0.45, 0.4, 1.0)), bots[2] + Vector((0.05, -0.05, -0.3))]),
        ("M_CableBlue", 0.004, [Vector((-0.42, -0.2, 1.05)), Vector((-0.3, -0.35, 1.18)),
                                Vector((-0.1, -0.5, 1.2)), tops[4] + Vector((0, 0.03, -0.04))]),
        ("M_CableWhite", 0.003, [bots[3] + Vector((0.04, 0.0, -0.1)), Vector((-0.55, 0.0, 0.85)),
                                 Vector((-0.45, -0.15, 1.0)), Vector((-0.42, -0.2, 1.03))]),
        ("M_CableBlack", 0.005, [bases[5] + Vector((-0.05, 0.05, 0.2)), Vector((0.3, -0.6, 0.7)),
                                 Vector((0.55, -0.35, 0.95)), bots[0] + Vector((-0.05, 0.0, -0.15))]),
        ("M_CableBlack", 0.003, [tops[5] + Vector((0, 0, -0.02)), Vector((0.25, -0.25, 1.25)),
                                 Vector((0.15, -0.1, 1.28)), Vector((0.0, -0.4, 1.3))]),
    ]
    for i, (mat, r, pts) in enumerate(cables):
        cu = bpy.data.curves.new("Cable_%d_curve" % i, "CURVE")
        cu.dimensions = "3D"
        cu.bevel_depth = r
        cu.bevel_resolution = 2
        cu.resolution_u = 12
        sp = cu.splines.new("BEZIER")
        sp.bezier_points.add(len(pts) - 1)
        for bp, p in zip(sp.bezier_points, pts):
            bp.co = p
            bp.handle_left_type = bp.handle_right_type = "AUTO"
        tmp = bpy.data.objects.new("tmp_cable", cu)
        bpy.context.scene.collection.objects.link(tmp)
        bpy.context.view_layer.update()
        me = bpy.data.meshes.new_from_object(tmp.evaluated_get(bpy.context.evaluated_depsgraph_get()))
        bpy.data.objects.remove(tmp)
        bpy.data.curves.remove(cu)
        me.name = "Cable_%d" % i
        me.materials.clear()
        me.materials.append(MATS[mat])
        ob = bpy.data.objects.new("Cable_%d" % i, me)
        col("Cables").objects.link(ob)


def build_clutter():
    cl = Part("Clutter", "Clutter", ["M_Fabric", "M_PanelGrey", "M_LaptopBody", "M_PatchWhite", "M_Webbing",
                                     "M_Velcro", "M_Connector", "M_Plastic"])
    # Canvas pouches clipped to the top ring, as in iss035e010551, iss038e013587 and iss050e033912:
    # lumpy body, darker webbing strap, Velcro flap and a seam lip.
    for az, rr, sz, roll in ((75.0, 0.47, (0.2, 0.13, 0.09), 10.0), (100.0, 0.50, (0.17, 0.13, 0.09), -15.0),
                             (55.0, 0.52, (0.12, 0.1, 0.07), 30.0)):
        a = math.radians(az)
        p = Vector((rr * math.cos(a), rr * math.sin(a), Z_TOP - TOP_RING_H - 0.05))
        m = place(p, (0, 0, 1), (math.cos(a + math.radians(roll)), math.sin(a + math.radians(roll)), 0))
        cl.soft_box(sz, m, mat=0, lump=0.007)
        cl.box((0.018, sz[1] + 0.008, sz[2] + 0.008), m @ Matrix.Translation((sz[0] * 0.2, 0, 0)), mat=4)
        cl.box((sz[0] * 0.5, sz[1] * 0.3, 0.003), m @ Matrix.Translation((0, 0, -sz[2] / 2 - 0.002)), mat=5)
        cl.box((sz[0] + 0.006, 0.006, 0.01), m @ Matrix.Translation((0, sz[1] / 2, sz[2] / 2 - 0.01)), mat=0,
               bevel=0.002)
    # Equipment boxes on the skirt walls (fixed list, no randomness).
    boxes = [(0, -0.25, 0.45, (0.30, 0.18, 0.08), 1), (0, 0.22, 0.62, (0.18, 0.12, 0.06), 2),
             (1, 0.0, 0.50, (0.40, 0.25, 0.10), 2), (2, -0.2, 0.70, (0.22, 0.16, 0.07), 1),
             (2, 0.25, 0.42, (0.16, 0.30, 0.09), 2), (3, 0.0, 0.55, (0.50, 0.30, 0.16), 2),
             (4, 0.1, 0.65, (0.25, 0.20, 0.08), 1), (5, -0.2, 0.48, (0.28, 0.14, 0.07), 2),
             (5, 0.25, 0.70, (0.14, 0.14, 0.05), 1)]
    for k, t, z, sz, mat in boxes:
        a = math.radians(60.0 * k)
        radial = Vector((math.cos(a), math.sin(a), 0.0))
        tang = Vector((-math.sin(a), math.cos(a), 0.0))
        p = radial * (A2 - sz[2] / 2) + tang * t + Vector((0, 0, z))
        m = place(p, -radial, tang)
        cl.box(sz, m, mat=mat, bevel=0.006)
        # Connectors with backshells on the front face, a handle, corner screws.
        front = sz[2] / 2
        for j in range(3 if sz[0] > 0.2 else 2):
            x = (j - 1) * 0.06 if sz[0] > 0.2 else (j - 0.5) * 0.05
            y = sz[1] * 0.3
            cl.cyl(m @ Vector((x, y, front)), m @ Vector((x, y, front + 0.012)), 0.011, mat=6, seg=16)
            cl.cyl(m @ Vector((x, y, front + 0.012)), m @ Vector((x, y, front + 0.035)), 0.008, mat=7, seg=12)
        for sx in (-1, 1):
            cl.cyl(m @ Vector((sx * sz[0] * 0.38, -sz[1] * 0.3, front)),
                   m @ Vector((sx * sz[0] * 0.38, -sz[1] * 0.3, front + 0.025)), 0.004, mat=6, seg=8)
            for sy in (-1, 1):
                q = Vector((sx * (sz[0] / 2 - 0.012), sy * (sz[1] / 2 - 0.012), front))
                cl.cyl(m @ q, m @ (q + Vector((0, 0, 0.002))), 0.004, mat=6, seg=8)
        cl.cyl(m @ Vector((-sz[0] * 0.38, -sz[1] * 0.3, front + 0.025)),
               m @ Vector((sz[0] * 0.38, -sz[1] * 0.3, front + 0.025)), 0.004, mat=6, seg=8)
    # Plain white Velcro patches on the mullion bands (no text).
    for k in range(6):
        f = facet_frame(k)
        for q in ((-0.28, 0.035), (0.30, 0.52)):
            m = place(fmap(f, q, -0.002), f[3], f[1])
            cl.box((0.04, 0.025, 0.002), m, mat=3)
    cl.build()
    led = Part("LEDs", "Emissive", ["M_LedGreen", "M_LedAmber"])
    for k, t, z, mat in ((0, -0.3, 0.45, 0), (3, 0.1, 0.6, 1), (3, 0.15, 0.6, 0), (5, -0.25, 0.5, 1)):
        a = math.radians(60.0 * k)
        radial = Vector((math.cos(a), math.sin(a), 0.0))
        tang = Vector((-math.sin(a), math.cos(a), 0.0))
        p = radial * (A2 - 0.17) + tang * t + Vector((0, 0, z))
        led.box((0.006, 0.006, 0.004), place(p, -radial, tang), mat=mat)
    led.build()


def build_mullion_detail(rng):
    """Mullion rails with cap screws, orange window tags, Velcro and label-like plain patches,
    shutter crank knobs, small brackets, top-plate fittings (iss050e033912, iss038e013587)."""
    hw = Part("Hardware", "Hardware", ["M_FrameBlack", "M_Bolt", "M_Tag", "M_Velcro", "M_VelcroBlack",
                                       "M_PatchWhite", "M_Plastic", "M_Connector"])
    corners = ccw(side_corners())
    m, h = SIDE_MARGIN_TOP, SIDE_H
    w0, w1 = HEX_APOTHEM / SQ3, A2 / SQ3
    for k in range(6):
        f = facet_frame(k)
        o, u, v, n = f
        for sx in (-1, 1):
            pt, pb = Vector((sx * SIDE_SHORT / 2, m)), Vector((sx * SIDE_LONG / 2, m + h))
            d = (pb - pt).normalized()
            out = Vector((d.y, -d.x)) if d.y * sx > 0 else Vector((-d.y, d.x))
            a2, b2 = pt + out * 0.062 + d * 0.02, pb + out * 0.062 - d * 0.02
            ln = (b2 - a2).length
            along = u * d.x + v * d.y
            hw.box((ln, 0.016, 0.005), place(fmap(f, (a2 + b2) / 2, -0.0025), n, along), mat=0)
            cnt = int(ln / 0.05)
            for j in range(cnt):
                q = a2.lerp(b2, (j + 0.5) / cnt)
                hw.cyl(fmap(f, q, -0.005), fmap(f, q, -0.0075), 0.0035, mat=1, seg=8)
        for _ in range(2):
            e = rng.randrange(4)
            a, b = Vector(corners[e]), Vector(corners[(e + 1) % 4])
            t = (b - a).normalized()
            q = a.lerp(b, rng.uniform(0.25, 0.75)) + Vector((t.y, -t.x)) * 0.02
            hw.box((0.012, 0.03, 0.0006), place(fmap(f, q, -0.0008), n, u * t.x + v * t.y), mat=2)
        for _ in range(rng.randint(2, 4)):
            if rng.random() < 0.4:
                y = rng.uniform(0.015, m - 0.015)
            else:
                y = rng.uniform(m + h + 0.02, FACET_LEN - 0.02)
            wf = w0 + (w1 - w0) * y / FACET_LEN - 0.05
            q = (rng.uniform(-wf, wf), y)
            size = (rng.uniform(0.03, 0.07), rng.uniform(0.016, 0.028), 0.0015)
            hw.box(size, place(fmap(f, q, -0.001), n, u), mat=rng.choice((3, 3, 4, 5, 5)))
        # Shutter crank knob on the lower band: base, dial, centre boss, index mark.
        q = (rng.choice((-1, 1)) * rng.uniform(0.12, 0.22), m + h + 0.068)
        hw.cyl(fmap(f, q, 0.0), fmap(f, q, -0.006), 0.03, mat=6, seg=24)
        hw.cyl(fmap(f, q, -0.006), fmap(f, q, -0.024), 0.025, mat=6, seg=24)
        hw.cyl(fmap(f, q, -0.024), fmap(f, q, -0.028), 0.008, mat=7, seg=12)
        hw.box((0.004, 0.018, 0.001), place(fmap(f, (q[0], q[1] - 0.012), -0.0245), n, u), mat=5)
        # Small L bracket with a screw on the upper band.
        q = (rng.uniform(-0.12, 0.12), m * 0.5)
        mb = place(fmap(f, q, -0.0015), n, u)
        hw.box((0.035, 0.022, 0.003), mb, mat=7, bevel=0.0008)
        hw.box((0.035, 0.003, 0.02), mb @ Matrix.Translation((0, 0.0095, -0.01)), mat=7, bevel=0.0008)
        hw.cyl(mb @ Vector((0, -0.004, -0.0015)), mb @ Vector((0, -0.004, -0.0045)), 0.004, mat=1, seg=8)
    # Top plate: round fittings at the hex corners, Velcro on the plate edges.
    rz = Z_TOP - TOP_RING_H
    for i in range(6):
        a = math.radians(30.0 + 60.0 * i)
        p = Vector((0.53 * math.cos(a), 0.53 * math.sin(a), Z_TOP))
        hw.cyl(p, p - Vector((0, 0, 0.006)), 0.016, mat=6, seg=16)
        hw.cyl(p - Vector((0, 0, 0.006)), p - Vector((0, 0, 0.022)), 0.01, mat=7, seg=16)
        a = math.radians(60.0 * i + rng.uniform(-18.0, 18.0))
        p = Vector((0.495 * math.cos(a), 0.495 * math.sin(a), Z_TOP - 0.001))
        hw.box((0.018, rng.uniform(0.03, 0.05), 0.0015), place(p, (0, 0, -1), (math.cos(a), math.sin(a), 0)),
               mat=rng.choice((3, 5)))
    for i in range(4):
        a = math.radians(rng.uniform(0.0, 360.0))
        p = Vector((0.455 * math.cos(a), 0.455 * math.sin(a), rz - 0.001))
        hw.box((0.012, 0.03, 0.0006), place(p, (0, 0, -1), (math.cos(a), math.sin(a), 0)), mat=2)
    hw.build()


def wall_point(k, t, z, d):
    """Point on skirt wall k (centred on azimuth 60k deg) at tangential t, height z, d off the wall."""
    a = math.radians(60.0 * k)
    return Vector((math.cos(a), math.sin(a), 0.0)) * (A2 - d) + Vector((-math.sin(a), math.cos(a), 0.0)) * t \
        + Vector((0.0, 0.0, z))


def build_walls(rng):
    """Dark closeout panels with fasteners, Velcro and plain patches, wall bags, a flex hose."""
    wp = Part("WallPanels", "Shell", ["M_PanelGrey", "M_ShellGrey", "M_Bolt", "M_Velcro", "M_VelcroBlack",
                                      "M_PatchWhite", "M_Plastic", "M_Fabric", "M_FabricBeige", "M_Webbing"])
    hwid = A2 / SQ3
    for k in range(6):
        a = math.radians(60.0 * k)
        R, T = Vector((math.cos(a), math.sin(a), 0.0)), Vector((-math.sin(a), math.cos(a), 0.0))
        s, zs = rng.uniform(-0.1, 0.1), rng.uniform(Z_BASE + 0.2, Z_FB - 0.2)
        for ta, tb in ((-hwid + 0.035, s - 0.004), (s + 0.004, hwid - 0.035)):
            for za, zb in ((Z_BASE + 0.02, zs - 0.004), (zs + 0.004, Z_FB - 0.02)):
                tc, zc = (ta + tb) / 2, (za + zb) / 2
                wp.box((tb - ta, zb - za, 0.008), place(wall_point(k, tc, zc, 0.004), -R, T),
                       mat=rng.choice((0, 0, 1)))
                for t in (ta + 0.015, tb - 0.015):
                    for z in (za + 0.015, zb - 0.015):
                        wp.cyl(wall_point(k, t, z, 0.008), wall_point(k, t, z, 0.011), 0.0045, mat=2, seg=8)
        for _ in range(rng.randint(3, 6)):
            p = wall_point(k, rng.uniform(-hwid + 0.08, hwid - 0.08), rng.uniform(Z_BASE + 0.06, Z_FB - 0.06),
                           0.0088)
            size = (rng.uniform(0.03, 0.09), rng.uniform(0.018, 0.035), 0.0015)
            wp.box(size, place(p, -R, T), mat=rng.choice((3, 3, 4, 5, 5)))
    for k, t, z, size, mat in ((1, 0.0, 0.75, (0.26, 0.11, 0.07), 8), (0, 0.15, 0.42, (0.2, 0.15, 0.08), 7),
                               (2, -0.2, 0.47, (0.22, 0.15, 0.09), 8), (4, -0.2, 0.45, (0.24, 0.17, 0.08), 7)):
        a = math.radians(60.0 * k)
        R, T = Vector((math.cos(a), math.sin(a), 0.0)), Vector((-math.sin(a), math.cos(a), 0.0))
        mb = place(wall_point(k, t, z, size[2] / 2 + 0.008), -R, T)
        wp.soft_box(size, mb, mat=mat, lump=0.006)
        wp.box((0.022, size[1] + 0.01, size[2] + 0.01), mb @ Matrix.Translation((-size[0] * 0.25, 0, 0)), mat=9)
        wp.box((0.022, size[1] + 0.01, size[2] + 0.01), mb @ Matrix.Translation((size[0] * 0.25, 0, 0)), mat=9)
    wp.build()
    # Corrugated flex hose from the hatch tunnel up to the large box on wall 3, as in iss035e010551.
    ho = Part("Hose", "Shell", ["M_Hose", "M_Plastic"])
    a = math.radians(205.0)
    pts = [Vector((0.58 * math.cos(a), 0.58 * math.sin(a), 0.0)),
           Vector((0.6 * math.cos(a), 0.6 * math.sin(a), 0.22)),
           wall_point(3, 0.3, 0.38, 0.06), wall_point(3, 0.28, 0.5, 0.05)]
    q, tg = ho.tube(pts, 0.022, seg=12, sub=24, rib=0.08)
    for i in (0, len(q) - 1):
        ho.cyl(q[i] - tg[i] * 0.02, q[i] + tg[i] * 0.02, 0.026, mat=1, seg=16)
    ho.build()


def build_harness():
    """Cable harnesses: two loops around the skirt with P-clamps, bundles down each mullion seam."""
    hz = Part("Harness", "Cables", ["M_CableBlack", "M_CableGrey", "M_CableWhite", "M_CableBlue", "M_Plastic",
                                    "M_Bolt"])
    hwid = A2 / SQ3
    bundle = [(0.0, 0.0, 0.0055, 0), (0.011, 0.004, 0.004, 1), (0.002, 0.011, 0.0045, 2),
              (0.012, -0.007, 0.0035, 0), (0.006, 0.017, 0.003, 3), (0.004, -0.01, 0.0035, 0)]
    for z, d in ((Z_FB - 0.04, 0.025), (Z_BASE + 0.06, 0.025)):
        for dd, dz, r, mat in bundle:
            pts = [wall_point(k, t, z + dz + 0.003 * math.sin(1.7 * k + 9.0 * t), d + dd)
                   for k in range(6) for t in (-hwid + 0.07, 0.0, hwid - 0.07)]
            hz.tube(pts, r, mat=mat, seg=8, closed=True, sub=4)
        for k in range(6):
            a = math.radians(60.0 * k)
            R, T = Vector((math.cos(a), math.sin(a), 0.0)), Vector((-math.sin(a), math.cos(a), 0.0))
            for t in (-0.25, 0.0, 0.25):
                hz.box((0.034, 0.036, 0.012), place(wall_point(k, t, z + 0.005, d + 0.007), T, -R), mat=4,
                       bevel=0.003)
                hz.cyl(wall_point(k, t, z + 0.005, 0.0), wall_point(k, t, z + 0.005, d - 0.008), 0.003, mat=5,
                       seg=8)
    tops, bots, bases = hex_corners(HEX_APOTHEM, Z_TOP), hex_corners(A2, Z_FB), hex_corners(A2, Z_BASE)
    for k in range(6):
        n_in = -(facet_frame(k)[3] + facet_frame((k + 1) % 6)[3]).normalized()
        s = (bots[k] - tops[k]).normalized().cross(n_in).normalized()
        rin = -Vector((bases[k].x, bases[k].y, 0.0)).normalized()
        for i, (r, mat) in enumerate(((0.005, 0), (0.004, 1), (0.0035, 0), (0.003, 2 + (k % 2)))):
            so = 0.032 + 0.009 * i
            off = s * so + n_in * (0.6 * so + 0.008 + 0.003 * (i % 2))
            offb = s * so + rin * (0.6 * so + 0.008 + 0.003 * (i % 2))
            pts = [tops[k].lerp(bots[k], f) + off for f in (0.03, 0.35, 0.7, 0.97)]
            pts += [bots[k] - Vector((0, 0, 0.1)) + offb, bases[k] + Vector((0, 0, Z_FB - Z_BASE - 0.3)) + offb,
                    bases[k] + Vector((0, 0, 0.09)) + offb]
            hz.tube(pts, r, mat=mat, seg=8, sub=6)
    hz.build()


def look_matrix(eye, target, right_hint, roll_deg):
    f = (Vector(target) - Vector(eye)).normalized()
    r = (Vector(right_hint) - f * Vector(right_hint).dot(f)).normalized()
    up = r.cross(f)
    m = Matrix((r, up, -f)).transposed()
    m = m @ Matrix.Rotation(math.radians(roll_deg), 3, "Z")
    m4 = m.to_4x4()
    m4.translation = Vector(eye)
    return m4


def build_lights_and_view():
    # Node 3 general luminaires shine up through the hatch (the Cupola has no ceiling lights): a disk
    # at the hatch plane, so the tunnel rim cuts the light and the dome gets a soft falloff.
    li = bpy.data.lights.new("L_Cabin", "AREA")
    li.energy = 60.0
    li.shape = "DISK"
    li.size = 1.0
    li.color = (1.0, 0.92, 0.82)
    ob = bpy.data.objects.new("L_Cabin", li)
    ob.location = (0.0, 0.0, -0.05)
    ob.rotation_euler = (math.pi, 0.0, 0.0)
    col("Lights_Cabin").objects.link(ob)
    cd = bpy.data.cameras.new("Cam_Rest")
    cd.lens = REST_LENS_MM
    cd.sensor_width = 36.0
    cd.clip_start = 0.01
    cam = bpy.data.objects.new("Cam_Rest", cd)
    c = math.radians(30.0)
    target = (REST_TARGET_R * math.cos(c), REST_TARGET_R * math.sin(c), Z_TOP + TOP_DEPTH)
    cam.matrix_world = look_matrix(EYE, target, (-math.cos(c), -math.sin(c), 0.0), REST_ROLL_DEG)
    col("View").objects.link(cam)
    bpy.context.scene.camera = cam


def main():
    out, top = parse_args()
    build(top)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=out, compress=False)
    print("CUPOLA_BUILD_OK", out, "objects=%d" % len(bpy.data.objects),
          "tilt_deg=%.3f" % math.degrees(FACET_TILT), "z_top=%.4f" % Z_TOP)


main()
