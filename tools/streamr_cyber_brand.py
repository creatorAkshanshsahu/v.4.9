#!/usr/bin/env python3
"""STREAMR cyberpunk identity: one geometry for every logo bitmap the app ships.

    python3 tools/streamr_cyber_brand.py        # needs Pillow

Writes (same file names the app already uses, so every screen picks the new logo up):
  res/drawable-nodpi/streamr_mark.png        512 x 512   mark
  res/drawable-nodpi/streamr_mark_small.png  256 x 256   mark
  res/drawable-nodpi/streamr_icon_fg.png     432 x 432   adaptive-icon foreground (mark in the safe zone)
  res/drawable-nodpi/streamr_logo_full.png   1200 x 278  wordmark + "BY AKSHANSH SAHU"
  res/drawable-nodpi/banner_img.png          640 x 360   Android TV banner
  design/streamr-cyber-*.png                 previews

The mark: a chamfered neon plate (top-left and bottom-right corners cut, the cyberpunk silhouette)
holding an angular "S" whose middle bar is cut by a play triangle, with an RGB-split glitch.
Palette: neon magenta #FF00D0 and cyan #00ECF7 (STREAMR's own), acid yellow #FCEE0A as the signal.
"""
import math
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
NODPI = ROOT / 'app/src/main/res/drawable-nodpi'
DESIGN = ROOT / 'design'
FONT = ROOT / 'app/src/main/res/font/rajdhani_bold.ttf'

MAGENTA = (255, 0, 208)
CYAN = (0, 236, 247)
ACID = (252, 238, 10)
INK = (6, 4, 16)

# ---- geometry, in a 100 x 100 box ------------------------------------------------------------
PLATE = [(20, 4), (96, 4), (96, 80), (80, 96), (4, 96), (4, 20)]
S_PATH = [(72, 26), (35, 26), (28, 33), (28, 43), (35, 50), (65, 50), (72, 57), (72, 67), (65, 74), (28, 74)]
S_WIDTH = 10.5
PLAY = [(45, 44.2), (45, 55.8), (55, 50)]


def stroke_polygon(points, width):
    """Mitred outline of a thick polyline (all corners here are 45/90 degrees, so mitres stay short)."""
    h = width / 2
    def offset(p, q, side):
        dx, dy = q[0] - p[0], q[1] - p[1]
        L = math.hypot(dx, dy)
        nx, ny = -dy / L * side, dx / L * side
        return (p[0] + nx * h, p[1] + ny * h), (q[0] + nx * h, q[1] + ny * h)
    def intersect(a1, a2, b1, b2):
        d = (a2[0] - a1[0]) * (b2[1] - b1[1]) - (a2[1] - a1[1]) * (b2[0] - b1[0])
        if abs(d) < 1e-9:
            return a2
        t = ((b1[0] - a1[0]) * (b2[1] - b1[1]) - (b1[1] - a1[1]) * (b2[0] - b1[0])) / d
        return (a1[0] + t * (a2[0] - a1[0]), a1[1] + t * (a2[1] - a1[1]))
    sides = []
    for side in (1, -1):
        segs = [offset(points[i], points[i + 1], side) for i in range(len(points) - 1)]
        out = [segs[0][0]]
        for i in range(len(segs) - 1):
            out.append(intersect(segs[i][0], segs[i][1], segs[i + 1][0], segs[i + 1][1]))
        out.append(segs[-1][1])
        sides.append(out)
    return sides[0] + sides[1][::-1]


def scale(points, s, ox=0, oy=0):
    return [(x * s + ox, y * s + oy) for x, y in points]


def gradient(size, top, bottom):
    w, h = size
    g = Image.new('RGB', (1, 256))
    g.putdata([tuple(round(top[i] + (bottom[i] - top[i]) * t / 255) for i in range(3)) for t in range(256)])
    return g.resize((w, h), Image.Resampling.BICUBIC)


def glow(layer, radius, strength=1.0):
    blurred = layer.filter(ImageFilter.GaussianBlur(radius))
    if strength != 1.0:
        a = blurred.getchannel('A').point(lambda v: min(255, int(v * strength)))
        blurred.putalpha(a)
    return blurred


def scanlines(size, alpha=22, step=3):
    w, h = size
    img = Image.new('RGBA', size, (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(0, h, step):
        d.line((0, y, w, y), fill=(0, 0, 0, alpha))
    return img


def mark(size, plate=True):
    S = size * 3  # supersample
    k = S / 100
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    if plate:
        # plate body + edge
        mask = Image.new('L', (S, S), 0)
        ImageDraw.Draw(mask).polygon(scale(PLATE, k), fill=255)
        body = gradient((S, S), (18, 10, 38), (6, 4, 16)).convert('RGBA'); body.putalpha(mask)
        edge = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        ed = ImageDraw.Draw(edge)
        pts = scale(PLATE, k)
        ed.line(pts + [pts[0]], fill=CYAN + (255,), width=round(1.6 * k), joint='curve')
        # acid-yellow signal stripes on the two cut corners
        ed.line([scale([(4, 24)], k)[0], scale([(24, 4)], k)[0]], fill=ACID + (255,), width=round(2.6 * k))
        ed.line([scale([(96, 76)], k)[0], scale([(76, 96)], k)[0]], fill=ACID + (255,), width=round(2.6 * k))
        img.alpha_composite(glow(edge, 3 * k, 1.4))
        img.alpha_composite(body)
        img.alpha_composite(edge)
    # the S: magenta -> cyan neon with an RGB-split glitch, built on its own layer so the glitch
    # slices displace only the letter, never the plate edge
    letter = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    s_mask = Image.new('L', (S, S), 0)
    sd = ImageDraw.Draw(s_mask)
    sd.polygon(scale(stroke_polygon(S_PATH, S_WIDTH), k), fill=255)
    sd.polygon(scale(PLAY, k), fill=0)
    fill = gradient((S, S), MAGENTA, CYAN).convert('RGBA'); fill.putalpha(s_mask)
    off = round(2.4 * k)
    for colour, dx in ((CYAN, -off), (MAGENTA, off)):
        ghost = Image.new('RGBA', (S, S), colour + (0,)); ghost.putalpha(s_mask.point(lambda v: v * 150 // 255))
        letter.alpha_composite(ImageChops.offset(ghost, dx, 0))
    letter.alpha_composite(fill)
    tri = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    inset = [(47.2, 46.9), (47.2, 53.1), (52.6, 50)]
    ImageDraw.Draw(tri).polygon(scale(inset, k), fill=ACID + (255,))
    letter.alpha_composite(glow(tri, 2 * k, 1.5)); letter.alpha_composite(tri)
    for (y0, y1, dx) in ((35.5, 38.5, 3.2), (63.0, 65.2, -2.6)):
        band = letter.crop((0, round(y0 * k), S, round(y1 * k)))
        letter.paste((0, 0, 0, 0), (0, round(y0 * k), S, round(y1 * k)))
        letter.alpha_composite(band, (round(dx * k), round(y0 * k)))
    img.alpha_composite(glow(letter, 4 * k, 1.5))
    img.alpha_composite(letter)
    if plate:
        lines = scanlines((S, S), alpha=30, step=max(3, round(k * 1.2)))
        clip = Image.new('L', (S, S), 0); ImageDraw.Draw(clip).polygon(scale(PLATE, k), fill=255)
        lines.putalpha(ImageChops.multiply(lines.getchannel('A'), clip))
        img.alpha_composite(lines)
    return img.resize((size, size), Image.Resampling.LANCZOS)


def wordmark(width=1200, height=278, credit=True, bg=None):
    W, H = width * 2, height * 2
    img = Image.new('RGBA', (W, H), bg or (0, 0, 0, 0))
    font = ImageFont.truetype(str(FONT), round(H * 0.62))
    text = 'STREAMR'
    probe = ImageDraw.Draw(img)
    track = H * 0.055
    widths = [probe.textlength(c, font=font) for c in text]
    total = sum(widths) + track * (len(text) - 1)
    x0 = (W - total) / 2; y = H * 0.40
    m = Image.new('L', (W, H), 0); md = ImageDraw.Draw(m)
    x = x0
    for c, cw in zip(text, widths):
        md.text((x, y), c, font=font, fill=255, anchor='lm'); x += cw + track
    # a diagonal slash cut through the letters (cyberpunk)
    md.polygon([(x0 + total * 0.46, y - H * 0.40), (x0 + total * 0.475, y - H * 0.40),
                (x0 + total * 0.445, y + H * 0.40), (x0 + total * 0.43, y + H * 0.40)], fill=0)
    # left-to-right magenta -> cyan, built as one row and stretched
    row = Image.new('RGB', (W, 1))
    row.putdata([tuple(round(MAGENTA[i] + (CYAN[i] - MAGENTA[i]) * min(1, max(0, (px - x0) / total))) for i in range(3))
                 for px in range(W)])
    fill = row.resize((W, H))
    letters = fill.convert('RGBA'); letters.putalpha(m)
    off = round(H * 0.018)
    for colour, dx in ((CYAN, -off), (MAGENTA, off)):
        ghost = Image.new('RGBA', (W, H), colour + (0,)); ghost.putalpha(m.point(lambda v: v * 140 // 255))
        img.alpha_composite(ImageChops.offset(ghost, dx, 0))
    img.alpha_composite(glow(letters, H * 0.03, 1.5))
    img.alpha_composite(letters)
    # glitch slice
    y0, y1 = round(H * 0.30), round(H * 0.36)
    band = img.crop((0, y0, W, y1)); img.paste((0, 0, 0, 0) if bg is None else bg, (0, y0, W, y1))
    img.alpha_composite(band, (round(H * 0.03), y0))
    d = ImageDraw.Draw(img)
    # acid HUD bracket + underline bar
    bx = x0 - H * 0.10
    d.line([(bx, y - H * 0.26), (bx, y + H * 0.26)], fill=ACID + (255,), width=round(H * 0.022))
    d.line([(bx, y - H * 0.26), (bx + H * 0.06, y - H * 0.26)], fill=ACID + (255,), width=round(H * 0.022))
    d.line([(bx, y + H * 0.26), (bx + H * 0.06, y + H * 0.26)], fill=ACID + (255,), width=round(H * 0.022))
    if credit:
        cf = ImageFont.truetype(str(FONT), round(H * 0.15))
        label = '//  BY AKSHANSH SAHU'
        cx = x0 + total; cy = H * 0.86
        spacing = H * 0.03
        lw = sum(d.textlength(c, font=cf) + spacing for c in label)
        x = cx - lw
        for c in label:
            d.text((x, cy), c, font=cf, fill=CYAN + (255,), anchor='lm'); x += d.textlength(c, font=cf) + spacing
        d.rectangle([x0, cy - H * 0.012, cx - lw - H * 0.06, cy + H * 0.012], fill=ACID + (255,))
    return img.resize((width, height), Image.Resampling.LANCZOS)


def grid_floor(img, horizon, colour=MAGENTA, alpha=110):
    w, h = img.size
    d = ImageDraw.Draw(img)
    cx = w / 2
    for i in range(-14, 15):
        d.line([(cx + i * w * 0.02, horizon), (cx + i * w * 0.16, h)], fill=colour + (alpha,), width=max(1, w // 600))
    y = horizon; step = (h - horizon) * 0.04
    while y < h:
        d.line([(0, y), (w, y)], fill=colour + (alpha,), width=max(1, w // 700)); step *= 1.32; y += step


def banner(w=640, h=360):
    W, H = w * 2, h * 2
    img = gradient((W, H), (12, 6, 30), (4, 3, 12)).convert('RGBA')
    horizon = round(H * 0.70)
    sun = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sun).ellipse((W * 0.30, horizon - H * 0.55, W * 0.70, horizon + H * 0.15), fill=MAGENTA + (90,))
    img.alpha_composite(sun.filter(ImageFilter.GaussianBlur(H * 0.08)))
    floor = Image.new('RGBA', (W, H), (0, 0, 0, 0)); grid_floor(floor, horizon, MAGENTA, 120)
    img.alpha_composite(glow(floor, 3, 1.2)); img.alpha_composite(floor)
    m = mark(round(H * 0.56))
    img.alpha_composite(m, (round(W * 0.06), round(H * 0.18)))
    wm = wordmark(round(W * 0.58), round(W * 0.58 * 278 / 1200))
    img.alpha_composite(wm, (round(W * 0.38), round(H * 0.30)))
    img.alpha_composite(scanlines((W, H), alpha=28, step=4))
    return img.resize((w, h), Image.Resampling.LANCZOS)


def main():
    NODPI.mkdir(parents=True, exist_ok=True); DESIGN.mkdir(parents=True, exist_ok=True)
    big = mark(1024)
    big.resize((512, 512), Image.Resampling.LANCZOS).save(NODPI / 'streamr_mark.png', optimize=True)
    big.resize((256, 256), Image.Resampling.LANCZOS).save(NODPI / 'streamr_mark_small.png', optimize=True)
    fg = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
    fg.alpha_composite(big.resize((272, 272), Image.Resampling.LANCZOS), (80, 80))   # inside the 66/108 safe zone
    fg.save(NODPI / 'streamr_icon_fg.png', optimize=True)
    wordmark().save(NODPI / 'streamr_logo_full.png', optimize=True)
    banner().convert('RGB').save(NODPI / 'banner_img.png', optimize=True)
    big.save(DESIGN / 'streamr-cyber-mark.png', optimize=True)
    wordmark(2400, 556).save(DESIGN / 'streamr-cyber-wordmark.png', optimize=True)
    banner(1280, 720).convert('RGB').save(DESIGN / 'streamr-cyber-banner.png', optimize=True)
    print('wrote STREAMR cyberpunk mark, icon, wordmark and banner')


if __name__ == '__main__':
    main()
