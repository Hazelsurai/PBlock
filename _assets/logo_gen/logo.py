"""PBlock 'Pb' logo: one geometry, emitted as VectorDrawables, SVGs and PNG renders."""
import io, math, os, cairosvg
from PIL import Image, ImageDraw

RED = "#EA111B"; BLACK = "#000000"; WHITE = "#FFFFFF"
# Geometry in a 1024 design grid (traced from the concept, rebuilt with clean primitives).
BOX = "M231,297 H492 A56,56 0 0 1 548,353 V641 A56,56 0 0 1 492,697 H231 A56,56 0 0 1 175,641 V353 A56,56 0 0 1 231,297 Z"
P = "M252,360 H382 A96,96 0 0 1 382,552 H318 V636 H252 Z M318,418 H382 A38,38 0 0 1 382,494 H318 Z"
B = "M594,323 H664 V474.4 A104,129 0 1 1 664,603.6 V668 H594 Z M725,472 A61,68 0 1 1 725,608 A61,68 0 1 1 725,472 Z"
BBOX = (175, 297, 858, 697)
CX, CY = (BBOX[0] + BBOX[2]) / 2, (BBOX[1] + BBOX[3]) / 2

def fit(view, width):
    """scale + translate so the mark is `width` wide, centred in a square viewport `view`."""
    s = width / (BBOX[2] - BBOX[0])
    return s, view / 2 - CX * s, view / 2 - CY * s

def r(v): return f"{v:.4f}".rstrip("0").rstrip(".")

def vd(view, size_dp, layers, width, bg=None, comment=""):
    s, tx, ty = fit(view, width)
    out = ['<?xml version="1.0" encoding="utf-8"?>']
    if comment: out.append(f"<!-- {comment} -->")
    out += ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            f'    android:width="{size_dp}dp"', f'    android:height="{size_dp}dp"',
            f'    android:viewportWidth="{view}"', f'    android:viewportHeight="{view}">']
    if bg: out += bg
    out += [f'    <group android:scaleX="{r(s)}" android:scaleY="{r(s)}" android:translateX="{r(tx)}" android:translateY="{r(ty)}">']
    for d, color, eo in layers:
        out += ['        <path', f'            android:fillColor="{color}"'] + (['            android:fillType="evenOdd"'] if eo else []) + [f'            android:pathData="{d}" />']
    out += ['    </group>', '</vector>']
    return "\r\n".join(out) + "\r\n"

# Adaptive icon: 108dp canvas, safe zone = central 66dp circle. Mark 56 wide x 32.8 high -> corners at r=32.4 < 33.
ICON_W = 56
FG = [(BOX, RED, False), (P, BLACK, True), (B, WHITE, True)]
MONO = [(BOX + " " + P, BLACK, True), (B, BLACK, True)]   # box with P cut out (P counter stays filled), b with hole

def svg(view, layers, width, bg=None, clip=None):
    s, tx, ty = fit(view, width)
    body = "".join(f'<path fill="{c}" fill-rule="{"evenodd" if eo else "nonzero"}" d="{d}"/>' for d, c, eo in layers)
    pre = f'<rect width="{view}" height="{view}" fill="{bg}"/>' if bg else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {view} {view}" width="{view}" height="{view}">{pre}<g transform="translate({tx},{ty}) scale({s})">{body}</g></svg>'

def render(svgtext, px):
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svgtext.encode(), output_width=px, output_height=px))).convert("RGBA")

def full_icon(px, crop=72):
    """Launcher view: the central `crop` dp of the 108dp adaptive canvas, at px pixels."""
    big = render(svg(108, FG, ICON_W, bg=BLACK), round(px * 108 / crop))
    o = (big.width - px) // 2
    return big.crop((o, o, o + px, o + px))

def mask(px, kind):
    ss = 4; m = Image.new("L", (px * ss, px * ss), 0); d = ImageDraw.Draw(m); n = px * ss
    if kind == "circle": d.ellipse((0, 0, n - 1, n - 1), fill=255)
    elif kind == "rounded": d.rounded_rectangle((0, 0, n - 1, n - 1), radius=n * 0.18, fill=255)
    elif kind == "squircle":
        pts = []; e = 5.0
        for i in range(720):
            t = 2 * math.pi * i / 720; c, s = math.cos(t), math.sin(t)
            pts.append((n / 2 + n / 2 * math.copysign(abs(c) ** (2 / e), c), n / 2 + n / 2 * math.copysign(abs(s) ** (2 / e), s)))
        d.polygon(pts, fill=255)
    return m.resize((px, px), Image.LANCZOS)

def masked(img, kind):
    out = Image.new("RGBA", img.size, (0, 0, 0, 0)); out.paste(img, (0, 0), mask(img.width, kind)); return out
