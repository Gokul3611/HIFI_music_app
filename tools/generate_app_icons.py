"""
Generates high-precision, dark & minimalist master SVG app icons and Android vector drawables.
Inspired by music-svgrepo-com.svg with bit-perfect audiophile craftsmanship.
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
        "glow_opacity": "0.14",
        "bg_top": "#12141A",
        "bg_bottom": "#090A0D",
        "rim": "rgba(255, 255, 255, 0.08)",
        "hex_preview": "#E2E8F0"
    },
    {
        "id": "emerald",
        "name": "Cyber Emerald",
        "accent_primary": "#00FF87",
        "accent_secondary": "#60EFA0",
        "glow": "#00FF87",
        "glow_opacity": "0.20",
        "bg_top": "#0E1814",
        "bg_bottom": "#080F0C",
        "rim": "rgba(0, 255, 135, 0.15)",
        "hex_preview": "#00FF87"
    },
    {
        "id": "violet",
        "name": "Electric Violet",
        "accent_primary": "#C084FC",
        "accent_secondary": "#9333EA",
        "glow": "#A855F7",
        "glow_opacity": "0.22",
        "bg_top": "#161022",
        "bg_bottom": "#0B0713",
        "rim": "rgba(192, 132, 252, 0.15)",
        "hex_preview": "#A855F7"
    },
    {
        "id": "amber",
        "name": "Sunset Amber",
        "accent_primary": "#FDE047",
        "accent_secondary": "#F97316",
        "glow": "#F59E0B",
        "glow_opacity": "0.20",
        "bg_top": "#1C150C",
        "bg_bottom": "#0F0B06",
        "rim": "rgba(253, 224, 71, 0.15)",
        "hex_preview": "#F59E0B"
    },
    {
        "id": "sapphire",
        "name": "Ocean Sapphire",
        "accent_primary": "#38BDF8",
        "accent_secondary": "#2563EB",
        "glow": "#0284C7",
        "glow_opacity": "0.22",
        "bg_top": "#0C1522",
        "bg_bottom": "#060B12",
        "rim": "rgba(56, 189, 248, 0.15)",
        "hex_preview": "#38BDF8"
    },
    {
        "id": "crimson",
        "name": "Crimson Ruby",
        "accent_primary": "#FB7185",
        "accent_secondary": "#E11D48",
        "glow": "#F43F5E",
        "glow_opacity": "0.22",
        "bg_top": "#1F0E13",
        "bg_bottom": "#10070A",
        "rim": "rgba(251, 113, 133, 0.15)",
        "hex_preview": "#F43F5E"
    },
    {
        "id": "gold",
        "name": "Midnight Gold",
        "accent_primary": "#FDE68A",
        "accent_secondary": "#D97706",
        "glow": "#F59E0B",
        "glow_opacity": "0.18",
        "bg_top": "#19150E",
        "bg_bottom": "#0D0B07",
        "rim": "rgba(253, 230, 138, 0.15)",
        "hex_preview": "#D97706"
    },
    {
        "id": "mono",
        "name": "Minimal Monochrome",
        "accent_primary": "#FFFFFF",
        "accent_secondary": "#E2E8F0",
        "glow": "#FFFFFF",
        "glow_opacity": "0.08",
        "bg_top": "#000000",
        "bg_bottom": "#000000",
        "rim": "rgba(255, 255, 255, 0.12)",
        "hex_preview": "#FFFFFF"
    }
]

def generate_master_svg(theme):
    svg_id = theme["id"]
    accent1 = theme["accent_primary"]
    accent2 = theme["accent_secondary"]
    glow = theme["glow"]
    glow_op = theme["glow_opacity"]
    bg_top = theme["bg_top"]
    bg_bottom = theme["bg_bottom"]
    rim = theme["rim"]

    svg = f'''<svg width="512" height="512" viewBox="0 0 512 512" fill="none" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <!-- Background Gradient -->
    <linearGradient id="bgGrad_{svg_id}" x1="256" y1="0" x2="256" y2="512" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="{bg_top}"/>
      <stop offset="100%" stop-color="{bg_bottom}"/>
    </linearGradient>

    <!-- Glyph Metallic Gradient -->
    <linearGradient id="glyphGrad_{svg_id}" x1="160" y1="120" x2="380" y2="380" gradientUnits="userSpaceOnUse">
      <stop offset="0%" stop-color="{accent1}"/>
      <stop offset="100%" stop-color="{accent2}"/>
    </linearGradient>

    <!-- Ambient Backlight Radial Glow -->
    <radialGradient id="aura_{svg_id}" cx="50%" cy="56%" r="46%">
      <stop offset="0%" stop-color="{glow}" stop-opacity="{glow_op}"/>
      <stop offset="60%" stop-color="{glow}" stop-opacity="0.03"/>
      <stop offset="100%" stop-color="{bg_bottom}" stop-opacity="0"/>
    </radialGradient>

    <!-- Soft Drop Shadow on Glyph -->
    <filter id="shadow_{svg_id}" x="-20%" y="-20%" width="140%" height="140%">
      <feDropShadow dx="0" dy="8" stdDeviation="12" flood-color="#000000" flood-opacity="0.6"/>
      <feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="{glow}" flood-opacity="0.25"/>
    </filter>
  </defs>

  <!-- Premium Squircle Container -->
  <rect width="512" height="512" rx="116" fill="url(#bgGrad_{svg_id})"/>

  <!-- Subtle Beveled Rim Light -->
  <rect x="2" y="2" width="508" height="508" rx="114" stroke="{rim}" stroke-width="2.5"/>

  <!-- Acoustic Vinyl Concentric Micro-Grooves -->
  <circle cx="256" cy="256" r="218" stroke="#FFFFFF" stroke-opacity="0.02" stroke-width="1.2"/>
  <circle cx="256" cy="256" r="184" stroke="#FFFFFF" stroke-opacity="0.028" stroke-width="1.2"/>
  <circle cx="256" cy="256" r="150" stroke="#FFFFFF" stroke-opacity="0.02" stroke-width="1.2"/>
  <circle cx="256" cy="256" r="116" stroke="#FFFFFF" stroke-opacity="0.015" stroke-width="1.2"/>

  <!-- Ambient Light Aura -->
  <circle cx="256" cy="270" r="180" fill="url(#aura_{svg_id})"/>

  <!-- The Minimalist Double Beamed Musical Note Glyph -->
  <!-- Transformed from 24x24 reference: scaled 15.5x, centered with optical balance -->
  <g transform="translate(256, 256) scale(15.5) translate(-12, -12)" filter="url(#shadow_{svg_id})">
    <!-- Solid weighted noteheads -->
    <ellipse cx="6" cy="19" rx="3" ry="2" fill="url(#glyphGrad_{svg_id})"/>
    <ellipse cx="18" cy="17" rx="3" ry="2" fill="url(#glyphGrad_{svg_id})"/>
    <!-- Precision stems and dual horizontal beams -->
    <path d="M9 19C9 20.1046 7.65685 21 6 21C4.34315 21 3 20.1046 3 19C3 17.8954 4.34315 17 6 17C7.65685 17 9 17.8954 9 19ZM9 19V5L21 3V17M21 17C21 18.1046 19.6569 19 18 19C16.3431 19 15 18.1046 15 17C15 15.8954 16.3431 15 18 15C19.6569 15 21 15.8954 21 17ZM9 9L21 7"
          stroke="url(#glyphGrad_{svg_id})" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
  </g>
</svg>'''
    return svg

def generate_android_vector(theme):
    accent1 = theme["accent_primary"]
    accent2 = theme["accent_secondary"]
    
    # Android VectorDrawable 108dp x 108dp
    # Safe zone: center 72x72 (from 18,18 to 90,90)
    # Scaled by 3.0, translated (18, 18)
    vector_xml = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
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
    return vector_xml

def generate_android_background(theme):
    bg_color = theme["bg_bottom"]
    vector_xml = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{bg_color}"
        android:pathData="M0,0h108v108h-108z" />
    <!-- Subtle Audiophile Concentric Grooves -->
    <path
        android:strokeColor="#FFFFFF"
        android:strokeAlpha="0.03"
        android:strokeWidth="0.8"
        android:pathData="M54,16 A38,38 0 1,0 54,92 A38,38 0 1,0 54,16 Z" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeAlpha="0.04"
        android:strokeWidth="0.8"
        android:pathData="M54,24 A30,30 0 1,0 54,84 A30,30 0 1,0 54,24 Z" />
    <path
        android:strokeColor="#FFFFFF"
        android:strokeAlpha="0.03"
        android:strokeWidth="0.8"
        android:pathData="M54,32 A22,22 0 1,0 54,76 A22,22 0 1,0 54,32 Z" />
</vector>'''
    return vector_xml

def generate_monochrome_vector():
    vector_xml = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
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
            android:fillColor="#FFFFFF"
            android:pathData="M6,17 C7.6569,17 9,17.8954 9,19 C9,20.1046 7.6569,21 6,21 C4.3431,21 3,20.1046 3,19 C3,17.8954 4.3431,17 6,17 Z" />
        <path
            android:fillColor="#FFFFFF"
            android:pathData="M18,15 C19.6569,15 21,15.8954 21,17 C21,18.1046 19.6569,19 18,19 C16.3431,19 15,18.1046 15,17 C15,15.8954 16.3431,15 18,15 Z" />
        <!-- Stems, Beams and Outlines -->
        <path
            android:strokeColor="#FFFFFF"
            android:strokeWidth="2.2"
            android:strokeLineCap="round"
            android:strokeLineJoin="round"
            android:pathData="M9,19 C9,20.1046 7.6569,21 6,21 C4.3431,21 3,20.1046 3,19 C3,17.8954 4.3431,17 6,17 C7.6569,17 9,17.8954 9,19 Z M9,19 V5 L21,3 V17 M21,17 C21,18.1046 19.6569,19 18,19 C16.3431,19 15,18.1046 15,17 C15,15.8954 16.3431,15 18,15 C19.6569,15 21,15.8954 21,17 Z M9,9 L21,7" />
    </group>
</vector>'''
    return vector_xml

# 1. Generate master SVG app_icon.svg (and variants)
os.makedirs("art", exist_ok=True)
for theme in COLOR_THEMES:
    svg_content = generate_master_svg(theme)
    if theme["id"] == "default":
        with open("app_icon.svg", "w", encoding="utf-8") as f:
            f.write(svg_content)
        with open("art/app_icon.svg", "w", encoding="utf-8") as f:
            f.write(svg_content)
    with open(f"art/app_icon_{theme['id']}.svg", "w", encoding="utf-8") as f:
        f.write(svg_content)

print("Master SVGs generated successfully.")
