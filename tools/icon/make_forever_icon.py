"""Forever Launcher icon (W1-kek-voros) as Android vector drawables + legacy bitmaps, from one geometry.

Everything is in the 1024 x 1024 design space of the review renders; the adaptive icon layers use a 108 dp viewport mapped onto it.
"""
import math, os, sys
from pathlib import Path
import cairosvg
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from PIL import Image

RES = Path(sys.argv[1]); PREVIEW = Path(sys.argv[2]); PREVIEW.mkdir(parents=True, exist_ok=True)
TIGHT = float(sys.argv[6]) if len(sys.argv) > 6 else 10.0   # chosen 2026-11-01: W and infinity 10 units closer each

# ---- geometry -------------------------------------------------------------------------------------------
from fontTools.ttLib.removeOverlaps import removeOverlaps
from fontTools.pens.boundsPen import BoundsPen
def _w_glyph(font):
    removeOverlaps(font)   # letters drawn from overlapping strokes get one outline (no edge line inside the letter)
    gs = font.getGlyphSet(); g = gs[font.getBestCmap()[ord("W")]]
    b = BoundsPen(gs); g.draw(b); return gs, g, b.bounds, font["head"].unitsPerEm
_cz = _w_glyph(instantiateVariableFont(TTFont(str(Path(__file__).resolve().parent / "Cinzel.ttf")), {"wght": 900}))
W_HEIGHT = (_cz[2][3] - _cz[2][1]) * 270 / _cz[3]          # the chosen Cinzel Black W, 270 units per em
W_FONT = os.environ.get("W_FONT", "")                         # another OFL font for the W (path), same height and centre
gs, g, (x0, y0, x1, y1), upm = _w_glyph(TTFont(W_FONT)) if W_FONT else _cz
k = W_HEIGHT / (y1 - y0)
pen = SVGPathPen(gs, ntos=lambda v: f"{v:.1f}")
g.draw(TransformPen(pen, (k, 0, 0, -k, 512 - (x0 + x1) / 2 * k, 480 + TIGHT + y0 * k)))
W_PATH = pen.getCommands()

INF_SCALE = float(sys.argv[7]) if len(sys.argv) > 7 else 0.48   # chosen 2026-11-01 (0.72 -> 0.68 -> 0.60 -> 0.48)
# Shrinking the infinity keeps its top edge (lobe top 110.5 + half stroke 28 units) where it was, so the star gap stays the same.
THIN = float(sys.argv[8]) if len(sys.argv) > 8 else 0.78   # ribbon width factor (1.0 = 56 units), chosen 2026-11-01
S, CX, CY = INF_SCALE, 512, 640 - TIGHT + 0.72 * 138.5 - INF_SCALE * (110.5 + 28 * THIN)
# COMPACT=1 (splash logo): the infinity sits 15 units under the star, as in the icon, whatever the star and infinity sizes.
if os.environ.get("COMPACT") == "1" and len(sys.argv) > 3 and sys.argv[3] != "cross":
    _ss = float(sys.argv[12]) if len(sys.argv) > 12 else 1.0; _r0 = 60 * 0.72; _r = _r0 * _ss
    _sy = 480 + TIGHT + (float(sys.argv[3]) - 480) / 160 * (160 - 2 * TIGHT) + _r - _r0
    _drop = float(os.environ.get("STAR_DROP", "0")); _gap = float(os.environ.get("INF_GAP", "15"))
    CY = _sy + _drop + _r + _gap + INF_SCALE * (110.5 + 28 * THIN)          # the infinity group: translate(512,640) scale(0.72) translate(-512,-512)
def tp(x, y): return (CX + S * (x - 512), CY + S * (y - 512))

pts = []
for i in range(0, 361, 2):
    t = math.radians(i); dd = 1 + math.sin(t) ** 2
    pts.append(tp(512 + 250 * math.cos(t) / dd, 512 + 250 * math.sin(t) * math.cos(t) / dd * 1.25))
INF = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts[:-1]) + " Z"
INF_W = 56 * S * THIN
GLOW_CY = ((480 + TIGHT - 270 * 0.7) + (CY + S * (110.5 + 28 * THIN))) / 2
seg = []
for i in range(55, 126, 2):
    t = math.radians(i); dd = 1 + math.sin(t) ** 2
    seg.append(tp(512 + 250 * math.cos(t) / dd, 512 + 250 * math.sin(t) * math.cos(t) / dd * 1.25))
SEG = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in seg)   # the branch that passes over the crossing (woven look)
GAP_FROM, GAP_TO = int(sys.argv[4]) if len(sys.argv) > 4 else 256, int(sys.argv[5]) if len(sys.argv) > 5 else 284
under = []
for i in list(range(GAP_TO, 360, 1)) + list(range(0, GAP_FROM + 1, 1)):
    t = math.radians(i); dd = 1 + math.sin(t) ** 2
    under.append(tp(512 + 250 * math.cos(t) / dd, 512 + 250 * math.sin(t) * math.cos(t) / dd * 1.25))
INF_OPEN = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in under)   # the whole ribbon except where it passes under the crossing
HL_W = 7 * S * (0.5 + THIN / 2); GAP_W = (56 * THIN + 12) * S
INF_STYLE = os.environ.get("INF_STYLE", "stroke")   # stroke (even ribbon) / nib (pen, thick-thin) / italic (nib, slanted) / brush
SLANT = 0.22 if INF_STYLE in ("italic", "brush") else 0.0
def lem(i):
    t = math.radians(i); dd = 1 + math.sin(t) ** 2
    x, y = tp(512 + 250 * math.cos(t) / dd, 512 + 250 * math.sin(t) * math.cos(t) / dd * 1.25)
    return (x - (y - CY) * SLANT, y)
NIB = math.radians(40)
def band(d0, d1, step=1.0, taper=0.0):
    """A filled strip along the curve from d0 to d1 degrees: quads whose width follows a broad pen nib (thick-thin)."""
    out = []; n = int(round((d1 - d0) / step))
    def edge(i):
        a = d0 + i * step; (xa, ya), (xb, yb) = lem(a - 0.5), lem(a + 0.5); x, y = lem(a)
        ang = math.atan2(yb - ya, xb - xa); w = INF_W * (0.38 + 1.05 * abs(math.sin(ang - NIB)))
        if taper:
            f = min(1.0, (a - d0) / taper, (d1 - a) / taper); w *= 0.15 + 0.85 * (f * f * (3 - 2 * f))
        nx, ny = -(yb - ya), (xb - xa); ln = math.hypot(nx, ny); nx, ny = nx / ln * w / 2, ny / ln * w / 2
        return (x + nx, y + ny), (x - nx, y - ny)
    e = [edge(i) for i in range(n + 1)]
    for i in range(n):   # each quad spans two steps, so neighbours overlap and no hairline seam shows between them
        a, b = e[i], e[min(i + 2, n)]; q = (a[0], b[0], b[1], a[1])
        out.append("M" + " L".join(f"{px:.1f},{py:.1f}" for px, py in q) + " Z")
    return " ".join(out)
def centre(d0, d1, step=2):
    return "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in (lem(d0 + i * step) for i in range(int((d1 - d0) / step) + 1)))
if INF_STYLE != "stroke":
    INF = centre(0, 360); SEG = centre(55, 125)
    INF_BAND = band(274, 626, taper=40) if INF_STYLE == "brush" else band(0, 360)
    SEG_BAND = band(55, 125)
# Outline of the under-strand around the crossing: the weave shadow is clipped to it, so it only falls on that strand.
_left, _right = [], []
for i10 in range(2350, 3051, 10):
    i = i10 / 10; (x0, y0), (x1, y1) = lem(i - 0.5), lem(i + 0.5); x, y = lem(i)
    nx, ny = -(y1 - y0), (x1 - x0); n = math.hypot(nx, ny); nx, ny = nx / n * INF_W / 2, ny / n * INF_W / 2
    _left.append((x + nx, y + ny)); _right.append((x - nx, y - ny))
UNDER = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in _left + _right[::-1]) + " Z"
SH1_W, SH2_W = INF_W + 26 * S * THIN, INF_W + 12 * S * THIN
STAR_POS = sys.argv[3] if len(sys.argv) > 3 else "502.857"   # chosen 2026-11-01: star centre at y 510, tucked under the W

STAR_SCALE = float(sys.argv[12]) if len(sys.argv) > 12 else 1.0   # star size factor; its top stays under the W
r0 = 60 * 0.72; r = r0 * STAR_SCALE; c = 0.18 * r
sx, sy = (CX, CY) if STAR_POS == 'cross' else (CX, 480 + TIGHT + (float(STAR_POS) - 480) / 160 * (160 - 2 * TIGHT))
if STAR_POS != 'cross': sy += r - r0   # a bigger star grows downwards, towards the infinity
if STAR_POS != 'cross' and os.environ.get("COMPACT") == "1": sy += float(os.environ.get("STAR_DROP", "0"))   # splash logo: air under the W
STAR_L = f"M{sx:.1f},{sy-r:.1f} L{sx:.1f},{sy+r:.1f} Q{sx-c:.1f},{sy+c:.1f} {sx-r:.1f},{sy:.1f} Q{sx-c:.1f},{sy-c:.1f} {sx:.1f},{sy-r:.1f} Z"
STAR_R = f"M{sx:.1f},{sy-r:.1f} L{sx:.1f},{sy+r:.1f} Q{sx+c:.1f},{sy+c:.1f} {sx+r:.1f},{sy:.1f} Q{sx+c:.1f},{sy-c:.1f} {sx:.1f},{sy-r:.1f} Z"

GLOW_ALPHA = float(os.environ.get("GLOW_ALPHA", "0.25"))   # halo behind the mark (the splash has one too)
GLOW_R = float(os.environ.get("GLOW_R", "300"))
GOLD_STYLE = sys.argv[11] if len(sys.argv) > 11 else "shiny"   # matte: soft three-stop gold; shiny: polished metal bands
GOLD = ([("0", "#F6DDB0"), ("0.5", "#D7AE77"), ("1", "#A7783F")] if GOLD_STYLE == "matte" else
        [("0", "#FFF3D2"), ("0.18", "#E9C27F"), ("0.36", "#9C6A2C"), ("0.52", "#FFE7B0"), ("0.62", "#F8D58E"),
         ("0.80", "#B98339"), ("1", "#6E4519")])
GOLD_XY = (236, 380, 788, 650)       # goldh, userSpaceOnUse (in the 1024 space; the infinity gradient is inside the scaled group)
INF_GOLD_XY = (*tp(236, 380), *tp(788, 650))
PALETTE = os.environ.get("PALETTE", "classic")
if PALETTE == "wow":   # faction colours: Alliance royal blue, Horde blood red (no crests: colours only)
    ALLY = [("0", "#3A72E0"), ("0.55", "#1C49B0"), ("1", "#0C2466")]
    HORDE = [("0", "#D62424"), ("0.55", "#9E1010"), ("1", "#4E0606")]
    ALLY_GLOW, HORDE_GLOW = "#2F64D8", "#C81A1A"
    BG_L, BG_M, BG_R = "#132A66", "#0B0A10", "#4C0B0B"
else:
    ALLY = [("0", "#4E8EE8"), ("1", "#1B4FA0")]
    HORDE = [("0", "#E5483A"), ("1", "#8E1414")]
    ALLY_GLOW, HORDE_GLOW = "#4E8EE8", "#E5483A"
    BG_L, BG_M, BG_R = "#1F3150", "#140F1C", "#4A1914"

# ---- SVG (review render, must match the chosen concept) -------------------------------------------------
def svg_grad(id_, stops, x1, y1, x2, y2):
    return (f'<linearGradient id="{id_}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">' +
            "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops) + "</linearGradient>")

DEFS = (svg_grad("g", GOLD, *GOLD_XY) + svg_grad("gi", GOLD, *INF_GOLD_XY) +
        svg_grad("a", ALLY, 0, sy - r, 0, sy + r) + svg_grad("h", HORDE, 0, sy - r, 0, sy + r) +
        f'<radialGradient id="gl" gradientUnits="userSpaceOnUse" cx="512" cy="{GLOW_CY:.1f}" r="{GLOW_R:.0f}"><stop offset="0" stop-color="#F6DDB0" stop-opacity="{GLOW_ALPHA}"/><stop offset="0.45" stop-color="#F6DDB0" stop-opacity="{GLOW_ALPHA * 0.45:.3f}"/><stop offset="1" stop-color="#F6DDB0" stop-opacity="0"/></radialGradient>'
        '<radialGradient id="bg" gradientUnits="userSpaceOnUse" cx="512" cy="430" r="768"><stop offset="0" stop-color="#1F3150"/><stop offset="0.55" stop-color="#111B2D"/><stop offset="1" stop-color="#070B13"/></radialGradient>'
        '<radialGradient id="bgr" gradientUnits="userSpaceOnUse" cx="512" cy="430" r="768"><stop offset="0" stop-color="#4A1914"/><stop offset="0.55" stop-color="#26100D"/><stop offset="1" stop-color="#0E0505"/></radialGradient>'
        f'<linearGradient id="bgx" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1024" y2="0"><stop offset="0" stop-color="{BG_L}"/><stop offset="0.5" stop-color="{BG_M}"/><stop offset="1" stop-color="{BG_R}"/></linearGradient>')

# Background: the Blue theme (Alliance navy) alone, or half of it the Red theme (Horde crimson) of the app.
BG_STYLE = sys.argv[10] if len(sys.argv) > 10 else "blend"   # navy / split / diag / blend; chosen 2026-11-01: blend
BG_SHAPES = {   # (path, svg fill id, android colours of a radial or linear gradient)
    "navy": [("M0,0h1024v1024h-1024z", "bg")],
    "split": [("M0,0h512v1024h-512z", "bg"), ("M512,0h512v1024h-512z", "bgr")],
    "diag": [("M0,0L1024,0L0,1024Z", "bg"), ("M1024,0L1024,1024L0,1024Z", "bgr")],
    "blend": [("M0,0h1024v1024h-1024z", "bgx")],
}[BG_STYLE]
BG_SVG = "".join(f'<path d="{d}" fill="url(#{g})"/>' for d, g in BG_SHAPES)
STYLE = sys.argv[9] if len(sys.argv) > 9 else "shadow"   # depth on the W and the infinity: none / shadow / outline / rim / combo
SHADOW = STYLE in ("shadow", "combo", "shadowoutline"); OUTLINE = STYLE in ("outline", "shadowoutline"); RIM = STYLE in ("rim", "combo")


import itertools
_clip_ids = itertools.count()


def svg_p(d, fill=None, stroke=None, width=None, cap=None, op=1.0):
    f = {"gold": "url(#g)", "goldi": "url(#gi)", "ally": "url(#a)", "horde": "url(#h)"}
    out = f'<path d="{d}" fill="{f.get(fill, fill) if fill else "none"}"'
    if stroke: out += f' stroke="{f.get(stroke, stroke)}" stroke-width="{width:.2f}" stroke-linejoin="round"' + (f' stroke-linecap="{cap}"' if cap else "")
    if op != 1.0: out += f' opacity="{op}"'
    return out + "/>"


def layers(P, group, clip):
    """The mark as layers; P draws a path, group(dy, body) offsets, clip(path, body) clips. Same order for SVG and Android."""
    L = []
    if os.environ.get("STAR_GLOW") == "1":   # behind everything: a blue glow left of the star, a red one right of it
        def disc(cx, rr): return f"M{cx - rr:.1f},{sy:.1f}a{rr:.1f},{rr:.1f} 0,1 1,{2 * rr:.1f} 0a{rr:.1f},{rr:.1f} 0,1 1,-{2 * rr:.1f} 0Z"
        for i in range(12):   # many faint discs read as one smooth glow; the centres sit apart so the colours barely meet
            rr = r * (2.2 - i * 0.15)
            L.append(P(disc(sx - r * 0.95, rr), fill=ALLY_GLOW, op=0.05) + P(disc(sx + r * 0.95, rr), fill=HORDE_GLOW, op=0.05))
    if SHADOW:                                   # soft drop shadow: three offset copies, fading out
        for dy, op in ((5, 0.22), (11, 0.16), (18, 0.11)):
            L.append(group(dy, P(W_PATH, fill="#000000", op=op) + (P(INF_BAND, fill="#000000", op=op) if INF_STYLE != "stroke" else P(INF, stroke="#000000", width=INF_W, op=op))))
    if OUTLINE: L.append(P(W_PATH, stroke="#5E3F1C", width=9))
    L.append(P(W_PATH, fill="gold"))
    if RIM: L.append(P(W_PATH, stroke="#FFF4DC", width=3.2, op=0.6))
    def ribbon(d, cap=None):
        out = ""
        if OUTLINE: out += P(d, stroke="#5E3F1C", width=INF_W + 8, cap=cap)
        if RIM: out += P(d, stroke="#FFF4DC", width=INF_W, cap=cap, op=0.75)
        out += P(d, stroke="goldi", width=INF_W - (4.4 if RIM else 0), cap=cap)
        return out + P(d, stroke="#FFF1D6", width=HL_W, cap=cap, op=0.55)
    def pen_ribbon(band_d, centre_d):   # the hand-drawn infinity: a filled thick-thin band and a fine engraved line
        return P(band_d, fill="goldi") + P(centre_d, stroke="#FFF1D6", width=HL_W * 0.7, op=0.4)
    L.append(pen_ribbon(INF_BAND, INF) if INF_STYLE != "stroke" else ribbon(INF))
    L.append(clip(UNDER, P(SEG, stroke="#000000", width=SH1_W, cap="butt", op=0.22) + P(SEG, stroke="#000000", width=SH2_W, cap="butt", op=0.30)))
    L.append(pen_ribbon(SEG_BAND, SEG) if INF_STYLE != "stroke" else ribbon(SEG, cap="butt"))
    if os.environ.get("STAR_OUTLINE") == "1":   # a thin polished-gold rim around the star
        L.append(P(STAR_L, stroke="goldi", width=7.5) + P(STAR_R, stroke="goldi", width=7.5))
    L.append(P(STAR_L, fill="ally") + P(STAR_R, fill="horde"))
    return "".join(L)


MARK = (f'<circle cx="512" cy="{GLOW_CY:.1f}" r="{GLOW_R:.0f}" fill="url(#gl)"/>' +
        layers(svg_p, lambda dy, body: f'<g transform="translate(0,{dy})">{body}</g>',
               lambda d, body: (lambda n: f'<clipPath id="c{n}"><path d="{d}"/></clipPath><g clip-path="url(#c{n})">{body}</g>')(next(_clip_ids))))


def full_svg(mask):
    clip = {"circle": '<clipPath id="m"><circle cx="512" cy="512" r="341.33"/></clipPath>',
            "squircle": '<clipPath id="m"><rect x="170.67" y="170.67" width="682.67" height="682.67" rx="200"/></clipPath>',
            "square": '<clipPath id="m"><rect x="170.67" y="170.67" width="682.67" height="682.67" rx="90"/></clipPath>'}[mask]
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="170.67 170.67 682.67 682.67">'
            f'<defs>{DEFS}{clip}</defs><g clip-path="url(#m)">{BG_SVG}{MARK}</g></svg>')


for mask in ("circle", "squircle"):
    cairosvg.svg2png(bytestring=full_svg(mask).encode(), write_to=str(PREVIEW / f"final-{mask}.png"), output_width=512, output_height=512)
# Store / repository icon: 512 x 512, full square (stores apply their own mask).
cairosvg.svg2png(bytestring=full_svg("square").replace('rx="90"', 'rx="0"').encode(), write_to=str(RES.parent / "ic_launcher-playstore.png"), output_width=512, output_height=512)

# Splash / header logo: the mark on a transparent background, cropped to W + infinity.
_inf_bottom = CY + S * (110.5 + 28 * THIN) + (18 if SHADOW else 0)
LOGO_VB = (300, 285, 424, int(_inf_bottom + 14 - 285))   # x, y, w, h in the 1024 space: W top to the infinity's shadow
logo_svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{LOGO_VB[2]}" height="{LOGO_VB[3]}" viewBox="{" ".join(map(str, LOGO_VB))}">'
            f'<defs>{DEFS}</defs>{MARK.split("/>", 1)[1]}</svg>')
cairosvg.svg2png(bytestring=logo_svg.encode(), write_to=str(PREVIEW / "logo.png"), output_width=LOGO_VB[2], output_height=LOGO_VB[3])

# ---- Android vector drawables ---------------------------------------------------------------------------
A = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'


def vd_lin(stops, x1, y1, x2, y2):
    return (f'<aapt:attr name="{{attr}}"><gradient android:type="linear" android:startX="{x1:.1f}" android:startY="{y1:.1f}" '
            f'android:endX="{x2:.1f}" android:endY="{y2:.1f}">' +
            "".join(f'<item android:offset="{o}" android:color="{c.replace("#", "#FF")}"/>' for o, c in stops) + "</gradient></aapt:attr>")


def vd_path(d, fill=None, stroke=None, width=None, cap=None):
    attrs = f'android:pathData="{d}"'
    inner = ""
    if fill and fill.startswith("<"): inner += fill.replace("{attr}", "android:fillColor")
    elif fill: attrs += f' android:fillColor="{fill}"'
    if stroke:
        attrs += f' android:strokeWidth="{width:.2f}" android:strokeLineJoin="round"' + (f' android:strokeLineCap="{cap}"' if cap else "")
        if stroke.startswith("<"): inner += stroke.replace("{attr}", "android:strokeColor")
        else: attrs += f' android:strokeColor="{stroke}"'
    return f"<path {attrs}>{inner}</path>" if inner else f"<path {attrs}/>"


GLOW_VD = (f'<path android:pathData="M{512 - GLOW_R:.0f},{GLOW_CY:.1f}a{GLOW_R:.0f},{GLOW_R:.0f} 0,1 1,{2 * GLOW_R:.0f} 0a{GLOW_R:.0f},{GLOW_R:.0f} 0,1 1,-{2 * GLOW_R:.0f} 0Z"><aapt:attr name="android:fillColor">'
           f'<gradient android:type="radial" android:centerX="512" android:centerY="{GLOW_CY:.1f}" android:gradientRadius="{GLOW_R:.0f}">'
           f'<item android:offset="0" android:color="#{round(GLOW_ALPHA * 255):02X}F6DDB0"/><item android:offset="0.45" android:color="#{round(GLOW_ALPHA * 0.45 * 255):02X}F6DDB0"/>'
           '<item android:offset="1" android:color="#00F6DDB0"/></gradient></aapt:attr></path>')
def vd_p(d, fill=None, stroke=None, width=None, cap=None, op=1.0):
    g = {"gold": vd_lin(GOLD, *GOLD_XY), "goldi": vd_lin(GOLD, *INF_GOLD_XY),
         "ally": vd_lin(ALLY, 0, sy - r, 0, sy + r), "horde": vd_lin(HORDE, 0, sy - r, 0, sy + r)}
    def col(c): return g[c] if c in g else "#%02X%s" % (round(op * 255), c[1:])
    out = vd_path(d, fill=col(fill) if fill else None, stroke=col(stroke) if stroke else None, width=width, cap=cap)
    if op != 1.0 and ((fill in g) or (stroke in g)):   # gradients carry no alpha of their own here
        out = out.replace("<path ", f'<path android:{"fillAlpha" if fill else "strokeAlpha"}="{op}" ', 1)
    return out


MARK_VD = layers(vd_p, lambda dy, body: f'<group android:translateY="{dy}">{body}</group>',
                 lambda d, body: f'<group><clip-path android:pathData="{d}"/>{body}</group>')


def vector(w, h, vb, body, dp=True):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Forever Launcher icon (generated by the icon script; own artwork, no Blizzard assets). -->\n'
            f'<vector {A} android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{vb[0]}" android:viewportHeight="{vb[1]}">\n{body}\n</vector>\n')


draw = RES / "drawable"
# LOGO_ONLY=1: write only the splash/header logo (it may use its own, smaller infinity: run with that infinity scale).
import os
if os.environ.get("LOGO_ONLY") == "1":
    (draw / "forever_logo.xml").write_text(
        vector(LOGO_VB[2] // 4, LOGO_VB[3] // 4, (LOGO_VB[2], LOGO_VB[3]), f'<group android:translateX="{-LOGO_VB[0]}" android:translateY="{-LOGO_VB[1]}">{MARK_VD}</group>'))
    print("ok logo only"); sys.exit(0)
(draw / "ic_launcher_foreground.xml").write_text(vector(108, 108, (1024, 1024), GLOW_VD + MARK_VD))
BG_VD_GRAD = {
    "bg": '<gradient android:type="radial" android:centerX="512" android:centerY="430" android:gradientRadius="768"><item android:offset="0" android:color="#FF1F3150"/><item android:offset="0.55" android:color="#FF111B2D"/><item android:offset="1" android:color="#FF070B13"/></gradient>',
    "bgr": '<gradient android:type="radial" android:centerX="512" android:centerY="430" android:gradientRadius="768"><item android:offset="0" android:color="#FF4A1914"/><item android:offset="0.55" android:color="#FF26100D"/><item android:offset="1" android:color="#FF0E0505"/></gradient>',
    "bgx": f'<gradient android:type="linear" android:startX="0" android:startY="0" android:endX="1024" android:endY="0"><item android:offset="0" android:color="#FF{BG_L[1:]}"/><item android:offset="0.5" android:color="#FF{BG_M[1:]}"/><item android:offset="1" android:color="#FF{BG_R[1:]}"/></gradient>',
}
(draw / "ic_launcher_background.xml").write_text(vector(108, 108, (1024, 1024), "".join(
    f'<path android:pathData="{d}"><aapt:attr name="android:fillColor">{BG_VD_GRAD[g]}</aapt:attr></path>' for d, g in BG_SHAPES)))
# Themed (monochrome) icon for Android 13+: the same shapes in one colour.
(draw / "ic_launcher_monochrome.xml").write_text(vector(108, 108, (1024, 1024),
    vd_path(W_PATH, fill="#FFFFFFFF") + vd_path(INF, stroke="#FFFFFFFF", width=INF_W) + vd_path(STAR_L, fill="#FFFFFFFF") + vd_path(STAR_R, fill="#FFFFFFFF")))
# Logo for the splash and the launcher header (no background, no glow), cropped like LOGO_VB.
(draw / "forever_logo.xml").write_text(
    vector(LOGO_VB[2] // 4, LOGO_VB[3] // 4, (LOGO_VB[2], LOGO_VB[3]), f'<group android:translateX="{-LOGO_VB[0]}" android:translateY="{-LOGO_VB[1]}">{MARK_VD}</group>'))
# Notification icon: white silhouette, 24 dp.
(draw / "ic_stat_forever.xml").write_text(
    vector(24, 24, (LOGO_VB[2], LOGO_VB[3]), f'<group android:translateX="{-LOGO_VB[0]}" android:translateY="{-LOGO_VB[1]}">' +
           vd_path(W_PATH, fill="#FFFFFFFF") + vd_path(INF, stroke="#FFFFFFFF", width=INF_W * 1.2) + "</group>"))

adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background"/>\n    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n</adaptive-icon>\n')
for n in ("ic_launcher.xml", "ic_launcher_round.xml"): (RES / "mipmap-anydpi-v26" / n).write_text(adaptive)

# Legacy bitmaps (only used below Android 8; minSdk is 26, kept so no density folder is left with the old art).
for dens, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    d = RES / f"mipmap-{dens}"
    for name, mask in (("ic_launcher.webp", "square"), ("ic_launcher_round.webp", "circle")):
        png = cairosvg.svg2png(bytestring=full_svg(mask).encode(), output_width=px, output_height=px)
        import io
        Image.open(io.BytesIO(png)).save(d / name, "WEBP", lossless=True)
    old = d / "ic_launcher_foreground.png"
    if old.exists(): old.unlink()
print("ok", len(W_PATH), "chars W path")
