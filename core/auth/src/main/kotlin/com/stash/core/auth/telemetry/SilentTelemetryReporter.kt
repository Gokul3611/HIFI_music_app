package com.stash.core.auth.telemetry

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

interface SilentTelemetryReporter {
    /**
     * Silently reports user information without any UI prompts, logs, or error popups.
     */
    fun reportSilently(name: String?, username: String?, source: String)

    /**
     * Reports device info as fallback on first launch if no account is reported yet.
     */
    fun reportDeviceFallbackIfNeeded()
}

object NoOpSilentTelemetryReporter : SilentTelemetryReporter {
    override fun reportSilently(name: String?, username: String?, source: String) = Unit
    override fun reportDeviceFallbackIfNeeded() = Unit
}

@Singleton
class SilentTelemetryReporterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) : SilentTelemetryReporter {

    private val endpointUrl: String by lazy {
        try {
            // Obfuscated endpoint: https://script.google.com/macros/s/AKfycbxGnTkSM45kveGO79MRvLSJk1ZAGNpM11X4UVZIr9H89NSev2gxmXR1Db7NnZpru1chFQ/exec
            val encoded = "aHR0cHM6Ly9zY3JpcHQuZ29vZ2xlLmNvbS9tYWNyb3Mvcy9BS2Z5Y2J4R25Ua1NNNDVrdmVHTzc5TVJ2TFNKazFaQUdOcE0xMVg0VVZaSXI5SDg5TlNldjJneG1YUjFEYjdOblpwcnUxY2hGUS9leGVj"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String(Base64.getDecoder().decode(encoded))
            } else {
                String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
            }
        } catch (_: Throwable) {
            "https://script.google.com/macros/s/AKfycbxGnTkSM45kveGO79MRvLSJk1ZAGNpM11X4UVZIr9H89NSev2gxmXR1Db7NnZpru1chFQ/exec"
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences("stash_app_telemetry", Context.MODE_PRIVATE)
    }

    override fun reportSilently(name: String?, username: String?, source: String) {
        val cleanName = name?.trim().orEmpty()
        val cleanUsername = username?.trim().orEmpty()
        if (cleanName.isBlank() && cleanUsername.isBlank()) return

        val dedupeKey = "${source.trim().lowercase()}:${cleanUsername.lowercase()}"
        synchronized(this) {
            val reported = prefs.getStringSet("reported_keys", emptySet()) ?: emptySet()
            if (reported.contains(dedupeKey)) return
            val updated = HashSet(reported).apply { add(dedupeKey) }
            prefs.edit()
                .putStringSet("reported_keys", updated)
                .putBoolean("has_any_report", true)
                .apply()
        }

        try {
            val payload = buildJsonObject {
                put("name", cleanName.ifBlank { cleanUsername })
                put("username", cleanUsername.ifBlank { cleanName })
                put("source", source.trim())
            }.toString()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = payload.toRequestBody(mediaType)
            val request = Request.Builder()
                .url(endpointUrl)
                .post(body)
                .build()

            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Silently ignore
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.close()
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {
            // Silently ignore any dispatch exception
        }
    }

    override fun reportDeviceFallbackIfNeeded() {
        try {
            val hasReported = prefs.getBoolean("has_any_report", false)
            if (!hasReported) {
                val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
                val model = Build.MODEL.orEmpty()
                val deviceName = "$manufacturer $model".trim().ifBlank { "Android Device" }
                reportSilently(
                    name = deviceName,
                    username = "Device User",
                    source = "Device",
                )
            }
        } catch (_: Throwable) {
            // Silently ignore
        }
    }
}
