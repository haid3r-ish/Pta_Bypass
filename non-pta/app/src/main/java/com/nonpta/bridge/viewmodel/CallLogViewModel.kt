package com.nonpta.bridge.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nonpta.bridge.BridgeApp
import com.nonpta.bridge.data.db.entity.BlockedNumberEntity
import com.nonpta.bridge.data.db.entity.CallLogEntity
import com.nonpta.bridge.model.ContactInfo
import com.nonpta.bridge.model.SignalMessage
import com.nonpta.bridge.util.ContactResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CallLogViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BridgeApp
    private val dao = app.database.callLogDao()
    private val blockedDao = app.database.blockedNumberDao()

    val searchQuery = MutableStateFlow("")
    val showContacts = MutableStateFlow(false)

    /** FIX #7: Room flows run on IO thread. */
    val callLogs: StateFlow<List<CallLogEntity>> = searchQuery
        .debounce(200)
        .flatMapLatest { q -> if (q.isBlank()) dao.getAllFlow() else dao.searchFlow(q) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** FIX #7: Blocked numbers reactive flow for block/unblock UI state. */
    val blockedNumbers = blockedDao.getAllFlow()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _allContacts = MutableStateFlow<List<ContactInfo>>(emptyList())

    /**
     * FIX #3: Contacts filtered by searchQuery locally before emission.
     * The ContactInfo.name contains "Name\nNumber" — we search both parts.
     */
    val contacts: StateFlow<List<ContactInfo>> = combine(
        _allContacts, searchQuery.debounce(200)
    ) { all, q ->
        if (q.isBlank()) all
        else all.filter { c -> c.name.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun loadContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            _allContacts.value = ContactResolver.getAllContacts(app)
        }
    }

    suspend fun suspendResolve(number: String): ContactInfo? = ContactResolver.suspendResolve(app, number)

    /** Check if a number is currently blocked (used for reactive UI). */
    fun isBlocked(number: String): Boolean =
        blockedNumbers.value.any { it.number == number }

    fun deleteLog(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteById(id) }
    }

    /** FIX #4: Block inserts into Room AND signals Hub. */
    fun blockNumber(number: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blockedDao.insert(BlockedNumberEntity(number))
            app.serviceConnection.service.value?.socketManager?.sendMessage(
                SignalMessage(type = "block_number", number = number)
            )
        }
    }

    /** FIX #4: Unblock removes from Room AND signals Hub. */
    fun unblockNumber(number: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blockedDao.delete(number)
            app.serviceConnection.service.value?.socketManager?.sendMessage(
                SignalMessage(type = "unblock_number", number = number)
            )
        }
    }

    /** FIX #4: Fire system contact-insert intent. */
    fun saveContact(context: Context, number: String) {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * FIX #4: Initiate a call by setting the dialed number on PhoneViewModel.
     * Called from the BottomSheet "Call Back" action.
     */
    fun callBack(phoneVM: PhoneViewModel, number: String) {
        phoneVM.updateDialedNumber(number)
        phoneVM.dial()
    }
}
