"""Measure the 7 clear apertures of a Cupola .blend from the glass meshes; exit 1 if any is off >5 mm.

Run: blender -b --factory-startup -P tools/blender/measure_apertures.py -- [BLEND] [--dump PATH]
--dump writes sorted world-space vertex coordinates (0.01 mm) of all evaluated meshes and prints
their SHA-256, for build determinism checks. Also prints the branding inventory.
"""
import hashlib
import math
import os
import sys

import bpy
from mathutils import Vector

TOL_MM = 5.0
TARGET = {"top_d": 706.0, "height": 405.0, "short": 400.0, "long": 644.0, "corner_r": 32.4}


def args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    blend, dump = os.path.join(root, "local", "blender", "cupola.blend"), None
    i = 0
    while i < len(argv):
        if argv[i] == "--dump":
            dump = argv[i + 1]
            i += 2
        else:
            blend = argv[i]
            i += 1
    return blend, dump


def plane_coords(ob):
    me = ob.data
    pts = [ob.matrix_world @ me.vertices[i].co for i in me.polygons[0].vertices]
    c = sum(pts, Vector()) / len(pts)
    n = (ob.matrix_world.to_3x3() @ me.polygons[0].normal).normalized()
    u = (pts[0] - c)
    u = (u - n * u.dot(n)).normalized()
    v = n.cross(u)
    return [Vector(((p - c).dot(u), (p - c).dot(v))) for p in pts], max(abs((p - c).dot(n)) for p in pts)


def measure_top(ob):
    p2, flat = plane_coords(ob)
    radii = [p.length for p in p2]
    return {"top_d": 2000.0 * sum(radii) / len(radii)}, flat, 2000.0 * (max(radii) - min(radii))


def line_intersect(a, d, b, e):
    den = d.x * e.y - d.y * e.x
    t = ((b - a).x * e.y - (b - a).y * e.x) / den
    return a + d * t


def measure_side(ob):
    p2, flat = plane_coords(ob)
    n = len(p2)
    # Merge collinear consecutive edges into segments, keep the 4 longest (the straight sides).
    segs = []
    i = 0
    edges = [(p2[k], p2[(k + 1) % n]) for k in range(n)]
    # Start at an edge whose direction differs from its predecessor.
    start = next(k for k in range(n)
                 if (edges[k][1] - edges[k][0]).angle(edges[k - 1][1] - edges[k - 1][0]) > math.radians(0.1))
    cur = [edges[start][0], edges[start][1]]
    for k in range(1, n):
        a, b = edges[(start + k) % n]
        if (b - a).angle(cur[1] - cur[0]) < math.radians(0.1):
            cur[1] = b
        else:
            segs.append(tuple(cur))
            cur = [a, b]
    segs.append(tuple(cur))
    straight = sorted(sorted(segs, key=lambda s: -(s[1] - s[0]).length)[:4],
                      key=lambda s: segs.index(s))
    lines = [(s[0], (s[1] - s[0]).normalized()) for s in straight]
    corners = [line_intersect(*lines[k - 1], *lines[k]) for k in range(4)]  # corner k: before side k
    # Parallel pair: sides whose directions are antiparallel.
    par = [k for k in range(4) if abs(lines[k][1].dot(lines[(k + 2) % 4][1])) > 0.9999]
    k0 = par[0]
    sides = [(corners[(k + 1) % 4] - corners[k]).length for k in range(4)]
    pa, pb = sides[k0], sides[(k0 + 2) % 4]
    dvec = lines[(k0 + 2) % 4][0] - lines[k0][0]
    d = lines[k0][1]
    height = abs(dvec.x * d.y - dvec.y * d.x)
    # Corner radius: tangent length from virtual corner to straight-side end, and arc midpoint.
    rs = []
    for k in range(4):
        dprev, dnext = -lines[k - 1][1], lines[k][1]
        theta = dprev.angle(dnext)
        t = (straight[k][0] - corners[k]).length
        rs.append(t * math.tan(theta / 2.0))
        mid = min(p2, key=lambda p: (p - corners[k]).length)
        dm = (mid - corners[k]).length
        rs.append(dm / (1.0 / math.sin(theta / 2.0) - 1.0))
    return ({"height": 1000 * height, "short": 1000 * min(pa, pb), "long": 1000 * max(pa, pb),
             "corner_r": 1000 * sum(rs) / len(rs)}, flat, 1000 * (max(rs) - min(rs)))


def dump_meshes(path):
    dg = bpy.context.evaluated_depsgraph_get()
    lines, nv, nf = [], 0, 0
    for ob in sorted(bpy.data.objects, key=lambda o: o.name):
        if ob.type != "MESH":
            lines.append("%s %s %s" % (ob.name, ob.type, " ".join("%.5f" % x for x in ob.matrix_world.col[3])))
            continue
        ev = ob.evaluated_get(dg)
        me = ev.to_mesh()
        nv += len(me.vertices)
        nf += len(me.polygons)
        mw = ob.matrix_world
        co = sorted(tuple(round(c, 5) for c in (mw @ v.co)) for v in me.vertices)
        lines += ["%s %.5f %.5f %.5f" % (ob.name, *c) for c in co]
        lines.append("%s faces %d" % (ob.name, len(me.polygons)))
        ev.to_mesh_clear()
    data = ("\n".join(lines) + "\n").replace("-0.00000", "0.00000").encode()
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)
    print("DUMP objects=%d vertices=%d faces=%d sha256=%s" %
          (len(bpy.data.objects), nv, nf, hashlib.sha256(data).hexdigest()))


def inventory():
    print("INVENTORY text objects:", [o.name for o in bpy.data.objects if o.type == "FONT"])
    print("INVENTORY images:", [i.name for i in bpy.data.images])
    tex = [(m.name, n.name) for m in bpy.data.materials if m.node_tree
           for n in m.node_tree.nodes if n.type in ("TEX_IMAGE", "TEX_ENVIRONMENT")]
    print("INVENTORY image texture nodes:", tex)
    print("INVENTORY materials:", sorted(m.name for m in bpy.data.materials))
    print("INVENTORY collections:", sorted(c.name for c in bpy.data.collections))


def main():
    blend, dump = args()
    bpy.ops.wm.open_mainfile(filepath=blend)
    rows, ok = [], True
    for ob in sorted(bpy.data.objects, key=lambda o: o.name):
        tag = ob.get("cupola_aperture")
        if not tag:
            continue
        vals, flat, spread = measure_top(ob) if tag == "top" else measure_side(ob)
        for key, val in vals.items():
            diff = val - TARGET[key]
            good = abs(diff) <= TOL_MM
            ok &= good
            rows.append("%-8s %-9s %9.2f %9.2f %+8.3f %s" % (tag, key, val, TARGET[key], diff,
                                                             "ok" if good else "FAIL"))
        rows.append("%-8s %-9s planarity %.4f mm, spread %.4f mm" % (tag, "check", 1000 * flat, spread))
    count = len({r.split()[0] for r in rows})
    print("APERTURE measured_mm target_mm diff_mm")
    print("\n".join(rows))
    ok &= count == 7
    print("APERTURES=%d RESULT=%s" % (count, "PASS" if ok else "FAIL"))
    inventory()
    if dump:
        dump_meshes(dump)
    sys.stdout.flush()
    sys.exit(0 if ok else 1)


main()
