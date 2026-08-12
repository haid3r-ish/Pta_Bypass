package com.nonpta.bridge.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nonpta.bridge.BridgeApp
import com.nonpta.bridge.data.db.entity.MessageEntity
import com.nonpta.bridge.model.ContactInfo
import com.nonpta.bridge.model.SignalMessage
import com.nonpta.bridge.util.ContactResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BridgeApp
    private val dao = app.database.messageDao()

    private val _threadNumber = MutableStateFlow("")
    val threadNumber: StateFlow<String> = _threadNumber.asStateFlow()

    /** FIX #7: Room flow runs on IO. */
    val messages: StateFlow<List<MessageEntity>> = _threadNumber
        .flatMapLatest { num -> if (num.isBlank()) flowOf(emptyList()) else dao.getThreadFlow(num) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val inputText = MutableStateFlow("")

    fun setThread(number: String) {
        _threadNumber.value = number
        viewModelScope.launch(Dispatchers.IO) { dao.markThreadRead(number) }
    }

    suspend fun suspendResolve(number: String): ContactInfo? = ContactResolver.suspendResolve(app, number)

    fun sendMessage() {
        val target = _threadNumber.value.trim()
        val body = inputText.value.trim()
        if (target.isBlank() || body.isBlank()) return

        val entity = MessageEntity(
            threadNumber = target, sender = "Me", body = body,
            isOutgoing = true, timestamp = System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) { dao.insert(entity) }

        app.serviceConnection.service.value?.socketManager?.sendMessage(
            SignalMessage(type = "sms_tx", target = target, body = body)
        )
        inputText.value = ""
    }
}
