package com.remodroid.shared

import com.remodroid.shared.control.ControlCodec
import com.remodroid.shared.control.GlobalAction
import com.remodroid.shared.control.GlobalActionCommand
import com.remodroid.shared.control.InputCommand
import com.remodroid.shared.control.NormalizedPoint
import com.remodroid.shared.control.ScreenInfo
import com.remodroid.shared.control.Tap
import com.remodroid.shared.models.Capability
import com.remodroid.shared.models.HostPlatform
import com.remodroid.shared.models.PermissionLevel
import com.remodroid.shared.protocol.IceCandidate
import com.remodroid.shared.rtc.HostRtcPeer
import com.remodroid.shared.rtc.InjectionResult
import com.remodroid.shared.rtc.InputInjector
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcConnectionState
import com.remodroid.shared.rtc.RtcEvent
import com.remodroid.shared.rtc.RtcFactory
import com.remodroid.shared.rtc.RtcPeer
import com.remodroid.shared.rtc.RtcStats
import com.remodroid.shared.session.ControllerAgent
import com.remodroid.shared.session.HostAgent
import com.remodroid.shared.session.SessionState
import com.remodroid.shared.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the real HostAgent and ControllerAgent through the real Node.js signaling server
 * (server/dist, built with `npm run build`). WebRTC is replaced by an in-memory transport so the test
 * exercises signaling, approval, SDP/ICE relay, permission enforcement and teardown without devices.
 * Skipped when the server has not been built or `node` is not available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsAgainstServerTest {
    companion object {
        private var server: Process? = null
        private var url: String = ""

        @JvmStatic
        @BeforeClass
        fun startServer() {
            val entry = File("../server/dist/index.js").absoluteFile
            if (!entry.exists()) return
            val pb = ProcessBuilder("node", entry.path).redirectErrorStream(true)
            pb.environment().putAll(
                mapOf(
                    "PORT" to "0", "HOST" to "127.0.0.1", "LOG_LEVEL" to "info", "NODE_ENV" to "test",
                    "APPROVAL_TIMEOUT_SECONDS" to "10", "RESUME_GRACE_SECONDS" to "10",
                ),
            )
            val p = try { pb.start() } catch (_: Exception) { return }
            val reader = p.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                val port = Regex("\"port\":(\\d+)").find(line)?.groupValues?.get(1)
                if (line.contains("signaling server listening") && port != null) {
                    url = "ws://127.0.0.1:$port/ws"
                    server = p
                    Thread { reader.forEachLine { } }.apply { isDaemon = true }.start()
                    return
                }
            }
            p.destroy()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            server?.destroy()
            server?.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var net: FakeNet
    private lateinit var injector: FakeInjector
    private lateinit var hostSignaling: SignalingClient
    private lateinit var controllerSignaling: SignalingClient
    private lateinit var host: HostAgent
    private lateinit var controller: ControllerAgent

    @Before
    fun setUp() {
        assumeTrue("server/dist not built or node unavailable", server != null)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        net = FakeNet()
        injector = FakeInjector()
        hostSignaling = SignalingClient({ url }, scope)
        controllerSignaling = SignalingClient({ url }, scope)
        host = HostAgent(hostSignaling, FakeRtcFactory(net), injector, scope, { "Phone A" })
        controller = ControllerAgent(controllerSignaling, FakeRtcFactory(net), scope, { "Phone B" })
    }

    @After
    fun tearDown() {
        if (!::scope.isInitialized) return
        hostSignaling.stop()
        controllerSignaling.stop()
        scope.cancel()
    }

    private fun <T> await(flow: StateFlow<T>, what: String, predicate: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(10_000) { flow.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; last value: ${flow.value}", e)
        }
    }

    private fun registeredHost(): Pair<String, String> {
        host.startSharing()
        val ui = await(host.ui, "host registration") { it.state == SessionState.AVAILABLE && it.password != null }
        return ui.deviceId!! to ui.password!!
    }

    private fun connectedSession(level: PermissionLevel = PermissionLevel.GUIDED_SUPPORT) {
        val (id, password) = registeredHost()
        controller.connect(id, password, level)
        await(controller.ui, "waiting for approval") { it.state == SessionState.WAITING_FOR_APPROVAL }
        val incoming = await(host.ui, "incoming request") { it.state == SessionState.INCOMING_REQUEST }.incoming!!
        assertEquals("Phone B", incoming.controllerName)
        assertEquals(level, incoming.requestedPermission)
        host.approve(level)
    }

    @Test
    fun `full session with host-side permission enforcement`() {
        connectedSession(PermissionLevel.GUIDED_SUPPORT)
        await(controller.ui, "controller connected") { it.state == SessionState.CONNECTED_CONTROL_ENABLED }
        await(host.ui, "host connected") { it.state == SessionState.CONNECTED_CONTROL_ENABLED }
        assertTrue(net.candidatesDelivered.get() >= 2, "ICE candidates must be relayed both ways")

        // Permitted gesture reaches the injector.
        assertTrue(controller.send { Tap(it, NormalizedPoint(0.5f, 0.5f)) })
        waitUntil("tap injected") { injector.received.any { it is Tap } }

        // The controller UI refuses navigation (not granted)...
        assertFalse(controller.send { GlobalActionCommand(it, GlobalAction.HOME) })
        // ...and the host refuses it even when sent directly over the channel.
        net.viewer!!.sendControl(ControlCodec.encode(GlobalActionCommand(99, GlobalAction.HOME)))
        await(controller.ui, "input rejected") { it.lastInputProblem != null }
        assertTrue(injector.received.none { it is GlobalActionCommand })

        // Revoking control applies immediately on the host and is reflected on the controller.
        host.revokeControl()
        await(host.ui, "host view only") { it.state == SessionState.CONNECTED_VIEW_ONLY }
        await(controller.ui, "controller view only") { it.state == SessionState.CONNECTED_VIEW_ONLY }
        val before = injector.received.size
        net.viewer!!.sendControl(ControlCodec.encode(Tap(100, NormalizedPoint(0.1f, 0.1f))))
        Thread.sleep(300)
        assertEquals(before, injector.received.size)

        // Restore, then pause: input refused while paused.
        host.setPermission(PermissionLevel.GUIDED_SUPPORT)
        await(controller.ui, "control restored") { it.state == SessionState.CONNECTED_CONTROL_ENABLED }
        host.setPaused(true)
        await(controller.ui, "paused") { it.hostState?.paused == true }
        assertFalse(net.hostPeer!!.videoOn)
        assertFalse(controller.send { Tap(it, NormalizedPoint(0.5f, 0.5f)) })
        host.setPaused(false)
        await(controller.ui, "unpaused") { it.hostState?.paused == false }

        // Controller ends; host returns to AVAILABLE and releases media.
        controller.disconnect()
        await(controller.ui, "controller disconnected") { it.state == SessionState.DISCONNECTED }
        await(host.ui, "host available again") { it.state == SessionState.AVAILABLE && it.sessionId == null }
        assertTrue(net.hostPeer!!.closed)
        assertTrue(net.viewer!!.closed)
    }

    @Test
    fun `wrong password fails without bothering the host`() {
        val (id, _) = registeredHost()
        controller.connect(id, "zzzz2222", PermissionLevel.VIEW_ONLY)
        val ui = await(controller.ui, "failure") { it.state == SessionState.FAILED }
        assertTrue(ui.message!!.contains("incorrect"))
        assertEquals(SessionState.AVAILABLE, host.ui.value.state)
    }

    @Test
    fun `host rejection is reported to the controller`() {
        val (id, password) = registeredHost()
        controller.connect(id, password, PermissionLevel.VIEW_ONLY)
        await(host.ui, "incoming") { it.state == SessionState.INCOMING_REQUEST }
        host.reject()
        val ui = await(controller.ui, "rejected") { it.state == SessionState.FAILED }
        assertEquals("The partner declined the request.", ui.message)
        await(host.ui, "available") { it.state == SessionState.AVAILABLE }
    }

    @Test
    fun `controller can cancel while waiting for approval`() {
        val (id, password) = registeredHost()
        controller.connect(id, password, PermissionLevel.VIEW_ONLY)
        await(host.ui, "incoming") { it.state == SessionState.INCOMING_REQUEST }
        controller.disconnect()
        await(controller.ui, "cancelled") { it.state == SessionState.DISCONNECTED }
        await(host.ui, "request withdrawn") { it.state == SessionState.AVAILABLE && it.incoming == null }
    }

    @Test
    fun `host ending the session reaches the controller`() {
        connectedSession(PermissionLevel.VIEW_ONLY)
        await(controller.ui, "connected view only") { it.state == SessionState.CONNECTED_VIEW_ONLY }
        host.endSession()
        val ui = await(controller.ui, "ended") { it.state == SessionState.DISCONNECTED }
        assertEquals("The host ended the session.", ui.message)
    }

    @Test
    fun `password refresh invalidates the old password`() {
        val (id, oldPassword) = registeredHost()
        host.refreshPassword()
        val fresh = await(host.ui, "new password") { it.password != null && it.password != oldPassword }.password!!
        controller.connect(id, oldPassword, PermissionLevel.VIEW_ONLY)
        await(controller.ui, "old password rejected") { it.state == SessionState.FAILED }
        controller.connect(id, fresh, PermissionLevel.VIEW_ONLY)
        await(controller.ui, "new password accepted") { it.state == SessionState.WAITING_FOR_APPROVAL }
    }

    @Test
    fun `stop sharing invalidates the ID and ends the session`() {
        connectedSession(PermissionLevel.VIEW_ONLY)
        await(controller.ui, "connected") { it.state.isConnected }
        val (id, password) = host.ui.value.deviceId!! to host.ui.value.password!!
        host.stopSharing()
        await(controller.ui, "ended") { it.state == SessionState.DISCONNECTED }
        await(host.ui, "offline") { it.state == SessionState.OFFLINE && it.deviceId == null }
        controller.connect(id, password, PermissionLevel.VIEW_ONLY)
        await(controller.ui, "credentials invalid") { it.state == SessionState.FAILED }
    }

    @Test
    fun `session survives both signaling sockets dropping`() {
        connectedSession(PermissionLevel.GUIDED_SUPPORT)
        await(controller.ui, "connected") { it.state == SessionState.CONNECTED_CONTROL_ENABLED }
        val sessionId = host.ui.value.sessionId
        assertNotNull(sessionId)
        hostSignaling.reconnectNow()
        controllerSignaling.reconnectNow()
        Thread.sleep(1500)
        assertEquals(sessionId, host.ui.value.sessionId)
        assertEquals(SessionState.CONNECTED_CONTROL_ENABLED, controller.ui.value.state)
        // Signaling still works after both resumed: ending reaches the other side.
        controller.disconnect()
        await(host.ui, "host available") { it.state == SessionState.AVAILABLE && it.sessionId == null }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out: $what")
            Thread.sleep(20)
        }
    }
}

// ---------------------------------------------------------------- in-memory WebRTC stand-in

class FakeNet {
    @Volatile var hostPeer: FakePeer? = null
    @Volatile var viewer: FakePeer? = null
    val candidatesDelivered = java.util.concurrent.atomic.AtomicInteger()
}

class FakePeer(private val net: FakeNet, private val isHost: Boolean) : HostRtcPeer {
    private val channel = Channel<RtcEvent>(Channel.UNLIMITED)
    override val events: Flow<RtcEvent> = channel.receiveAsFlow()
    override val screen = MutableStateFlow<ScreenInfo?>(if (isHost) ScreenInfo(720, 1600, 0) else null)

    @Volatile var connected = false
    @Volatile var closed = false
    @Volatile var videoOn = true

    private fun other() = if (isHost) net.viewer else net.hostPeer
    fun emit(e: RtcEvent) { channel.trySend(e) }

    override suspend fun createOffer(iceRestart: Boolean): String {
        emit(RtcEvent.LocalCandidate(IceCandidate("candidate:host 1 udp 1 10.0.0.1 1 typ host", "0", 0)))
        return "fake-offer"
    }

    override suspend fun acceptOffer(sdp: String): String {
        require(sdp == "fake-offer")
        emit(RtcEvent.LocalCandidate(IceCandidate("candidate:viewer 1 udp 1 10.0.0.2 2 typ host", "0", 0)))
        return "fake-answer"
    }

    override suspend fun acceptAnswer(sdp: String) {
        require(sdp == "fake-answer")
        val viewer = other() ?: error("no viewer")
        for (p in listOf(this, viewer)) {
            p.connected = true
            p.emit(RtcEvent.ConnectionChanged(RtcConnectionState.CONNECTED))
            p.emit(RtcEvent.ControlChannelOpen)
        }
        viewer.emit(RtcEvent.FirstFrameRendered)
    }

    override fun addRemoteCandidate(candidate: IceCandidate) {
        net.candidatesDelivered.incrementAndGet()
    }

    override fun sendControl(text: String): Boolean {
        val target = other() ?: return false
        if (!connected || closed) return false
        target.emit(RtcEvent.ControlMessageReceived(text))
        return true
    }

    override suspend fun stats() = RtcStats(40.0, 0.0, 1500.0, 30.0, 720, 1600, false)

    override fun setVideoEnabled(enabled: Boolean) { videoOn = enabled }

    override fun close() {
        closed = true
        connected = false
        channel.close()
    }
}

class FakeRtcFactory(private val net: FakeNet) : RtcFactory {
    override suspend fun createHostPeer(config: RtcConfig): HostRtcPeer = FakePeer(net, true).also { net.hostPeer = it }
    override suspend fun createViewerPeer(config: RtcConfig): RtcPeer = FakePeer(net, false).also { net.viewer = it }
}

class FakeInjector : InputInjector {
    override val platform = HostPlatform.ANDROID
    override val supported = MutableStateFlow(
        setOf(Capability.POINTER, Capability.NAVIGATION, Capability.TEXT_INPUT, Capability.SYSTEM_PANELS),
    )
    override val note = MutableStateFlow<String?>(null)
    val received = CopyOnWriteArrayList<InputCommand>()

    override suspend fun inject(command: InputCommand, screen: ScreenInfo?): InjectionResult {
        received += command
        return InjectionResult.Ok
    }
}
