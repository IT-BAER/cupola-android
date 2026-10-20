"""Which interior cube-face regions the wallpaper can ever show (plan P4.3).

Run: blender -b --factory-startup -P tools/blender/visible_region.py -- [BLEND] [--json PATH] [--self-test]

The view envelope is each rest view of the settings (Cam_Rest's diagonal view and NADIR_REST, both with
Cam_Rest's eye, lens and sensor fit AUTO) at every screen
aspect in ASPECTS, turned by yaw about the camera's up axis and pitch about its right axis within the
look-around limits of LookAroundController (soft clamp 20 deg x strength 1, launcher pan +-4 deg yaw,
idle float +-2 deg). The runtime camera of P3 must use the same yaw/pitch convention.
Face coordinates: major axis a, u and v are the two other axes in X, Y, Z order divided by |a|, in [-1, 1].
"""
import json
import math
import os
import sys

import numpy as np

YAW_DEG = 20.0 + 4.0 + 2.0
PITCH_DEG = 20.0 + 2.0
# width / height: phone portrait and landscape (A35 1080 x 2340), tablet 3:4 and 4:3, unfolded foldable 1:1.
PHONE_ASPECTS = [1080.0 / 2340.0, 2340.0 / 1080.0]
ASPECTS = PHONE_ASPECTS + [3.0 / 4.0, 4.0 / 3.0, 1.0]
FACE_PX = 2048
PAD_PX = 16  # filtering and mip margin around each cropped region
# Allocation table: 3 full faces (Earthlight+alpha, cabin, emissive, 512 px ambient) = 15.4 MiB.
MIB_PER_FACE = 15.4 / 3.0
FACES = ["+X", "-X", "+Y", "-Y", "+Z", "-Z"]
# Second rest direction of the settings (owner, 18 October 2026): straight down through the round window.
# Camera-to-world columns: right, up, back (camera looks along -back = +Z); image up toward azimuth 30 deg,
# the azimuth Cam_Rest aims at.
_C30, _S30 = math.cos(math.radians(30.0)), math.sin(math.radians(30.0))
NADIR_REST = np.array([[-_S30, _C30, 0.0], [_C30, _S30, 0.0], [0.0, 0.0, -1.0]]).T


def rot_y(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return np.array([[c, 0.0, s], [0.0, 1.0, 0.0], [-s, 0.0, c]])


def rot_x(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return np.array([[1.0, 0.0, 0.0], [0.0, c, -s], [0.0, s, c]])


def view_directions(cam_to_world, lens_mm, sensor_mm, aspects, yaw_deg, pitch_deg, grid=48, steps=9):
    """Unit world directions covering every pixel of every view in the envelope (camera looks along -Z)."""
    t = np.linspace(-1.0, 1.0, grid)
    gx, gy = np.meshgrid(t, t)
    base = []
    for aspect in aspects:
        half = sensor_mm / 2.0 / lens_mm  # tangent of the half FOV on the longer image axis
        tw, th = (half, half / aspect) if aspect >= 1.0 else (half * aspect, half)
        base.append(np.stack([gx.ravel() * tw, gy.ravel() * th, -np.ones(gx.size)], axis=1))
    base = np.concatenate(base)
    out = []
    for yaw in np.linspace(-yaw_deg, yaw_deg, steps):
        for pitch in np.linspace(-pitch_deg, pitch_deg, steps):
            out.append(base @ (cam_to_world @ rot_y(yaw) @ rot_x(pitch)).T)
    d = np.concatenate(out)
    return d / np.linalg.norm(d, axis=1, keepdims=True)


def face_rects(dirs, cube_rot):
    """Bounding (u, v) rectangle per touched face; cube_rot maps cube axes to world axes."""
    c = dirs @ cube_rot
    # Directions on a cube edge belong to both faces; slivers of zero area are dropped below.
    major = np.abs(c).max(axis=1) - 1e-9
    rects = {}
    for axis in range(3):
        rest = [i for i in range(3) if i != axis]
        for sign, name in ((1.0, "+"), (-1.0, "-")):
            sel = c[(np.abs(c[:, axis]) >= major) & (np.sign(c[:, axis]) == sign)]
            if len(sel) == 0:
                continue
            m = np.abs(sel[:, axis])
            u, v = sel[:, rest[0]] / m, sel[:, rest[1]] / m
            r = {"u0": float(u.min()), "u1": float(u.max()), "v0": float(v.min()), "v1": float(v.max())}
            r["area"] = (r["u1"] - r["u0"]) * (r["v1"] - r["v0"]) / 4.0
            if r["area"] > 1e-6:
                rects[name + "XYZ"[axis]] = r
    return {k: rects[k] for k in FACES if k in rects}


def pixel_rect(r):
    def px(lo, hi):
        return (max(0, math.floor((lo + 1.0) / 2.0 * FACE_PX) - PAD_PX),
                min(FACE_PX, math.ceil((hi + 1.0) / 2.0 * FACE_PX) + PAD_PX))
    x0, x1 = px(r["u0"], r["u1"])
    y0, y1 = px(r["v0"], r["v1"])
    return [x0, y0, x1, y1]


def cost(rects):
    """Baked texels as a fraction of one full face, padding included."""
    return sum((p[2] - p[0]) * (p[3] - p[1]) for p in map(pixel_rect, rects.values())) / FACE_PX ** 2


def quat_to_mat(q):
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
        [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)],
    ])


def best_rotation(dirs, seed=7, tries=3000, refine=400):
    """Cube orientation with the fewest baked texels (random search, then local refinement)."""
    rng = np.random.default_rng(seed)
    sub = dirs[rng.choice(len(dirs), size=min(len(dirs), 20000), replace=False)]
    best_q = np.array([1.0, 0.0, 0.0, 0.0])
    best = cost(face_rects(sub, np.eye(3)))
    for q in rng.normal(size=(tries, 4)):
        c = cost(face_rects(sub, quat_to_mat(q)))
        if c < best:
            best, best_q = c, q / np.linalg.norm(q)
    step = 0.05
    for i in range(refine):
        q = best_q + rng.normal(scale=step, size=4)
        c = cost(face_rects(sub, quat_to_mat(q)))
        if c < best:
            best, best_q = c, q / np.linalg.norm(q)
        if i % 100 == 99:
            step /= 2.0
    return quat_to_mat(best_q), best_q


def report(label, dirs, rot):
    rects = face_rects(dirs, rot)
    c = cost(rects)
    print("%s faces=%d texel_faces=%.3f est_mib=%.1f" % (label, len(rects), c, c * MIB_PER_FACE))
    for k, r in rects.items():
        print("  %s px=%s area=%.3f" % (k, pixel_rect(r), r["area"]))
    return {"faces": {k: {"rect": r, "px": pixel_rect(r)} for k, r in rects.items()},
            "texel_faces": c, "est_mib": c * MIB_PER_FACE}


def self_test():
    ident = np.eye(3)
    # 90 deg square view along -Z, no motion: exactly the -Z face, full.
    d = view_directions(ident, 18.0, 36.0, [1.0], 0.0, 0.0)
    r = face_rects(d, ident)
    assert list(r) == ["-Z"], r
    assert abs(r["-Z"]["area"] - 1.0) < 1e-3, r
    # 60 deg square view, yaw +-20 deg: the 30 deg edge turns to 50 deg, so on +X z/x reaches -1/tan(50 deg).
    # Top and bottom edges stay on -Z (|z| >= 1.155 cos 50 deg = 0.742 > tan 30 deg).
    lens60 = 18.0 / math.tan(math.radians(30.0))
    r = face_rects(view_directions(ident, lens60, 36.0, [1.0], 20.0, 0.0), ident)
    assert sorted(r) == ["+X", "-X", "-Z"], r
    assert abs((r["+X"]["v1"] - r["+X"]["v0"]) - (1.0 - math.tan(math.radians(40.0)))) < 0.01, r
    # Pitch touches +Y and -Y only.
    r = face_rects(view_directions(ident, lens60, 36.0, [1.0], 0.0, 20.0), ident)
    assert sorted(r) == ["+Y", "-Y", "-Z"], r
    # Portrait aspect 0.5: long axis vertical gets the lens FOV, horizontal is narrower.
    r = face_rects(view_directions(ident, 18.0, 36.0, [0.5], 0.0, 0.0), ident)
    assert abs((r["-Z"]["v1"] - r["-Z"]["v0"]) - 2.0) < 1e-3, r
    assert abs((r["-Z"]["u1"] - r["-Z"]["u0"]) - 1.0) < 1e-3, r
    # A rotated cube sees the same directions on other faces; cost is rotation-dependent but finite.
    assert cost(face_rects(d, rot_y(45.0))) > cost(face_rects(d, ident))
    # Nadir rest view: orthonormal, right-handed, looking along +Z (out through the round window).
    assert np.allclose(NADIR_REST @ NADIR_REST.T, ident) and abs(np.linalg.det(NADIR_REST) - 1.0) < 1e-9
    assert np.allclose(-NADIR_REST[:, 2], [0.0, 0.0, 1.0]), NADIR_REST
    print("SELF_TEST=PASS")


def main(argv):
    import bpy

    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    blend = os.path.join(root, "local", "blender", "cupola.blend")
    out = os.path.join(root, "local", "blender", "visible_region.json")
    i = 0
    while i < len(argv):
        if argv[i] == "--json":
            out = argv[i + 1]
            i += 2
        else:
            blend = argv[i]
            i += 1
    bpy.ops.wm.open_mainfile(filepath=blend)
    cam = bpy.data.objects["Cam_Rest"]
    if cam.data.sensor_fit != "AUTO":
        raise SystemExit("Cam_Rest sensor fit must be AUTO, is " + cam.data.sensor_fit)
    m = np.array(cam.matrix_world.to_3x3().normalized())
    lens, sensor = cam.data.lens, cam.data.sensor_width
    print("REST lens_mm=%.1f sensor_mm=%.1f long_axis_fov_deg=%.1f yaw=+-%.0f pitch=+-%.0f"
          % (lens, sensor, math.degrees(2 * math.atan(sensor / 2 / lens)), YAW_DEG, PITCH_DEG))
    result = {"lens_mm": lens, "sensor_mm": sensor, "yaw_deg": YAW_DEG, "pitch_deg": PITCH_DEG,
              "face_px": FACE_PX, "pad_px": PAD_PX, "mib_per_face": MIB_PER_FACE,
              "rest_views": {"diagonal": m.tolist(), "nadir": NADIR_REST.tolist()}}
    for label, aspects in (("phone", PHONE_ASPECTS), ("all", ASPECTS)):
        dirs = np.concatenate([view_directions(rest, lens, sensor, aspects, YAW_DEG, PITCH_DEG)
                               for rest in (m, NADIR_REST)])
        world = report(label + "_world_cube", dirs, np.eye(3))
        rot, q = best_rotation(dirs)
        best = report(label + "_best_cube", dirs, rot)
        best["cube_to_world"] = rot.tolist()
        best["quat_wxyz"] = q.tolist()
        result[label] = {"aspects": aspects, "world_cube": world, "best_cube": best}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=1)
    print("JSON", out)


if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if "--self-test" in argv:
        self_test()
    else:
        main(argv)
