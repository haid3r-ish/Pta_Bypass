package com.nonpta.bridge.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.SharedPreferences
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nonpta.bridge.audio.AudioEngine
import com.nonpta.bridge.model.ConnectionState
import com.nonpta.bridge.model.CallState
import com.nonpta.bridge.audio.RingerManager
import com.nonpta.bridge.network.SocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Persists the SocketManager + AudioEngine in a foreground service.
 *
 * Also manages a [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK] that
 * blanks the screen during an active call (prevents face-dials).
 *
 * **Binding**: Activities bind to obtain direct references to
 * [socketManager] and [audioEngine] for observation / control.
 *
 * **Starting**: Send [ACTION_CONNECT] with [EXTRA_SERVER_IP] to
 * connect, or [ACTION_DISCONNECT] to tear down.
 */
class BridgeService : Service() {

    /* ── constants ─────────────────────────────────────────────────────── */

    companion object {
        const val ACTION_CONNECT    = "com.nonpta.bridge.action.CONNECT"
        const val ACTION_DISCONNECT = "com.nonpta.bridge.action.DISCONNECT"
        const val ACTION_ACCEPT_CALL = "com.nonpta.bridge.action.ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "com.nonpta.bridge.action.REJECT_CALL"
        const val ACTION_END_CALL    = "com.nonpta.bridge.action.END_CALL"
        const val EXTRA_SERVER_IP   = "extra_server_ip"

        private const val PREFS_NAME = "bridge_service_prefs"
        private const val KEY_SAVED_IP = "saved_server_ip"

        private const val NOTIFICATION_ID = 1001
        private const val ACTIVE_CALL_NOTIFICATION_ID = 1002
        private const val INCOMING_CALL_NOTIFICATION_ID = 1003
        private const val CHANNEL_ID      = "bridge_connection"
        private const val CHANNEL_NAME    = "Bridge Connection"
        private const val CALL_CHANNEL_ID = "bridge_calls_v2"
        private const val CALL_CHANNEL_NAME = "Incoming Calls"
    }

    /* ── public components (accessed by bound Activities) ───────────── */

    val socketManager = SocketManager()
    val audioEngine   = AudioEngine()
    private lateinit var ringerManager: RingerManager

    private val _activeCallState = MutableStateFlow(CallState.IDLE)
    val activeCallState: StateFlow<CallState> = _activeCallState.asStateFlow()

    private val _ringingCallState = MutableStateFlow(CallState.IDLE)
    val ringingCallState: StateFlow<CallState> = _ringingCallState.asStateFlow()

    var activeCallerId: String = ""
    var ringingCallerId: String = ""

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _missedCallEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val missedCallEvent = _missedCallEvent.asSharedFlow()

    private val _activeCallDisconnectEvent = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 1)
    val activeCallDisconnectEvent = _activeCallDisconnectEvent.asSharedFlow()

    private val _callEndedDueToDisconnect = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val callEndedDueToDisconnect = _callEndedDueToDisconnect.asSharedFlow()

    /**
     * Emitted when an incoming call is accepted while another call is already ACTIVE/HELD.
     * Carries the **previous** activeCallerId so PhoneViewModel can log it with its
     * accumulated duration before the caller ID is overwritten.
     */
    private val _callReplacedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val callReplacedEvent = _callReplacedEvent.asSharedFlow()

    private var reconnectJob: Job? = null
    private val GRACE_PERIOD_MS = 10_000L

    /* ── internals ─────────────────────────────────────────────────────── */

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): BridgeService = this@BridgeService
    }

    /* ── Service lifecycle ─────────────────────────────────────────────── */

    private lateinit var prefs: SharedPreferences

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ringerManager = RingerManager(this)
        createNotificationChannel()
        initProximityWakeLock()

        serviceScope.launch {
            _activeCallState.collect { state ->
                Log.d("BridgeService", "ActiveCallState transitioned to: $state")
            }
        }
        serviceScope.launch {
            _ringingCallState.collect { state ->
                Log.d("BridgeService", "RingingCallState transitioned to: $state")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            // OS Resurrected the service after a task kill
            val savedIp = prefs.getString(KEY_SAVED_IP, null)
            if (savedIp != null) {
                val notification = buildNotification("Connecting to $savedIp…")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                socketManager.connect(savedIp)
                observeConnectionState()
                observeIncomingSignals()
            } else {
                stopSelf()
            }
            return START_STICKY
        }

        when (intent.action) {

            ACTION_CONNECT -> {
                val ip = intent.getStringExtra(EXTRA_SERVER_IP) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Save IP for potential sticky restarts
                prefs.edit().putString(KEY_SAVED_IP, ip).apply()

                // Start foreground with dataSync only — no mic indicator yet
                val notification = buildNotification("Connecting to $ip…")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                socketManager.connect(ip)
                observeConnectionState()
                observeIncomingSignals()
            }

            ACTION_DISCONNECT -> {
                prefs.edit().remove(KEY_SAVED_IP).apply()
                disconnectAndStop()
            }

            ACTION_ACCEPT_CALL -> acceptCall()
            ACTION_REJECT_CALL -> rejectCall()
            ACTION_END_CALL -> endCall()
        }

        return START_STICKY
    }

    fun acceptCall() {
        if (_ringingCallState.value != CallState.RINGING) return
        ringerManager.stopRinging()
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(INCOMING_CALL_NOTIFICATION_ID)
        
        if (_activeCallState.value == CallState.ACTIVE || _activeCallState.value == CallState.HELD) {
            // Notify PhoneViewModel to log the previous call BEFORE we overwrite its ID.
            _callReplacedEvent.tryEmit(activeCallerId)
            socketManager.sendMessage(com.nonpta.bridge.model.SignalMessage(type = "call_hold", target = activeCallerId))
            _activeCallState.value = CallState.HELD
        }
        
        activeCallerId = ringingCallerId
        ringingCallerId = ""
        socketManager.sendMessage(com.nonpta.bridge.model.SignalMessage(type = "call_accepted", target = activeCallerId))
        
        try {
            audioEngine.start(this, java.net.InetAddress.getByName(socketManager.serverIp))
            upgradeForegroundType()
            audioEngine.enableMicrophone()
            acquireProximityWakeLock()
        } catch (_: Exception) {}
        
        _activeCallState.value = CallState.ACTIVE
        _ringingCallState.value = CallState.IDLE
        com.nonpta.bridge.DeepLinkHandler.pendingUnminimize.tryEmit(Unit)
        bringUiToFront()
    }

    fun rejectCall() {
        if (_ringingCallState.value != CallState.RINGING) return
        ringerManager.stopRinging()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(INCOMING_CALL_NOTIFICATION_ID)
        // Capture before clearing — the event carries the number for DB insertion.
        val rejectedCaller = ringingCallerId
        ringingCallerId = ""
        _ringingCallState.value = CallState.IDLE
        socketManager.sendMessage(com.nonpta.bridge.model.SignalMessage(type = "call_rejected", target = rejectedCaller))
        // Emit so PhoneViewModel logs a "missed" entry regardless of call path
        // (notification action or UI button — both funnel through here).
        _missedCallEvent.tryEmit(rejectedCaller)
    }

    fun endCall() {
        if (_activeCallState.value != CallState.ACTIVE && _activeCallState.value != CallState.HELD) return

        // ── Teardown audio & hardware ──────────────────────────────────────
        audioEngine.stop()
        releaseProximityWakeLock()

        socketManager.sendMessage(com.nonpta.bridge.model.SignalMessage(type = "call_ended", target = activeCallerId))
        activeCallerId = ""

        // ── Cancel the call-specific notifications ─────────────────────────
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ACTIVE_CALL_NOTIFICATION_ID)

        // ── Restore the foreground notification to its idle "Connected" text.
        //    upgradeForegroundType() changed NOTIFICATION_ID to show "In call…";
        //    we must reset it here so it no longer reads as an active call.
        if (socketManager.connectionState.value == com.nonpta.bridge.model.ConnectionState.CONNECTED) {
            updateNotification("Connected to Hub")
        }

        // ── Transition to IDLE (not ENDED) so the next call can be accepted.
        _activeCallState.value = CallState.IDLE

        // If a new incoming call is already ringing, leave it — do NOT cancel it.
    }

    fun toggleHold(isHeld: Boolean) {
        if (_activeCallState.value != CallState.ACTIVE && _activeCallState.value != CallState.HELD) return
        _activeCallState.value = if (isHeld) CallState.HELD else CallState.ACTIVE
        socketManager.sendMessage(com.nonpta.bridge.model.SignalMessage(type = "call_hold", target = activeCallerId))
    }

    fun startOutgoingCall(number: String) {
        if (_activeCallState.value != CallState.IDLE) return
        
        socketManager.sendMessage(com.nonpta.bridge.model.SignalMessage(type = "call_outgoing", target = number))
        
        activeCallerId = number
        _activeCallState.value = CallState.ACTIVE
        
        try {
            audioEngine.start(this, java.net.InetAddress.getByName(socketManager.serverIp))
            upgradeForegroundType()
            audioEngine.enableMicrophone()
            acquireProximityWakeLock()
        } catch (_: Exception) {}
        
        bringUiToFront()
    }

    private fun endCallDueToDisconnect() {
        if (_activeCallState.value != CallState.ACTIVE) return
        
        val callerId = activeCallerId
        // Emit active call duration for logs (placeholder duration is 0, since duration is strictly evaluated by ViewModel, but we emit event)
        _activeCallDisconnectEvent.tryEmit(Pair(callerId, 0)) // 0 passed because PhoneViewModel manages its own duration
        
        audioEngine.stop()
        releaseProximityWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        _activeCallState.value = CallState.IDLE
        activeCallerId = ""
        _isReconnecting.value = false
        
        _callEndedDueToDisconnect.tryEmit(Unit)
        stopSelf()
    }

    private fun bringUiToFront() {
        val intent = Intent(this, com.nonpta.bridge.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("open_active_call", true)
        }
        startActivity(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("BridgeService", "Task removed, but persisting...")
    }

    override fun onDestroy() {
        disconnectAndStop()
        socketManager.destroy()
        audioEngine.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    /* ── connection state → notification updates ───────────────────────── */

    private fun observeConnectionState() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            socketManager.connectionState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        updateNotification("Connected to Hub")
                        if (reconnectJob?.isActive == true) {
                            reconnectJob?.cancel()
                            reconnectJob = null
                            _isReconnecting.value = false
                            try {
                                audioEngine.start(this@BridgeService, java.net.InetAddress.getByName(socketManager.serverIp))
                                audioEngine.enableMicrophone()
                            } catch (_: Exception) {}
                        }
                    }
                    ConnectionState.DISCONNECTED -> {
                        updateNotification("Disconnected")
                        when {
                            _ringingCallState.value == CallState.RINGING -> {
                                val missedNumber = ringingCallerId
                                ringerManager.stopRinging()
                                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                nm.cancel(INCOMING_CALL_NOTIFICATION_ID)
                                _ringingCallState.value = CallState.IDLE
                                ringingCallerId = ""
                                _missedCallEvent.tryEmit(missedNumber)
                            }
                            _activeCallState.value == CallState.ACTIVE -> {
                                reconnectJob?.cancel()
                                reconnectJob = serviceScope.launch {
                                    delay(GRACE_PERIOD_MS)
                                    withContext(Dispatchers.Main) {
                                        endCallDueToDisconnect()
                                    }
                                }
                                _isReconnecting.value = true
                            }
                            else -> {
                                delay(2_000)
                                disconnectAndStop()
                            }
                        }
                    }
                    ConnectionState.CONNECTING -> { /* notification already shows "Connecting…" */ }
                }
            }
        }
    }

    private var signalsObserverJob: Job? = null

    private fun observeIncomingSignals() {
        signalsObserverJob?.cancel()
        signalsObserverJob = serviceScope.launch {
            socketManager.incomingMessages.collect { msg ->
                if (msg.type == "call_incoming") {
                    val callerId = msg.caller ?: "Unknown"
                    if (_ringingCallState.value == CallState.RINGING && ringingCallerId.isNotBlank()) {
                        _missedCallEvent.tryEmit(ringingCallerId)
                    }
                    ringingCallerId = callerId
                    _ringingCallState.value = CallState.RINGING
                    ringerManager.startRinging()
                    showIncomingCallNotification(callerId)
                }
            }
        }
    }

    fun isSocketConnected(): Boolean = 
        socketManager.connectionState.value == ConnectionState.CONNECTED

    /* ── proximity wake lock (screen-off during calls) ─────────────── */

    @Suppress("DEPRECATION")
    private fun initProximityWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        proximityWakeLock = pm.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "nonpta:proximity"
        )
    }

    /** Call when entering ACTIVE call state. */
    fun acquireProximityWakeLock() {
        if (proximityWakeLock?.isHeld != true) {
            proximityWakeLock?.acquire(30 * 60 * 1000L) // 30-min safety ceiling
        }
    }

    /** Call when leaving ACTIVE call state. */
    fun releaseProximityWakeLock() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
    }

    /**
     * Upgrades the foreground service type to include MICROPHONE.
     * Call this right before [AudioEngine.enableMicrophone] so Android 14
     * permits background mic access.
     */
    fun upgradeForegroundType() {
        val isCallActive = activeCallerId.isNotBlank()
        val callerName = if (isCallActive) activeCallerId else "Active Call"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("In call…", isCallActive, callerName),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            updateNotification("In call…", isCallActive, callerName)
        }
    }

    /* ── teardown ──────────────────────────────────────────────────────── */

    private fun disconnectAndStop() {
        stateObserverJob?.cancel()

        // Clean up ringing state if socket drops
        if (_ringingCallState.value == CallState.RINGING) {
            ringerManager.stopRinging()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(INCOMING_CALL_NOTIFICATION_ID)
            _ringingCallState.value = CallState.IDLE
            ringingCallerId = ""
        }

        // Clean up active call state if socket drops
        if (_activeCallState.value == CallState.ACTIVE || _activeCallState.value == CallState.HELD) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ACTIVE_CALL_NOTIFICATION_ID)
            _activeCallState.value = CallState.IDLE
            activeCallerId = ""
        }

        audioEngine.stop()
        socketManager.disconnect()
        releaseProximityWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /* ── notification plumbing ─────────────────────────────────────────── */

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while connected to the P2P Hub"
                setShowBadge(false)
            }
            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                CALL_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming calls from P2P Bridge"
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannels(listOf(channel, callChannel))
        }
    }

    private fun buildNotification(text: String, isCall: Boolean = false, callerName: String = "Active Call"): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isCall) {
            val endIntent = Intent(this, BridgeService::class.java).apply { action = ACTION_END_CALL }
            val endPending = PendingIntent.getService(
                this, ACTION_END_CALL.hashCode(), endIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val person = androidx.core.app.Person.Builder().setName(callerName).build()
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, endPending))
        }

        return builder.build()
    }

    private fun updateNotification(text: String, isCall: Boolean = false, callerName: String = "Active Call") {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, isCall, callerName))
    }

    private fun showIncomingCallNotification(callerId: String) {
        val acceptIntent = Intent(this, BridgeService::class.java).apply { action = ACTION_ACCEPT_CALL }
        val rejectIntent = Intent(this, BridgeService::class.java).apply { action = ACTION_REJECT_CALL }

        val acceptPending = PendingIntent.getService(
            this, ACTION_ACCEPT_CALL.hashCode(), acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectPending = PendingIntent.getService(
            this, ACTION_REJECT_CALL.hashCode(), rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val person = androidx.core.app.Person.Builder().setName(callerId).build()
        val callStyle = NotificationCompat.CallStyle.forIncomingCall(person, rejectPending, acceptPending)

        // Intent to just open the app (don't accept the call)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPending = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // The intent no longer needs to use full screen heads up
        // We only use the standard setPriority(MAX) to drop the OS tray down
        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming Call")
            .setContentText(callerId)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setStyle(callStyle)
            .setContentIntent(contentPending)
            .setFullScreenIntent(contentPending, true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }
}
