package com.nonpta.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.nonpta.bridge.service.BridgeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the [ServiceConnection] to [BridgeService].
 *
 * Exposes [service] as a [StateFlow] so ViewModels can reactively
 * observe when the service becomes available (bound) or disappears.
 */
class BridgeServiceConnection : ServiceConnection {

    private val _service = MutableStateFlow<BridgeService?>(null)
    val service: StateFlow<BridgeService?> = _service.asStateFlow()

    private var bound = false

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        _service.value = (binder as BridgeService.LocalBinder).getService()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        bound = false
        _service.value = null
    }

    fun isBound(): Boolean = bound && _service.value != null

    /** Binds to the already-started [BridgeService]. */
    fun bind(context: Context) {
        if (bound) return
        val intent = Intent(context, BridgeService::class.java)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        bound = true
    }

    /** Unbinds without stopping the service. */
    fun unbind(context: Context) {
        if (!bound) return
        runCatching { context.unbindService(this) }
        _service.value = null
        bound = false
    }
}
