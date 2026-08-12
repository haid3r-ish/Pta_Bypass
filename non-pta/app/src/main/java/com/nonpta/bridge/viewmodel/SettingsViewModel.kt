package com.nonpta.bridge.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nonpta.bridge.BridgeApp
import com.nonpta.bridge.model.ConnectionState
import com.nonpta.bridge.model.SignalMessage
import com.nonpta.bridge.network.NetworkConfigHelper
import com.nonpta.bridge.service.BridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BridgeApp
    private val ds = app.dataStore

    val gatewayIp = MutableStateFlow(NetworkConfigHelper.getGatewayIp(application) ?: "")
    val savedIps = ds.savedIps
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val lastConnectedIp = ds.lastConnectedIp
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val callRecordingEnabled = ds.callRecordingEnabled
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val newIpInput = MutableStateFlow("")

    /** Dropdown selection state for the saved IPs picker. */
    val selectedSavedIp = MutableStateFlow("")

    val connectionState: StateFlow<ConnectionState> =
        app.serviceConnection.service
            .flatMapLatest { it?.socketManager?.connectionState ?: flowOf(ConnectionState.DISCONNECTED) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

    val blockedNumbers = app.database.blockedNumberDao().getAllFlow()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun connect(ip: String) {
        val intent = Intent(app, BridgeService::class.java).apply {
            action = BridgeService.ACTION_CONNECT
            putExtra(BridgeService.EXTRA_SERVER_IP, ip)
        }
        ContextCompat.startForegroundService(app, intent)
        app.serviceConnection.bind(app)
        viewModelScope.launch(Dispatchers.IO) { ds.setLastConnected(ip) }
    }

    /**
     * FIX #1: SocketManager.disconnect() is now fully non-blocking (immediate state flip
     * + detached GlobalScope IO close). So this can safely run on Main without freeze.
     */
    fun disconnect() {
        app.serviceConnection.unbind(app)
        val intent = Intent(app, BridgeService::class.java).apply {
            action = BridgeService.ACTION_DISCONNECT
        }
        app.startService(intent)
    }

    fun reconnect() {
        viewModelScope.launch {
            val ip = lastConnectedIp.value ?: gatewayIp.value
            if (ip.isNotBlank()) connect(ip)
        }
    }

    fun addIp(ip: String) {
        if (ip.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { ds.addIp(ip) }
        newIpInput.value = ""
    }

    fun removeIp(ip: String) {
        viewModelScope.launch(Dispatchers.IO) { ds.removeIp(ip) }
    }

    fun setCallRecording(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { ds.setCallRecordingEnabled(enabled) }
    }

    fun unblockNumber(number: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.database.blockedNumberDao().delete(number)
            // Also signal the Hub to unblock
            app.serviceConnection.service.value?.socketManager?.sendMessage(
                SignalMessage(type = "unblock_number", number = number)
            )
        }
    }

    /** Called by MainActivity on launch for auto-connect. */
    fun autoConnect() {
        viewModelScope.launch {
            val ip = ds.lastConnectedIp.first()
            if (ip.isNullOrBlank()) return@launch

            try {
                val service = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    app.serviceConnection.service.filterNotNull().first()
                }

                if (service == null || !service.isSocketConnected()) {
                    connect(ip)
                }
            } catch (e: Exception) {
                // Ignore, proceed to manual connection if needed
            }
        }
    }
}
