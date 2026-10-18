"""Build the Cupola interior from scratch (decision 23) and save local/blender/cupola.blend.

Run: blender -b --factory-startup -P tools/blender/build_cupola.py -- [--out PATH] [--top-aperture M]
Frame: metres; origin at the centre of the hatch plane to Node 3; +Z points out through the
round nadir window toward Earth. Deterministic: no random state, no time, fixed lists only.
"""
import math
import os
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


# ---------- scene helpers ----------

COLS = {}
MATS = {}


def col(name):
    if name not in COLS:
        c = bpy.data.collections.new(name)
        bpy.context.scene.collection.children.link(c)
        COLS[name] = c
    return COLS[name]


def principled(name, color, metal=0.0, rough=0.5, noise=None, emit=None, strength=0.0):
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except AttributeError:
        pass
    nt = m.node_tree
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Base Color"].default_value = (*color, 1.0)
    bsdf.inputs["Metallic"].default_value = metal
    bsdf.inputs["Roughness"].default_value = rough
    if emit:
        bsdf.inputs["Emission Color"].default_value = (*emit, 1.0)
        bsdf.inputs["Emission Strength"].default_value = strength
    if noise:
        # Procedural roughness variation (scale, low, high); no image textures anywhere.
        tex = nt.nodes.new("ShaderNodeTexNoise")
        tex.inputs["Scale"].default_value = noise[0]
        mr = nt.nodes.new("ShaderNodeMapRange")
        mr.inputs["To Min"].default_value = noise[1]
        mr.inputs["To Max"].default_value = noise[2]
        nt.links.new(tex.outputs["Fac"], mr.inputs["Value"])
        nt.links.new(mr.outputs["Result"], bsdf.inputs["Roughness"])
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

    principled("M_FrameBlack", (0.018, 0.018, 0.02), metal=0.3, rough=0.4, noise=(40.0, 0.3, 0.55))
    principled("M_Gasket", (0.25, 0.06, 0.04), rough=0.6)
    principled("M_Bolt", (0.55, 0.55, 0.56), metal=1.0, rough=0.35)
    principled("M_Handrail", (0.05, 0.25, 0.65), metal=0.7, rough=0.3)
    principled("M_Shutter", (0.55, 0.53, 0.5), metal=0.6, rough=0.45, noise=(8.0, 0.35, 0.6))
    principled("M_ShellGrey", (0.12, 0.12, 0.125), metal=0.2, rough=0.55, noise=(25.0, 0.45, 0.7))
    principled("M_PanelGrey", (0.32, 0.32, 0.33), metal=0.1, rough=0.6)
    principled("M_Fabric", (0.36, 0.28, 0.19), rough=0.95, noise=(120.0, 0.85, 1.0))
    principled("M_PatchWhite", (0.75, 0.75, 0.72), rough=0.9)
    principled("M_LaptopBody", (0.03, 0.03, 0.03), rough=0.5)
    principled("M_CameraBody", (0.02, 0.02, 0.02), rough=0.45, noise=(200.0, 0.4, 0.6))
    principled("M_LensMetal", (0.05, 0.05, 0.05), metal=0.8, rough=0.3)
    principled("M_CableBlack", (0.015, 0.015, 0.015), rough=0.5)
    principled("M_CableBlue", (0.08, 0.3, 0.6), rough=0.45)
    principled("M_CableWhite", (0.7, 0.7, 0.7), rough=0.5)
    principled("M_ScreenEmit", (0.0, 0.0, 0.0), rough=0.2, emit=(0.55, 0.62, 0.7), strength=1.5)
    principled("M_LedGreen", (0.0, 0.0, 0.0), emit=(0.1, 1.0, 0.2), strength=8.0)
    principled("M_LedAmber", (0.0, 0.0, 0.0), emit=(1.0, 0.55, 0.05), strength=8.0)
    glass_material()

    # Top window: plate ring (hexagon to aperture circle), tunnel to the pane, glass.
    r_top = top_d / 2.0
    hexc = [tuple(p.xy) for p in hex_corners(HEX_APOTHEM, Z_TOP)]
    fr = Part("Frame_Top", "Frames", ["M_FrameBlack", "M_Gasket"])
    inner, outer = ring_loops(circle(r_top), hexc, (0.0, 0.0))
    fr.strip([(p.x, p.y, Z_TOP) for p in inner], [(p.x, p.y, Z_TOP) for p in outer])
    zg = Z_TOP + TOP_DEPTH
    fr.strip([(p.x, p.y, Z_TOP) for p in inner], [(p.x, p.y, Z_TOP + 0.7 * TOP_DEPTH) for p in inner])
    fr.strip([(p.x, p.y, Z_TOP + 0.7 * TOP_DEPTH) for p in inner], [(p.x, p.y, zg) for p in inner], mat=1)
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
        p = Part("Frame_Side_%d" % k, "Frames", ["M_FrameBlack", "M_Gasket"])
        inner, outer = ring_loops(win, ccw(facet_outline()), cen)
        p.strip([fmap(f, q) for q in inner], [fmap(f, q) for q in outer])
        p.strip([fmap(f, q) for q in inner], [fmap(f, q, 0.7 * SIDE_DEPTH) for q in inner])
        p.strip([fmap(f, q, 0.7 * SIDE_DEPTH) for q in inner], [fmap(f, q, SIDE_DEPTH) for q in inner], mat=1)
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
    bo.build()

    # Handrails: one per mullion and one per skirt corner, with standoffs.
    hr = Part("Handrails", "Handrails", ["M_Handrail", "M_Bolt"])
    tops, bots, bases = hex_corners(HEX_APOTHEM, Z_TOP), hex_corners(A2, Z_FB), hex_corners(A2, Z_BASE)
    for k in range(6):
        n_in = -(facet_frame(k)[3] + facet_frame((k + 1) % 6)[3]).normalized()
        a, b = tops[k].lerp(bots[k], 0.22), tops[k].lerp(bots[k], 0.85)
        hr.cyl(a + n_in * 0.05, b + n_in * 0.05, 0.011)
        hr.cyl(a, a + n_in * 0.05, 0.007, mat=1, seg=8)
        hr.cyl(b, b + n_in * 0.05, 0.007, mat=1, seg=8)
        rin = -Vector((bases[k].x, bases[k].y, 0.0)).normalized()
        a, b = bases[k] + Vector((0, 0, 0.12)), bots[k] - Vector((0, 0, 0.10))
        hr.cyl(a + rin * 0.06, b + rin * 0.06, 0.011)
        hr.cyl(a, a + rin * 0.06, 0.007, mat=1, seg=8)
        hr.cyl(b, b + rin * 0.06, 0.007, mat=1, seg=8)
    hr.build()

    build_laptop()
    build_cameras()
    build_cables()
    build_clutter()
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
    lp = Part("Laptop", "Laptop", ["M_LaptopBody", "M_PanelGrey"])
    lp.box((0.34, 0.24, 0.025), m, bevel=0.004)
    hinge = Matrix.Translation((0, 0.12, 0.0125)) @ Matrix.Rotation(math.radians(-105), 4, "X")
    lp.box((0.34, 0.23, 0.008), m @ hinge @ Matrix.Translation((0, -0.115, 0.004)), bevel=0.002)
    wall = radial * (A2 - 0.01) + Vector((0, 0, 1.0))
    lp.cyl(m @ Vector((0, 0, -0.0125)), wall, 0.015, mat=1)
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
    cl = Part("Clutter", "Clutter", ["M_Fabric", "M_PanelGrey", "M_LaptopBody", "M_PatchWhite"])
    # Fabric pouches clipped to the top ring, as in iss035e010551 and iss038e013587.
    for az, rr, sz, roll in ((75.0, 0.47, (0.2, 0.13, 0.09), 10.0), (100.0, 0.50, (0.17, 0.13, 0.09), -15.0),
                             (55.0, 0.52, (0.12, 0.1, 0.07), 30.0)):
        a = math.radians(az)
        p = Vector((rr * math.cos(a), rr * math.sin(a), Z_TOP - TOP_RING_H - 0.05))
        m = place(p, (0, 0, 1), (math.cos(a + math.radians(roll)), math.sin(a + math.radians(roll)), 0))
        cl.box(sz, m, mat=0, bevel=0.025)
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
        cl.box(sz, place(p, -radial, tang), mat=mat, bevel=0.006)
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
    li = bpy.data.lights.new("L_Cabin", "AREA")
    li.energy = 40.0
    li.size = 0.6
    li.color = (1.0, 0.95, 0.88)
    ob = bpy.data.objects.new("L_Cabin", li)
    ob.location = (0.0, 0.0, Z_BASE - 0.05)
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
