package com.remodroid.windows

import com.remodroid.shared.session.ControllerAgent
import com.remodroid.shared.session.HostAgent
import com.remodroid.shared.signaling.SignalingClient
import com.remodroid.windows.data.DesktopSettings
import com.remodroid.windows.input.WindowsInputInjector
import com.remodroid.windows.rtc.DesktopRtcFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/** Process-wide objects for the desktop app. The Swing EDT is single-threaded, as the agents require. */
class DesktopContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val settings = DesktopSettings()
    val rtcFactory = DesktopRtcFactory()
    val injector = WindowsInputInjector()

    val signaling = SignalingClient(
        url = { settings.current.signalingUrl },
        scope = scope,
        log = { println("[signaling] $it") },
    )

    val host = HostAgent(
        signaling = signaling,
        rtcFactory = rtcFactory,
        injector = injector,
        scope = scope,
        displayName = { settings.current.displayName },
        rtcSettings = { settings.current.relayOnly to settings.current.quality },
    )

    val controller = ControllerAgent(
        signaling = signaling,
        rtcFactory = rtcFactory,
        scope = scope,
        displayName = { settings.current.displayName },
        relayOnly = { settings.current.relayOnly },
    )

    init {
        scope.launch {
            settings.settings.map { it.signalingUrl }.distinctUntilChanged().drop(1).collect { signaling.reconnectNow() }
        }
    }

    /** Tells the server and any partner that this device is leaving, then releases native resources. */
    suspend fun shutdown() {
        withTimeoutOrNull(2_000) {
            host.stopSharing().join()
            controller.disconnect().join()
            delay(250) // let the WebSocket flush the goodbye messages
        }
        signaling.stop()
        rtcFactory.dispose()
        scope.cancel()
    }
}

