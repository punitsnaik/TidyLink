#!/usr/bin/env python3
"""TidyLink social share images. Run: python3 make_social.py"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = "."
F = "/usr/share/fonts/truetype/lato/Lato-%s.ttf"
BLACK, BOLD, REG, LIGHT = F % "Black", F % "Bold", F % "Regular", F % "Light"

BG0, BG1 = (13, 74, 70), (6, 38, 36)          # deep teal, diagonal
TILE0, TILE1 = (20, 184, 166), (17, 94, 89)   # launcher gradient
WHITE = (255, 255, 255)
MUTED = (168, 214, 208)
ACCENT = (94, 234, 212)

TITLE = "TidyLink"
TAG = "A tidy home for every link you save."
BULLETS = ["Share a link from any app", "AI sorts and summarises it", "On-device. No account, no tracking"]
FOOT = "github.com/punitsnaik/TidyLink"
FOOT2 = "Free & open source  ·  MIT  ·  Android 10+"


def grad(size, c0, c1, diagonal=True):
    w, h = size
    s = Image.new("RGB", (64, 64))
    px = s.load()
    for y in range(64):
        for x in range(64):
            t = (x + y) / 126 if diagonal else y / 63
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(c0, c1))
    return s.resize((w, h), Image.BICUBIC)


def rounded_mask(size, r):
    m = Image.new("L", (size[0] * 4, size[1] * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] * 4 - 1, size[1] * 4 - 1], r * 4, fill=255)
    return m.resize(size, Image.LANCZOS)


GLYPH = Image.open("glyph.png").convert("RGBA")  # white foreground on 108dp canvas


def icon(px):
    """Reproduce the launcher icon: adaptive mask crops the centre 72/108 of the canvas."""
    tile = grad((px, px), TILE0, TILE1)
    g = GLYPH.copy()
    c = g.width * (72 / 108)
    o = (g.width - c) / 2
    g = g.crop((round(o), round(o), round(o + c), round(o + c))).resize((px, px), Image.LANCZOS)
    tile = tile.convert("RGBA")
    tile.alpha_composite(g)
    tile.putalpha(rounded_mask((px, px), round(px * 0.24)))
    return tile


def shadow(im, blur, off, alpha=110):
    s = Image.new("RGBA", (im.width + blur * 4, im.height + blur * 4), (0, 0, 0, 0))
    lay = Image.new("RGBA", im.size, (0, 0, 0, alpha))
    lay.putalpha(im.getchannel("A").point(lambda v: v * alpha // 255))
    s.paste(lay, (blur * 2, blur * 2 + off), lay)
    return s.filter(ImageFilter.GaussianBlur(blur))


def over(base, layer, pos):
    """alpha_composite that tolerates clipping / negative offsets."""
    base.paste(layer, (round(pos[0]), round(pos[1])), layer)


def build(w, h, layout, name):
    im = grad((w, h), BG0, BG1).convert("RGBA")
    d = ImageDraw.Draw(im)

    # watermark glyph, bottom-right
    wm_px = round(min(w, h) * 1.15)
    wm = GLYPH.resize((wm_px, wm_px), Image.LANCZOS)
    wm.putalpha(wm.getchannel("A").point(lambda v: v * 16 // 255))
    over(im, wm, (w - round(wm_px * 0.72), h - round(wm_px * 0.78)))

    if layout == "wide":
        s = h / 630
        ip = round(190 * s)
        pad = round(78 * s)
        ic = icon(ip)
        over(im, shadow(ic, round(22 * s), round(10 * s)), (pad - round(44 * s), round(h / 2 - ip / 2) - round(44 * s)))
        over(im, ic, (pad, round(h / 2 - ip / 2)))
        x = pad + ip + round(64 * s)
        f_t, f_g, f_b, f_f = (ImageFont.truetype(BLACK, round(104 * s)), ImageFont.truetype(LIGHT, round(38 * s)),
                              ImageFont.truetype(REG, round(29 * s)), ImageFont.truetype(BOLD, round(25 * s)))
        blk = round(104 * s) + round(22 * s) + round(38 * s) + round(38 * s) + 3 * round(46 * s) + round(34 * s) + round(25 * s)
        y = round(h / 2 - blk / 2)
        d.text((x, y), TITLE, WHITE, f_t); y += round(104 * s) + round(22 * s)
        d.text((x, y), TAG, MUTED, f_g); y += round(38 * s) + round(38 * s)
        for b in BULLETS:
            d.ellipse([x + round(2 * s), y + round(12 * s), x + round(14 * s), y + round(24 * s)], fill=ACCENT)
            d.text((x + round(32 * s), y), b, WHITE, f_b); y += round(46 * s)
        y += round(34 * s) - round(46 * s) + round(46 * s)
        d.text((x, y), FOOT, ACCENT, f_f)
    else:
        cx = w // 2
        margin = round(min(w, h) * 0.085)
        G = 78 if h > w * 1.4 else 52
        # unit-space block height (linear in k), so k can be solved to fit the canvas
        unit = 300 + 70 + 150 + 30 + 2 * 66 + G + 3 * 74 + G + 36 + 22 + 29
        k = min(1.12 * (w / 1080), (h - 2 * margin) / unit)
        u = lambda v: round(v * k)

        f_t = ImageFont.truetype(BLACK, u(150))
        f_g = ImageFont.truetype(LIGHT, u(52))
        f_b = ImageFont.truetype(REG, u(42))
        f_f = ImageFont.truetype(BOLD, u(36))
        f_f2 = ImageFont.truetype(REG, u(29))

        ip = u(300)
        y = round(h / 2 - unit * k / 2)
        ic = icon(ip)
        over(im, shadow(ic, u(30), u(14)), (cx - ip // 2 - u(60), y - u(60)))
        over(im, ic, (cx - ip // 2, y))
        y += ip + u(70)
        d.text((cx, y), TITLE, WHITE, f_t, anchor="ma"); y += u(150) + u(30)
        for ln in ["A tidy home for every", "link you save."]:
            d.text((cx, y), ln, MUTED, f_g, anchor="ma"); y += u(66)
        y += u(G)
        bw = max(d.textlength(b, f_b) for b in BULLETS) + u(48)
        bx = round(cx - bw / 2)
        for b in BULLETS:
            d.ellipse([bx, y + u(16), bx + u(17), y + u(33)], fill=ACCENT)
            d.text((bx + u(48), y), b, WHITE, f_b); y += u(74)
        y += u(G)
        d.text((cx, y), FOOT, ACCENT, f_f, anchor="ma"); y += u(36) + u(22)
        d.text((cx, y), FOOT2, MUTED, f_f2, anchor="ma")

    im.convert("RGB").save(f"{OUT}/{name}.png", optimize=True)
    print(name, im.size)


if __name__ == "__main__":
    build(1200, 630, "wide", "tidylink-link-preview-1200x630")
    build(1280, 640, "wide", "tidylink-readme-banner-1280x640")
    build(1080, 1080, "center", "tidylink-square-1080x1080")
    build(1080, 1920, "center", "tidylink-story-1080x1920")
