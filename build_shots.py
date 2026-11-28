"""Rebuild Play Store phone screenshots at exactly 1080x1920 (9:16).

Sources are the 720px captures currently on the listing. Each one is:
  * stripped of the Android status bar (top) and navigation bar (bottom),
  * cropped to 9:16 from the top so the app header survives,
  * upscaled once with Lanczos to 1080x1920.

The fake first slide (a different app, a TikTok watermark and an "AI-generated"
label) is deliberately excluded.
"""
from PIL import Image
import os

SRC = "shots"
OUT = "store1080"
TARGET_W, TARGET_H = 1080, 1920
RATIO = TARGET_H / TARGET_W  # 1.7778

os.makedirs(OUT, exist_ok=True)

# (output name, source, status bar height, nav bar height)
PLAN = [
    ("01", "s3.png", 56, 130),
    ("02", "s2.png", 56, 130),
    ("03", "s5.png", 56, 130),
    ("04", "s4.png", 56, 130),
    ("05", "s6.png", 56, 130),
]

for name, src, top, bottom in PLAN:
    im = Image.open(os.path.join(SRC, src)).convert("RGB")
    w, h = im.size
    im = im.crop((0, top, w, h - bottom))
    w, h = im.size

    # Trim the extra height from the bottom, never the top: the app header
    # ("Phone Fortress") lives there and cutting it looked like a broken grab.
    want_h = int(round(w * RATIO))
    if h > want_h:
        im = im.crop((0, 0, w, want_h))
    elif h < want_h:
        canvas = Image.new("RGB", (w, want_h), (13, 20, 32))
        canvas.paste(im, (0, 0))
        im = canvas

    im = im.resize((TARGET_W, TARGET_H), Image.LANCZOS)
    im.save(os.path.join(OUT, f"{name}.png"), optimize=True)
    print(name, im.size, "<-", src)


