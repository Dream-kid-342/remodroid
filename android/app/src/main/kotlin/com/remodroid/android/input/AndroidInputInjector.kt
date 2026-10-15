package com.remodroid.android.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.remodroid.android.rtc.displayInfo
import com.remodroid.shared.control.CoordinateMapper
import com.remodroid.shared.control.GlobalAction
import com.remodroid.shared.control.GlobalActionCommand
import com.remodroid.shared.control.InputCommand
import com.remodroid.shared.control.Key
import com.remodroid.shared.control.KeyCommand
import com.remodroid.shared.control.LongPress
import com.remodroid.shared.control.MouseButtonEvent
import com.remodroid.shared.control.MouseMove
import com.remodroid.shared.control.NormalizedPoint
import com.remodroid.shared.control.ScreenInfo
import com.remodroid.shared.control.Swipe
import com.remodroid.shared.control.Tap
import com.remodroid.shared.control.TextInput
import com.remodroid.shared.control.Wheel
import com.remodroid.shared.models.Capability
import com.remodroid.shared.models.HostPlatform
import com.remodroid.shared.rtc.InjectionResult
import com.remodroid.shared.rtc.InputInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Performs remote input through [RemoteControlAccessibilityService]. Without the service the
 * host offers view-only sessions and explains why.
 */
class AndroidInputInjector(private val context: Context, scope: CoroutineScope) : InputInjector {
    override val platform = HostPlatform.ANDROID

    override val supported: StateFlow<Set<Capability>> = RemoteControlAccessibilityService.connected
        .map { if (it) ALL_SUPPORTED else emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val note: StateFlow<String?> = RemoteControlAccessibilityService.connected
        .map { if (it) null else DISABLED_NOTE }
        .stateIn(scope, SharingStarted.Eagerly, DISABLED_NOTE)

    /** For tests and diagnostics. */
    val lastGesture = MutableStateFlow<String?>(null)

    override suspend fun inject(command: InputCommand, screen: ScreenInfo?): InjectionResult {
        val service = RemoteControlAccessibilityService.instance ?: return InjectionResult.Unsupported(DISABLED_NOTE)
        // Use the live display size: rotation may have changed since the last host_state.
        val display = displayInfo(context)
        fun px(p: NormalizedPoint) = CoordinateMapper.normalizedToDisplay(p, display.width, display.height)

        return when (command) {
            is Tap -> gesture(service, listOf(px(command.point)), TAP_MS)
            is LongPress -> gesture(service, listOf(px(command.point)), command.durationMs)
            is Swipe -> gesture(service, command.path.map(::px), command.durationMs)
            is GlobalActionCommand -> globalAction(service, command.action)
            is TextInput -> typeText(service, command.text)
            is KeyCommand -> key(service, command)
            is MouseMove, is MouseButtonEvent, is Wheel ->
                InjectionResult.Unsupported("Android devices accept touch gestures, not mouse events.")
        }
    }

    private fun gesture(service: AccessibilityService, points: List<Pair<Int, Int>>, durationMs: Long): InjectionResult {
        val path = Path()
        val (x0, y0) = points.first()
        path.moveTo(x0.toFloat(), y0.toFloat())
        if (points.size == 1) {
            // A zero-length path is invalid for some OEM implementations; nudge by one pixel.
            path.lineTo(x0.toFloat() + 0.5f, y0.toFloat())
        } else {
            points.drop(1).forEach { (x, y) -> path.lineTo(x.toFloat(), y.toFloat()) }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, GestureDescription.getMaxGestureDuration()))
        val description = "${points.size} point(s) from $x0,$y0, ${durationMs}ms"
        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "gesture completed: $description")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "gesture cancelled by the system: $description")
                }
            },
            null,
        )
        Log.d(TAG, "gesture dispatched=$ok: $description")
        lastGesture.value = description
        return if (ok) InjectionResult.Ok else InjectionResult.Failed("The system refused the gesture.")
    }

    private fun globalAction(service: AccessibilityService, action: GlobalAction): InjectionResult {
        val id = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        }
        return if (service.performGlobalAction(id)) InjectionResult.Ok else InjectionResult.Failed("The system refused the action.")
    }

    private fun focusedEditable(service: AccessibilityService): AccessibilityNodeInfo? =
        service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun typeText(service: AccessibilityService, text: String): InjectionResult {
        val node = focusedEditable(service) ?: return InjectionResult.Unsupported("Tap a text field on the device first.")
        // Password fields: RemoDroid neither reads nor writes them (their content is masked to services).
        if (node.isPassword) return InjectionResult.Unsupported("Typing into password fields is not supported.")
        val current = node.text?.toString().orEmpty()
        return setText(node, current + text)
    }

    private fun key(service: AccessibilityService, command: KeyCommand): InjectionResult {
        if (!command.down) return InjectionResult.Ok
        return when (command.key) {
            Key.BACKSPACE -> {
                val node = focusedEditable(service) ?: return InjectionResult.Unsupported("Tap a text field on the device first.")
                if (node.isPassword) return InjectionResult.Unsupported("Editing password fields is not supported.")
                setText(node, node.text?.toString().orEmpty().dropLast(1))
            }
            Key.ENTER -> {
                val node = focusedEditable(service) ?: return InjectionResult.Unsupported("Tap a text field on the device first.")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    return InjectionResult.Unsupported("Enter requires Android 11 or newer.")
                }
                val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                if (ok) InjectionResult.Ok else InjectionResult.Failed("The text field did not accept Enter.")
            }
            else -> InjectionResult.Unsupported("That key is not available on Android devices.")
        }
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): InjectionResult {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) InjectionResult.Ok
        else InjectionResult.Failed("The text field did not accept the text.")
    }

    companion object {
        private const val TAG = "RemoDroidInput"
        private const val TAP_MS = 60L
        const val DISABLED_NOTE =
            "Remote control is off on this device. Enable \"RemoDroid remote control\" in Accessibility settings to allow it."
        val ALL_SUPPORTED = setOf(Capability.POINTER, Capability.NAVIGATION, Capability.TEXT_INPUT, Capability.SYSTEM_PANELS)
    }
}
