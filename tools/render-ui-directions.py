"""Concept renders for the AK TV 4.9.24 UI redesign decision.

Renders five clearly different TV home directions plus selected player-overlay and
Movies-tab studies inside the locked midnight + aqua identity, using the app's real
Sora typefaces and the selected AK mark. All content is explicitly labelled sample
content (fictional poster titles, sample channel captions); no network access and no
decoded playback are involved. These are design-direction concepts for the user to
choose between, not screenshots of the running app.

Usage: python3 tools/render-ui-directions.py
Output: design/previews/direction-*.webp
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / "app/src/main/res/font"
OUT = ROOT / "design/previews"
W, H = 1600, 900

MID = (8, 13, 20)
DEEP = (5, 8, 12)
AQUA = (50, 229, 206)
ICE = (239, 246, 250)
DIM = (166, 183, 198)
FAINT = (109, 130, 150)
LIVE = (255, 92, 108)
INDIGO = (86, 108, 255)

SECTIONS = [("live", "Live"), ("always", "Always on"), ("movies", "Movies"), ("series", "Series"),
            ("search", "Search")]
CHANNELS = [
    ("SS", (14, 68, 58), "Star Sports 1 Hindi HD", "IND vs AUS · 3rd ODI · live"),
    ("YAY", (88, 44, 96), "Sony YAY Hindi", "Honey Bunny Ka Jholmaal"),
    ("ZEE", (24, 40, 84), "Zee Cinema HD", "Sample feature: Skyline Express"),
    ("N18", (112, 26, 34), "News18 India", "Sample bulletin · 8 PM"),
    ("SAB", (16, 74, 74), "Sony SAB HD", "Sample sitcom: Warden Wars"),
    ("CN", (40, 30, 92), "Cartoon Network", "Sample toons marathon"),
]
MOVIES = [
    ("NORTHWIND", "2025 · U/A 16+", "Thriller", (12, 40, 52), (26, 20, 48)),
    ("The Glass Harbour", "2024 · U/A 13+", "Drama", (16, 30, 58), (10, 44, 46)),
    ("Kite Season", "2025 · U/A 7+", "Family", (20, 52, 56), (34, 26, 60)),
    ("Monsoon Line", "2026 · A", "Noir", (10, 18, 30), (24, 36, 66)),
    ("Static Bloom", "2023 · U/A 16+", "Sci-fi", (14, 46, 44), (40, 22, 56)),
]


def f(size, weight="regular"):
    return ImageFont.truetype(str(FONT / f"sora_{weight}.ttf"), size)


def measure(d, text, font, ls=0):
    return sum(d.textlength(c, font=font) for c in text) + ls * max(0, len(text) - 1)


def tracked(d, xy, text, font, fill, ls=2, anchor_left=True):
    x, y = xy
    if not anchor_left:
        x -= measure(d, text, font, ls)
    for c in text:
        d.text((x, y), c, font=font, fill=fill)
        x += d.textlength(c, font=font) + ls
    return measure(d, text, font, ls)


def gtext(canvas, xy, text, font, fill, alpha):
    """Ghost text: drawn on its own layer so translucency survives compositing."""
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).text(xy, text, font=font, fill=fill + (alpha,))
    canvas.alpha_composite(layer)


def vgrad(size, top, bottom):
    w, h = size
    base = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        base.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return base.resize((w, h))


def radial(size, center, radius, color, alpha):
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx, cy = center
    d.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=color + (alpha,))
    return layer.filter(ImageFilter.GaussianBlur(radius / 2.2))


def panel(canvas, box, r, fill=None, outline=None, width=2):
    """Translucent-safe rounded rectangle: drawn on its own layer, then composited."""
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(box, r, fill=fill, outline=outline, width=width)
    canvas.alpha_composite(layer)


def glow(canvas, box, r, color=AQUA, alpha=110, blur=18, width=5, pad=None):
    pad = pad if pad is not None else blur
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(
        [box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad], r + pad,
        outline=color + (alpha,), width=width)
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def glass(canvas, box, r, blur=16, tint=(255, 255, 255, 22), border=(255, 255, 255, 42), bw=2):
    crop = canvas.crop(box).filter(ImageFilter.GaussianBlur(blur))
    mask = Image.new("L", crop.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, crop.size[0] - 1, crop.size[1] - 1), r, fill=255)
    pane = Image.alpha_composite(crop.convert("RGBA"), Image.new("RGBA", crop.size, tint))
    canvas.paste(pane, (box[0], box[1]), mask)
    if border:
        panel(canvas, box, r, outline=border, width=bw)


def art(canvas, box, r, top, bottom, motif=None, seed=0):
    """Rounded, masked gradient artwork tile."""
    w = box[2] - box[0]
    h = box[3] - box[1]
    tile = vgrad((w, h), top, bottom).convert("RGBA")
    tile.alpha_composite(radial(tile.size, (w * (0.3 + (seed % 3) * 0.25), h * 0.3), int(h * 0.7),
                                 AQUA if seed % 2 else INDIGO, 30))
    if motif in ("ring", "band"):
        ml = Image.new("RGBA", tile.size, (0, 0, 0, 0))
        dm = ImageDraw.Draw(ml)
        if motif == "ring":
            dm.ellipse((w * .55, h * .18, w * .95, h * .58), outline=(255, 255, 255, 44), width=3)
        else:
            dm.polygon([(0, h * .72), (w, h * .42), (w, h * .56), (0, h * .86)], fill=(255, 255, 255, 22))
        tile.alpha_composite(ml)
    mask = Image.new("L", tile.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    canvas.paste(tile, (box[0], box[1]), mask)


def chip(canvas, xy, text, font, fg, bg, pad_x=18, pad_y=10, outline=None, icon_kind=None,
         icon_fg=None):
    d = ImageDraw.Draw(canvas)
    tw = measure(d, text, font)
    ix = 30 if icon_kind else 0
    w = tw + pad_x * 2 + ix
    h = font.size + pad_y * 2
    box = (xy[0], xy[1], xy[0] + w, xy[1] + h)
    panel(canvas, box, h // 2, fill=bg, outline=outline, width=2)
    d = ImageDraw.Draw(canvas)
    if icon_kind:
        icon(d, icon_kind, xy[0] + pad_x + 8, xy[1] + h // 2, 10, icon_fg or fg)
    d.text((xy[0] + pad_x + ix, xy[1] + pad_y - 3), text, font=font, fill=fg)
    return box


def icon(d, kind, cx, cy, s, color, w=3):
    if kind == "live":
        d.rounded_rectangle((cx - s, cy - s * .72, cx + s, cy + s * .42), 6, outline=color, width=w)
        d.line((cx, cy + s * .42, cx, cy + s * .8), fill=color, width=w)
        d.line((cx - s * .5, cy + s * .8, cx + s * .5, cy + s * .8), fill=color, width=w)
    elif kind == "always":
        d.arc((cx - s, cy - s * .7, cx + s, cy + s * .7), 0, 360, fill=color, width=w)
        d.line((cx, cy, cx, cy - s * .38), fill=color, width=w)
        d.line((cx, cy, cx + s * .34, cy + s * .12), fill=color, width=w)
    elif kind == "movies":
        d.rounded_rectangle((cx - s, cy - s * .3, cx + s, cy + s * .75), 5, outline=color, width=w)
        d.polygon([(cx - s, cy - s * .3), (cx + s, cy - s * .3), (cx + s * .82, cy - s * .8),
                   (cx - s * .9, cy - s * .62)], outline=color)
    elif kind == "series":
        d.rounded_rectangle((cx - s, cy - s * .15, cx + s * .7, cy + s * .75), 5, outline=color, width=w)
        d.rounded_rectangle((cx - s * .7, cy - s * .75, cx + s, cy - s * .05), 5, outline=color, width=w)
    elif kind == "search":
        d.ellipse((cx - s * .7, cy - s * .7, cx + s * .35, cy + s * .35), outline=color, width=w)
        d.line((cx + s * .25, cy + s * .25, cx + s * .8, cy + s * .8), fill=color, width=w)
    elif kind == "play":
        d.polygon([(cx - s * .45, cy - s * .6), (cx - s * .45, cy + s * .6), (cx + s * .65, cy)], fill=color)
    elif kind == "plus":
        d.line((cx - s * .5, cy, cx + s * .5, cy), fill=color, width=w)
        d.line((cx, cy - s * .5, cx, cy + s * .5), fill=color, width=w)
    elif kind == "cc":
        d.rounded_rectangle((cx - s, cy - s * .7, cx + s, cy + s * .7), 4, outline=color, width=2)
    elif kind == "gear":
        d.ellipse((cx - s * .5, cy - s * .5, cx + s * .5, cy + s * .5), outline=color, width=2)
        for a in range(0, 360, 60):
            import math
            dx, dy = math.cos(a * math.pi / 180), math.sin(a * math.pi / 180)
            d.line((cx + dx * s * .5, cy + dy * s * .5, cx + dx * s * .9, cy + dy * s * .9),
                   fill=color, width=2)
    elif kind == "retry":
        d.arc((cx - s * .7, cy - s * .7, cx + s * .7, cy + s * .7), -60, 240, fill=color, width=2)
        d.polygon([(cx + s * .5, cy - s * .95), (cx + s * .95, cy - s * .25), (cx + s * .15, cy - s * .45)],
                  fill=color)


MARK = Image.open(ROOT / "design/ak-tv-mark-transparent.png").convert("RGBA")


def brand(canvas, xy, height=44, wordmark=True):
    canvas.alpha_composite(MARK.resize((height, height)), xy)
    if wordmark:
        ImageDraw.Draw(canvas).text((xy[0] + height + 12, xy[1] + height // 2 - 15), "AK TV",
                                    font=f(26, "bold"), fill=ICE)


def sample_tag(canvas, direction, y=None):
    d = ImageDraw.Draw(canvas)
    tracked(d, (W - 40, 30), "SAMPLE CONTENT · UI PREVIEW", f(15, "semibold"), DIM, 3, anchor_left=False)
    fb = f(15, "semibold")
    w = measure(d, direction, fb, 3)
    ty = y if y is not None else H - 54
    box = (W - 40 - w - 26, ty, W - 40, ty + 32)
    panel(canvas, box, 8, fill=(8, 13, 20, 210), outline=AQUA + (120,), width=2)
    tracked(d, (box[0] + 13, box[1] + 8), direction, fb, AQUA, 3)


def clock(canvas, xy, size=22):
    ImageDraw.Draw(canvas).text(xy, "8:45 PM", font=f(size, "semibold"), fill=DIM, anchor="rs")


def logo_plate(canvas, xy, initials, tint, size=52, r=12):
    box = (xy[0], xy[1], xy[0] + size, xy[1] + size)
    panel(canvas, box, r, fill=tint + (255,))
    ImageDraw.Draw(canvas).text(((box[0] + box[2]) / 2, (box[1] + box[3]) / 2), initials,
                                font=f(size // 2.6, "bold"), fill=ICE, anchor="mm")
    return box


def live_badge(canvas, xy):
    panel(canvas, (xy[0], xy[1], xy[0] + 74, xy[1] + 30), 15, fill=(10, 14, 20, 215))
    d = ImageDraw.Draw(canvas)
    d.ellipse((xy[0] + 12, xy[1] + 11, xy[0] + 20, xy[1] + 19), fill=LIVE)
    tracked(d, (xy[0] + 28, xy[1] + 8), "LIVE", f(14, "bold"), ICE, 2)


def progress(canvas, box, frac, height=6):
    panel(canvas, box, height // 2, fill=(255, 255, 255, 40))
    x0, y0, x1, y1 = box
    panel(canvas, (x0, y0, x0 + (x1 - x0) * frac, y1), height // 2, fill=AQUA + (255,))
    d = ImageDraw.Draw(canvas)
    px = x0 + (x1 - x0) * frac
    d.ellipse((px - 7, y0 + height // 2 - 7, px + 7, y0 + height // 2 + 7), fill=AQUA)


def rail_plate(canvas, d, box, kind, label, focused):
    if focused:
        glow(canvas, box, 22, AQUA, alpha=120, blur=20)
        panel(canvas, box, 22, fill=(50, 229, 206, 42), outline=AQUA + (255,), width=3)
        fg, tf = AQUA, ICE
    else:
        fg, tf = DIM, DIM
    icon(d, kind, (box[0] + box[2]) / 2, box[1] + 42, 17, fg)
    d.text(((box[0] + box[2]) / 2, box[1] + 82), label, font=f(19, "semibold" if focused else "regular"),
           fill=tf, anchor="mm")


# ---------------------------------------------------------------- direction 1
def d1_rail_cinematic():
    c = vgrad((W, H), (10, 22, 32), MID).convert("RGBA")
    c.alpha_composite(radial(c.size, (1180, 250), 520, (18, 74, 78), 120))
    c.alpha_composite(radial(c.size, (340, 760), 460, (30, 34, 84), 90))
    c.alpha_composite(radial(c.size, (1500, 820), 380, (16, 60, 92), 70))
    c = Image.alpha_composite(c, Image.new("RGBA", c.size, MID + (140,)))
    d = ImageDraw.Draw(c)

    rail_w = 132
    panel(c, (0, 0, rail_w, H), 0, fill=(6, 10, 16, 195))
    brand(c, (rail_w // 2 - 22, 34), 44, wordmark=False)
    y = 150
    for i, (kind, label) in enumerate(SECTIONS):
        rail_plate(c, d, (14, y, rail_w - 14, y + 108), kind, label, i == 0)
        y += 126

    x0 = rail_w + 46
    tracked(d, (x0, 46), "YOUR FRONT ROW", f(16, "semibold"), AQUA, 4)
    d.text((x0, 74), "Live TV", font=f(46, "bold"), fill=ICE)
    clock(c, (W - 40, 72))

    hb = (x0, 150, W - 46, 470)
    glass(c, hb, 26, blur=26, tint=(10, 18, 26, 120), border=(255, 255, 255, 26))
    c.alpha_composite(radial(c.size, (hb[2] - 300, 300), 300, (24, 96, 96), 100))
    c.alpha_composite(radial(c.size, (hb[2] - 120, 430), 220, (40, 60, 140), 70))
    d = ImageDraw.Draw(c)
    d.ellipse((hb[0] + 46, hb[1] + 44, hb[0] + 56, hb[1] + 54), fill=AQUA)
    tracked(d, (hb[0] + 68, hb[1] + 40), "LIVE · SPORTS / HINDI", f(16, "semibold"), DIM, 3)
    d.text((hb[0] + 46, hb[1] + 84), "Star Sports 1 Hindi HD", font=f(58, "bold"), fill=ICE)
    d.text((hb[0] + 46, hb[1] + 168), "IND vs AUS · 3rd ODI — India 214/4 (38.2 ov)",
           font=f(23), fill=DIM)
    chip(c, (hb[0] + 46, hb[1] + 224), "Watch live", f(21, "semibold"), (6, 12, 16),
         (245, 250, 252, 255), pad_x=26, pad_y=14, icon_kind="play")
    chip(c, (hb[0] + 290, hb[1] + 224), "My favorites", f(21, "semibold"), ICE, (255, 255, 255, 14),
         pad_x=26, pad_y=14, outline=(255, 255, 255, 70), icon_kind="plus")
    logo_plate(c, (hb[2] - 150, hb[1] + 60), "SS", (14, 68, 58), 96, 20)

    tracked(d, (x0, 512), "LIVE NOW", f(20, "semibold"), ICE, 3)
    d.line((x0 + 128, 526, W - 46, 526), fill=(255, 255, 255, 26), width=2)
    cw, chh = 322, 182
    gx = x0
    for i, (ini, tint, name, cap) in enumerate(CHANNELS[:4]):
        focused = i == 0
        box = (gx, 552, gx + cw, 552 + chh)
        fb = (box[0] - 8, box[1] - 8, box[2] + 8, box[3] + 8) if focused else box
        art(c, fb, 20 if focused else 16, (tint[0] + 8, tint[1] + 14, tint[2] + 16), (10, 16, 24),
            motif="band" if i % 2 else "ring", seed=i)
        d = ImageDraw.Draw(c)
        if focused:
            glow(c, fb, 22, AQUA, alpha=130, blur=22)
            panel(c, fb, 20, outline=AQUA + (255,), width=3)
        else:
            panel(c, box, 16, outline=(255, 255, 255, 34), width=2)
        logo_plate(c, (fb[0] + 18, fb[1] + 16), ini, tint, 46, 10)
        live_badge(c, (fb[2] - 92, fb[1] + 16))
        scrim = Image.new("RGBA", c.size, (0, 0, 0, 0))
        ImageDraw.Draw(scrim).rounded_rectangle(fb, 20, fill=(5, 8, 12, 150))
        c.alpha_composite(scrim.crop((fb[0], fb[3] - 96, fb[2], fb[3])), (fb[0], fb[3] - 96))
        d = ImageDraw.Draw(c)
        d.text((fb[0] + 18, fb[3] - 62), name, font=f(21, "semibold"), fill=ICE)
        d.text((fb[0] + 18, fb[3] - 34), cap, font=f(16), fill=DIM)
        gx += cw + 26
    tracked(d, (x0, 776), "ALWAYS ON", f(20, "semibold"), ICE, 3)
    d.line((x0 + 140, 790, W - 46, 790), fill=(255, 255, 255, 26), width=2)
    px = x0
    for j, (title, year, genre, t0, t1) in enumerate(MOVIES):
        art(c, (px, 812, px + 148, 900), 12, t0, t1, motif="ring" if j % 2 else None, seed=j)
        px += 168
    sample_tag(c, "DIRECTION 1 · RAIL-LED CINEMATIC")
    return c


# ---------------------------------------------------------------- direction 2
def d2_grid_editorial():
    c = Image.new("RGBA", (W, H), MID + (255,))
    d = ImageDraw.Draw(c)
    brand(c, (48, 40), 40)
    d.line((48, 108, W - 48, 108), fill=(255, 255, 255, 60), width=2)
    tracked(d, (48, 122), "TUESDAY · 20 OCT · ISSUE 4.9", f(15, "semibold"), FAINT, 3)
    clock(c, (W - 48, 72))

    ry = 190
    for i, (kind, label) in enumerate(SECTIONS[:4]):
        focused = i == 0
        d.text((52, ry), f"{i + 1:02d}", font=f(17, "semibold"), fill=AQUA if focused else FAINT)
        d.text((92, ry - 8), label.upper(), font=f(27 if focused else 22, "bold" if focused else "semibold"),
               fill=ICE if focused else FAINT)
        if focused:
            d.line((92, ry + 30, 92 + measure(d, label.upper(), f(27, "bold")), ry + 30), fill=AQUA, width=4)
        ry += 66

    x0 = 300
    gtext(c, (x0, 146), "01", f(74, "bold"), (255, 255, 255), 40)
    tracked(d, (x0 + 108, 192), "LIVE NOW — SIX CHANNELS ON AIR", f(19, "semibold"), ICE, 3)
    d.line((x0, 240, W - 48, 240), fill=(255, 255, 255, 46), width=2)

    cw, chh = 380, 178
    gx, gy = x0, 268
    for i, (ini, tint, name, cap) in enumerate(CHANNELS):
        col, row = i % 3, i // 3
        bx, by = gx + col * (cw + 24), gy + row * (chh + 40)
        focused = i == 0
        box = (bx, by, bx + cw, by + chh)
        if focused:
            panel(c, (bx - 6, by - 8, bx + cw + 12, by + chh + 14), 8, fill=(16, 26, 36, 255))
        art(c, box, 4, (tint[0] + 6, tint[1] + 10, tint[2] + 12), (10, 15, 22),
            motif="band" if i % 2 else None, seed=i)
        d = ImageDraw.Draw(c)
        d.text((bx + 18, by + 16), ini, font=f(32, "bold"), fill=ICE)
        live_badge(c, (bx + cw - 92, by + 16))
        panel(c, (bx, by + 74, bx + cw, by + chh), 0, fill=(8, 12, 18, 205))
        d = ImageDraw.Draw(c)
        d.text((bx + 18, by + 92), name, font=f(21, "semibold"), fill=ICE if focused else DIM)
        d.text((bx + 18, by + 124), cap, font=f(16), fill=AQUA if focused else FAINT)
        if focused:
            panel(c, box, 4, outline=AQUA + (255,), width=3)
        else:
            panel(c, box, 4, outline=(255, 255, 255, 34), width=2)
        d.text((bx + 2, by + chh + 10), f"{i + 1:02d}", font=f(14, "semibold"), fill=FAINT)

    by2 = gy + 2 * (chh + 40) + 6
    d.line((x0, by2, W - 48, by2), fill=(255, 255, 255, 26), width=2)
    gtext(c, (x0, by2 + 14), "02", f(46, "bold"), (255, 255, 255), 30)
    tracked(d, (x0 + 76, by2 + 36), "ALWAYS ON — 24/7 SHELVES", f(17, "semibold"), FAINT, 3)
    sample_tag(c, "DIRECTION 2 · GRID-FORWARD EDITORIAL")
    return c


# ---------------------------------------------------------------- direction 3
def d3_glass():
    c = vgrad((W, H), (10, 16, 28), (6, 9, 16)).convert("RGBA")
    c.alpha_composite(radial(c.size, (360, 220), 480, (24, 118, 116), 150))
    c.alpha_composite(radial(c.size, (1240, 640), 560, (56, 66, 170), 130))
    c.alpha_composite(radial(c.size, (1520, 160), 340, (30, 150, 170), 110))
    c.alpha_composite(radial(c.size, (760, 900), 420, (20, 80, 120), 90))
    d = ImageDraw.Draw(c)

    brand(c, (44, 36), 44)
    glass(c, (W - 210, 52, W - 44, 104), 26, tint=(255, 255, 255, 18), border=(255, 255, 255, 46))
    d.text((W - 127, 78), "8:45 PM", font=f(21, "semibold"), fill=ICE, anchor="mm")

    glass(c, (36, 130, 148, 780), 34, blur=22, tint=(255, 255, 255, 20), border=(255, 255, 255, 44))
    y = 158
    for i, (kind, label) in enumerate(SECTIONS):
        focused = i == 0
        box = (52, y, 132, y + 106)
        if focused:
            glow(c, box, 20, AQUA, alpha=130, blur=18)
            panel(c, box, 20, fill=(50, 229, 206, 50), outline=AQUA + (255,), width=3)
            fg = AQUA
        else:
            fg = (220, 235, 240)
        icon(d, kind, 92, y + 40, 16, fg)
        d.text((92, y + 80), label, font=f(16, "semibold" if focused else "regular"),
               fill=ICE if focused else DIM, anchor="mm")
        y += 122

    x0 = 190
    hb = (x0, 130, W - 44, 452)
    glass(c, hb, 30, blur=30, tint=(12, 20, 32, 90), border=(255, 255, 255, 40))
    c.alpha_composite(radial(c.size, (hb[2] - 320, 280), 300, (40, 190, 175), 80))
    d = ImageDraw.Draw(c)
    glass(c, (hb[0] + 40, hb[1] + 38, hb[0] + 300, hb[1] + 80), 21, tint=(255, 255, 255, 16),
          border=(255, 255, 255, 40))
    d.ellipse((hb[0] + 60, hb[1] + 54, hb[0] + 70, hb[1] + 64), fill=AQUA)
    tracked(d, (hb[0] + 82, hb[1] + 50), "LIVE · SPORTS / HINDI", f(15, "semibold"), ICE, 3)
    d.text((hb[0] + 40, hb[1] + 104), "Star Sports 1 Hindi HD", font=f(56, "bold"), fill=ICE)
    d.text((hb[0] + 40, hb[1] + 184), "IND vs AUS · 3rd ODI — India 214/4 (38.2 ov)", font=f(22), fill=(210, 226, 234))
    chip(c, (hb[0] + 40, hb[1] + 240), "Watch live", f(21, "semibold"), (6, 12, 16),
         (245, 250, 252, 255), pad_x=26, pad_y=14, icon_kind="play")
    glass(c, (hb[0] + 284, hb[1] + 240, hb[0] + 520, hb[1] + 292), 26, tint=(255, 255, 255, 16),
          border=(255, 255, 255, 60))
    icon(d, "plus", hb[0] + 316, hb[1] + 266, 10, ICE)
    d.text((hb[0] + 336, hb[1] + 254), "My favorites", font=f(21, "semibold"), fill=ICE)
    glass(c, (hb[2] - 176, hb[1] + 56, hb[2] - 60, hb[1] + 172), 26, tint=(255, 255, 255, 14),
          border=(255, 255, 255, 46))
    logo_plate(c, (hb[2] - 146, hb[1] + 86), "SS", (14, 68, 58), 56, 14)

    tracked(d, (x0 + 4, 500), "LIVE NOW", f(20, "semibold"), ICE, 3)
    cw, chh = 322, 180
    gx = x0
    for i, (ini, tint, name, cap) in enumerate(CHANNELS[:4]):
        focused = i == 0
        box = (gx, 540, gx + cw, 540 + chh)
        art(c, box, 24, (tint[0] + 10, tint[1] + 18, tint[2] + 20), (12, 18, 28),
            motif="ring" if i % 2 else "band", seed=i + 1)
        d = ImageDraw.Draw(c)
        glass(c, box, 24, blur=10, tint=(255, 255, 255, 12 if not focused else 22),
              border=(255, 255, 255, 46 if not focused else 90))
        if focused:
            glow(c, box, 24, AQUA, alpha=140, blur=24)
            panel(c, box, 24, outline=AQUA + (255,), width=3)
        logo_plate(c, (box[0] + 18, box[1] + 16), ini, tint, 46, 12)
        live_badge(c, (box[2] - 92, box[1] + 16))
        d.text((box[0] + 18, box[3] - 62), name, font=f(21, "semibold"), fill=ICE)
        d.text((box[0] + 18, box[3] - 34), cap, font=f(16), fill=(206, 222, 230))
        gx += cw + 24
    tracked(d, (x0 + 4, 752), "ALWAYS ON", f(20, "semibold"), ICE, 3)
    px = x0
    for j, (title, year, genre, t0, t1) in enumerate(MOVIES):
        art(c, (px, 786, px + 148, 900), 16, t0, t1, motif="band" if j % 2 else None, seed=j + 1)
        panel(c, (px, 786, px + 148, 900), 16, outline=(255, 255, 255, 40), width=2)
        px += 168
    sample_tag(c, "DIRECTION 3 · GLASS")
    return c


# ---------------------------------------------------------------- direction 4
def d4_typographic():
    c = Image.new("RGBA", (W, H), DEEP + (255,))
    d = ImageDraw.Draw(c)
    brand(c, (56, 44), 40)
    d.text((56, 116), "LIVE", font=f(118, "bold"), fill=ICE)
    d.ellipse((56 + measure(d, "LIVE", f(118, "bold")) + 16, 196,
               56 + measure(d, "LIVE", f(118, "bold")) + 34, 214), fill=AQUA)
    clock(c, (W - 56, 76), 24)

    ry = 300
    for i, (kind, label) in enumerate(SECTIONS[:4]):
        focused = i == 0
        col = ICE if focused else (74, 86, 98)
        if focused:
            d.ellipse((60, ry + 12, 70, ry + 22), fill=AQUA)
        tracked(d, (86, ry), label.upper(), f(20 if focused else 17, "bold" if focused else "semibold"),
                col, 5)
        ry += 52

    x0 = 330
    rows = CHANNELS[:5]
    y = 292
    for i, (ini, tint, name, cap) in enumerate(rows):
        focused = i == 0
        rh = 108
        if focused:
            panel(c, (x0 - 24, y - 12, W - 56, y + rh - 8), 10, fill=(255, 255, 255, 10))
            d.rectangle((x0 - 24, y - 12, x0 - 18, y + rh - 8), fill=AQUA)
        d.text((x0, y + 18), f"{i + 1:02d}", font=f(20, "semibold"), fill=AQUA if focused else (70, 82, 94))
        d.text((x0 + 64, y + 4), name.upper(), font=f(46 if focused else 40, "bold"),
               fill=ICE if focused else (86, 98, 110))
        if focused:
            art(c, (W - 356, y - 6, W - 116, y + rh - 2), 8, (tint[0] + 10, tint[1] + 16, tint[2] + 18),
                (10, 16, 24), motif="band", seed=i)
            live_badge(c, (W - 190, y + 2))
            panel(c, (W - 356, y - 6, W - 116, y + rh - 2), 8, outline=AQUA + (255,), width=3)
            d = ImageDraw.Draw(c)
            d.text((x0 + 64, y + 62), cap, font=f(19), fill=AQUA)
        else:
            tw = measure(d, name.upper(), f(40, "bold"))
            d.text((x0 + 64 + tw + 28, y + 30), cap, font=f(17), fill=(70, 82, 94))
        y += rh
    tracked(d, (x0, H - 64), "OK WATCH · MENU OPTIONS · BACK EXIT", f(15, "semibold"), (70, 82, 94), 3)
    sample_tag(c, "DIRECTION 4 · BOLD TYPOGRAPHIC MINIMAL")
    return c


# ---------------------------------------------------------------- direction 5
def d5_broadcast_deck():
    c = vgrad((W, H), (9, 14, 22), MID).convert("RGBA")
    d = ImageDraw.Draw(c)
    panel(c, (0, 0, W, 96), 0, fill=(6, 10, 16, 220))
    brand(c, (44, 26), 44)
    tracked(d, (W // 2 - 130, 40), "TONIGHT ON AK TV", f(17, "semibold"), AQUA, 5)
    clock(c, (W - 44, 76), 22)
    d.line((0, 96, W, 96), fill=(255, 255, 255, 40), width=2)

    rail_w = 116
    panel(c, (0, 96, rail_w, H), 0, fill=(6, 10, 16, 160))
    y = 130
    for i, (kind, label) in enumerate(SECTIONS[:4]):
        focused = i == 0
        box = (12, y, rail_w - 12, y + 96)
        if focused:
            panel(c, box, 14, fill=(50, 229, 206, 36), outline=AQUA + (255,), width=3)
            fg = AQUA
        else:
            fg = DIM
        icon(d, kind, rail_w // 2, y + 36, 15, fg)
        d.text((rail_w // 2, y + 72), label, font=f(16, "semibold" if focused else "regular"),
               fill=ICE if focused else DIM, anchor="mm")
        y += 110

    x0 = rail_w + 40
    tracked(d, (x0, 128), "NOW / NEXT DECK — GUIDE DATA FROM YOUR PROVIDER FEED", f(15, "semibold"), FAINT, 3)
    y = 168
    for i, (ini, tint, name, cap) in enumerate(CHANNELS[:5]):
        focused = i == 0
        rh = 148 if focused else 96
        box = (x0, y, W - 44, y + rh)
        if focused:
            panel(c, box, 16, fill=(14, 24, 34, 235), outline=AQUA + (255,), width=3)
            glow(c, box, 16, AQUA, alpha=90, blur=16, pad=10)
        else:
            panel(c, box, 10, fill=(10, 16, 24, 150))
        d = ImageDraw.Draw(c)
        logo_plate(c, (box[0] + 22, box[1] + (rh - 60) // 2), ini, tint, 60 if focused else 48, 12)
        nx = box[0] + 106
        d.text((nx, box[1] + (18 if focused else 16)), name, font=f(23 if focused else 20, "semibold"),
               fill=ICE)
        live_badge(c, (box[2] - 96, box[1] + 16))
        if focused:
            d.text((nx, box[1] + 52), cap, font=f(18), fill=DIM)
            tracked(d, (nx, box[1] + 88), "NOW", f(14, "bold"), AQUA, 2)
            d.text((nx + 52, box[1] + 86), "8:00 – 9:30 PM", font=f(16), fill=DIM)
            d.text((nx + 220, box[1] + 86), "· 62% elapsed", font=f(16), fill=FAINT)
            progress(c, (nx, box[1] + 114, box[2] - 260, box[1] + 120), 0.62)
            chip(c, (box[2] - 220, box[1] + 92), "Watch", f(19, "semibold"), (6, 12, 16),
                 (245, 250, 252, 255), pad_x=22, pad_y=11, icon_kind="play")
            d.text((nx, box[1] + 128), "", font=f(2), fill=DIM)
        else:
            d.text((nx, box[1] + 48), cap, font=f(16), fill=FAINT)
            progress(c, (nx, box[1] + 78, box[2] - 240, box[1] + 82), [0.3, 0.8, 0.15, 0.5][i % 4])
            d.text((box[2] - 220, box[1] + 48), f"NEXT 9:30 PM", font=f(15, "semibold"), fill=DIM)
        y += rh + 14
        d.line((x0, y - 7, W - 44, y - 7), fill=(255, 255, 255, 18), width=1)
    sample_tag(c, "DIRECTION 5 · BROADCAST DECK")
    return c


# ---------------------------------------------------------------- player overlays
def picture():
    c = vgrad((W, H), (16, 34, 40), (6, 10, 14)).convert("RGBA")
    c.alpha_composite(radial(c.size, (800, 300), 520, (40, 120, 110), 90))
    c.alpha_composite(radial(c.size, (300, 700), 420, (24, 60, 90), 80))
    layer = Image.new("RGBA", c.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for i in range(6):
        d.line((0, 430 + i * 66, W, 400 + i * 66), fill=(255, 255, 255, 16 + i * 5), width=2)
    edge = Image.new("L", (W, 1), 0)
    for x in range(W):
        t = min(x, W - x) / (W * 0.22)
        edge.putpixel((x, 0), round(255 * max(0.0, min(1.0, t))))
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), edge.resize((W, H))))
    c.alpha_composite(layer)
    c.alpha_composite(radial(c.size, (1150, 520), 260, (120, 200, 190), 40))
    return c


def p1_cinematic_player():
    c = picture()
    d = ImageDraw.Draw(c)
    scrim = Image.new("RGBA", (W, 260), (4, 6, 9, 255))
    scrim.putalpha(vgrad((W, 260), (0, 0, 0), (225, 225, 225)).convert("L"))
    c.alpha_composite(scrim, (0, H - 260))
    top = Image.new("RGBA", (W, 120), (4, 6, 9, 255))
    top.putalpha(vgrad((W, 120), (170, 170, 170), (0, 0, 0)).convert("L"))
    c.alpha_composite(top, (0, 0))
    d = ImageDraw.Draw(c)

    chip(c, (40, 36), "Back", f(18, "semibold"), ICE, (10, 14, 20, 150), pad_x=20, pad_y=10)
    gx = 150
    for i, (ini, tint, name, cap) in enumerate(CHANNELS[:6]):
        focused = i == 0
        box = (gx, 30, gx + 76, 30 + 56)
        panel(c, box, 12, fill=tint + ((235,) if focused else (120,)),
              outline=(AQUA + (255,)) if focused else (255, 255, 255, 40), width=3 if focused else 2)
        d.text(((box[0] + box[2]) / 2, (box[1] + box[3]) / 2), ini, font=f(19, "bold"),
               fill=ICE, anchor="mm")
        gx += 88
    tracked(d, (gx + 16, 50), "CHANNEL STRIP · OK TO SWAP", f(14, "semibold"), DIM, 3)
    clock(c, (W - 40, 72), 20)

    y = H - 208
    logo_plate(c, (48, y), "SS", (14, 68, 58), 64, 14)
    d.text((132, y + 4), "Star Sports 1 Hindi HD", font=f(30, "bold"), fill=ICE)
    live_badge(c, (132 + int(measure(d, "Star Sports 1 Hindi HD", f(30, "bold"))) + 22, y + 10))
    d.text((132, y + 44), "NOW · IND vs AUS · 3rd ODI", font=f(18, "semibold"), fill=AQUA)
    d.text((132, y + 74), "NEXT 9:30 PM · Sample Match Review", font=f(17), fill=DIM)
    progress(c, (132, y + 112, 900, y + 118), 0.62)
    d.text((132, y + 128), "8:00 PM", font=f(15), fill=FAINT)
    d.text((900, y + 128), "9:30 PM", font=f(15), fill=FAINT, anchor="rs")
    chip(c, (960, y + 6), "Source · JJTV", f(18, "semibold"), ICE, (255, 255, 255, 14), pad_x=20, pad_y=11,
         outline=(255, 255, 255, 60))
    chip(c, (1160, y + 6), "Quality · Auto", f(18, "semibold"), ICE, (255, 255, 255, 14), pad_x=20,
         pad_y=11, outline=(255, 255, 255, 60))
    chip(c, (1360, y + 6), "Retry", f(18, "semibold"), ICE, (255, 255, 255, 14), pad_x=20, pad_y=11,
         outline=(255, 255, 255, 60), icon_kind="retry")
    tracked(d, (W - 40, H - 40), "OVERLAY AUTO-HIDES IN 3 S", f(14, "semibold"), FAINT, 3, anchor_left=False)
    sample_tag(c, "DIRECTION 1 · PLAYER OVERLAY")
    return c


SHORT = ["Star 1", "YAY", "Zee", "News18", "SAB", "CN"]


def p3_glass_player():
    c = picture()
    glass(c, (40, 32, 486, 92), 30, tint=(255, 255, 255, 16), border=(255, 255, 255, 50))
    d = ImageDraw.Draw(c)
    logo_plate(c, (56, 44), "SS", (14, 68, 58), 38, 10)
    d.text((108, 62), "CH 872 · Sony YAY Hindi", font=f(19, "semibold"), fill=ICE, anchor="lm")
    live_badge(c, (392, 47))
    glass(c, (W - 190, 32, W - 40, 92), 30, tint=(255, 255, 255, 16), border=(255, 255, 255, 50))
    d.text((W - 115, 62), "8:45 PM", font=f(20, "semibold"), fill=ICE, anchor="mm")

    sheet = (40, H - 320, W - 40, H - 40)
    glass(c, sheet, 32, blur=26, tint=(10, 16, 26, 130), border=(255, 255, 255, 44))
    d = ImageDraw.Draw(c)
    tracked(d, (sheet[0] + 40, sheet[1] + 30), "CHANNEL STRIP", f(14, "semibold"), DIM, 3)
    gx = sheet[0] + 40
    for i, (ini, tint, name, cap) in enumerate(CHANNELS):
        focused = i == 0
        box = (gx, sheet[1] + 56, gx + 150, sheet[1] + 140)
        glass(c, box, 16, blur=8, tint=(255, 255, 255, 14 if not focused else 26),
              border=(255, 255, 255, 40 if not focused else 80))
        if focused:
            glow(c, box, 16, AQUA, alpha=120, blur=16, pad=8)
            panel(c, box, 16, outline=AQUA + (255,), width=3)
        logo_plate(c, (box[0] + 12, box[1] + 12), ini, tint, 34, 9)
        d = ImageDraw.Draw(c)
        d.text((box[0] + 56, box[1] + 29), SHORT[i], font=f(16, "semibold"), fill=ICE, anchor="lm")
        gx += 166
    d.text((sheet[0] + 40, sheet[1] + 164), "NOW · IND vs AUS · 3rd ODI", font=f(21, "semibold"), fill=ICE)
    d.text((sheet[0] + 40, sheet[1] + 196), "NEXT 9:30 PM · Sample Match Review", font=f(17), fill=DIM)
    progress(c, (sheet[0] + 40, sheet[1] + 232, sheet[0] + 760, sheet[1] + 238), 0.62)
    chip(c, (sheet[2] - 560, sheet[1] + 160), "Source · JJTV", f(18, "semibold"), ICE, (255, 255, 255, 14),
         pad_x=20, pad_y=11, outline=(255, 255, 255, 60))
    chip(c, (sheet[2] - 360, sheet[1] + 160), "Quality · Auto", f(18, "semibold"), ICE, (255, 255, 255, 14),
         pad_x=20, pad_y=11, outline=(255, 255, 255, 60))
    chip(c, (sheet[2] - 160, sheet[1] + 160), "Retry", f(18, "semibold"), ICE, (255, 255, 255, 14),
         pad_x=20, pad_y=11, outline=(255, 255, 255, 60), icon_kind="retry")
    sample_tag(c, "DIRECTION 3 · PLAYER OVERLAY", y=112)
    return c


# ---------------------------------------------------------------- movies studies
def poster(canvas, box, title, year, tint, focused=False, rank=None):
    if rank:
        d = ImageDraw.Draw(canvas)
        gtext(canvas, (box[0] - 30, box[1] - 92), str(rank), f(170, "bold"), (255, 255, 255), 34)
    art(canvas, box, 14, tint[0], tint[1], motif="ring" if rank % 2 else "band", seed=rank)
    d = ImageDraw.Draw(canvas)
    w = box[2] - box[0]
    h = box[3] - box[1]
    size = 24
    while size > 15 and measure(d, title, f(size, "bold")) > w - 34:
        size -= 1
    d.text((box[0] + 17, box[3] - 74), title, font=f(size, "bold"), fill=ICE)
    d.text((box[0] + 18, box[3] - 42), year, font=f(16), fill=DIM)
    if focused:
        glow(canvas, box, 16, AQUA, alpha=140, blur=22)
        panel(canvas, box, 14, outline=AQUA + (255,), width=3)
    else:
        panel(canvas, box, 14, outline=(255, 255, 255, 34), width=2)


def m2_editorial_movies():
    c = Image.new("RGBA", (W, H), MID + (255,))
    d = ImageDraw.Draw(c)
    brand(c, (48, 40), 40)
    d.line((48, 108, W - 48, 108), fill=(255, 255, 255, 60), width=2)
    tracked(d, (48, 122), "TUESDAY · 20 OCT · ISSUE 4.9", f(15, "semibold"), FAINT, 3)
    clock(c, (W - 48, 72))
    ry = 190
    for i, (kind, label) in enumerate(SECTIONS[:4]):
        focused = i == 2
        d.text((52, ry), f"{i + 1:02d}", font=f(17, "semibold"), fill=AQUA if focused else FAINT)
        d.text((92, ry - 8), label.upper(), font=f(27 if focused else 22, "bold" if focused else "semibold"),
               fill=ICE if focused else FAINT)
        if focused:
            d.line((92, ry + 30, 92 + measure(d, label.upper(), f(27, "bold")), ry + 30), fill=AQUA, width=4)
        ry += 66

    x0 = 300
    gtext(c, (x0, 140), "03", f(74, "bold"), (255, 255, 255), 40)
    tracked(d, (x0 + 108, 186), "MOVIES — THIS WEEK'S FRONT ROW", f(19, "semibold"), ICE, 3)
    d.line((x0, 234, W - 48, 234), fill=(255, 255, 255, 46), width=2)
    d.text((x0, 258), "NORTHWIND", font=f(54, "bold"), fill=ICE)
    d.text((x0, 326), "2025 · U/A 16+ · 2 h 14 m", font=f(19), fill=DIM)
    for j, txt in enumerate(["THRILLER", "HEIST", "HINDI DUB"]):
        xx = x0 + j * 150
        d.line((xx, 372, xx + 118, 372), fill=(255, 255, 255, 50), width=2)
        tracked(d, (xx, 382), txt, f(14, "semibold"), FAINT, 3)
    pw, ph = 218, 327
    gx = x0
    for i, (title, year, genre, t0, t1) in enumerate(MOVIES):
        box = (gx, 452, gx + pw, 452 + ph)
        poster(c, box, title, year, (t0, t1), focused=i == 0, rank=i + 1)
        gx += pw + 30
    sample_tag(c, "DIRECTION 2 · MOVIES TAB")
    return c


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = [
        ("direction-1-rail-cinematic-tv", d1_rail_cinematic),
        ("direction-2-grid-editorial-tv", d2_grid_editorial),
        ("direction-3-glass-tv", d3_glass),
        ("direction-4-typographic-minimal-tv", d4_typographic),
        ("direction-5-broadcast-deck-tv", d5_broadcast_deck),
        ("direction-1-rail-cinematic-player", p1_cinematic_player),
        ("direction-3-glass-player", p3_glass_player),
        ("direction-2-grid-editorial-movies", m2_editorial_movies),
    ]
    for name, fn in jobs:
        fn().convert("RGB").save(OUT / f"{name}.webp", quality=88, method=6)
        print("rendered", name)
