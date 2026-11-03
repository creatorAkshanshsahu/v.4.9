#!/usr/bin/env python3
"""Derive the Android launcher/banner bitmaps from the glossy-crimson mark.

Geometry comes from `tools/brand_mark.py`, the same source that emits
`res/drawable/aktv_mark_cinematic.xml`, so the raster and vector identities cannot drift.

    python3 tools/build-brand-assets.py
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import brand_mark  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/res/drawable-nodpi'
DESIGN = ROOT / 'design'
FONTS = ROOT / 'app/src/main/res/font'
INK = (7, 4, 10)


def save(image, name, size=None):
    OUT.mkdir(parents=True, exist_ok=True)
    target = image.resize(size, Image.Resampling.LANCZOS) if size else image
    target.save(OUT / name, optimize=True)
    return target


def banner():
    """320x180 Android TV banner: mark on the left, wordmark on the right."""
    canvas = Image.new('RGBA', (640, 360), INK + (255,))
    glow = Image.new('RGBA', (640, 360), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-120, -80, 400, 440), fill=(255, 52, 72, 78))
    canvas = Image.alpha_composite(canvas, glow.filter(ImageFilter.GaussianBlur(80)))

    mark = brand_mark.render_mark((420, 245))
    canvas.alpha_composite(mark, (10, (360 - mark.height) // 2))

    drawer = ImageDraw.Draw(canvas)
    title = ImageFont.truetype(str(FONTS / 'sora_semibold.ttf'), 76)
    tagline = ImageFont.truetype(str(FONTS / 'sora_regular.ttf'), 19)
    drawer.text((352, 126), 'AK TV', font=title, fill=(252, 247, 248))
    drawer.text((356, 220), 'LIVE. FILMS. YOUR WORLD.', font=tagline, fill=(196, 180, 186))

    flat = Image.new('RGB', canvas.size, INK)
    flat.paste(canvas, (0, 0), canvas)
    flat.save(DESIGN / 'ak-tv-banner.png', optimize=True)
    return flat.resize((320, 180), Image.Resampling.LANCZOS)


def main():
    mark_plate = brand_mark.render_mark((960, 560))
    mark_plate.save(DESIGN / 'ak-tv-mark-transparent.png', optimize=True)

    bare = brand_mark.render_mark((512, 512), plate=False, glow=False)
    for name in ('ic_mark', 'ic_mark_blue', 'ic_intro'):
        save(bare, f'{name}.png')

    icon = Image.new('RGBA', (512, 512), INK + (255,))
    inset = mark_plate.resize((420, round(420 * mark_plate.height / mark_plate.width)),
                              Image.Resampling.LANCZOS)
    icon.alpha_composite(inset, ((512 - inset.width) // 2, (512 - inset.height) // 2))
    icon.convert('RGB').save(OUT / 'ic_logo.png', optimize=True)

    foreground = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
    small = bare.resize((300, 300), Image.Resampling.LANCZOS)
    foreground.alpha_composite(small, ((432 - small.width) // 2, (432 - small.height) // 2))
    foreground.save(OUT / 'ic_launcher_foreground.png', optimize=True)

    save(banner(), 'banner_img.png')
    print('Brand assets rebuilt from tools/brand_mark.py')


if __name__ == '__main__':
    main()
