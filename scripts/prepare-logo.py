"""Turns docs/logo-source.jpg (logo on black) into a cropped PNG with a transparent
background for the app toolbar and the README banner.

Usage: python scripts/prepare-logo.py
Needs Pillow.
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "logo-source.jpg"
APP_OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "logo_bdokgg.png"
DOCS_OUT = ROOT / "docs" / "logo.png"


def main():
    img = Image.open(SRC).convert("RGB")
    w, h = img.size

    # Flood-fill the black background from the border. Black areas enclosed by the
    # white outline (letter counters, the hole in the hand) stay opaque.
    work = img.copy()
    marker = (255, 0, 255)
    for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1), (w // 2, 2), (w // 2, h - 3)]:
        if sum(work.getpixel(seed)) < 120:
            ImageDraw.floodfill(work, seed, marker, thresh=90)
    bg = ImageChops.difference(work, Image.new("RGB", work.size, marker)).convert("L")
    alpha = bg.point(lambda v: 255 if v > 8 else 0)
    # Soften the cut edge a little.
    alpha = alpha.filter(ImageFilter.GaussianBlur(1.2))

    out = img.convert("RGBA")
    out.putalpha(alpha)
    bbox = alpha.point(lambda v: 255 if v > 40 else 0).getbbox()
    pad = 12
    out = out.crop((max(0, bbox[0] - pad), max(0, bbox[1] - pad),
                    min(w, bbox[2] + pad), min(h, bbox[3] + pad)))
    out.save(DOCS_OUT, optimize=True)

    app = out.copy()
    app.thumbnail((720, 720), Image.LANCZOS)
    app.save(APP_OUT, optimize=True)
    print(f"wrote {DOCS_OUT} {out.size} and {APP_OUT} {app.size}")


if __name__ == "__main__":
    main()
