package com.remodroid.windows

import com.remodroid.shared.control.Key
import com.remodroid.shared.control.NormalizedPoint
import com.remodroid.windows.data.DesktopSettings
import com.remodroid.windows.input.VirtualScreen
import com.remodroid.windows.input.WindowsInputInjector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WindowsLogicTest {
    @Test
    fun `normalised points map onto the SendInput absolute range`() {
        val vs = VirtualScreen(-1920, 0, 3840, 1080)
        assertEquals(0 to 0, vs.toAbsolute(NormalizedPoint(0f, 0f)))
        assertEquals(65535 to 65535, vs.toAbsolute(NormalizedPoint(1f, 1f)))
        assertEquals(32768 to 32768, vs.toAbsolute(NormalizedPoint(0.5f, 0.5f)))
        assertEquals(65535 to 0, vs.toAbsolute(NormalizedPoint(2f, -1f))) // clamped
    }

    @Test
    fun `every protocol key has a virtual-key code`() {
        Key.entries.forEach { assertNotNull(WindowsInputInjector.virtualKey(it), it.name) }
        assertEquals(0x0D, WindowsInputInjector.virtualKey(Key.ENTER))
    }

    @Test
    fun `server URL validation`() {
        assertNull(DesktopSettings.validateUrl("wss://signal.example.com/ws"))
        assertNull(DesktopSettings.validateUrl("ws://localhost:8080/ws"))
        assertNotNull(DesktopSettings.validateUrl("http://localhost"))
    }

    @Test
    fun `virtual screen reports a positive size`() {
        val vs = VirtualScreen.current()
        assert(vs.width > 0 && vs.height > 0)
    }
}
