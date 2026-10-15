package com.remodroid.windows.data

import com.remodroid.shared.rtc.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.util.prefs.Preferences

data class DesktopSettingsData(
    val signalingUrl: String,
    val displayName: String,
    val quality: VideoQuality,
    val relayOnly: Boolean,
)

/**
 * Non-secret preferences stored with java.util.prefs (per-user registry on Windows).
 * No credentials are persisted. The default server URL can be supplied with the
 * REMODROID_SIGNALING_URL environment variable or -Dremodroid.signalingUrl.
 */
class DesktopSettings {
    private val prefs = Preferences.userRoot().node("com/remodroid/windows")
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DesktopSettingsData> = _settings.asStateFlow()
    val current get() = _settings.value

    private fun load() = DesktopSettingsData(
        signalingUrl = prefs.get(KEY_URL, null) ?: defaultUrl(),
        displayName = prefs.get(KEY_NAME, null) ?: defaultName(),
        quality = prefs.get(KEY_QUALITY, null)?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() } ?: VideoQuality.AUTO,
        relayOnly = prefs.getBoolean(KEY_RELAY, false),
    )

    fun update(next: DesktopSettingsData) {
        val clean = next.copy(signalingUrl = next.signalingUrl.trim(), displayName = next.displayName.trim().ifEmpty { defaultName() })
        prefs.put(KEY_URL, clean.signalingUrl)
        prefs.put(KEY_NAME, clean.displayName)
        prefs.put(KEY_QUALITY, clean.quality.name)
        prefs.putBoolean(KEY_RELAY, clean.relayOnly)
        prefs.flush()
        _settings.value = clean
    }

    companion object {
        private const val KEY_URL = "signaling_url"
        private const val KEY_NAME = "display_name"
        private const val KEY_QUALITY = "video_quality"
        private const val KEY_RELAY = "relay_only"

        fun defaultUrl(): String =
            System.getProperty("remodroid.signalingUrl") ?: System.getenv("REMODROID_SIGNALING_URL") ?: "ws://localhost:8080/ws"

        fun defaultName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.take(64) ?: "Windows PC"

        fun validateUrl(url: String): String? {
            val u = url.trim()
            return when {
                u.startsWith("wss://") && u.length > 6 -> null
                u.startsWith("ws://") && u.length > 5 -> null // allowed for local testing; production should use wss://
                else -> "The address must start with wss:// (or ws:// for local testing)."
            }
        }
    }
}
