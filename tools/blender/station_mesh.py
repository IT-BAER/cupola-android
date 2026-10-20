"""JVM-free reader and validator for station.mesh (plan P4.6). Format: tools/blender/README.md.

Run: python tools/blender/station_mesh.py FILE            validate FILE, exit 1 on any defect
     python tools/blender/station_mesh.py --self-test FILE  corrupted copies must fail, FILE must pass
"""
import math
import struct
import sys

MAGIC, VERSION, VERTEX_BYTES = b"CPST", 1, 22
KINDS = (0, 1, 2, 3)


def read_station(data):
    """Parse bytes into [{name, pivot, axis, kind, pos, normal, uv, indices}]; raises ValueError."""
    if data[:4] != MAGIC:
        raise ValueError("bad magic %r" % data[:4])
    if len(data) < 12:
        raise ValueError("truncated header")
    version, count = struct.unpack_from("<II", data, 4)
    if version != VERSION:
        raise ValueError("unsupported version %d" % version)
    off, nodes = 12, []
    for _ in range(count):
        if off + 2 > len(data):
            raise ValueError("truncated node header")
        (n,) = struct.unpack_from("<H", data, off)
        name = data[off + 2:off + 2 + n].decode("utf-8")
        off += 2 + n
        f = struct.unpack_from("<3f3fBII", data, off)
        off += struct.calcsize("<3f3fBII")
        pivot, axis, kind, nv, ni = f[0:3], f[3:6], f[6], f[7], f[8]
        if kind not in KINDS:
            raise ValueError("%s: bad kind %d" % (name, kind))
        if ni % 3:
            raise ValueError("%s: index count %d not a multiple of 3" % (name, ni))
        isz = 4 if nv > 65535 else 2
        need = nv * VERTEX_BYTES + ni * isz
        if off + need > len(data):
            raise ValueError("%s: truncated vertex or index data" % name)
        recs = list(struct.iter_unpack("<3f3h2H", data[off:off + nv * VERTEX_BYTES]))
        off += nv * VERTEX_BYTES
        isz = 4 if nv > 65535 else 2
        idx = struct.unpack_from("<%d%s" % (ni, "I" if isz == 4 else "H"), data, off)
        off += ni * isz
        if ni and max(idx) >= nv:
            raise ValueError("%s: index %d out of range (%d vertices)" % (name, max(idx), nv))
        if abs(math.dist(axis, (0, 0, 0)) - 1.0) > 1e-3:
            raise ValueError("%s: axis length %.4f" % (name, math.dist(axis, (0, 0, 0))))
        for r in recs:
            if not all(math.isfinite(x) for x in r[:3]):
                raise ValueError("%s: non-finite position" % name)
            if abs(math.dist(r[3:6], (0, 0, 0)) / 32767.0 - 1.0) > 0.01:
                raise ValueError("%s: normals not unit length" % name)
        nodes.append({"name": name, "pivot": pivot, "axis": axis, "kind": kind, "vertices": recs, "indices": idx})
    if off != len(data):
        raise ValueError("%d trailing bytes" % (len(data) - off))
    return nodes


def summary(nodes):
    tris = sum(len(n["indices"]) // 3 for n in nodes)
    verts = sum(len(n["vertices"]) for n in nodes)
    return "nodes %d vertices %d triangles %d" % (len(nodes), verts, tris)


def self_test(path):
    good = open(path, "rb").read()
    cases = {"magic": b"XXXX" + good[4:], "version": good[:4] + struct.pack("<I", 2) + good[8:],
             "truncated": good[:-7], "trailing": good + b"\0"}
    for label, bad in cases.items():
        try:
            read_station(bad)
        except ValueError as e:
            print("RED  %-10s rejected: %s" % (label, e))
        else:
            print("FAIL %s accepted" % label)
            return 1
    print("GREEN %s: %s" % (path, summary(read_station(good))))
    return 0


def main(argv):
    if len(argv) == 3 and argv[1] == "--self-test":
        return self_test(argv[2])
    if len(argv) != 2:
        print(__doc__)
        return 2
    try:
        print(summary(read_station(open(argv[1], "rb").read())))
    except ValueError as e:
        print("INVALID %s: %s" % (argv[1], e))
        return 1
    return 0


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    sys.exit(main(sys.argv))
