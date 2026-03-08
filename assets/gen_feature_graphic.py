#!/usr/bin/env python3
"""Generate Inv3 feature graphic 1024x500 for Google Play."""
from PIL import Image, ImageDraw, ImageFont
import os

W, H = 1024, 500
img = Image.new('RGB', (W, H))
draw = ImageDraw.Draw(img)

# Green gradient background
for y in range(H):
    t = y / H
    r = int(200 * (1 - t) + 46 * t)
    g = int(230 * (1 - t) + 125 * t)
    b = int(200 * (1 - t) + 50 * t)
    draw.line([(0, y), (W, y)], fill=(r, g, b))

# Left icon area - rounded rectangle
icon_x, icon_y, icon_sz = 80, 160, 180
draw.rounded_rectangle(
    [icon_x, icon_y, icon_x + icon_sz, icon_y + icon_sz],
    radius=24, fill=(255, 255, 255), outline=(220, 255, 220), width=3
)

# Simple document icon
draw.rectangle([icon_x + 50, icon_y + 40, icon_x + 130, icon_y + 120], fill=(27, 94, 32))
draw.polygon([
    (icon_x + 60, icon_y + 140), (icon_x + 100, icon_y + 140),
    (icon_x + 110, icon_y + 160), (icon_x + 70, icon_y + 160)
], outline=(27, 94, 32), width=3)

# Try system fonts for Lithuanian text
font_paths = [
    'C:/Windows/Fonts/segoeui.ttf',
    'C:/Windows/Fonts/arial.ttf',
    '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
]
font_large = font_small = None
for fp in font_paths:
    if os.path.exists(fp):
        try:
            font_large = ImageFont.truetype(fp, 72)
            font_small = ImageFont.truetype(fp, 32)
            break
        except Exception:
            pass
if font_large is None:
    font_large = ImageFont.load_default()
    font_small = ImageFont.load_default()

# Title Inv3
draw.text((300, 180), 'Inv3', fill=(255, 255, 255), font=font_large)

# Subtitle
draw.text((300, 270), 'Sąskaitų skenavimas ir XML generavimas i.SAF', fill=(255, 255, 255), font=font_small)

out_path = os.path.join(os.path.dirname(__file__), 'feature_graphic_1024x500.png')
img.save(out_path)
print(f'Saved: {out_path}')
