"""Cap-mean airlight luma seen from 420 km, by the Sun's elevation at the ground (single scattering, no LUTs).

Brute-force reference for Scene.airlight (core/render .../scene/SceneInputs.kt) and ExposureTest: view rays to the
ground over the visible cap (rings and weights as Scene.earthLight), Sun azimuth averaged over 8 directions, sun
transmittance ray-marched with the Earth's shadow. Model constants: core/render/src/main/cpp/atmosphere_params.h.
Shader units (sun illuminance pi). Run: python tools/atmosphere/airlight.py (numpy).
"""
import numpy as np

RG, RT = 6378.137, 6478.137
RAY = np.array([5.802e-3, 13.558e-3, 33.1e-3]); HR = 8.0
MIE_S, MIE_E, HM, G = 3.996e-3, 4.440e-3, 1.2, 0.8
OZ = np.array([0.650e-3, 1.881e-3, 0.085e-3]); OZC, OZW = 25.0, 15.0
LUMA = np.array([0.2126, 0.7152, 0.0722])
NV, NS = 200, 120

def ext(h):
    h = h[..., None]
    return RAY * np.exp(-h / HR) + MIE_E * np.exp(-h / HM) + OZ * np.clip(1 - np.abs(h - OZC) / OZW, 0, None)

def airlight(cam, d, sun):
    b = cam @ d; c = cam @ cam
    tg = -b - np.sqrt(b * b - c + RG * RG)
    t0 = -b - np.sqrt(b * b - c + RT * RT)
    ts = t0 + (np.arange(NV) + 0.5) / NV * (tg - t0); dt = (tg - t0) / NV
    p = cam + ts[:, None] * d
    h = np.linalg.norm(p, axis=1) - RG
    e = ext(h)
    tv = np.exp(-np.cumsum(e, 0) * dt + e * dt)
    # Sun rays: blocked by the ground, else optical depth to the top.
    bs = p @ sun; cs = np.einsum('ij,ij->i', p, p)
    discg = bs * bs - cs + RG * RG
    blocked = (discg >= 0) & (-bs - np.sqrt(np.maximum(discg, 0)) > 0)
    t1 = -bs + np.sqrt(bs * bs - cs + RT * RT)
    u = (np.arange(NS) + 0.5) / NS
    q = p[:, None, :] + (t1[:, None] * u)[:, :, None] * sun
    hq = np.linalg.norm(q, axis=2) - RG
    ts_ = np.exp(-ext(hq).sum(1) * (t1 / NS)[:, None]); ts_[blocked] = 0
    nu = d @ sun
    pr = 3 / (16 * np.pi) * (1 + nu * nu)
    pm = 3 / (8 * np.pi) * (1 - G * G) * (1 + nu * nu) / ((2 + G * G) * (1 + G * G - 2 * G * nu) ** 1.5)
    src = (RAY * np.exp(-h / HR)[:, None] * pr + (MIE_S * np.exp(-h / HM) * pm)[:, None]) * ts_
    return np.pi * (tv * src * dt).sum(0) @ LUMA

r = RG + 420.0
cam = np.array([0.0, 0.0, r])
cap = np.arccos(RG / r)
rings = 16
for elev in [-8, -6, -5, -4, -3, -2, -1.5, -1, -0.5, 0, 0.5, 1, 2, 3, 5, 8, 12, 20, 30, 45, 60, 75, 90]:
    num = den = 0.0
    for i in range(rings):
        g = cap * (i + 0.5) / rings
        theta = np.arctan2(RG * np.sin(g), r - RG * np.cos(g))
        w = np.sin(theta) * RG * (r * np.cos(g) - RG) / (r * r - 2 * r * RG * np.cos(g) + RG * RG)
        gp = RG * np.array([np.sin(g), 0.0, np.cos(g)])  # ground point in the x-z plane
        d = gp - cam; d /= np.linalg.norm(d)
        up = gp / RG; east = np.array([0.0, 1.0, 0.0]); north = np.cross(up, east)
        vals = []
        for k in range(8):
            az = 2 * np.pi * (k + 0.5) / 8
            el = np.radians(elev)
            sun = up * np.sin(el) + np.cos(el) * (north * np.cos(az) + east * np.sin(az))
            vals.append(airlight(cam, d, sun))
        num += w * np.mean(vals); den += w
    print(f"{elev:+6.1f} {num / den:.4e}")
