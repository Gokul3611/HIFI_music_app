package com.stash.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val espStatus by Esp32SocketStreamer.status.collectAsStateWithLifecycle()
    val supported = viewModel.isSupported
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var confirmBitPerfect by remember { mutableStateOf(false) }
    var showEsp32Guide by remember { mutableStateOf(false) }
    var espIpInput by remember { mutableStateOf(Esp32SocketStreamer.espHost) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isPinging by remember { mutableStateOf(false) }
    var isSwitchingMode by remember { mutableStateOf(false) }
    var verificationResultText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val now = verdict
    val device = now?.deviceName
    val verdictText = when {
        mode == HiResOutputMode.ESP32_WIFI_DIRECT ->
            if (espStatus.isConnected) {
                "🟢 Connected: ${espStatus.host}:8888 · ${espStatus.activeDacMode.label}"
            } else if (espStatus.latencyMs != null) {
                "🟡 Reachable: ${espStatus.host} (${espStatus.latencyMs}ms) · ${espStatus.activeDacMode.label}"
            } else {
                "🔴 Disconnected (${espStatus.host}) · Tap to connect / test"
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
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ESP32 Hi-Fi Direct Stream", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 1. LIVE HARDWARE STATUS CARD
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                espStatus.isConnected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                espStatus.isHardwareVerified -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                espStatus.isConnected -> Color(0xFF2E7D32)
                                                espStatus.isHardwareVerified -> Color(0xFFF57F17)
                                                else -> Color(0xFFC62828)
                                            }
                                        )
                                )
                                Text(
                                    text = when {
                                        espStatus.isConnected -> "CONNECTED & READY"
                                        espStatus.isHardwareVerified -> "REACHABLE ON NETWORK"
                                        else -> "DISCONNECTED / UNVERIFIED"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        espStatus.isConnected -> Color(0xFF2E7D32)
                                        espStatus.isHardwareVerified -> Color(0xFFE65100)
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                            }

                            Text(
                                text = "Active Mode: ${espStatus.activeDacMode.label}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )

                            if (espStatus.latencyMs != null || espStatus.rssiDbm != null) {
                                Text(
                                    text = "Ping: ${espStatus.latencyMs ?: 0} ms · Wi-Fi Signal: ${espStatus.rssiDbm ?: -50} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            if (!espStatus.statusMessage.isNullOrEmpty()) {
                                Text(
                                    text = espStatus.statusMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // 2. HARDWARE DAC MODE SELECTOR WITH INSTANT VERIFICATION
                    Text(
                        text = "1. Select Hardware DAC Mode (Two-Way ACK):",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val isPcm = espStatus.activeDacMode == Esp32SocketStreamer.DacMode.PCM5102A
                        Button(
                            onClick = {
                                isSwitchingMode = true
                                verificationResultText = "Switching to PCM5102A..."
                                Esp32SocketStreamer.switchDacMode(Esp32SocketStreamer.DacMode.PCM5102A) { success, msg ->
                                    isSwitchingMode = false
                                    verificationResultText = if (success) "✓ Confirmed by ESP32: PCM5102A 32-bit I2S Active" else msg
                                }
                            },
                            enabled = !isSwitchingMode,
                            colors = if (isPcm) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (isPcm) "✓ PCM5102A" else "PCM5102A",
                                fontWeight = if (isPcm) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                            )
                        }

                        val isInternal = espStatus.activeDacMode == Esp32SocketStreamer.DacMode.INTERNAL_DAC
                        Button(
                            onClick = {
                                isSwitchingMode = true
                                verificationResultText = "Switching to Built-in DAC..."
                                Esp32SocketStreamer.switchDacMode(Esp32SocketStreamer.DacMode.INTERNAL_DAC) { success, msg ->
                                    isSwitchingMode = false
                                    verificationResultText = if (success) "✓ Confirmed by ESP32: Built-in DAC Active" else msg
                                }
                            },
                            enabled = !isSwitchingMode,
                            colors = if (isInternal) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (isInternal) "✓ Built-in" else "Built-in",
                                fontWeight = if (isInternal) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    if (verificationResultText != null) {
                        Text(
                            text = verificationResultText ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (verificationResultText?.startsWith("✓") == true) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        )
                    }

                    // 3. PING & TEST CONNECTION
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                isPinging = true
                                verificationResultText = "Pinging ESP32..."
                                val targetIp = espIpInput.trim().ifEmpty { Esp32SocketStreamer.espHost }
                                Esp32SocketStreamer.pingOrQueryStatus(targetIp) { success, resultStatus ->
                                    isPinging = false
                                    verificationResultText = if (success) {
                                        "✓ Pong in ${resultStatus.latencyMs}ms! Signal: ${resultStatus.rssiDbm ?: -50}dBm · Mode: ${resultStatus.activeDacMode.label}"
                                    } else {
                                        "❌ No response from $targetIp:8889. Ensure ESP32 is powered on."
                                    }
                                }
                            },
                            enabled = !isPinging,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (isPinging) "Pinging..." else "⚡ Test Ping", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                isDiscovering = true
                                verificationResultText = "Scanning network for ESP32..."
                                Esp32SocketStreamer.discoverAndConnect(
                                    onDiscovered = { ip ->
                                        isDiscovering = false
                                        espIpInput = ip
                                        verificationResultText = "✓ Discovered ESP32 at $ip!"
                                    },
                                    onFailed = {
                                        isDiscovering = false
                                        verificationResultText = "❌ Discovery timed out. Check hotspot 'FBI-VAN'."
                                    },
                                )
                            },
                            enabled = !isDiscovering,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (isDiscovering) "Scanning..." else "🔍 Discover", fontSize = 12.sp)
                        }
                    }

                    // 4. MANUAL IP CONFIGURATION
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
                Button(onClick = {
                    val cleanIp = espIpInput.trim().ifEmpty { "192.168.43.1" }
                    Esp32SocketStreamer.espHost = cleanIp
                    Esp32SocketStreamer.close()
                    Esp32SocketStreamer.connect(cleanIp) { success, msg ->
                        verificationResultText = msg
                    }
                    viewModel.setMode(HiResOutputMode.ESP32_WIFI_DIRECT)
                    showEsp32Guide = false
                }) { Text("Connect & Stream") }
            },
            dismissButton = {
                TextButton(onClick = { showEsp32Guide = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}
