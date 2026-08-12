package com.nonpta.bridge.network

import com.google.gson.Gson
import com.nonpta.bridge.model.ConnectionState
import com.nonpta.bridge.model.SignalMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent TCP client for JSON signaling.
 *
 * - Newline-delimited JSON protocol.
 * - Sends {"type":"ping"} every 12s; if no data arrives within 25s the
 *   connection is considered dead and state transitions to DISCONNECTED.
 * - All writes go through a Mutex so concurrent coroutines never interleave on
 *   the output stream.
 *
 * DISCONNECT FIX: disconnect() immediately flips ConnectionState to DISCONNECTED
 * on the calling thread, then fires a completely detached IO coroutine to
 * forcefully close the socket/streams. No blocking, no deadlock.
 */
class SocketManager {

    companion object {
        const val TCP_PORT = 5000
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val HEARTBEAT_INTERVAL_MS = 12_000L
        private const val HEARTBEAT_TIMEOUT_MS = 25_000L
    }

    /* ── coroutine infrastructure ─────────────────────────────────────── */

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val writeMutex = Mutex()

    /* ── socket state ─────────────────────────────────────────────────── */

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    private val lastReceivedTime = AtomicLong(0L)

    /* ── public observable state ───────────────────────────────────────── */

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SignalMessage>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<SignalMessage> = _incomingMessages.asSharedFlow()

    /** IP address of the currently connected (or last attempted) server. */
    var serverIp: String = ""
        private set

    /* ── connect / disconnect ──────────────────────────────────────────── */

    fun connect(ip: String, port: Int = TCP_PORT) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        serverIp = ip

        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING

                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                newSocket.tcpNoDelay = true
                newSocket.keepAlive = true

                socket = newSocket
                reader = BufferedReader(
                    InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8)
                )
                writer = PrintWriter(
                    BufferedWriter(
                        OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8)
                    ),
                    false
                )

                lastReceivedTime.set(System.currentTimeMillis())
                _connectionState.value = ConnectionState.CONNECTED

                startReader()
                startHeartbeat()
            } catch (e: Exception) {
                forceCleanup()
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * FIX #1: Non-blocking disconnect.
     *
     * 1. Immediately cancel coroutine jobs (non-blocking).
     * 2. Immediately flip state to DISCONNECTED (UI updates instantly).
     * 3. Fire a DETACHED coroutine to forcefully close socket/streams.
     *    This runs on a raw IO thread and does NOT block any scope.
     */
    fun disconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return

        // Step 1: Cancel reader/heartbeat jobs immediately (non-blocking)
        heartbeatJob?.cancel()
        readerJob?.cancel()
        heartbeatJob = null
        readerJob = null

        // Step 2: Flip state IMMEDIATELY so UI is responsive
        _connectionState.value = ConnectionState.DISCONNECTED

        // Step 3: Capture references, null them out, then close on detached IO thread
        val s = socket
        val r = reader
        val w = writer
        socket = null
        reader = null
        writer = null

        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            // Force close — swallow all IOExceptions
            runCatching { w?.close() }
            runCatching { r?.close() }
            runCatching { s?.close() }
        }
    }

    /* ── reader coroutine ──────────────────────────────────────────────── */

    private fun startReader() {
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val line = try {
                        reader?.readLine()
                    } catch (e: IOException) {
                        null // Force disconnect on socket read failure
                    }
                    if (line == null) {
                        handleDisconnect()
                        break
                    }
                    lastReceivedTime.set(System.currentTimeMillis())

                    try {
                        val msg = gson.fromJson(line, SignalMessage::class.java)
                        if (msg.type != "pong") {
                            _incomingMessages.emit(msg)
                        }
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                handleDisconnect()
            }
        }
    }

    /* ── heartbeat coroutine ───────────────────────────────────────────── */

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)

                val elapsed = System.currentTimeMillis() - lastReceivedTime.get()
                if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                    handleDisconnect()
                    break
                }

                sendRaw("""{"type":"ping"}""")
            }
        }
    }

    /* ── sending ───────────────────────────────────────────────────────── */

    fun sendMessage(msg: SignalMessage) {
        scope.launch(Dispatchers.IO) {
            try {
                sendRaw(gson.toJson(msg))
            } catch (_: Exception) {
                handleDisconnect()
            }
        }
    }

    private suspend fun sendRaw(json: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                writer?.apply {
                    println(json)
                    flush()
                    if (checkError()) {
                        handleDisconnect()
                    }
                }
            } catch (e: Exception) {
                handleDisconnect()
            }
        }
    }

    /* ── cleanup ───────────────────────────────────────────────────────── */

    /** Called from internal coroutines (reader timeout, heartbeat fail). */
    private fun handleDisconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return
        forceCleanup()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Synchronous cleanup — only called from within IO coroutines. */
    private fun forceCleanup() {
        heartbeatJob?.cancel()
        readerJob?.cancel()
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }

    fun destroy() {
        forceCleanup()
        scope.cancel()
    }
}
