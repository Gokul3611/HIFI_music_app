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
    val accentHex: Long,
    val aliasSuffix: String,
) {
    DEFAULT("default", "Obsidian Titanium", 0xFFE2E8F0, "Default"),
    EMERALD("emerald", "Cyber Emerald", 0xFF00FF87, "Emerald"),
    VIOLET("violet", "Electric Violet", 0xFFA855F7, "Violet"),
    AMBER("amber", "Sunset Amber", 0xFFF59E0B, "Amber"),
    SAPPHIRE("sapphire", "Ocean Sapphire", 0xFF38BDF8, "Sapphire"),
    CRIMSON("crimson", "Crimson Ruby", 0xFFF43F5E, "Crimson"),
    GOLD("gold", "Midnight Gold", 0xFFD97706, "Gold"),
    MONO("mono", "Minimal Mono", 0xFFFFFFFF, "Mono");

    val aliasName: String get() = "com.stash.app.MainActivityAlias$aliasSuffix"

    companion object {
        fun fromId(id: String?): AppIcon = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
