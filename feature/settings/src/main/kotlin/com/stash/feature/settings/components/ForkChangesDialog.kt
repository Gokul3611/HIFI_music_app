package com.stash.feature.settings.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.feature.settings.R
import com.stash.feature.settings.STASH_GITHUB_URL

/**
 * Dialog displaying a breakdown of features, bug fixes, hardware streaming
 * capabilities, and architectural improvements added to this app fork of Stash.
 */
@Composable
fun ForkChangesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.settings_about_fork_changes_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.settings_about_fork_changes_dialog_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Overview banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = stringResource(R.string.settings_about_fork_changes_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                        lineHeight = 18.sp,
                    )
                }

                // Section 1: ESP32 DAC
                ChangeCategorySection(
                    icon = Icons.Rounded.Wifi,
                    title = "ESP32 Hi-Fi Wireless Streaming",
                    items = listOf(
                        "Lossless 24-bit & 32-bit / 96kHz PCM streaming directly to ESP32 over Wi-Fi SoftAP / UDP.",
                        "Dual-mode hardware DAC: instant out-of-band switching between external I2S (PCM5102A) and built-in internal DAC direct to amp.",
                        "Audiophile stability: anti-jitter prebuffering, symmetric TPDF dither, and lockless ringbuffer memory management.",
                        "Automatic phone speaker muting during wireless DAC streaming.",
                    ),
                )

                // Section 2: Audio & Playback
                ChangeCategorySection(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Audio & Playback Intelligence",
                    items = listOf(
                        "Quick Equalizer access directly embedded inside track options and playback menus.",
                        "Smart local music recommendations with continuous Spotify-like autoplay fallback.",
                        "Fixed queue reordering glitches when dragging tracks to the very top.",
                    ),
                )

                // Section 3: Spotify & Lossless
                ChangeCategorySection(
                    icon = Icons.Rounded.Sync,
                    title = "Spotify Sync & Lossless Pipeline",
                    items = listOf(
                        "Spotify onboarding sync prompt for effortless library and playlist imports.",
                        "Resilient FLAC downloading and streaming pipeline with automated retries and fallback.",
                    ),
                )

                // Section 4: UI & System Polish
                ChangeCategorySection(
                    icon = Icons.Rounded.Palette,
                    title = "UI, Theming & Polish",
                    items = listOf(
                        "Lockscreen song title fix: enforced VISIBILITY_PUBLIC so track metadata is always visible on lockscreen.",
                        "Custom dark minimalist vinyl app icon with in-app dynamic icon switcher and live previews.",
                        "Decluttered UI: stripped upstream donation banners, Ko-fi tickers, and unused telemetry workers.",
                    ),
                )

                // Section 5: Updates & Performance
                ChangeCategorySection(
                    icon = Icons.Rounded.Speed,
                    title = "Updates & Performance",
                    items = listOf(
                        "100% automated direct updates via the GitHub Releases API.",
                        "Multi-threaded IR compiler backend, ARM-only NDK release filters, and R8 minification for fast startup and smaller APK.",
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.settings_about_fork_changes_close),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val open = Intent(Intent.ACTION_VIEW, Uri.parse(STASH_GITHUB_URL))
                    runCatching { context.startActivity(open) }
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(stringResource(R.string.settings_about_fork_changes_github))
                }
            }
        },
    )
}

@Composable
private fun ChangeCategorySection(
    icon: ImageVector,
    title: String,
    items: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}
