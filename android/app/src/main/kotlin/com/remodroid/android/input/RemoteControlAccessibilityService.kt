package com.remodroid.android.input

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Accessibility service that performs remote gestures, navigation actions and text entry.
 * It exists only if the user enables it in system settings, and it does nothing unless a host
 * session is active and the host granted control. It ignores accessibility events: no screen
 * content is read or forwarded.
 */
class RemoteControlAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _connected.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: RemoteControlAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)

        /** True while the system has the service bound, i.e. remote control is technically possible. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        fun component(context: Context) = ComponentName(context, RemoteControlAccessibilityService::class.java)

        /** Whether the user switched the service on in Settings (it may still be binding). */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val c = component(context)
            return AccessibilityStatus.isServiceListed(enabled, c.flattenToString(), c.flattenToShortString())
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Pure parsing helpers, unit-tested without a device. */
object AccessibilityStatus {
    /** Parses Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES ("pkg/cls:pkg/.cls2"). */
    fun isServiceListed(enabledServices: String?, fullName: String, shortName: String): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        return enabledServices.split(':').any { it.equals(fullName, ignoreCase = true) || it.equals(shortName, ignoreCase = true) }
    }
}
