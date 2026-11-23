package com.stash.core.model

/**
 * App icon color variations available to the user in Settings > Appearance.
 *
 * Each variant corresponds to an `<activity-alias>` in `AndroidManifest.xml`
 * targeting `MainActivity`.
 */
enum class AppIcon(
    val id: String,
    val title: String,
    val description: String,
    val accentHex: Long,
    val bgHex: Long,
    val aliasSuffix: String,
) {
    DEFAULT("default", "Obsidian Titanium", "Deep obsidian with titanium silver & frosty glow", 0xFFE2E8F0, 0xFF090A0D, "Default"),
    EMERALD("emerald", "Cyber Emerald", "Audiophile electric neon green", 0xFF00FF87, 0xFF060F0A, "Emerald"),
    VIOLET("violet", "Electric Violet", "Vivid cyberpunk purple", 0xFFA855F7, 0xFF0D0714, "Violet"),
    AMBER("amber", "Sunset Amber", "Golden flame and warm ember orange", 0xFFF59E0B, 0xFF0F0B06, "Amber"),
    SAPPHIRE("sapphire", "Ocean Sapphire", "Vivid marine blue and cyan", 0xFF38BDF8, 0xFF060C14, "Sapphire"),
    CRIMSON("crimson", "Crimson Ruby", "Deep scarlet ruby red", 0xFFF43F5E, 0xFF140608, "Crimson"),
    GOLD("gold", "Midnight Gold", "Champagne luxury gold", 0xFFD97706, 0xFF120B04, "Gold"),
    MONO("mono", "Minimal Mono", "Pure monochrome high-contrast", 0xFFFFFFFF, 0xFF0A0A0A, "Mono");

    val aliasName: String get() = "com.stash.app.MainActivityAlias$aliasSuffix"

    companion object {
        fun fromId(id: String?): AppIcon = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
