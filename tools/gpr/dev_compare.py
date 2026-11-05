"""Photo|render sheets and image metrics for the G-PR dev set (tools/gpr/dev-render.ps1).

Usage: python dev_compare.py <photo_dir with pNN-photo.png> <render_dir with pNN-render.png>
Writes <render_dir>/sheet1.jpg, sheet2.jpg (four pairs each) and prints one metrics line per image:
luma median and p95 (sRGB 0..255), mean saturation of non-dark pixels, sharpness (mean |Laplacian| of luma at half
size) and colourfulness (Hasler-Suesstrunk).
"""
import glob
import os
import sys

import numpy as np
from PIL import Image, ImageDraw


def metrics(path):
    a = np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
    mx, mn = a.max(axis=2), a.min(axis=2)
    lit = mx > 25
    sat = float(((mx - mn) / np.maximum(mx, 1))[lit].mean()) if lit.any() else 0.0
    half = luma[::2, ::2]
    lap = np.abs(4 * half[1:-1, 1:-1] - half[:-2, 1:-1] - half[2:, 1:-1] - half[1:-1, :-2] - half[1:-1, 2:])
    rg, yb = r - g, 0.5 * (r + g) - b
    colour = np.hypot(rg.std(), yb.std()) + 0.3 * np.hypot(rg.mean(), yb.mean())
    return np.median(luma), np.percentile(luma, 95), sat, float(lap.mean()), float(colour)


def sheets(photos, renders, ids):
    for n in range(0, len(ids), 4):
        rows = []
        for i in ids[n:n + 4]:
            p = Image.open(os.path.join(photos, f"{i}-photo.png")).convert("RGB")
            r = Image.open(os.path.join(renders, f"{i}-render.png")).convert("RGB")
            w = 640
            h = int(p.height * w / p.width)
            row = Image.new("RGB", (2 * w + 10, h + 24), (40, 40, 40))
            row.paste(p.resize((w, h), Image.LANCZOS), (0, 24))
            row.paste(r.resize((w, h), Image.LANCZOS), (w + 10, 24))
            ImageDraw.Draw(row).text((4, 4), f"{i}  left=photo  right=render", fill=(255, 255, 0))
            rows.append(row)
        sheet = Image.new("RGB", (rows[0].width, sum(x.height for x in rows)))
        y = 0
        for x in rows:
            sheet.paste(x, (0, y))
            y += x.height
        sheet.save(os.path.join(renders, f"sheet{n // 4 + 1}.jpg"), quality=88)


def main():
    photos, renders = sys.argv[1], sys.argv[2]
    ids = sorted(os.path.basename(f)[:3] for f in glob.glob(os.path.join(renders, "p??-render.png")))
    print(f"{'pair':5} {'kind':6} {'med':>5} {'p95':>5} {'sat':>5} {'sharp':>6} {'colour':>6}")
    for i in ids:
        for kind, path in (("photo", os.path.join(photos, f"{i}-photo.png")), ("render", os.path.join(renders, f"{i}-render.png"))):
            med, p95, sat, sharp, colour = metrics(path)
            print(f"{i:5} {kind:6} {med:5.0f} {p95:5.0f} {sat:5.2f} {sharp:6.2f} {colour:6.1f}")
    sheets(photos, renders, ids)


if __name__ == "__main__":
    main()
