#!/usr/bin/env python3
"""Update subtitle on feature graphic - paint over old text and add new."""
from PIL import Image, ImageDraw, ImageFont
import os

# Source: user's image with dark green icon and "VMI eksportas" text
assets_dir = os.path.dirname(__file__)
src = os.path.join(assets_dir, "source_feature.png")
alt_src = os.path.join(assets_dir, "c__Users_vitol_AppData_Roaming_Cursor_User_workspaceStorage_82b65afdb9bab83e15dab82e3de3148f_images_image-f1fe1a6a-aaea-4275-bf7a-f6d499d77a4c.png")
cursor_src = r"C:\Users\vitol\.cursor\projects\c-Users-vitol-AndroidStudioProjects-Inv3\assets\c__Users_vitol_AppData_Roaming_Cursor_User_workspaceStorage_82b65afdb9bab83e15dab82e3de3148f_images_image-f1fe1a6a-aaea-4275-bf7a-f6d499d77a4c.png"

for path in [src, alt_src, cursor_src]:
    if os.path.exists(path):
        img = Image.open(path).convert("RGB")
        break
else:
    raise FileNotFoundError("Source image not found")

# Crop to 1024x500 if source is taller (Play Store requirement)
W, H = img.size
if H > 500:
    top = (H - 500) // 2  # Center crop
    img = img.crop((0, top, W, top + 500))
    H = 500

# Subtitle area - extend well below to catch all text
tx, ty = 250, 140
tw, th = 770, 250

draw = ImageDraw.Draw(img)

# Draw 2D gradient from corners (right of icon)
pixels = img.load()
sx_l, sx_r = 350, W - 15
y_above, y_below = 40, H - 40  # Well above/below text
c_tl, c_tr = pixels[sx_l, y_above], pixels[sx_r, y_above]
c_bl, c_br = pixels[sx_l, y_below], pixels[sx_r, y_below]
for py in range(ty, ty + th):
    tv = (py - ty) / th if th > 0 else 0
    for px in range(tx, min(tx + tw, W)):
        th_ = (px - tx) / tw if tw > 0 else 0
        r = int(c_tl[0]*(1-th_)*(1-tv) + c_tr[0]*th_*(1-tv) + c_bl[0]*(1-th_)*tv + c_br[0]*th_*tv)
        g = int(c_tl[1]*(1-th_)*(1-tv) + c_tr[1]*th_*(1-tv) + c_bl[1]*(1-th_)*tv + c_br[1]*th_*tv)
        b = int(c_tl[2]*(1-th_)*(1-tv) + c_tr[2]*th_*(1-tv) + c_bl[2]*(1-th_)*tv + c_br[2]*th_*tv)
        draw.point((px, py), fill=(r, g, b))

# Font for new subtitle
font_paths = [
    "C:/Windows/Fonts/segoeui.ttf",
    "C:/Windows/Fonts/arial.ttf",
]
font = None
for fp in font_paths:
    if os.path.exists(fp):
        try:
            font = ImageFont.truetype(fp, 36)
            break
        except Exception:
            pass
if font is None:
    font = ImageFont.load_default()

# New text - two lines
line1 = "Sąskaitų skenavimas"
line2 = "ir XML generavimas i.SAF"
# Draw new text in patch (centered vertically)
draw.text((tx, ty + 85), line1, fill=(255, 255, 255), font=font)
draw.text((tx, ty + 130), line2, fill=(255, 255, 255), font=font)

out_path = os.path.join(os.path.dirname(__file__), "feature_graphic_1024x500.png")
img.save(out_path)
print(f"Saved: {out_path}")
