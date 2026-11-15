package com.stash.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.prefs.HiResOutputMode
import com.stash.core.media.output.Esp32SocketStreamer
import com.stash.core.media.output.SignalQuality
import com.stash.feature.settings.components.SettingsChoice
import com.stash.feature.settings.components.SettingsChoiceSheet
import com.stash.feature.settings.components.SettingsValueRow

/**
 * Audio & Quality > Output > Hi-Res output: one row showing the current mode,
 * opening a sheet with the output modes including ESP32 Hi-Fi Direct.
 */
@Composable
fun HiResOutputRow(viewModel: HiResOutputViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val verdict by viewModel.signalPath.collectAsStateWithLifecycle()
    val supported = viewModel.isSupported
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var confirmBitPerfect by remember { mutableStateOf(false) }
    var showEsp32Guide by remember { mutableStateOf(false) }
    var espIpInput by remember { mutableStateOf(Esp32SocketStreamer.espHost) }
    var selectedDacMode by remember { mutableStateOf(Esp32SocketStreamer.dacMode) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveryStatus by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val now = verdict
    val device = now?.deviceName
    val verdictText = when {
        mode == HiResOutputMode.ESP32_WIFI_DIRECT ->
            if (Esp32SocketStreamer.isConnected.get()) {
                "Connected to ESP32 (${Esp32SocketStreamer.espHost}:8888) · 24-bit/96kHz"
            } else {
                "Waiting for ESP32 on Hotspot (${Esp32SocketStreamer.espHost})"
            }
        now == null -> null
        device != null -> stringResource(R.string.settings_hires_verdict_now_with_device, now.headline, device)
        else -> stringResource(R.string.settings_hires_verdict_now, now.headline)
    }
    val verdictColor = if (now?.quality == SignalQuality.RESAMPLED && mode != HiResOutputMode.ESP32_WIFI_DIRECT) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    SettingsValueRow(
        title = stringResource(R.string.settings_hires_output_title),
        value = when (mode) {
            HiResOutputMode.STANDARD -> stringResource(R.string.settings_hires_mode_standard)
            HiResOutputMode.MATCH_RATE -> stringResource(R.string.settings_hires_value_match_rate)
            HiResOutputMode.BIT_PERFECT -> stringResource(R.string.settings_hires_value_bit_perfect)
            HiResOutputMode.ESP32_WIFI_DIRECT -> "ESP32 Hi-Fi Direct"
        },
        subtitle = verdictText,
        onClick = { sheetOpen = true },
    )

    if (sheetOpen) {
        val unsupported = stringResource(R.string.settings_hires_needs_android_14)
        SettingsChoiceSheet(
            title = stringResource(R.string.settings_hires_output_title),
            header = {
                Text(
                    text = verdictText ?: stringResource(R.string.settings_hires_verdict_idle),
                    style = MaterialTheme.typography.bodySmall,
                    color = verdictColor,
                )
            },
            choices = listOf(
                SettingsChoice(
                    HiResOutputMode.STANDARD,
                    stringResource(R.string.settings_hires_mode_standard),
                    stringResource(R.string.settings_hires_mode_standard_desc),
                ),
                SettingsChoice(
                    HiResOutputMode.MATCH_RATE,
                    stringResource(R.string.settings_hires_mode_match_rate),
                    if (supported) stringResource(R.string.settings_hires_mode_match_rate_desc) else unsupported,
                    enabled = supported,
                ),
                SettingsChoice(
                    HiResOutputMode.BIT_PERFECT,
                    stringResource(R.string.settings_hires_mode_bit_perfect),
                    if (supported) stringResource(R.string.settings_hires_mode_bit_perfect_desc) else unsupported,
                    enabled = supported,
                ),
                SettingsChoice(
                    HiResOutputMode.ESP32_WIFI_DIRECT,
                    "ESP32 Hi-Fi Direct (Wi-Fi Hotspot)",
                    "Streams uncompressed 24-bit/96kHz bit-perfect audio directly to ESP32 over local Wi-Fi Hotspot without Bluetooth compression",
                    enabled = true,
                ),
            ),
            selected = mode,
            onSelect = { picked ->
                when (picked) {
                    HiResOutputMode.BIT_PERFECT -> if (picked != mode) confirmBitPerfect = true
                    HiResOutputMode.ESP32_WIFI_DIRECT -> {
                        sheetOpen = false
                        showEsp32Guide = true
                    }
                    else -> if (picked != mode) viewModel.setMode(picked)
                }
            },
            onDismiss = { sheetOpen = false },
            footer = stringResource(R.string.settings_hires_footer_note),
        )
    }

    if (confirmBitPerfect) {
        AlertDialog(
            onDismissRequest = { confirmBitPerfect = false },
            title = { Text(stringResource(R.string.settings_hires_bit_perfect_dialog_title)) },
            text = {
                Text(stringResource(R.string.settings_hires_bit_perfect_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBitPerfect = false
                    viewModel.setMode(HiResOutputMode.BIT_PERFECT)
                }) { Text(stringResource(R.string.settings_hires_bit_perfect_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBitPerfect = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }

    if (showEsp32Guide) {
        AlertDialog(
            onDismissRequest = { showEsp32Guide = false },
            title = { Text("ESP32 Hi-Fi Direct Stream", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Zero tethering: ESP32 joins your existing Wi-Fi / Hotspot automatically without disturbing other devices.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "1. Select Hardware DAC Mode:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                selectedDacMode = Esp32SocketStreamer.DacMode.PCM5102A
                                Esp32SocketStreamer.setDacMode(Esp32SocketStreamer.DacMode.PCM5102A)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (selectedDacMode == Esp32SocketStreamer.DacMode.PCM5102A) "✓ PCM5102A (24/96)" else "PCM5102A (24/96)",
                                fontWeight = if (selectedDacMode == Esp32SocketStreamer.DacMode.PCM5102A) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                selectedDacMode = Esp32SocketStreamer.DacMode.INTERNAL_DAC
                                Esp32SocketStreamer.setDacMode(Esp32SocketStreamer.DacMode.INTERNAL_DAC)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (selectedDacMode == Esp32SocketStreamer.DacMode.INTERNAL_DAC) "✓ Built-in DAC" else "Built-in DAC",
                                fontWeight = if (selectedDacMode == Esp32SocketStreamer.DacMode.INTERNAL_DAC) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }

                    Text(
                        text = "2. Auto-Discovery (UDP broadcast port 8889):",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(
                        onClick = {
                            isDiscovering = true
                            discoveryStatus = "Scanning network for ESP32 Hi-Fi..."
                            Esp32SocketStreamer.discoverAndConnect(
                                onDiscovered = { ip ->
                                    isDiscovering = false
                                    espIpInput = ip
                                    discoveryStatus = "Found ESP32 at $ip!"
                                },
                                onFailed = {
                                    isDiscovering = false
                                    discoveryStatus = "Not discovered automatically. Verify IP below."
                                },
                            )
                        },
                        enabled = !isDiscovering,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isDiscovering) "Scanning network..." else "🔍 Auto-Discover ESP32")
                    }

                    if (discoveryStatus != null) {
                        Text(
                            text = discoveryStatus ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Text(
                        text = "3. Or manually enter the ESP32 IP:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = espIpInput,
                        onValueChange = { espIpInput = it },
                        label = { Text("ESP32 IP Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleanIp = espIpInput.trim().ifEmpty { "192.168.43.1" }
                    Esp32SocketStreamer.espHost = cleanIp
                    Esp32SocketStreamer.dacMode = selectedDacMode
                    Esp32SocketStreamer.close()
                    Esp32SocketStreamer.connect(cleanIp)
                    viewModel.setMode(HiResOutputMode.ESP32_WIFI_DIRECT)
                    showEsp32Guide = false
                }) { Text("Connect & Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showEsp32Guide = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}
