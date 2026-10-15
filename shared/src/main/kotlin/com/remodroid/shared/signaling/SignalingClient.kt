package com.remodroid.shared.signaling

import com.remodroid.shared.protocol.ClientMessage
import com.remodroid.shared.protocol.ServerMessage
import com.remodroid.shared.protocol.SignalingCodec
import com.remodroid.shared.util.Backoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionStatus { STOPPED, CONNECTING, CONNECTED, WAITING_TO_RETRY }

/**
 * Persistent outbound WebSocket to the signaling server. Both the host role and the controller role
 * share one connection. On every (re)connect [connected] fires so each role can register or resume.
 *
 * The client only ever opens outbound connections, so it works behind NAT, carrier-grade NAT and
 * firewalls on both Wi-Fi and mobile data.
 */
class SignalingClient(
    /** Read on every connection attempt, so a changed setting applies on the next reconnect. */
    private val url: () -> String,
    private val scope: CoroutineScope,
    baseClient: OkHttpClient = OkHttpClient(),
    private val backoff: Backoff = Backoff(),
    private val log: (String) -> Unit = {},
) {
    private val http = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // WebSocket pings detect half-open connections (e.g. after switching from Wi-Fi to mobile data).
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow(ConnectionStatus.STOPPED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMessage>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    /** OkHttp delivers messages on its own thread; a single pump preserves their order (SDP before ICE). */
    private val inbox = Channel<ServerMessage>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (message in inbox) _messages.emit(message)
        }
    }

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Fires after each successful (re)connection. */
    val connected: SharedFlow<Unit> = _connected.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var generation = 0
    private var running = false
    private var retryJob: Job? = null

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        connect()
    }

    fun stop() {
        val ws: WebSocket?
        synchronized(lock) {
            running = false
            generation++
            ws = socket
            socket = null
            retryJob?.cancel()
            retryJob = null
        }
        ws?.close(1000, "Client stopped")
        _status.value = ConnectionStatus.STOPPED
    }

    /** Drops the current socket and reconnects immediately, e.g. after the OS reports a network change. */
    fun reconnectNow() {
        val ws: WebSocket?
        synchronized(lock) {
            if (!running) return
            generation++
            ws = socket
            socket = null
            retryJob?.cancel()
            retryJob = null
        }
        ws?.cancel()
        backoff.reset()
        connect()
    }

    /** Returns false if not connected; callers decide whether the message matters after a reconnect. */
    fun send(message: ClientMessage): Boolean {
        val ws = synchronized(lock) { socket } ?: return false
        if (_status.value != ConnectionStatus.CONNECTED) return false
        return ws.send(SignalingCodec.encode(message))
    }

    private fun connect() {
        val myGeneration: Int
        synchronized(lock) {
            if (!running) return
            myGeneration = ++generation
        }
        _status.value = ConnectionStatus.CONNECTING
        val request = try {
            Request.Builder().url(url()).build()
        } catch (e: IllegalArgumentException) {
            _lastError.value = "Invalid signaling server URL"
            _status.value = ConnectionStatus.STOPPED
            synchronized(lock) { running = false }
            return
        }
        val ws = http.newWebSocket(request, Listener(myGeneration))
        synchronized(lock) {
            if (generation == myGeneration) socket = ws else ws.cancel()
        }
    }

    private fun isCurrent(gen: Int) = synchronized(lock) { gen == generation && running }

    private fun scheduleRetry(gen: Int) {
        synchronized(lock) {
            if (gen != generation || !running) return
            socket = null
            val delayMs = backoff.nextDelayMs()
            _status.value = ConnectionStatus.WAITING_TO_RETRY
            log("signaling: retrying in ${delayMs}ms")
            retryJob = scope.launch {
                delay(delayMs)
                connect()
            }
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(gen)) return webSocket.cancel()
            backoff.reset()
            _lastError.value = null
            _status.value = ConnectionStatus.CONNECTED
            log("signaling: connected")
            _connected.tryEmit(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(gen)) return
            val message = SignalingCodec.decode(text) ?: return log("signaling: ignored unknown message")
            inbox.trySend(message)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(gen)) return
            log("signaling: closed ($code)")
            if (code == 4001) _lastError.value = "This device signed in again from another connection"
            scheduleRetry(gen)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(gen)) return
            _lastError.value = when (response?.code) {
                403 -> "The signaling server refused this client"
                429 -> "Too many connections from this network; retrying"
                else -> "Cannot reach the signaling server"
            }
            log("signaling: failure ${t.javaClass.simpleName}")
            scheduleRetry(gen)
        }
    }
}
