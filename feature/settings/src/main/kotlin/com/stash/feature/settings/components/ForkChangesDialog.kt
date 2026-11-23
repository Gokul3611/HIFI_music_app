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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.feature.settings.R
import com.stash.feature.settings.STASH_GITHUB_URL

/**
 * Technical changelog dialog detailing architectural modifications,
 * hardware streaming extensions, upstream cleanup, and bug fixes
 * applied to this fork of Stash.
 */
@Composable
fun ForkChangesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.settings_about_fork_changes_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.settings_about_fork_changes_dialog_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Text(
                        text = stringResource(R.string.settings_about_fork_changes_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                        lineHeight = 17.sp,
                    )
                }

                TechnicalChangelogSection(
                    tag = "STREAMING",
                    title = "ESP32 Wi-Fi DAC Pipeline",
                    entries = listOf(
                        "Raw linear PCM streaming over TCP (port 8888) bypassing Android AudioFlinger resampling and Bluetooth SBC/AAC compression.",
                        "Out-of-band UDP control (port 8889) for runtime DAC mode switching between external I2S (PCM5102A) and internal 8-bit DAC with TPDF dither.",
                        "SPSC 64 KB ringbuffer with binary semaphore handshake to eliminate FreeRTOS priority inversion and audio DMA underruns.",
                        "Frame-aligned stream reset (& ~7u) and 0x8000 DC mid-rail bias on underrun to eliminate speaker pop transients.",
                        "Phone audio sink silenced automatically during active ESP32 Wi-Fi direct playback.",
                    ),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TechnicalChangelogSection(
                    tag = "PLAYBACK",
                    title = "Audio Engine & Queue Fixes",
                    entries = listOf(
                        "Equalizer shortcuts wired directly into track options and player context menus.",
                        "Continuous playback fallback: local recommendation radio kicks in when queue is exhausted.",
                        "Fixed queue drag-to-top reordering index corruption in playback queue sheet.",
                        "Enforced VISIBILITY_PUBLIC and dedicated notification channel to guarantee track metadata on lockscreens across Android versions.",
                    ),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TechnicalChangelogSection(
                    tag = "SYNC",
                    title = "Spotify Onboarding & FLAC Sources",
                    entries = listOf(
                        "First-run onboarding prompt for one-tap Spotify library and playlist synchronization.",
                        "Lossless FLAC downloader resilience with active YouTube Music / Tidal fallback and retry cooldown handling.",
                    ),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TechnicalChangelogSection(
                    tag = "CLEANUP",
                    title = "Upstream De-bloat & Theming",
                    entries = listOf(
                        "Stripped upstream sponsor banners, Ko-fi donation tickers, web-link cloud sync paywalls, and Discord self-bot prompts.",
                        "Removed unused upstream telemetry workers and unauthenticated background pings.",
                        "Custom dark minimalist SVG vinyl launcher icon with dynamic in-app icon picker sheet.",
                    ),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TechnicalChangelogSection(
                    tag = "BUILD",
                    title = "Distribution & CI",
                    entries = listOf(
                        "GitHub Releases API integration for direct in-app update checks and APK downloads.",
                        "ARM-only release NDK filters (dropping unused x86/x86_64 ffmpeg/python slices), multi-threaded IR compiler backend, and R8 minification.",
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
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(stringResource(R.string.settings_about_fork_changes_github))
                }
            }
        },
    )
}

@Composable
private fun TechnicalChangelogSection(
    tag: String,
    title: String,
    entries: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "-",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
