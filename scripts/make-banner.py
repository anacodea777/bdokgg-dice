"""Builds docs/banner.png from docs/logo.png and the screenshots in docs/screenshots.

Usage: python scripts/make-banner.py
Needs Pillow. Fonts default to Windows Segoe UI; pass --font/--font-bold to override.
"""
import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "docs" / "screenshots"
LOGO = ROOT / "docs" / "logo.png"
OUT = ROOT / "docs" / "banner.png"

W, H = 1600, 900
ORANGE = (255, 144, 0)


def background():
    img = Image.new("RGBA", (W, H), (8, 8, 8, 255))
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    g.ellipse((780, 60, 1640, 920), fill=(255, 144, 0, 70))
    g.ellipse((-260, 560, 380, 1200), fill=(255, 144, 0, 40))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(120)))
    return img


def phone(path, height):
    shot = Image.open(path).convert("RGB")
    scale = height / shot.height
    shot = shot.resize((round(shot.width * scale), height), Image.LANCZOS)
    radius = round(height * 0.045)
    bezel = round(height * 0.016)
    w, h = shot.width + bezel * 2, shot.height + bezel * 2

    frame = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle((0, 0, w - 1, h - 1), radius + bezel, fill=(40, 40, 40, 255),
                        outline=(255, 144, 0, 160), width=2)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, shot.width - 1, shot.height - 1), radius, fill=255)
    frame.paste(shot, (bezel, bezel), mask)
    return frame


def paste_with_shadow(canvas, img, xy):
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    alpha = img.split()[3].point(lambda a: 170 if a else 0)
    shadow.paste((0, 0, 0, 255), (xy[0] + 14, xy[1] + 22), alpha)
    shadow = shadow.filter(ImageFilter.GaussianBlur(20))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(img, xy)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", default="C:/Windows/Fonts/segoeui.ttf")
    ap.add_argument("--font-bold", default="C:/Windows/Fonts/segoeuib.ttf")
    ap.add_argument("--shots", nargs=3, default=["07_result_bn.png", "05_six_dice_en.png", "02_rolling_en.png"],
                    help="left, right and centre screenshot")
    args = ap.parse_args()

    canvas = background()

    logo = Image.open(LOGO).convert("RGBA")
    logo.thumbnail((560, 300), Image.LANCZOS)
    canvas.alpha_composite(logo, (80, 120))

    d = ImageDraw.Draw(canvas)
    title = ImageFont.truetype(args.font_bold, 84)
    sub = ImageFont.truetype(args.font, 34)
    chip = ImageFont.truetype(args.font_bold, 27)

    x = 84
    y = 120 + logo.height + 20
    d.text((x, y), "DICE", font=title, fill=ORANGE)
    d.text((x, y + 108), "Shake, tap, roll. Anywhere, offline.", font=sub, fill=(220, 220, 220))

    y += 180
    cx = x
    for label in ("1–6 dice", "Roll sound", "No permissions", "English + বাংলা"):
        try:
            f = chip if label.isascii() else ImageFont.truetype("C:/Windows/Fonts/Nirmala.ttc", 27)
        except OSError:
            f = chip
        tw = d.textlength(label, font=f)
        if cx + tw + 44 > 760:
            cx, y = x, y + 70
        d.rounded_rectangle((cx, y, cx + tw + 44, y + 54), 27, fill=(30, 30, 30, 255), outline=ORANGE, width=2)
        d.text((cx + 22, y + 9), label, font=f, fill="white")
        cx += tw + 44 + 14

    left, right, centre = (SHOTS / s for s in args.shots)
    for img, xy in [
        (phone(left, 640), (870, 170)),
        (phone(right, 640), (1230, 170)),
        (phone(centre, 730), (1030, 100)),
    ]:
        paste_with_shadow(canvas, img, xy)

    canvas.convert("RGB").save(OUT, optimize=True)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
