package com.nonpta.bridge.viewmodel

import android.app.Application
import android.media.AudioManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nonpta.bridge.BridgeApp
import com.nonpta.bridge.data.db.entity.CallLogEntity
import com.nonpta.bridge.data.db.entity.MessageEntity
import com.nonpta.bridge.model.CallState
import com.nonpta.bridge.model.SignalMessage
import com.nonpta.bridge.service.BridgeService
import com.nonpta.bridge.util.ContactResolver
import com.nonpta.bridge.model.ContactInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetAddress
import com.nonpta.bridge.audio.RingerManager
import com.nonpta.bridge.util.NotificationHelper
import com.nonpta.bridge.receiver.CallActionReceiver

class PhoneViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BridgeApp
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeCallState = app.serviceConnection.service
        .flatMapLatest { it?.activeCallState ?: flowOf(CallState.IDLE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CallState.IDLE)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ringingCallState = app.serviceConnection.service
        .flatMapLatest { it?.ringingCallState ?: flowOf(CallState.IDLE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CallState.IDLE)
    val dialedNumber = MutableStateFlow("")
    val incomingCaller = MutableStateFlow("")
    val isMuted = MutableStateFlow(false)
    val isSpeaker = MutableStateFlow(false)
    val isHeld = MutableStateFlow(false)
    val isRecording = MutableStateFlow(false)
    val callDurationSeconds = MutableStateFlow(0)
    val isCallMinimized = MutableStateFlow(false)

    val ringingCaller = MutableStateFlow("")

    private var durationJob: Job? = null
    private var callStartTime = 0L

    private var lastActiveCallerId = ""

    init {
        viewModelScope.launch {
            app.serviceConnection.service.collectLatest { svc ->
                svc?.socketManager?.incomingMessages?.collect { handleSignal(it) }
            }
        }
        
        viewModelScope.launch {
            com.nonpta.bridge.DeepLinkHandler.pendingUnminimize.collect {
                isCallMinimized.value = false
            }
        }

        viewModelScope.launch {
            app.serviceConnection.service.collectLatest { svc ->
                if (svc == null) return@collectLatest
                launch {
                    svc.missedCallEvent.collect { callerId: String ->
                        if (callerId.isNotBlank()) insertCallLogForNumber(callerId, "missed", 0)
                    }
                }
                launch {
                    svc.activeCallDisconnectEvent.collect { pair: Pair<String, Int> ->
                        val (callerId, _) = pair
                        if (callerId.isNotBlank()) insertCallLogForNumber(callerId, "received", callDurationSeconds.value)
                    }
                }
                launch {
                    svc.callEndedDueToDisconnect.collect {
                        isCallMinimized.value = false
                        durationJob?.cancel()
                        durationJob = null
                        incomingCaller.value = ""
                        dialedNumber.value = ""
                        callDurationSeconds.value = 0
                    }
                }
                launch {
                    svc.callReplacedEvent.collect { replacedCallerId ->
                        // Previous call was displaced by call-waiting acceptance.
                        // Log it now, before activeCallerId is overwritten in the service.
                        if (replacedCallerId.isNotBlank()) {
                            val type = if (dialedNumber.value.isNotBlank()) "outgoing" else "received"
                            insertCallLogForNumber(replacedCallerId, type, callDurationSeconds.value)
                        }
                        // Reset timer/state — the active-call state observer will
                        // restart the timer and update incomingCaller for the new call.
                        durationJob?.cancel()
                        durationJob = null
                        callDurationSeconds.value = 0
                        dialedNumber.value = ""
                        incomingCaller.value = ""
                        lastActiveCallerId = ""
                    }
                }
            }
        }

        viewModelScope.launch {
            activeCallState.collect { state ->
                if (state == CallState.ACTIVE) {
                    val currentCaller = service?.activeCallerId ?: ""
                    if (currentCaller.isNotBlank() && currentCaller != lastActiveCallerId) {
                        lastActiveCallerId = currentCaller
                        callDurationSeconds.value = 0
                        incomingCaller.value = currentCaller
                    }
                    startTimer()
                } else if (state == CallState.IDLE || state == CallState.ENDED) {
                    lastActiveCallerId = ""
                    durationJob?.cancel()
                    durationJob = null
                    if (ringingCallState.value == CallState.IDLE) {
                        incomingCaller.value = ""
                    }
                }
            }
        }

        viewModelScope.launch {
            ringingCallState.collect { state ->
                if (state == CallState.IDLE || state == CallState.ENDED) {
                    ringingCaller.value = ""
                    if (activeCallState.value == CallState.IDLE) {
                        incomingCaller.value = ""
                    }
                }
            }
        }
    }

    private fun handleSignal(msg: SignalMessage) {
        when (msg.type) {
            "call_incoming" -> {
                val callerNum = msg.caller ?: "Unknown"
                ringingCaller.value = callerNum
                // Only promote to primary caller display if we aren't already in an active call
                if (activeCallState.value == CallState.IDLE) {
                    incomingCaller.value = callerNum
                }
            }
            "call_accepted" -> { 
                // Promoted from ringing to active
                incomingCaller.value = ringingCaller.value.ifBlank { incomingCaller.value }
                ringingCaller.value = "" 
                callDurationSeconds.value = 0
                startTimer()
            }
            "call_ended" -> {
                val dur = callDurationSeconds.value
                val type = if (incomingCaller.value.isNotBlank()) "incoming" else "outgoing"
                insertCallLog(type, dur)
                durationJob?.cancel(); callDurationSeconds.value = 0
                incomingCaller.value = ""
                dialedNumber.value = ""
                isMuted.value = false; isSpeaker.value = false; isHeld.value = false; isRecording.value = false
                isCallMinimized.value = false
            }
            "call_rejected" -> {
                insertCallLogForNumber(ringingCaller.value, "missed", 0)
                ringingCaller.value = ""
            }

            // FIX #1: Handle incoming SMS by inserting into Room
            "sms_rx" -> {
                val sender = msg.sender ?: return
                val body = msg.body ?: return
                viewModelScope.launch(Dispatchers.IO) {
                    app.database.messageDao().insert(
                        MessageEntity(
                            threadNumber = sender,
                            sender = sender,
                            body = body,
                            isOutgoing = false,
                            isRead = false,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    NotificationHelper.showMessageNotification(app, sender, body)
                }
            }
        }
    }

    fun appendDigit(d: String) { dialedNumber.value += d }
    fun backspace() { dialedNumber.value = dialedNumber.value.dropLast(1) }
    fun updateDialedNumber(n: String) { dialedNumber.value = n }

    fun dial() {
        val number = dialedNumber.value
        if (number.isBlank()) return
        
        val service = app.serviceConnection.service.value
        if (service?.isSocketConnected() != true) {
            android.widget.Toast.makeText(app, "Not connected to Hub", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        if (activeCallState.value == CallState.ACTIVE || ringingCallState.value == CallState.RINGING) {
            android.widget.Toast.makeText(app, "Already in a call", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        incomingCaller.value = ""
        callStartTime = System.currentTimeMillis()
        viewModelScope.launch {
            service.startOutgoingCall(number)
        }
    }

    fun acceptCall() {
        // Now handled via Notification + BridgeService.
    }

    fun rejectCall() {
        val service = app.serviceConnection.service.value
        if (service != null && service.ringingCallState.value == CallState.RINGING) {
            // BridgeService.rejectCall() emits missedCallEvent, which the collector
            // above uses to insert the log entry — no direct DB call needed here.
            service.rejectCall()
            ringingCaller.value = ""
        }
    }

    fun endCall() {
        val service = app.serviceConnection.service.value
        if (service != null && (service.activeCallState.value == CallState.ACTIVE || service.activeCallState.value == CallState.HELD)) {
            val dur = callDurationSeconds.value
            val type = if (incomingCaller.value.isNotBlank()) "incoming" else "outgoing"
            insertCallLog(type, dur)
            service.endCall()
            // Reset all per-call UI state so they don't bleed into the next call.
            durationJob?.cancel(); callDurationSeconds.value = 0
            incomingCaller.value = ""
            dialedNumber.value = ""
            isMuted.value = false; isSpeaker.value = false
            isHeld.value = false; isRecording.value = false
            isCallMinimized.value = false
        }
    }

    fun toggleMute() { val m = !isMuted.value; isMuted.value = m; service?.audioEngine?.setMuted(m) }

    fun toggleSpeaker() {
        val s = !isSpeaker.value
        isSpeaker.value = s

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (s) {
                val devices = audioManager.availableCommunicationDevices
                val speaker = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                speaker?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = s
        }
    }

    fun toggleHold() {
        val h = !isHeld.value; isHeld.value = h
        app.serviceConnection.service.value?.toggleHold(h)
    }

    fun toggleRecording() { isRecording.value = !isRecording.value }

    fun toggleMinimize() { isCallMinimized.value = !isCallMinimized.value }

    fun sendDtmf(digit: String) {
        service?.socketManager?.sendMessage(SignalMessage(type = "call_dtmf", digit = digit))
    }

    suspend fun suspendResolve(number: String): ContactInfo? = ContactResolver.suspendResolve(app, number)


    private fun startTimer() {
        durationJob?.cancel()
        callStartTime = System.currentTimeMillis() - (callDurationSeconds.value * 1000L)
        durationJob = viewModelScope.launch {
            while (isActive) {
                delay(500) // update more frequently to not miss seconds accurately
                val elapsed = ((System.currentTimeMillis() - callStartTime) / 1000L).toInt()
                if (elapsed != callDurationSeconds.value) {
                    callDurationSeconds.value = elapsed
                }
            }
        }
    }

    private fun insertCallLog(type: String, duration: Int) {
        val num = when (type) {
            "outgoing" -> dialedNumber.value.ifBlank { "Unknown" }
            else -> incomingCaller.value.ifBlank { "Unknown" }
        }
        if (num.isBlank() || num == "Unknown") return
        insertCallLogForNumber(num, type, duration)
    }

    private fun insertCallLogForNumber(number: String, type: String, duration: Int) {
        if (number.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            app.database.callLogDao().insert(CallLogEntity(number = number, type = type, duration = duration))
        }
    }

    private fun sendSignal(msg: SignalMessage) { service?.socketManager?.sendMessage(msg) }
    private val service: BridgeService? get() = app.serviceConnection.service.value
}
