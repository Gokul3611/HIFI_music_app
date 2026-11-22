import os
import math
from PIL import Image, ImageDraw, ImageFilter

def draw_eighth_note(draw, size, fg_color=(255, 255, 255, 255)):
    scale = size / 108.0

    # Transformation helper
    def S(x, y):
        return (x * scale, y * scale)

    # Notehead: tilted ellipse
    # Center around (44, 68), width ~18, height ~13, tilted ~-25 deg
    cx, cy = 44.0, 68.0
    rx, ry = 9.5, 6.8
    angle = math.radians(-26)

    # Compute rotated ellipse points
    ellipse_pts = []
    for step in range(64):
        theta = 2 * math.pi * step / 64.0
        ex = rx * math.cos(theta)
        ey = ry * math.sin(theta)
        # Rotate
        rx_rot = ex * math.cos(angle) - ey * math.sin(angle)
        ry_rot = ex * math.sin(angle) + ey * math.cos(angle)
        ellipse_pts.append(S(cx + rx_rot, cy + ry_rot))

    draw.polygon(ellipse_pts, fill=fg_color)

    # Stem: from right side of notehead up to top
    # Stem x is approx 51.5 to 55.5 (width ~4)
    stem_x0 = 51.5
    stem_x1 = 55.2
    stem_y0 = 31.0
    stem_y1 = 66.5
    draw.rectangle([S(stem_x0, stem_y0), S(stem_x1, stem_y1)], fill=fg_color)

    # Rounded top of stem
    draw.ellipse([S(stem_x0, stem_y0 - 1.8), S(stem_x1, stem_y0 + 1.8)], fill=fg_color)

    # Flag: iconic sweeping curve starting from (55.2, 31) curving down to right and tapering back
    # Bezier curve approximation
    flag_pts = []
    # Outer curve: (55.2, 31) -> control (72, 40) -> control (76, 56) -> tip (67, 60)
    # Inner curve: tip (67, 60) -> control (70, 50) -> control (63, 44) -> connect to stem (55.2, 48)
    def cubic_bezier(p0, p1, p2, p3, t):
        return (
            (1-t)**3 * p0[0] + 3*(1-t)**2*t * p1[0] + 3*(1-t)*t**2 * p2[0] + t**3 * p3[0],
            (1-t)**3 * p0[1] + 3*(1-t)**2*t * p1[1] + 3*(1-t)*t**2 * p2[1] + t**3 * p3[1]
        )

    # Outer sweep
    for step in range(30):
        t = step / 29.0
        pt = cubic_bezier((54.5, 31.0), (74.0, 37.0), (78.0, 52.0), (67.0, 61.0), t)
        flag_pts.append(S(pt[0], pt[1]))

    # Inner return
    for step in range(30):
        t = step / 29.0
        pt = cubic_bezier((67.0, 61.0), (71.0, 52.0), (62.0, 44.0), (54.5, 47.0), t)
        flag_pts.append(S(pt[0], pt[1]))

    draw.polygon(flag_pts, fill=fg_color)

def generate_icons(base_dir):
    res_dir = os.path.join(base_dir, "Stash", "app", "src", "main", "res")

    # 1. Generate ic_launcher_foreground.png (432 x 432 high-res for adaptive icon)
    fg_size = 432
    fg_img = Image.new("RGBA", (fg_size, fg_size), (0, 0, 0, 0))
    fg_draw = ImageDraw.Draw(fg_img)
    draw_eighth_note(fg_draw, fg_size, (255, 255, 255, 255))
    
    fg_path = os.path.join(res_dir, "drawable", "ic_launcher_foreground.png")
    fg_img.save(fg_path, "PNG")
    print(f"Saved: {fg_path}")

    # 2. Update ic_launcher_background.xml
    bg_xml_path = os.path.join(res_dir, "drawable", "ic_launcher_background.xml")
    with open(bg_xml_path, "w", encoding="utf-8") as f:
        f.write('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Premium Deep Obsidian Dark Background -->
    <path android:fillColor="#0E1015" android:pathData="M0,0h108v108h-108z" />
</vector>
''')
    print(f"Updated: {bg_xml_path}")

    # 3. Mipmap densities for legacy launchers
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    for folder, size in densities.items():
        folder_path = os.path.join(res_dir, folder)
        os.makedirs(folder_path, exist_ok=True)

        # Standard square/squircle icon with rounded corners
        icon_img = Image.new("RGBA", (size, size), (14, 16, 21, 255))
        draw = ImageDraw.Draw(icon_img)

        # Draw subtle inner circular vignette/accent
        draw_eighth_note(draw, size, (255, 255, 255, 255))

        # Save standard icon
        sq_path = os.path.join(folder_path, "ic_launcher.png")
        icon_img.save(sq_path, "PNG")

        # Round icon (mask with circle)
        round_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        mask = Image.new("L", (size, size), 0)
        mask_draw = ImageDraw.Draw(mask)
        mask_draw.ellipse([0, 0, size, size], fill=255)
        round_img.paste(icon_img, (0, 0), mask)

        round_path = os.path.join(folder_path, "ic_launcher_round.png")
        round_img.save(round_path, "PNG")
        print(f"Saved: {sq_path} and {round_path}")

if __name__ == "__main__":
    generate_icons(r"C:\Users\Gokul E\Desktop\Indie Projects\Esp32_DAC")

