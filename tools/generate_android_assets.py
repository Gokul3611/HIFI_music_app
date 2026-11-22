"""
Generates all Android drawables, mipmap adaptive XMLs, and supersampled PNGs.
"""

import os
from PIL import Image, ImageDraw, ImageFilter

COLOR_THEMES = [
    {
        "id": "default",
        "name": "Obsidian Titanium",
        "accent_primary": "#FFFFFF",
        "accent_secondary": "#B8C2D1",
        "glow": "#FFFFFF",
        "glow_opacity": 0.18,
        "bg_top": (18, 20, 26),
        "bg_bottom": (9, 10, 13),
        "accent_rgb": (255, 255, 255),
        "accent_sec_rgb": (184, 194, 209),
        "glow_rgb": (255, 255, 255),
        "alias_suffix": "Default"
    },
    {
        "id": "emerald",
        "name": "Cyber Emerald",
        "accent_primary": "#00FF87",
        "accent_secondary": "#60EFA0",
        "glow": "#00FF87",
        "glow_opacity": 0.22,
        "bg_top": (14, 24, 20),
        "bg_bottom": (8, 15, 12),
        "accent_rgb": (0, 255, 135),
        "accent_sec_rgb": (96, 239, 160),
        "glow_rgb": (0, 255, 135),
        "alias_suffix": "Emerald"
    },
    {
        "id": "violet",
        "name": "Electric Violet",
        "accent_primary": "#C084FC",
        "accent_secondary": "#9333EA",
        "glow": "#A855F7",
        "glow_opacity": 0.24,
        "bg_top": (22, 16, 34),
        "bg_bottom": (11, 7, 19),
        "accent_rgb": (192, 132, 252),
        "accent_sec_rgb": (147, 51, 234),
        "glow_rgb": (168, 85, 247),
        "alias_suffix": "Violet"
    },
    {
        "id": "amber",
        "name": "Sunset Amber",
        "accent_primary": "#FDE047",
        "accent_secondary": "#F97316",
        "glow": "#F59E0B",
        "glow_opacity": 0.22,
        "bg_top": (28, 21, 12),
        "bg_bottom": (15, 11, 6),
        "accent_rgb": (253, 224, 71),
        "accent_sec_rgb": (249, 115, 22),
        "glow_rgb": (245, 158, 11),
        "alias_suffix": "Amber"
    },
    {
        "id": "sapphire",
        "name": "Ocean Sapphire",
        "accent_primary": "#38BDF8",
        "accent_secondary": "#2563EB",
        "glow": "#0284C7",
        "glow_opacity": 0.24,
        "bg_top": (12, 21, 34),
        "bg_bottom": (6, 11, 18),
        "accent_rgb": (56, 189, 248),
        "accent_sec_rgb": (37, 99, 235),
        "glow_rgb": (2, 132, 199),
        "alias_suffix": "Sapphire"
    },
    {
        "id": "crimson",
        "name": "Crimson Ruby",
        "accent_primary": "#FB7185",
        "accent_secondary": "#E11D48",
        "glow": "#F43F5E",
        "glow_opacity": 0.24,
        "bg_top": (31, 14, 19),
        "bg_bottom": (16, 7, 10),
        "accent_rgb": (251, 113, 133),
        "accent_sec_rgb": (225, 29, 72),
        "glow_rgb": (244, 63, 94),
        "alias_suffix": "Crimson"
    },
    {
        "id": "gold",
        "name": "Midnight Gold",
        "accent_primary": "#FDE68A",
        "accent_secondary": "#D97706",
        "glow": "#F59E0B",
        "glow_opacity": 0.20,
        "bg_top": (25, 21, 14),
        "bg_bottom": (13, 11, 7),
        "accent_rgb": (253, 230, 138),
        "accent_sec_rgb": (217, 119, 6),
        "glow_rgb": (245, 158, 11),
        "alias_suffix": "Gold"
    },
    {
        "id": "mono",
        "name": "Minimal Monochrome",
        "accent_primary": "#FFFFFF",
        "accent_secondary": "#E2E8F0",
        "glow": "#FFFFFF",
        "glow_opacity": 0.08,
        "bg_top": (10, 10, 10),
        "bg_bottom": (0, 0, 0),
        "accent_rgb": (255, 255, 255),
        "accent_sec_rgb": (226, 232, 240),
        "glow_rgb": (255, 255, 255),
        "alias_suffix": "Mono"
    }
]

RES_DIR = "app/src/main/res"

def generate_drawables():
    # 1. Backgrounds
    for theme in COLOR_THEMES:
        tid = theme["id"]
        bg_file = f"{RES_DIR}/drawable/ic_launcher_{tid}_background.xml" if tid != "default" else f"{RES_DIR}/drawable/ic_launcher_background.xml"
        bg_color = f"#{theme['bg_bottom'][0]:02X}{theme['bg_bottom'][1]:02X}{theme['bg_bottom'][2]:02X}"
        xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{bg_color}"
        android:pathData="M0,0h108v108h-108z" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeAlpha="0.03"
        android:strokeWidth="0.8"
        android:pathData="M54,14 A40,40 0 1,0 54,94 A40,40 0 1,0 54,14 Z" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeAlpha="0.04"
        android:strokeWidth="0.8"
        android:pathData="M54,22 A32,32 0 1,0 54,86 A32,32 0 1,0 54,22 Z" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeAlpha="0.03"
        android:strokeWidth="0.8"
        android:pathData="M54,30 A24,24 0 1,0 54,78 A24,24 0 1,0 54,30 Z" />
</vector>'''
        with open(bg_file, "w", encoding="utf-8") as f:
            f.write(xml)

    # 2. Foregrounds
    for theme in COLOR_THEMES:
        tid = theme["id"]
        fg_file = f"{RES_DIR}/drawable/ic_launcher_{tid}_foreground.xml" if tid != "default" else f"{RES_DIR}/drawable/ic_launcher_foreground.xml"
        accent1 = theme["accent_primary"]
        xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:translateX="18"
        android:translateY="18"
        android:scaleX="3.0"
        android:scaleY="3.0">
        <!-- Solid Noteheads -->
        <path
            android:fillColor="{accent1}"
            android:pathData="M6,17 C7.6569,17 9,17.8954 9,19 C9,20.1046 7.6569,21 6,21 C4.3431,21 3,20.1046 3,19 C3,17.8954 4.3431,17 6,17 Z" />
        <path
            android:fillColor="{accent1}"
            android:pathData="M18,15 C19.6569,15 21,15.8954 21,17 C21,18.1046 19.6569,19 18,19 C16.3431,19 15,18.1046 15,17 C15,15.8954 16.3431,15 18,15 Z" />
        <!-- Stems, Beams and Outlines -->
        <path
            android:strokeColor="{accent1}"
            android:strokeWidth="2.2"
            android:strokeLineCap="round"
            android:strokeLineJoin="round"
            android:pathData="M9,19 C9,20.1046 7.6569,21 6,21 C4.3431,21 3,20.1046 3,19 C3,17.8954 4.3431,17 6,17 C7.6569,17 9,17.8954 9,19 Z M9,19 V5 L21,3 V17 M21,17 C21,18.1046 19.6569,19 18,19 C16.3431,19 15,18.1046 15,17 C15,15.8954 16.3431,15 18,15 C19.6569,15 21,15.8954 21,17 Z M9,9 L21,7" />
    </group>
</vector>'''
        with open(fg_file, "w", encoding="utf-8") as f:
            f.write(xml)

    # 3. Monochrome
    mono_file = f"{RES_DIR}/drawable/ic_launcher_monochrome.xml"
    mono_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:translateX="18"
        android:translateY="18"
        android:scaleX="3.0"
        android:scaleY="3.0">
        <path
            android:fillColor="#FFFFFF"
            android:pathData="M6,17 C7.6569,17 9,17.8954 9,19 C9,20.1046 7.6569,21 6,21 C4.3431,21 3,20.1046 3,19 C3,17.8954 4.3431,17 6,17 Z" />
        <path
            android:fillColor="#FFFFFF"
            android:pathData="M18,15 C19.6569,15 21,15.8954 21,17 C21,18.1046 19.6569,19 18,19 C16.3431,19 15,18.1046 15,17 C15,15.8954 16.3431,15 18,15 Z" />
        <path
            android:strokeColor="#FFFFFF"
            android:strokeWidth="2.2"
            android:strokeLineCap="round"
            android:strokeLineJoin="round"
            android:pathData="M9,19 C9,20.1046 7.6569,21 6,21 C4.3431,21 3,20.1046 3,19 C3,17.8954 4.3431,17 6,17 C7.6569,17 9,17.8954 9,19 Z M9,19 V5 L21,3 V17 M21,17 C21,18.1046 19.6569,19 18,19 C16.3431,19 15,18.1046 15,17 C15,15.8954 16.3431,15 18,15 C19.6569,15 21,15.8954 21,17 Z M9,9 L21,7" />
    </group>
</vector>'''
    with open(mono_file, "w", encoding="utf-8") as f:
        f.write(mono_xml)

    # 4. Adaptive icon mipmaps (mipmap-anydpi-v26)
    mipmap_v26 = f"{RES_DIR}/mipmap-anydpi-v26"
    for theme in COLOR_THEMES:
        tid = theme["id"]
        bg_ref = f"@drawable/ic_launcher_{tid}_background" if tid != "default" else "@drawable/ic_launcher_background"
        fg_ref = f"@drawable/ic_launcher_{tid}_foreground" if tid != "default" else "@drawable/ic_launcher_foreground"
        
        xml_content = f'''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="{bg_ref}" />
    <foreground android:drawable="{fg_ref}" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>'''
        
        if tid == "default":
            with open(f"{mipmap_v26}/ic_launcher.xml", "w", encoding="utf-8") as f:
                f.write(xml_content)
            with open(f"{mipmap_v26}/ic_launcher_round.xml", "w", encoding="utf-8") as f:
                f.write(xml_content)
        
        with open(f"{mipmap_v26}/ic_launcher_{tid}.xml", "w", encoding="utf-8") as f:
            f.write(xml_content)
        with open(f"{mipmap_v26}/ic_launcher_{tid}_round.xml", "w", encoding="utf-8") as f:
            f.write(xml_content)

    print("Android vector drawables & adaptive mipmaps created.")

def render_bitmap_icon(theme, size, is_round=False):
    scale = 4
    canvas_size = size * scale
    im = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)

    bg_top = theme["bg_top"]
    bg_bottom = theme["bg_bottom"]
    
    if is_round:
        draw.ellipse([0, 0, canvas_size, canvas_size], fill=(bg_bottom[0], bg_bottom[1], bg_bottom[2], 255))
        draw.ellipse([scale, scale, canvas_size - scale, canvas_size - scale], outline=(255, 255, 255, 25), width=max(1, int(1.5 * scale)))
    else:
        radius = int(canvas_size * 0.22)
        draw.rounded_rectangle([0, 0, canvas_size, canvas_size], radius=radius, fill=(bg_bottom[0], bg_bottom[1], bg_bottom[2], 255))
        draw.rounded_rectangle([scale, scale, canvas_size - scale, canvas_size - scale], radius=radius - scale, outline=(255, 255, 255, 25), width=max(1, int(1.5 * scale)))

    center = canvas_size // 2
    for r_ratio in [0.42, 0.35, 0.28]:
        r_px = int(canvas_size * r_ratio)
        draw.ellipse([center - r_px, center - r_px, center + r_px, center + r_px], outline=(255, 255, 255, 8), width=max(1, int(1 * scale)))

    glyph_scale = (canvas_size * 0.52) / 18.0
    ox = center - 12.0 * glyph_scale
    oy = center - 12.0 * glyph_scale

    def pt(x, y):
        return (ox + x * glyph_scale, oy + y * glyph_scale)

    accent_color = theme["accent_rgb"]
    sw = max(1, int(2.2 * glyph_scale))

    lh_bbox = [ox + 3 * glyph_scale, oy + 17 * glyph_scale, ox + 9 * glyph_scale, oy + 21 * glyph_scale]
    rh_bbox = [ox + 15 * glyph_scale, oy + 15 * glyph_scale, ox + 21 * glyph_scale, oy + 19 * glyph_scale]
    
    draw.ellipse(lh_bbox, fill=(accent_color[0], accent_color[1], accent_color[2], 255), outline=(accent_color[0], accent_color[1], accent_color[2], 255), width=sw)
    draw.ellipse(rh_bbox, fill=(accent_color[0], accent_color[1], accent_color[2], 255), outline=(accent_color[0], accent_color[1], accent_color[2], 255), width=sw)

    draw.line([pt(9, 19), pt(9, 5)], fill=(accent_color[0], accent_color[1], accent_color[2], 255), width=sw)
    draw.line([pt(21, 17), pt(21, 3)], fill=(accent_color[0], accent_color[1], accent_color[2], 255), width=sw)
    draw.line([pt(9, 5), pt(21, 3)], fill=(accent_color[0], accent_color[1], accent_color[2], 255), width=sw)
    draw.line([pt(9, 9), pt(21, 7)], fill=(accent_color[0], accent_color[1], accent_color[2], 255), width=sw)

    res = im.resize((size, size), Image.Resampling.LANCZOS)
    return res

def render_foreground_only(size=432):
    scale = 2
    canvas_size = size * scale
    im = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)

    center = canvas_size // 2
    glyph_scale = (canvas_size * 0.50) / 18.0
    ox = center - 12.0 * glyph_scale
    oy = center - 12.0 * glyph_scale

    def pt(x, y):
        return (ox + x * glyph_scale, oy + y * glyph_scale)

    accent_color = (255, 255, 255)
    sw = max(1, int(2.2 * glyph_scale))

    lh_bbox = [ox + 3 * glyph_scale, oy + 17 * glyph_scale, ox + 9 * glyph_scale, oy + 21 * glyph_scale]
    rh_bbox = [ox + 15 * glyph_scale, oy + 15 * glyph_scale, ox + 21 * glyph_scale, oy + 19 * glyph_scale]
    
    draw.ellipse(lh_bbox, fill=(255, 255, 255, 255), outline=(255, 255, 255, 255), width=sw)
    draw.ellipse(rh_bbox, fill=(255, 255, 255, 255), outline=(255, 255, 255, 255), width=sw)

    draw.line([pt(9, 19), pt(9, 5)], fill=(255, 255, 255, 255), width=sw)
    draw.line([pt(21, 17), pt(21, 3)], fill=(255, 255, 255, 255), width=sw)
    draw.line([pt(9, 5), pt(21, 3)], fill=(255, 255, 255, 255), width=sw)
    draw.line([pt(9, 9), pt(21, 7)], fill=(255, 255, 255, 255), width=sw)

    res = im.resize((size, size), Image.Resampling.LANCZOS)
    return res

def generate_png_mipmaps():
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    fg_im = render_foreground_only(432)
    fg_im.save(f"{RES_DIR}/drawable/ic_launcher_foreground.png")
    print(f"Updated {RES_DIR}/drawable/ic_launcher_foreground.png")

    for dir_name, size in densities.items():
        folder = f"{RES_DIR}/{dir_name}"
        os.makedirs(folder, exist_ok=True)
        for theme in COLOR_THEMES:
            tid = theme["id"]
            sq_im = render_bitmap_icon(theme, size, is_round=False)
            rd_im = render_bitmap_icon(theme, size, is_round=True)

            if tid == "default":
                sq_im.save(f"{folder}/ic_launcher.png")
                rd_im.save(f"{folder}/ic_launcher_round.png")

            sq_im.save(f"{folder}/ic_launcher_{tid}.png")
            rd_im.save(f"{folder}/ic_launcher_{tid}_round.png")

    logo_im = render_bitmap_icon(COLOR_THEMES[0], 256, is_round=False)
    logo_im.save("docs/readme/logo.png")
    print("Updated docs/readme/logo.png")

generate_drawables()
generate_png_mipmaps()
print("All drawables, adaptive icons, and mipmaps generated successfully!")
