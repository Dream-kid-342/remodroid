package com.remodroid.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.remodroid.android.data.AppSettings
import com.remodroid.android.input.AndroidInputInjector
import com.remodroid.android.rtc.AndroidRtcFactory
import com.remodroid.shared.session.ControllerAgent
import com.remodroid.shared.session.HostAgent
import com.remodroid.shared.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-wide objects. Agents live here (not in an Activity) so sessions survive configuration
 * changes and the foreground service can reach them.
 */
@OptIn(FlowPreview::class)
class AppContainer(private val app: Application) {
    /** Main is single-threaded, which the agents require. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = AppSettings(app)

    val signaling = SignalingClient(
        url = { settings.current.signalingUrl },
        scope = scope,
        log = { Log.d(TAG, it) },
    )

    lateinit var host: HostAgent
        private set

    val rtcFactory = AndroidRtcFactory(app) {
        // MediaProjection was stopped by the system or the user from the status bar.
        host.endSession()
    }
    val injector = AndroidInputInjector(app, scope)

    val controller = ControllerAgent(
        signaling = signaling,
        rtcFactory = rtcFactory,
        scope = scope,
        displayName = { settings.current.displayName },
        relayOnly = { settings.current.relayOnly },
        log = { Log.d(TAG, it) },
    )

    init {
        host = HostAgent(
            signaling = signaling,
            rtcFactory = rtcFactory,
            injector = injector,
            scope = scope,
            displayName = { settings.current.displayName },
            rtcSettings = { settings.current.relayOnly to settings.current.quality },
            log = { Log.d(TAG, it) },
        )
        watchNetwork()
        reconnectOnUrlChange()
        stopSignalingWhenIdle()
    }

    /** Switching between Wi-Fi and mobile data leaves the old socket half-open; reconnect at once. */
    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) {
                val previous = current
                current = network
                if (previous != null && previous != network) scope.launch { signaling.reconnectNow() }
            }
        })
    }

    private fun reconnectOnUrlChange() {
        scope.launch {
            settings.settings.map { it.signalingUrl }.distinctUntilChanged().drop(1).collect { signaling.reconnectNow() }
        }
    }

    /** Battery: keep no socket open when the user is neither sharing nor controlling. */
    private fun stopSignalingWhenIdle() {
        scope.launch {
            combine(host.ui, controller.ui) { h, c -> !h.sharingEnabled && !c.state.isInSession }
                .distinctUntilChanged()
                .debounce(30_000)
                .collect { idle -> if (idle) signaling.stop() }
        }
    }

    companion object {
        const val TAG = "RemoDroid"
    }
}
