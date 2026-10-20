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
        mul2 = N("ShaderNodeMath")
        mul2.operation = "MULTIPLY"
        L(mul.outputs["Value"], mul2.inputs[0])
        L(remap(noise_fac(2.5, 2.0), 0.35, 0.65, 0.15, 1.0), mul2.inputs[1])
        mask = mul2.outputs["Value"]
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

    def cyl(self, p0, p1, r, mat=0, seg=16, r2=None, rot=0.0):
        p0, p1 = Vector(p0), Vector(p1)
        axis = p1 - p0
        m = Matrix.Translation((p0 + p1) / 2) @ axis.to_track_quat("Z", "Y").to_matrix().to_4x4() \
            @ Matrix.Rotation(rot, 4, "Z")
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

    def _mark_old(self):
        # BMesh ops reallocate vertex storage, so identity sets of BMVert go stale, and ops may use
        # the TAG flag as scratch; an int layer survives (new verts start at 0, removed in build()).
        lay = self.bm.verts.layers.int.get("old") or self.bm.verts.layers.int.new("old")
        for v in self.bm.verts:
            v[lay] = 1
        return lay

    def soft_box(self, size, matrix, mat=0, lump=0.006):
        """Bevelled box with a smooth deterministic lumpy displacement (soft fabric bag)."""
        lay = self._mark_old()
        self.box(size, matrix, mat=mat, bevel=min(size) * 0.3)
        c = matrix.translation
        for v in self.bm.verts:
            if not v[lay]:
                p = v.co
                d = math.sin(37.0 * p.x + 1.1) * math.sin(41.0 * p.y + 0.5) * math.sin(29.0 * p.z + 0.3)
                v.co = p + (p - c).normalized() * (lump * d)

    def bag(self, size, matrix, rng, mat=0, seam=None, cuts=7):
        """Cloth bag: subdivided box pushed onto a rounded superellipsoid, then seeded creases
        (sharp sine valleys in random directions) and a sag; optional piped seam round the top."""
        lay = self._mark_old()
        r = bmesh.ops.create_cube(self.bm, size=1.0, matrix=Matrix.Diagonal((*size, 1.0)))
        edges = list({e for v in r["verts"] for e in v.link_edges})
        bmesh.ops.subdivide_edges(self.bm, edges=edges, cuts=cuts, use_grid_fill=True)
        new = [v for v in self.bm.verts if not v[lay]]
        hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
        folds = [(Vector((rng.uniform(-1, 1), rng.uniform(-1, 1), rng.uniform(-1, 1))).normalized(),
                  rng.uniform(25.0, 60.0), rng.uniform(0, 6.28), rng.uniform(0.002, 0.005)) for _ in range(4)]
        sag = rng.uniform(-0.25, 0.25)
        for v in new:
            u = Vector((v.co.x / hx, v.co.y / hy, v.co.z / hz))
            ln = (abs(u.x) ** 4 + abs(u.y) ** 4 + abs(u.z) ** 4) ** 0.25
            p = Vector((u.x / ln * hx, u.y / ln * hy, u.z / ln * hz))
            nrm = Vector((p.x / hx ** 2, p.y / hy ** 2, p.z / hz ** 2)).normalized()
            d = sum(a * (0.6 - abs(math.sin(f * dv.dot(p) + ph))) for dv, f, ph, a in folds)
            p.z += sag * hz * (1.0 - (p.x / hx) ** 2) * (p.z / hz)
            v.co = p + nrm * d
        bmesh.ops.transform(self.bm, matrix=matrix, verts=new)
        for f in {f for v in new for f in v.link_faces}:
            f.material_index = mat
            f.smooth = True
        if seam is not None:
            k = 0.86
            pts = [matrix @ Vector((x * hx * k, y * hy * k, hz * 0.97)) for x, y in
                   ((-1, -1), (0, -1.08), (1, -1), (1.08, 0), (1, 1), (0, 1.08), (-1, 1), (-1.08, 0))]
            self.tube(pts, 0.0022, mat=seam, seg=6, closed=True, sub=6)

    def patch(self, w, h, matrix, mat, rng, thick=0.0015):
        """Irregular rounded patch (Velcro, label-like blank): jittered superellipse, extruded."""
        n = 20
        out = []
        for i in range(n):
            a = 2 * math.pi * i / n
            c, s_ = math.cos(a), math.sin(a)
            f = 1.0 + rng.uniform(-0.07, 0.07)
            out.append((math.copysign(abs(c) ** 0.35, c) * w / 2 * f, math.copysign(abs(s_) ** 0.35, s_) * h / 2 * f))
        top = [matrix @ Vector((x, y, -thick / 2)) for x, y in out]
        bot = [matrix @ Vector((x, y, thick / 2)) for x, y in out]
        self.face(top, mat)
        self.face(list(reversed(bot)), mat)
        self.strip(bot, top, mat)

    def build(self, props=None):
        me = bpy.data.meshes.new(self.name)
        lay = self.bm.verts.layers.int.get("old")
        if lay is not None:
            self.bm.verts.layers.int.remove(lay)
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
    principled("M_Hose", (0.55, 0.55, 0.52), rough=0.6, cvar=0.1, bump=(300.0, 0.1, 3.0))
    principled("M_BoltBlack", (0.04, 0.04, 0.042), metal=1.0, rough=0.42, bevel=0.0008)
    principled("M_PatchYellow", (0.7, 0.64, 0.4), rough=0.85, cvar=0.1, bevel=0.0008)
    principled("M_PatchGrey", (0.32, 0.32, 0.31), rough=0.8, cvar=0.1, bevel=0.0008)
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

    # Bolts: top ring and around each side window. Seeded variation: hex rotation and height, socket
    # caps, black-oxide heads, empty holes (iss050e033912 shows mixed fasteners).
    bo = Part("Bolts", "Bolts", ["M_Bolt", "M_BoltBlack"])
    rb = random.Random(DETAIL_SEED + 1)

    def bolt(p, out, r, h):
        out = Vector(out).normalized()
        x = rb.random()
        if x < 0.04:
            bo.cyl(p, p + out * 0.0006, r * 0.55, mat=1, seg=10)
            return
        bo.cyl(p, p + out * 0.0015, r * 1.45, seg=12)
        if x < 0.16:
            bo.cyl(p, p + out * h * 1.25, r * 0.8, mat=1, seg=14)
        else:
            bo.cyl(p, p + out * h * rb.uniform(0.8, 1.2), r, mat=1 if x < 0.28 else 0, seg=6,
                   rot=rb.uniform(0.0, math.pi / 3))

    down = Vector((0, 0, -1))
    for x, y in circle(TOP_BOLT_R, TOP_BOLTS):
        bolt(Vector((x, y, rz)), down, 0.007, 0.008)
    for x, y in circle(0.39, 60, phase=0.03):
        bolt(Vector((x, y, rz)), down, 0.0045, 0.004)
    hx = hex_corners(HEX_APOTHEM - 0.025, Z_TOP)
    for i in range(6):
        for j in range(1, 6):
            bolt(hx[i].lerp(hx[(i + 1) % 6], j / 6.0), down, 0.005, 0.005)
    sc = ccw(offset_corners(corners, SIDE_BOLT_OFFSET))
    pts2 = []
    for i in range(4):
        a, b = Vector(sc[i]), Vector(sc[(i + 1) % 4])
        cnt = max(2, int((b - a).length / SIDE_BOLT_STEP))
        pts2 += [a.lerp(b, (j + 0.5) / cnt) for j in range(cnt)]
    for k in range(6):
        f = facet_frame(k)
        for q in pts2:
            bolt(fmap(f, q, 0.0), -f[3], 0.006, 0.008)
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
    rng = random.Random(DETAIL_SEED)
    build_clutter(rng)
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
    lp = Part("Laptop", "Laptop", ["M_LaptopBody", "M_PanelGrey", "M_VelcroBlack", "M_PatchWhite", "M_Plastic",
                                   "M_Bolt"])
    lp.box((0.34, 0.24, 0.025), m, bevel=0.004)
    # Underside: Velcro pads, a plain label patch, rubber feet; lid back: Velcro and a vent grille.
    for x, y, sz, mt in ((-0.12, -0.08, (0.05, 0.03), 2), (0.12, -0.08, (0.05, 0.03), 2), (0.0, 0.06, (0.07, 0.035), 3)):
        lp.box((*sz, 0.0015), m @ Matrix.Translation((x, y, -0.0132)), mat=mt)
    for x in (-0.15, 0.15):
        for y in (-0.105, 0.105):
            lp.cyl(m @ Vector((x, y, -0.0125)), m @ Vector((x, y, -0.016)), 0.006, mat=4, seg=12)
    hinge = Matrix.Translation((0, 0.12, 0.0125)) @ Matrix.Rotation(math.radians(-105), 4, "X")
    lp.box((0.34, 0.23, 0.008), m @ hinge @ Matrix.Translation((0, -0.115, 0.004)), bevel=0.002)
    # Articulated arm: wall plate on skirt wall 3, three links, friction knuckles with lock knobs.
    mount = wall_point(3, A2 * math.tan(math.radians(20.0)), 0.78, 0.0)
    inward = Vector((1.0, 0.0, 0.0))
    lp.box((0.08, 0.13, 0.012), place(mount + inward * 0.006, inward, (0, 0, 1)), mat=1, bevel=0.003)
    j0 = mount + inward * 0.06
    j3 = m @ Vector((0, 0, -0.0125))
    j2 = m @ Vector((0, -0.02, -0.09))
    j1 = j0.lerp(j2, 0.5) + Vector((0.0, 0.0, 0.14))
    lp.cyl(mount, j0, 0.016, mat=5, seg=16)
    chain = [j0, j1, j2, j3]
    for a, b in zip(chain, chain[1:]):
        lp.cyl(a, b, 0.013, mat=1, seg=16)
    for i in (0, 1, 2):
        d1 = (chain[i + 1] - chain[i]).normalized()
        d0 = (chain[i] - chain[i - 1]).normalized() if i else inward
        ax = d0.cross(d1)
        ax = ax.normalized() if ax.length > 1e-6 else d1.orthogonal().normalized()
        lp.cyl(chain[i] - ax * 0.026, chain[i] + ax * 0.026, 0.021, mat=4, seg=20)
        lp.cyl(chain[i] + ax * 0.026, chain[i] + ax * 0.042, 0.011, mat=5, seg=12)
    lp.box((0.12, 0.08, 0.01), m @ Matrix.Translation((0, -0.02, -0.0175)), mat=1, bevel=0.002)
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
    """DSLR-like bodies (grip, prism, hood, ribbed grips) on brackets clamped to the seam handrails."""
    cam = Part("PhotoCameras", "PhotoCameras", ["M_CameraBody", "M_LensMetal", "M_Bolt", "M_Plastic"])
    tops, bots = hex_corners(HEX_APOTHEM, Z_TOP), hex_corners(A2, Z_FB)
    for k, back, lens_len, lens_r, y in ((1, 0.42, 0.30, 0.05, 0.55), (4, 0.30, 0.10, 0.035, 0.55),
                                         (3, 0.36, 0.22, 0.045, 0.35)):
        f = facet_frame(k)
        n = f[3]
        p = fmap(f, (0.05, SIDE_MARGIN_TOP + y * SIDE_H), -back)
        m = place(p, n, f[1])
        cam.box((0.15, 0.11, 0.09), m, bevel=0.012)
        cam.box((0.045, 0.115, 0.1), m @ Matrix.Translation((0.085, 0.0, -0.005)), bevel=0.015)
        cam.box((0.06, 0.05, 0.05), m @ Matrix.Translation((0.0, 0.07, -0.01)), bevel=0.012)
        cam.box((0.11, 0.004, 0.06), m @ Matrix.Translation((0.0, -0.057, -0.015)), mat=3, bevel=0.001)
        for sx in (-1, 1):
            cam.cyl(m @ Vector((sx * 0.078, 0.04, 0.0)), m @ Vector((sx * 0.086, 0.04, 0.0)), 0.006, mat=2, seg=8)
        cam.cyl(m @ Vector((0, 0, 0.04)), m @ Vector((0, 0, 0.04 + lens_len)), lens_r, mat=1, seg=32)
        cam.cyl(m @ Vector((0, 0, 0.045)), m @ Vector((0, 0, 0.052)), lens_r * 1.08, mat=2, seg=32)
        for z0, z1 in ((0.3, 0.45), (0.6, 0.78)):
            for j in range(6):
                za = 0.04 + lens_len * (z0 + (z1 - z0) * j / 6)
                cam.cyl(m @ Vector((0, 0, za)), m @ Vector((0, 0, za + lens_len * (z1 - z0) / 12)),
                        lens_r * 1.06, mat=3, seg=32)
        e = 0.04 + lens_len
        cam.cyl(m @ Vector((0, 0, e)), m @ Vector((0, 0, e + 0.04 + lens_len * 0.12)), lens_r * 1.12, mat=0, seg=32,
                r2=lens_r * 1.45)
        # Bracket: tripod plate, two links, clamp on the seam handrail.
        seam = tops[k].lerp(bots[k], 0.5)
        n_in = -(facet_frame(k)[3] + facet_frame((k + 1) % 6)[3]).normalized()
        rail = seam + n_in * 0.05
        base = m @ Vector((0, -0.06, 0.0))
        cam.box((0.06, 0.012, 0.05), m @ Matrix.Translation((0, -0.061, 0.0)), mat=3, bevel=0.002)
        mid = base.lerp(rail, 0.5) + n_in * 0.06
        cam.cyl(base, mid, 0.01, mat=2, seg=12)
        cam.cyl(mid, rail, 0.01, mat=2, seg=12)
        cam.cyl(mid - n * 0.02, mid + n * 0.02, 0.017, mat=3, seg=16)
        d = (bots[k] - tops[k]).normalized()
        cam.cyl(rail - d * 0.025, rail + d * 0.025, 0.02, mat=3, seg=16)
        cam.cyl(rail + n_in * 0.02, rail + n_in * 0.045, 0.008, mat=2, seg=10)
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


def build_clutter(rng):
    cl = Part("Clutter", "Clutter", ["M_Fabric", "M_PanelGrey", "M_LaptopBody", "M_PatchWhite", "M_Webbing",
                                     "M_Velcro", "M_Connector", "M_Plastic", "M_FabricBeige", "M_CableBlack",
                                     "M_CableGrey", "M_CableWhite", "M_CableBlue", "M_Bolt"])
    # Canvas pouches clipped to the top ring (iss035e010551, iss038e013587, iss050e033912): creased cloth,
    # piped seam, webbing strap, Velcro tab.
    for az, rr, sz, roll in ((75.0, 0.47, (0.22, 0.14, 0.09), 10.0), (100.0, 0.50, (0.19, 0.14, 0.09), -15.0),
                             (55.0, 0.52, (0.13, 0.11, 0.07), 30.0)):
        a = math.radians(az)
        p = Vector((rr * math.cos(a), rr * math.sin(a), Z_TOP - TOP_RING_H - 0.05))
        m = place(p, (0, 0, 1), (math.cos(a + math.radians(roll)), math.sin(a + math.radians(roll)), 0))
        cl.bag(sz, m, rng, mat=0, seam=4)
        cl.box((0.018, sz[1] + 0.006, sz[2] + 0.006), m @ Matrix.Translation((sz[0] * 0.22, 0, 0)), mat=4)
        cl.patch(sz[0] * 0.35, sz[1] * 0.25, m @ Matrix.Translation((-sz[0] * 0.1, 0, -sz[2] / 2 - 0.002)), 5, rng)
    # Cables floating across the windows between the mullions, with connectors at both ends.
    mats = (9, 9, 10, 11, 12)
    for k in range(6):
        if rng.random() < 0.35:
            continue
        f = facet_frame(k)
        for _ in range(rng.randint(1, 2)):
            sx = rng.choice((-1, 1))
            ya, yb = rng.uniform(0.12, 0.45), rng.uniform(0.12, 0.45)
            pts = [fmap(f, (-sx * 0.34, ya), -0.012),
                   fmap(f, (-sx * rng.uniform(0.05, 0.15), rng.uniform(0.1, 0.45)), -rng.uniform(0.05, 0.11)),
                   fmap(f, (sx * rng.uniform(0.05, 0.18), rng.uniform(0.1, 0.45)), -rng.uniform(0.06, 0.13)),
                   fmap(f, (sx * 0.34, yb), -0.012)]
            q, tg = cl.tube(pts, rng.uniform(0.0028, 0.0045), mat=rng.choice(mats), seg=8, sub=10)
            for i, s_ in ((0, 1), (len(q) - 1, -1)):
                cl.cyl(q[i] - tg[i] * s_ * 0.004, q[i] + tg[i] * s_ * 0.022, 0.0075, mat=6, seg=12)
                cl.cyl(q[i] + tg[i] * s_ * 0.022, q[i] + tg[i] * s_ * 0.04, 0.0055, mat=7, seg=12)
    # Stowed items tucked into window corners (iss038e013587, iss073e0025760).
    for k in range(6):
        f = facet_frame(k)
        o, u, v, n = f
        sx = rng.choice((-1, 1))
        q = (sx * rng.uniform(0.24, 0.3), SIDE_MARGIN_TOP + SIDE_H + 0.05)
        kind = rng.randrange(5)
        if kind == 0:
            sz = (rng.uniform(0.07, 0.1), rng.uniform(0.05, 0.07), 0.03)
            cl.bag(sz, place(fmap(f, q, -0.018), n, u), rng, mat=rng.choice((0, 8)), seam=4, cuts=5)
        elif kind == 1:
            a, b = fmap(f, (q[0] - 0.06, q[1]), -0.018), fmap(f, (q[0] + 0.06, q[1] - 0.01), -0.018)
            cl.cyl(a, b, 0.014, mat=7, seg=16)
            cl.cyl(b, b + (b - a).normalized() * 0.012, 0.017, mat=13, seg=16)
        elif kind == 2:
            cl.box((0.12, 0.09, 0.008), place(fmap(f, q, -0.006), n, u * math.cos(0.3) + v * math.sin(0.3)), mat=3,
                   bevel=0.001)
            cl.box((0.12, 0.012, 0.0085), place(fmap(f, (q[0], q[1] - 0.04), -0.006), n, u), mat=7)
        elif kind == 3:
            c = fmap(f, q, -0.02)
            ring = [c + (u * math.cos(t) + v * math.sin(t)) * 0.035 + n * (0.006 * math.sin(3 * t))
                    for t in (2 * math.pi * i / 8 for i in range(8))]
            cl.tube(ring, 0.003, mat=rng.choice(mats), seg=6, closed=True, sub=4)
            cl.box((0.01, 0.012, 0.012), place(c + u * 0.035, n, v), mat=7)
        else:
            cl.box((0.05, 0.03, 0.02), place(fmap(f, q, -0.012), n, u), mat=7, bevel=0.003)
            cl.cyl(fmap(f, (q[0] + 0.025, q[1]), -0.012), fmap(f, (q[0] + 0.055, q[1] + 0.02), -0.03), 0.004,
                   mat=9, seg=8)
    cl.build()


def build_mullion_detail(rng):
    """Mullion rails with cap screws, orange window tags, Velcro and label-like plain patches,
    shutter crank knobs, small brackets, top-plate fittings (iss050e033912, iss038e013587)."""
    hw = Part("Hardware", "Hardware", ["M_FrameBlack", "M_Bolt", "M_Tag", "M_Velcro", "M_VelcroBlack",
                                       "M_PatchWhite", "M_Plastic", "M_Connector", "M_PatchYellow", "M_PatchGrey"])
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
            hw.patch(rng.uniform(0.02, 0.08), rng.uniform(0.012, 0.035), place(fmap(f, q, -0.001), n, u),
                     rng.choice((3, 3, 4, 5, 5, 8, 9)), rng)
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
        hw.patch(0.018, rng.uniform(0.02, 0.05), place(p, (0, 0, -1), (math.cos(a), math.sin(a), 0)),
                 rng.choice((3, 5, 8)), rng)
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
    """Skirt walls as equipment racks (iss035e010551): dark backing, perforated rails, stacked avionics
    boxes of mixed depth with connectors, jumper cables, handles and blank patches; hoses; bags; LEDs."""
    wp = Part("WallRacks", "Shell", ["M_PanelGrey", "M_ShellGrey", "M_Bolt", "M_Velcro", "M_VelcroBlack",
                                     "M_PatchWhite", "M_Plastic", "M_Fabric", "M_FabricBeige", "M_Webbing",
                                     "M_Connector", "M_CableBlack", "M_CableGrey", "M_LaptopBody", "M_PatchYellow",
                                     "M_Hose"])
    led = Part("LEDs", "Emissive", ["M_LedGreen", "M_LedAmber"])
    hwid = A2 / SQ3
    leds = 0
    for k in range(6):
        a = math.radians(60.0 * k)
        R, T = Vector((math.cos(a), math.sin(a), 0.0)), Vector((-math.sin(a), math.cos(a), 0.0))
        wp.box((2 * hwid - 0.02, Z_FB - Z_BASE - 0.01, 0.004), place(wall_point(k, 0.0, (Z_BASE + Z_FB) / 2, 0.002),
                                                                     -R, T), mat=1)
        for t in (-hwid + 0.05, hwid - 0.05):
            wp.box((0.022, Z_FB - Z_BASE - 0.03, 0.03), place(wall_point(k, t, (Z_BASE + Z_FB) / 2, 0.019), -R, T),
                   mat=0)
            for j in range(int((Z_FB - Z_BASE - 0.06) / 0.04)):
                z = Z_BASE + 0.04 + 0.04 * j
                wp.cyl(wall_point(k, t, z, 0.034), wall_point(k, t, z, 0.0365), 0.004, mat=2, seg=6,
                       rot=rng.uniform(0, 1.0))
        z = Z_BASE + 0.1
        while z < Z_FB - 0.1:
            h = rng.uniform(0.06, 0.16)
            if z + h > Z_FB - 0.07:
                break
            t0 = -hwid + 0.07
            while t0 < hwid - 0.12:
                w = min(rng.uniform(0.12, 0.38), hwid - 0.07 - t0)
                dep = rng.uniform(0.03, 0.13)
                if rng.random() < 0.15:
                    t0 += w + 0.01
                    continue
                tc, zc = t0 + w / 2, z + h / 2
                mb = place(wall_point(k, tc, zc, 0.004 + dep / 2), -R, T)
                wp.box((w - 0.006, h - 0.006, dep), mb, mat=rng.choice((0, 0, 13, 1)), bevel=0.003)
                front = dep / 2
                for j in range(rng.randint(0, 4)):
                    x = rng.uniform(-w / 2 + 0.03, w / 2 - 0.03)
                    y = rng.uniform(-h / 2 + 0.025, h / 2 - 0.025)
                    r = rng.choice((0.008, 0.011, 0.014))
                    wp.cyl(mb @ Vector((x, y, front)), mb @ Vector((x, y, front + 0.012)), r, mat=10, seg=16)
                    if rng.random() < 0.7:
                        p0 = mb @ Vector((x, y, front + 0.012))
                        p1 = mb @ Vector((x, y, front + 0.04))
                        wp.cyl(p0, p1, r * 0.75, mat=6, seg=12)
                        end = wall_point(k, tc + rng.uniform(-0.15, 0.15), Z_BASE + 0.06, 0.03)
                        mid = p1.lerp(end, 0.5) - R * rng.uniform(0.03, 0.08)
                        wp.tube([p1, mid, end], rng.uniform(0.003, 0.0055), mat=rng.choice((11, 11, 12)), seg=8,
                                sub=8)
                if rng.random() < 0.6:
                    for sx in (-1, 1):
                        wp.cyl(mb @ Vector((sx * (w / 2 - 0.02), -h / 2 + 0.02, front)),
                               mb @ Vector((sx * (w / 2 - 0.02), -h / 2 + 0.02, front + 0.022)), 0.004, mat=2, seg=8)
                    wp.cyl(mb @ Vector((-(w / 2 - 0.02), -h / 2 + 0.02, front + 0.022)),
                           mb @ Vector((w / 2 - 0.02, -h / 2 + 0.02, front + 0.022)), 0.004, mat=2, seg=8)
                for sx in (-1, 1):
                    for sy in (-1, 1):
                        q = Vector((sx * (w / 2 - 0.012), sy * (h / 2 - 0.012), front))
                        wp.cyl(mb @ q, mb @ (q + Vector((0, 0, 0.002))), 0.0035, mat=2, seg=6, rot=rng.uniform(0, 1))
                if rng.random() < 0.7:
                    pw, ph = rng.uniform(0.02, 0.08), rng.uniform(0.01, 0.03)
                    wp.patch(pw, ph, mb @ Matrix.Translation((rng.uniform(-w / 4, w / 4), h / 2 - 0.02, front + 0.001)),
                             rng.choice((5, 5, 14, 3, 4)), rng)
                if leds < (1, 0, 0, 3, 0, 4)[k]:  # one LED on wall 0, two on 3, one on 5
                    for j in range(2 if k == 3 else 1):
                        led.box((0.006, 0.006, 0.004), mb @ Matrix.Translation((w / 2 - 0.03 - 0.012 * j, 0.0,
                                                                                front + 0.002)),
                                mat=(leds + j) % 2)
                    leds += 2 if k == 3 else 1
                t0 += w + 0.01
            z += h + rng.uniform(0.01, 0.04)
        if k % 2 == 0:
            t = rng.uniform(-hwid + 0.1, hwid - 0.1)
            pts = [wall_point(k, t, Z_BASE + 0.02, 0.05), wall_point(k, t + 0.04, (Z_BASE + Z_FB) / 2, 0.16),
                   wall_point(k, t - 0.02, Z_FB - 0.05, 0.08), wall_point(k, t + 0.1, Z_FB + 0.05, 0.03)]
            q, tg = wp.tube(pts, 0.018, mat=15, seg=12, sub=24, rib=0.07)
            for i in (0, len(q) - 1):
                wp.cyl(q[i] - tg[i] * 0.015, q[i] + tg[i] * 0.015, 0.022, mat=6, seg=16)
    for k, t, z, size, mat in ((1, 0.0, 0.75, (0.26, 0.11, 0.07), 8), (0, 0.15, 0.42, (0.2, 0.15, 0.08), 7),
                               (2, -0.2, 0.47, (0.22, 0.15, 0.09), 8), (4, -0.2, 0.45, (0.24, 0.17, 0.08), 7)):
        a = math.radians(60.0 * k)
        R, T = Vector((math.cos(a), math.sin(a), 0.0)), Vector((-math.sin(a), math.cos(a), 0.0))
        mb = place(wall_point(k, t, z, size[2] / 2 + 0.1), -R, T)
        wp.bag(size, mb, rng, mat=mat, seam=9)
        wp.box((0.02, size[1] + 0.006, size[2] + 0.006), mb @ Matrix.Translation((-size[0] * 0.25, 0, 0)), mat=9)
        wp.patch(size[0] * 0.3, size[1] * 0.3, mb @ Matrix.Translation((0.03, 0, size[2] / 2 + 0.002)), 3, rng)
    wp.build()
    led.build()
    # Corrugated flex hose from the hatch tunnel up to wall 3, as in iss035e010551.
    ho = Part("Hose", "Shell", ["M_Hose", "M_Plastic"])
    a = math.radians(205.0)
    pts = [Vector((0.58 * math.cos(a), 0.58 * math.sin(a), 0.0)),
           Vector((0.6 * math.cos(a), 0.6 * math.sin(a), 0.22)),
           wall_point(3, 0.3, 0.38, 0.12), wall_point(3, 0.28, 0.5, 0.1)]
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
