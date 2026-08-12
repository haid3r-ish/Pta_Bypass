package com.nonpta.bridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nonpta.bridge.audio.RingerManager
import com.nonpta.bridge.util.NotificationHelper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton flow to communicate notification action button clicks 
 * to the active PhoneViewModel without direct references.
 */
object CallBroadcastChannel {
    private val _actions = MutableSharedFlow<String>(
        extraBufferCapacity = 1, 
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val actions = _actions.asSharedFlow()

    fun sendAction(action: String) {
        _actions.tryEmit(action)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ANSWER = "com.nonpta.bridge.ACTION_ANSWER"
        const val ACTION_DECLINE = "com.nonpta.bridge.ACTION_DECLINE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        // Unconditionally stop ringing immediately
        RingerManager(context).stopRinging()

        // Immediately dismiss the lingering incoming call notification upon action
        NotificationHelper.cancelCallNotification(context)

        // Relay to PhoneViewModel
        CallBroadcastChannel.sendAction(action)
    }
}
