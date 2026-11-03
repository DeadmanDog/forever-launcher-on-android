"""Package the approved 04-2 master into Android sizes, without redrawing it.

Requires Pillow. The master PNG was prepared using image_gen, 2026-11-01.
Its geometry, texture and transparency are retained. This only encodes resources.
"""
from pathlib import Path
import hashlib
import json
import sys
import math
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
MASTER = RES / "drawable-nodpi/forever_emblem_042.png"
EXPECTED = "4bf2d84a2c230a31c4d8ae97de7e5adab1b173f000b6ea4f54cc0e8ec49df482"
assert hashlib.sha256(MASTER.read_bytes()).hexdigest() == EXPECTED, "Approved master changed"
art = Image.open(MASTER).convert("RGBA")
assert art.getextrema()[3] == (0, 255) and art.width == art.height

def background(size):
    image = Image.new("RGBA", (size, size))
    pixels = image.load()
    # Same vertical Blue palette and barely visible F1 halo as the splash.
    stops = [(0., (10, 14, 23)), (.5, (16, 25, 42)), (1., (8, 12, 20))]
    radius = size * .86 * .75
    warm = (246, 221, 176)
    for y in range(size):
        t = y / max(1, size - 1)
        left, right = (stops[0], stops[1]) if t <= .5 else (stops[1], stops[2])
        f = (t - left[0]) / (right[0] - left[0])
        base = [a + (b - a) * f for a, b in zip(left[1], right[1])]
        for x in range(size):
            d = math.hypot(x - size / 2, y - size / 2) / radius
            alpha = (.03 + (.01125 - .03) * d / .45) if d < .45 else (.01125 * (1 - d) / .55 if d < 1 else 0)
            pixels[x, y] = tuple(round(v * (1 - alpha) + w * alpha) for v, w in zip(base, warm)) + (255,)
    return image

def icon(size, rounded=False):
    image = background(size)
    extent = round(size * .86)
    image.alpha_composite(art.resize((extent, extent), Image.Resampling.LANCZOS), ((size - extent) // 2, (size - extent) // 2))
    if rounded:
        mask = Image.new("L", (size, size))
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        image.putalpha(mask)
    return image

for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    for name, rounded in [("ic_launcher.webp", False), ("ic_launcher_round.webp", True)]:
        icon(size, rounded).save(RES / f"mipmap-{density}" / name, "WEBP", lossless=True)
        # A separately named plain bitmap for package/archive viewers; launcher alias stays adaptive.
        package_name = "ic_package_042_round.webp" if rounded else "ic_package_042.webp"
        icon(size, rounded).save(RES / f"mipmap-{density}" / package_name, "WEBP", lossless=True)
        launcher_name = "ic_launcher_042_round.webp" if rounded else "ic_launcher_042.webp"
        icon(size, rounded).save(RES / f"mipmap-{density}" / launcher_name, "WEBP", lossless=True)
icon(512).save(RES.parent / "ic_launcher-playstore.png")
mask = Image.new("RGBA", art.size, (255, 255, 255, 0))
mask.putalpha(art.getchannel("A"))
mask.save(RES / "drawable-nodpi/forever_emblem_042_mono.png")
mask.resize((96, 96), Image.Resampling.LANCZOS).save(RES / "drawable-nodpi/forever_notification_042.png")
# Bake the safe margin into bitmap pixels: it survives consumers that unwrap Drawable wrappers.
def padded_layer(image):
    layer = Image.new("RGBA", (432, 432))
    scaled = image.resize((259, 259), Image.Resampling.LANCZOS)
    layer.alpha_composite(scaled, (86, 86))
    return layer
padded_layer(art).save(RES / "drawable-nodpi/forever_icon_layer_042.png")
padded_layer(mask).save(RES / "drawable-nodpi/forever_icon_layer_042_mono.png")
header = '<?xml version="1.0" encoding="utf-8"?>\n'
def bitmap(name):
    return f'<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/{name}" android:gravity="fill" android:filter="true" />\n'
def adaptive_layer(name):
    return f'<layer-list xmlns:android="http://schemas.android.com/apk/res/android"><item android:width="108dp" android:height="108dp" android:gravity="fill"><bitmap android:src="@drawable/{name}" android:gravity="fill" android:filter="true" /></item></layer-list>\n'
draw = RES / "drawable"
(draw / "ic_launcher_foreground.xml").write_text(header + adaptive_layer("forever_icon_layer_042"))
(draw / "ic_launcher_monochrome.xml").write_text(header + adaptive_layer("forever_icon_layer_042_mono"))
(draw / "forever_logo.xml").write_text(header + bitmap("forever_emblem_042"))
(draw / "ic_stat_forever.xml").write_text(header + bitmap("forever_notification_042"))
(draw / "ic_launcher_background.xml").write_text(header + '''<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
  <!-- Adaptive layers extend 25% beyond each mask edge; coordinates compensate for that. -->
  <path android:pathData="M0,0h108v108h-108z"><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" android:startY="18" android:endX="0" android:endY="90">
    <item android:offset="0" android:color="#FF0A0E17"/><item android:offset="0.5" android:color="#FF10192A"/><item android:offset="1" android:color="#FF080C14"/>
  </gradient></aapt:attr></path>
  <path android:pathData="M7.56,54a46.44,46.44 0,1 1,92.88 0a46.44,46.44 0,1 1,-92.88 0Z"><aapt:attr name="android:fillColor"><gradient android:type="radial" android:centerX="54" android:centerY="54" android:gradientRadius="46.44">
    <item android:offset="0" android:color="#08F6DDB0"/><item android:offset="0.45" android:color="#03F6DDB0"/><item android:offset="1" android:color="#00F6DDB0"/>
  </gradient></aapt:attr></path>
</vector>
''')
adaptive = header + '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@drawable/ic_launcher_background"/>
  <foreground android:drawable="@drawable/ic_launcher_foreground"/>
  <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''
for name in ("ic_launcher", "ic_launcher_round", "ic_launcher_042", "ic_launcher_042_round"):
    (RES / f"mipmap-anydpi-v26/{name}.xml").write_text(adaptive)
if len(sys.argv) > 1:
    preview = Path(sys.argv[1]); preview.mkdir(parents=True, exist_ok=True)
    icon(512).save(preview / "icon-square.png")
    icon(512, True).save(preview / "icon-round.png")
    art.save(preview / "splash-emblem.png")
assert hashlib.sha256(MASTER.read_bytes()).hexdigest() == EXPECTED
print(json.dumps({"selected": "04-2", "masterSha256": EXPECTED, "legacyDensities": 5, "adaptivePixelMargin": 86, "adaptiveLayerSize": 432, "blueF1Background": True}))
