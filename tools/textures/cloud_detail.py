"""Builds the cloud_detail master tile from a MODIS 250 m true-colour crop (build_textures.ps1 -Assets clouddetail).

Usage: python cloud_detail.py <source png, (T + O) px square> <master png out>

Output: T x T RGB PNG (R = G = B), a standard normal field G stored as G / G_RANGE + 0.5: the rank of the source luma
(plus its 1 and 4 km blur) through the inverse normal CDF. The shader blends rotated copies of it with
variance-preserving weights (Heitz and Neyret 2018) and turns the result back into a rank with the normal CDF, so map
cover c is cloud where the rank exceeds 1 - c. The tile wraps: the last O source columns (then rows) are cross-faded
into the first O in Gaussian space with weights cos and sin (w_a^2 + w_b^2 = 1), which keeps the local variance, so
the seam band has as many cloud cores and gaps as the rest.
"""
import sys
from statistics import NormalDist

import numpy as np
from PIL import Image

T = 2048
O = 256
G_RANGE = 7.0  # stored G spans -3.5..3.5 (earth.frag CLOUD_G_RANGE)


def luma(path):
    rgb = np.asarray(Image.open(path).convert('RGB')).astype(np.float64)
    if rgb.shape[:2] != (T + O, T + O):
        raise SystemExit(f'{path} is {rgb.shape[1]}x{rgb.shape[0]}, expected {T + O}x{T + O}')
    return rgb @ np.array([0.2126, 0.7152, 0.0722])


def smooth(a, sigma=0.7):
    # Separable Gaussian: breaks the 8-bit plateaus so ranks follow the image, not the scan order.
    x = np.arange(-2, 3)
    k = np.exp(-x * x / (2 * sigma * sigma))
    k /= k.sum()
    for axis in (0, 1):
        p = np.pad(a, [(2, 2) if i == axis else (0, 0) for i in (0, 1)], mode='edge')
        a = sum(k[i] * np.take(p, np.arange(i, i + a.shape[axis]), axis=axis) for i in range(5))
    return a


def box(a, r, axis):
    p = np.pad(a, [(r + 1, r) if i == axis else (0, 0) for i in (0, 1)], mode='edge')
    c = np.cumsum(p, axis=axis)
    n = a.shape[axis]
    return (np.take(c, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis) - np.take(c, np.arange(n), axis=axis)) / (2 * r + 1)


def blur(a, sigma):
    # Three box passes approximate a Gaussian of `sigma` px.
    r = max(1, int(round(np.sqrt(4 * sigma * sigma + 1) / 2)))
    for _ in range(3):
        a = box(box(a, r, 0), r, 1)
    return a


def ranks(a):
    flat = a.ravel()
    r = np.empty(flat.size, np.float64)
    r[np.argsort(flat, kind='stable')] = (np.arange(flat.size) + 0.5) / flat.size
    return r.reshape(a.shape)


def gaussian(u):
    table_u = np.linspace(1e-6, 1 - 1e-6, 65537)
    table_g = np.array([NormalDist().inv_cdf(v) for v in table_u])
    return np.interp(u, table_u, table_g)


def wrap(g, axis):
    t = np.arange(O) * (0.5 * np.pi / O)
    shape = [1, 1]
    shape[axis] = O
    wa = np.sin(t).reshape(shape)
    wb = np.cos(t).reshape(shape)
    head = np.take(g, np.arange(O), axis=axis)
    tail = np.take(g, np.arange(T, T + O), axis=axis)
    body = np.take(g, np.arange(O, T), axis=axis)
    return np.concatenate([wa * head + wb * tail, body], axis=axis)


def main(src, out):
    rng = np.random.default_rng(20200202)
    y = luma(src)
    # Luma blurred over 1 and 4 km is added so clear sea ranks by its nearness to cloud, not by its own speckle:
    # heavy cover then leaves smooth holes far from the cumulus instead of salt-and-pepper sea texture.
    l = smooth(y) + blur(y, 4) + blur(y, 16) + rng.uniform(0.0, 1e-3, (T + O, T + O))
    g = gaussian(ranks(wrap(wrap(gaussian(ranks(l)), 1), 0)))
    q = np.clip(np.round((g / G_RANGE + 0.5) * 255.0), 0, 255).astype(np.uint8)
    Image.fromarray(np.repeat(q[..., None], 3, axis=2)).save(out, compress_level=1)
    # Seam check: mean |difference| across the wrap edges against the mean over the interior.
    qi = q.astype(np.float64)
    seam = 0.5 * (np.abs(qi[:, 0] - qi[:, -1]).mean() + np.abs(qi[0] - qi[-1]).mean())
    inner = 0.5 * (np.abs(np.diff(qi, axis=1)).mean() + np.abs(np.diff(qi, axis=0)).mean())
    gs = (qi / 255.0 - 0.5) * G_RANGE
    print(f'cloud_detail {T}x{T}: seam step {seam:.2f} vs interior step {inner:.2f}, '
          f'G mean {gs.mean():.3f} std {gs.std():.3f}, clipped {np.mean((q == 0) | (q == 255)):.5f}')
    if seam > 1.5 * inner:
        raise SystemExit('cloud_detail seam step exceeds 1.5 x the interior step')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
