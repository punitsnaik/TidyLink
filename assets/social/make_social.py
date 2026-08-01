#!/usr/bin/env python3
"""TidyLink share images + README header. Run: python3 make_social.py

Two rules that are not obvious and cost a redo each:

1. The field is LIGHT because the icon tile keeps the real launcher gradient
   (#14B8A6 -> #115E59). A dark teal background makes the tile go muddy - that
   is why the first dark version was scrapped.
2. The README banner carries ONLY the icon + wordmark. The README repeats the
   title, tagline and feature list immediately below it, so anything more makes
   the top of the page stutter three times.
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter

F = "/usr/share/fonts/truetype/lato/Lato-%s.ttf"
BLACK, BOLD, REG, LIGHT = F % "Black", F % "Bold", F % "Regular", F % "Light"

BG0, BG1 = (133, 240, 226), (38, 198, 180)    # aqua field, diagonal
TILE0, TILE1 = (20, 184, 166), (17, 94, 89)   # launcher gradient - do not change
INK = (7, 54, 51)                             # headline
BODY = (11, 78, 74)                           # bullets
MUTED = (14, 104, 98)                         # tagline / fine print
DOT = (13, 118, 110)

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


def over(base, layer, pos):
    """alpha_composite that tolerates clipping and negative offsets."""
    base.paste(layer, (round(pos[0]), round(pos[1])), layer)


GLYPH = Image.open("glyph.png").convert("RGBA")  # white foreground on the 108dp canvas


def icon(px):
    """The real launcher icon: Android's adaptive mask crops the centre 72/108."""
    t = grad((px, px), TILE0, TILE1).convert("RGBA")
    c = GLYPH.width * (72 / 108)
    o = (GLYPH.width - c) / 2
    g = GLYPH.crop((round(o), round(o), round(o + c), round(o + c))).resize((px, px), Image.LANCZOS)
    over(t, g, (0, 0))
    t.putalpha(rounded_mask((px, px), round(px * 0.24)))
    return t


def shadow(im, blur, off, alpha=64):
    s = Image.new("RGBA", (im.width + blur * 4, im.height + blur * 4), (0, 0, 0, 0))
    lay = Image.new("RGBA", im.size, (0, 40, 38, alpha))
    lay.putalpha(im.getchannel("A").point(lambda v: v * alpha // 255))
    s.paste(lay, (blur * 2, blur * 2 + off), lay)
    return s.filter(ImageFilter.GaussianBlur(blur))


def watermark(im, w, h):
    px = round(min(w, h) * 1.15)
    a = GLYPH.resize((px, px), Image.LANCZOS).getchannel("A")
    tint = Image.new("RGBA", (px, px), (5, 70, 66, 0))
    tint.putalpha(a.point(lambda v: v * 24 // 255))
    over(im, tint, (w - round(px * 0.72), h - round(px * 0.78)))


def build_banner(name, w=1280, h=320):
    """README header: icon + wordmark only, nothing the README repeats below."""
    im = grad((w, h), BG0, BG1).convert("RGBA")
    d = ImageDraw.Draw(im)
    f = ImageFont.truetype(BLACK, 132)
    ip, gap = 196, 56
    x = round((w - (ip + gap + d.textlength(TITLE, f))) / 2)
    y = (h - ip) // 2
    ic = icon(ip)
    over(im, shadow(ic, 26, 14), (x - 52, y - 52))
    over(im, ic, (x, y))
    d.text((x + ip + gap, h // 2), TITLE, INK, f, anchor="lm")
    im.convert("RGB").save(name, optimize=True)
    print(name, im.size)


def build(w, h, layout, name):
    im = grad((w, h), BG0, BG1).convert("RGBA")
    d = ImageDraw.Draw(im)
    watermark(im, w, h)

    if layout == "wide":
        s = h / 630
        u = lambda v: round(v * s)
        ip, pad = u(190), u(78)
        ic = icon(ip)
        over(im, shadow(ic, u(22), u(10)), (pad - u(44), h / 2 - ip / 2 - u(44)))
        over(im, ic, (pad, h / 2 - ip / 2))
        x = pad + ip + u(64)
        f_t = ImageFont.truetype(BLACK, u(104))
        f_g = ImageFont.truetype(LIGHT, u(38))
        f_b = ImageFont.truetype(REG, u(29))
        f_f = ImageFont.truetype(BOLD, u(25))
        blk = u(104) + u(22) + u(38) + u(38) + 3 * u(46) + u(34) + u(25)
        y = round(h / 2 - blk / 2)
        d.text((x, y), TITLE, INK, f_t); y += u(104) + u(22)
        d.text((x, y), TAG, MUTED, f_g); y += u(38) + u(38)
        for b in BULLETS:
            d.ellipse([x + u(2), y + u(12), x + u(14), y + u(24)], fill=DOT)
            d.text((x + u(32), y), b, BODY, f_b); y += u(46)
        y += u(34)
        d.text((x, y), FOOT, MUTED, f_f)
    else:
        cx = w // 2
        margin = round(min(w, h) * 0.085)
        G = 78 if h > w * 1.4 else 52
        # block height is linear in k, so k is solved to fit the canvas instead of
        # hand-tuned per format - adding a bullet rescales everything
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
        d.text((cx, y), TITLE, INK, f_t, anchor="ma"); y += u(150) + u(30)
        for ln in ["A tidy home for every", "link you save."]:
            d.text((cx, y), ln, MUTED, f_g, anchor="ma"); y += u(66)
        y += u(G)
        bw = max(d.textlength(b, f_b) for b in BULLETS) + u(48)
        bx = round(cx - bw / 2)
        for b in BULLETS:
            d.ellipse([bx, y + u(16), bx + u(17), y + u(33)], fill=DOT)
            d.text((bx + u(48), y), b, BODY, f_b); y += u(74)
        y += u(G)
        d.text((cx, y), FOOT, INK, f_f, anchor="ma"); y += u(36) + u(22)
        d.text((cx, y), FOOT2, MUTED, f_f2, anchor="ma")

    im.convert("RGB").save(name, optimize=True)
    print(name, im.size)


if __name__ == "__main__":
    build_banner("tidylink-readme-banner-1280x320.png")
    build(1200, 630, "wide", "tidylink-link-preview-1200x630.png")
    build(1080, 1080, "center", "tidylink-square-1080x1080.png")
    build(1080, 1920, "center", "tidylink-story-1080x1920.png")
