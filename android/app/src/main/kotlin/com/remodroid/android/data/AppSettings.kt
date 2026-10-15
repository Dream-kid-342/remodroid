package com.remodroid.android.data

import android.content.Context
import android.os.Build
import com.remodroid.android.BuildConfig
import com.remodroid.shared.rtc.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val signalingUrl: String,
    val displayName: String,
    val quality: VideoQuality,
    val relayOnly: Boolean,
)

/**
 * Non-secret preferences. RemoDroid persists no credentials: IDs, passwords and resume tokens
 * live only in memory and disappear with the process.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("remodroid_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value

    private fun load() = Settings(
        signalingUrl = prefs.getString(KEY_URL, null) ?: BuildConfig.DEFAULT_SIGNALING_URL,
        displayName = prefs.getString(KEY_NAME, null) ?: defaultName(),
        quality = prefs.getString(KEY_QUALITY, null)?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() }
            ?: VideoQuality.AUTO,
        relayOnly = prefs.getBoolean(KEY_RELAY_ONLY, false),
    )

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_URL, next.signalingUrl.trim())
            .putString(KEY_NAME, next.displayName.trim())
            .putString(KEY_QUALITY, next.quality.name)
            .putBoolean(KEY_RELAY_ONLY, next.relayOnly)
            .apply()
        _settings.value = next.copy(signalingUrl = next.signalingUrl.trim(), displayName = next.displayName.trim())
    }

    companion object {
        private const val KEY_URL = "signaling_url"
        private const val KEY_NAME = "display_name"
        private const val KEY_QUALITY = "video_quality"
        private const val KEY_RELAY_ONLY = "relay_only"

        fun defaultName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(64)

        /** Accepts ws:// only for debug builds (enforced again by the network security config). */
        fun validateUrl(url: String, allowCleartext: Boolean = BuildConfig.DEBUG): String? {
            val u = url.trim()
            return when {
                u.startsWith("wss://") && u.length > 6 -> null
                u.startsWith("ws://") && u.length > 5 -> if (allowCleartext) null else "Use a secure wss:// address."
                else -> "The address must start with wss:// (or ws:// for local testing)."
            }
        }
    }
}
