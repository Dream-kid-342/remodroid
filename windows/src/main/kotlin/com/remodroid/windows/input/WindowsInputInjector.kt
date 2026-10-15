package com.remodroid.windows.input

import com.remodroid.shared.control.GlobalActionCommand
import com.remodroid.shared.control.InputCommand
import com.remodroid.shared.control.Key
import com.remodroid.shared.control.KeyCommand
import com.remodroid.shared.control.LongPress
import com.remodroid.shared.control.MouseButton
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
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** The Windows virtual screen: the bounding box of all monitors, in physical pixels. */
data class VirtualScreen(val left: Int, val top: Int, val width: Int, val height: Int) {
    /** Maps a normalised point to SendInput's absolute 0..65535 virtual-desktop coordinates. */
    fun toAbsolute(p: NormalizedPoint): Pair<Int, Int> {
        val ax = (p.x.coerceIn(0f, 1f) * 65535f).roundToInt()
        val ay = (p.y.coerceIn(0f, 1f) * 65535f).roundToInt()
        return ax to ay
    }

    companion object {
        private const val SM_XVIRTUALSCREEN = 76
        private const val SM_YVIRTUALSCREEN = 77
        private const val SM_CXVIRTUALSCREEN = 78
        private const val SM_CYVIRTUALSCREEN = 79

        fun current(): VirtualScreen {
            val u = User32.INSTANCE
            return VirtualScreen(
                u.GetSystemMetrics(SM_XVIRTUALSCREEN),
                u.GetSystemMetrics(SM_YVIRTUALSCREEN),
                u.GetSystemMetrics(SM_CXVIRTUALSCREEN).coerceAtLeast(1),
                u.GetSystemMetrics(SM_CYVIRTUALSCREEN).coerceAtLeast(1),
            )
        }
    }
}

/**
 * Injects mouse and keyboard input with the documented Win32 SendInput API.
 *
 * Windows limits apply: input cannot reach windows of elevated (administrator) processes unless
 * RemoDroid itself runs elevated (UIPI), and nothing can be injected into the secure desktop
 * (UAC prompts, lock screen, Ctrl+Alt+Del).
 */
class WindowsInputInjector : InputInjector {
    override val platform = HostPlatform.WINDOWS
    override val supported: StateFlow<Set<Capability>> = MutableStateFlow(setOf(Capability.POINTER, Capability.TEXT_INPUT))
    override val note: StateFlow<String?> = MutableStateFlow(null)

    override suspend fun inject(command: InputCommand, screen: ScreenInfo?): InjectionResult {
        val vs = VirtualScreen.current()
        return when (command) {
            is MouseMove -> send(mouse(vs.toAbsolute(command.point), MOVE))
            is MouseButtonEvent -> send(mouse(vs.toAbsolute(command.point), MOVE), mouse(vs.toAbsolute(command.point), buttonFlag(command.button, command.down)))
            is Wheel -> {
                val (ax, ay) = vs.toAbsolute(command.point)
                val events = mutableListOf<InputSpec>(mouse(ax to ay, MOVE))
                // Positive dy means "scroll content down" = negative wheel delta on Windows.
                if (command.dy != 0f) events += mouse(ax to ay, WHEEL, data = (-command.dy * WHEEL_DELTA).roundToInt())
                if (command.dx != 0f) events += mouse(ax to ay, HWHEEL, data = (command.dx * WHEEL_DELTA).roundToInt())
                send(*events.toTypedArray())
            }
            // Touch gestures from a phone controller map onto the mouse.
            is Tap -> click(vs, command.point, MouseButton.LEFT)
            is LongPress -> click(vs, command.point, MouseButton.RIGHT)
            is Swipe -> drag(vs, command)
            is TextInput -> send(*command.text.flatMap { ch -> listOf(unicode(ch, up = false), unicode(ch, up = true)) }.toTypedArray())
            is KeyCommand -> {
                val vk = virtualKey(command.key) ?: return InjectionResult.Unsupported("Key not supported")
                send(key(vk, up = !command.down))
            }
            is GlobalActionCommand -> InjectionResult.Unsupported("Android navigation buttons do not apply to Windows.")
        }
    }

    private fun click(vs: VirtualScreen, p: NormalizedPoint, button: MouseButton): InjectionResult {
        val abs = vs.toAbsolute(p)
        return send(mouse(abs, MOVE), mouse(abs, buttonFlag(button, true)), mouse(abs, buttonFlag(button, false)))
    }

    private suspend fun drag(vs: VirtualScreen, swipe: Swipe): InjectionResult {
        val first = vs.toAbsolute(swipe.path.first())
        send(mouse(first, MOVE), mouse(first, LEFTDOWN))
        val stepDelay = (swipe.durationMs / swipe.path.size).coerceIn(1, 50)
        for (p in swipe.path.drop(1)) {
            send(mouse(vs.toAbsolute(p), MOVE))
            delay(stepDelay)
        }
        return send(mouse(vs.toAbsolute(swipe.path.last()), LEFTUP))
    }

    /** Fills one slot of the native INPUT array passed to SendInput. */
    private fun interface InputSpec {
        fun fill(input: WinUser.INPUT)
    }

    private fun send(vararg specs: InputSpec): InjectionResult {
        if (specs.isEmpty()) return InjectionResult.Ok
        // SendInput needs a contiguous native array of INPUT structures.
        @Suppress("UNCHECKED_CAST")
        val array = WinUser.INPUT().toArray(specs.size) as Array<WinUser.INPUT>
        specs.forEachIndexed { i, spec -> spec.fill(array[i]) }
        val sent = User32.INSTANCE.SendInput(WinDef.DWORD(specs.size.toLong()), array, array[0].size())
        return if (sent.toInt() == specs.size) InjectionResult.Ok
        else InjectionResult.Failed("Windows blocked the input (for example an administrator window or a secure screen is active).")
    }

    private fun mouse(abs: Pair<Int, Int>, flags: Int, data: Int = 0) = InputSpec { it ->
        it.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
        it.input.setType("mi")
        it.input.mi.dx = WinDef.LONG(abs.first.toLong())
        it.input.mi.dy = WinDef.LONG(abs.second.toLong())
        it.input.mi.mouseData = WinDef.DWORD(data.toLong() and 0xFFFFFFFFL)
        it.input.mi.dwFlags = WinDef.DWORD((flags or ABSOLUTE or VIRTUALDESK).toLong())
        it.input.mi.time = WinDef.DWORD(0)
        it.input.mi.dwExtraInfo = BaseTSD.ULONG_PTR(0)
    }

    private fun key(vk: Int, up: Boolean) = InputSpec { it ->
        it.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        it.input.setType("ki")
        it.input.ki.wVk = WinDef.WORD(vk.toLong())
        it.input.ki.wScan = WinDef.WORD(0)
        it.input.ki.dwFlags = WinDef.DWORD(((if (up) KEYEVENTF_KEYUP else 0) or (if (vk in EXTENDED_KEYS) KEYEVENTF_EXTENDEDKEY else 0)).toLong())
        it.input.ki.time = WinDef.DWORD(0)
        it.input.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)
    }

    private fun unicode(ch: Char, up: Boolean) = InputSpec { it ->
        it.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        it.input.setType("ki")
        it.input.ki.wVk = WinDef.WORD(0)
        it.input.ki.wScan = WinDef.WORD(ch.code.toLong())
        it.input.ki.dwFlags = WinDef.DWORD((KEYEVENTF_UNICODE or (if (up) KEYEVENTF_KEYUP else 0)).toLong())
        it.input.ki.time = WinDef.DWORD(0)
        it.input.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)
    }

    private fun buttonFlag(button: MouseButton, down: Boolean) = when (button) {
        MouseButton.LEFT -> if (down) LEFTDOWN else LEFTUP
        MouseButton.RIGHT -> if (down) RIGHTDOWN else RIGHTUP
        MouseButton.MIDDLE -> if (down) MIDDLEDOWN else MIDDLEUP
    }

    companion object {
        private const val MOVE = 0x0001
        private const val LEFTDOWN = 0x0002
        private const val LEFTUP = 0x0004
        private const val RIGHTDOWN = 0x0008
        private const val RIGHTUP = 0x0010
        private const val MIDDLEDOWN = 0x0020
        private const val MIDDLEUP = 0x0040
        private const val WHEEL = 0x0800
        private const val HWHEEL = 0x1000
        private const val VIRTUALDESK = 0x4000
        private const val ABSOLUTE = 0x8000
        private const val WHEEL_DELTA = 120f
        private const val KEYEVENTF_EXTENDEDKEY = 0x0001
        private const val KEYEVENTF_KEYUP = 0x0002
        private const val KEYEVENTF_UNICODE = 0x0004

        private val EXTENDED_KEYS = setOf(0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2E)

        fun virtualKey(key: Key): Int? = when (key) {
            Key.ENTER -> 0x0D
            Key.BACKSPACE -> 0x08
            Key.DELETE -> 0x2E
            Key.TAB -> 0x09
            Key.ESCAPE -> 0x1B
            Key.ARROW_LEFT -> 0x25
            Key.ARROW_UP -> 0x26
            Key.ARROW_RIGHT -> 0x27
            Key.ARROW_DOWN -> 0x28
            Key.HOME -> 0x24
            Key.END -> 0x23
            Key.PAGE_UP -> 0x21
            Key.PAGE_DOWN -> 0x22
            Key.SHIFT -> 0x10
            Key.CONTROL -> 0x11
            Key.ALT -> 0x12
        }
    }
}
