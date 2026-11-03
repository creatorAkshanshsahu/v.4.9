#!/usr/bin/env python3
"""Single source of truth for the AK TV mark geometry.

The same coordinates produce (a) the shipped Android vector drawable and (b) the PNG previews and
launcher bitmaps, so the two can never drift. Run:

    python3 tools/brand_mark.py preview   # PNGs in design/previews/4.9.45/
    python3 tools/brand_mark.py emit      # rewrite the two vector drawables

Geometry lives in a 480x280 viewport. Polygons are (points, is_hole).
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
VIEW = (480, 280)
OUT_PREVIEW = ROOT / 'design/previews/4.9.45'
DRAWABLE = ROOT / 'app/src/main/res/drawable'

INK = (7, 4, 10)
CRIMSON_BRIGHT = (255, 90, 107)
CRIMSON = (225, 29, 46)
CRIMSON_DEEP = (140, 15, 28)
GLOSS_HI = (255, 235, 238)

# Plate inset inside the viewport.
PLATE = (8, 8, 472, 272)
PLATE_RADIUS = 44

# --- The monogram -------------------------------------------------------------------------
# A: apex at 150, legs spread to the baseline. The counter runs all the way down so the two legs
# stay separate, then a crossbar reconnects them.
A_OUTER = [(150, 40), (44, 244), (256, 244)]
A_COUNTER = [(150, 122), (100, 244), (200, 244)]
A_BAR = [(101, 176), (199, 176), (199, 208), (101, 208)]

# K: ONE polygon. The stem starts at x=250 so it overlaps the A's right leg; the arms are cut
# from the same outline, which is why there is no seam where they meet the stem.
K = [
    (250, 40), (306, 40), (306, 120), (372, 40), (448, 40),
    (340, 148),
    (448, 244), (372, 244), (306, 166), (306, 244), (250, 244),
]

SHAPES = [
    (A_OUTER, False),
    (A_COUNTER, True),
    (A_BAR, False),
    (K, False),
]

# Sheen wedge down the leading edge of the A.
SHEEN = [(56, 232), (144, 54), (162, 54), (76, 232)]


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(size, stops):
    width, height = size
    strip = Image.new('RGB', (1, height))
    load = strip.load()
    for y in range(height):
        t = y / max(1, height - 1)
        if t < 0.5:
            load[0, y] = lerp(stops[0], stops[1], t / 0.5)
        else:
            load[0, y] = lerp(stops[1], stops[2], (t - 0.5) / 0.5)
    return strip.resize((width, height))


def monogram_mask(size, scale, offset, supersample=1):
    """Draw at supersample x on a matching canvas, then downscale: sharp edges, no clipping."""
    work = (size[0] * supersample, size[1] * supersample)
    mask = Image.new('L', work, 0)
    draw = ImageDraw.Draw(mask)
    for points, hole in SHAPES:
        scaled = [((offset[0] + x * scale) * supersample, (offset[1] + y * scale) * supersample)
                  for x, y in points]
        draw.polygon(scaled, fill=0 if hole else 255)
    return mask if supersample == 1 else mask.resize(size, Image.Resampling.LANCZOS)


def render_mark(size=(960, 560), plate=True, glow=True):
    """Render the mark on (or without) the lacquered plate."""
    width, height = size
    scale = min(width / VIEW[0], height / VIEW[1])
    offset = ((width - VIEW[0] * scale) / 2, (height - VIEW[1] * scale) / 2)
    canvas = Image.new('RGBA', size, (0, 0, 0, 0))

    if plate:
        plate_box = (offset[0] + PLATE[0] * scale, offset[1] + PLATE[1] * scale,
                     offset[0] + PLATE[2] * scale, offset[1] + PLATE[3] * scale)
        radius = PLATE_RADIUS * scale
        body = gradient(size, ((34, 10, 19), (12, 7, 16), (28, 8, 18)))
        plate_mask = Image.new('L', size, 0)
        ImageDraw.Draw(plate_mask).rounded_rectangle(plate_box, radius=radius, fill=255)
        canvas.paste(body, (0, 0), plate_mask)
        # Specular band, confined to the top third and heavily feathered.
        sheen = Image.new('L', size, 0)
        ImageDraw.Draw(sheen).rounded_rectangle((plate_box[0], plate_box[1], plate_box[2],
                                                 plate_box[1] + (plate_box[3] - plate_box[1]) * 0.42),
                                                radius=radius, fill=34)
        sheen = sheen.filter(ImageFilter.GaussianBlur(22 * scale / 2.2))
        canvas = Image.composite(Image.new('RGBA', size, (255, 214, 219, 255)), canvas, sheen)
        rim = Image.new('RGBA', size, (0, 0, 0, 0))
        ImageDraw.Draw(rim).rounded_rectangle(plate_box, radius=radius,
                                              outline=(255, 52, 72, 150), width=max(2, round(3 * scale)))
        canvas = Image.alpha_composite(canvas, rim)

    if glow:
        bloom = Image.new('RGBA', size, (0, 0, 0, 0))
        ImageDraw.Draw(bloom).polygon([(offset[0] + x * scale, offset[1] + y * scale) for x, y in A_OUTER],
                                      fill=(255, 52, 72, 110))
        bloom = bloom.filter(ImageFilter.GaussianBlur(26 * scale))
        canvas = Image.alpha_composite(canvas, bloom)

    mask = monogram_mask(size, scale, offset, supersample=2)
    paint = gradient(size, (CRIMSON_BRIGHT, CRIMSON, CRIMSON_DEEP)).convert('RGBA')
    paint.putalpha(mask)
    canvas = Image.alpha_composite(canvas, paint)

    specular = Image.new('L', size, 0)
    ImageDraw.Draw(specular).polygon([(offset[0] + x * scale, offset[1] + y * scale) for x, y in SHEEN], fill=150)
    specular = Image.composite(specular, Image.new('L', size, 0), mask.point(lambda v: 255 if v > 128 else 0))
    canvas = Image.composite(Image.new('RGBA', size, GLOSS_HI + (235,)), canvas, specular.point(lambda v: v * 2 // 3))
    return canvas


def emit_vector():
    def path_of(polygon):
        head, *tail = polygon
        return f'M{head[0]},{head[1]} ' + ' '.join(f'L{x},{y}' for x, y in tail) + ' Z'

    def group(polys, gradient_span):
        # One path per letter so the gradient flows across the whole glyph.
        d = ' '.join(path_of(p) for p, _ in polys)
        x0, y0, x1, y1 = gradient_span
        return f'''    <path android:fillType="evenOdd" android:pathData="{d}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="{x0}" android:startY="{y0}" android:endX="{x1}" android:endY="{y1}">
                <item android:color="#FFFF5A6B" android:offset="0"/>
                <item android:color="#FFE11D2E" android:offset="0.5"/>
                <item android:color="#FF8C0F1C" android:offset="1"/>
            </gradient>
        </aapt:attr>
    </path>'''

    body = group([(A_OUTER, False), (A_COUNTER, True), (A_BAR, False)], (44, 40, 256, 244))
    arms = group([(K, False)], (250, 40, 448, 244))

    xml = f'''<!--
  AK TV 4.9.45 glossy-crimson mark. Generated by tools/brand_mark.py - edit the geometry there.
  One monogram: the K stem bites into the A's right leg, so the mark reads as a single shape.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="240dp" android:height="140dp"
    android:viewportWidth="480" android:viewportHeight="280">

    <path android:pathData="M52,8 H428 Q472,8 472,52 V228 Q472,272 428,272 H52 Q8,272 8,228 V52 Q8,8 52,8 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="8" android:endX="0" android:endY="272">
                <item android:color="#220A13" android:offset="0"/>
                <item android:color="#0C0710" android:offset="0.58"/>
                <item android:color="#1C0812" android:offset="1"/>
            </gradient>
        </aapt:attr>
    </path>
    <path android:strokeWidth="3" android:strokeColor="#8CFF3448"
        android:pathData="M52,8 H428 Q472,8 472,52 V228 Q472,272 428,272 H52 Q8,272 8,228 V52 Q8,8 52,8 Z"/>

    {body}

    {arms}

    <path android:fillAlpha="0.7" android:pathData="{path_of(SHEEN)}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="46" android:startY="46" android:endX="64" android:endY="236">
                <item android:color="#CCFFEBEE" android:offset="0"/>
                <item android:color="#00FFEBEE" android:offset="1"/>
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''
    (DRAWABLE / 'aktv_mark_cinematic.xml').write_text(xml)
    return DRAWABLE / 'aktv_mark_cinematic.xml'


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else 'preview'
    if command == 'emit':
        print(emit_vector())
        return
    OUT_PREVIEW.mkdir(parents=True, exist_ok=True)
    render_mark().save(OUT_PREVIEW / 'mark-plate.png')
    render_mark((512, 512), plate=False, glow=False).save(OUT_PREVIEW / 'mark-bare.png')
    render_mark((1200, 700)).resize((600, 350), Image.Resampling.LANCZOS).save(OUT_PREVIEW / 'mark-small.png')
    print(OUT_PREVIEW)


if __name__ == '__main__':
    main()
