package com.nonpta.bridge.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nonpta.bridge.BridgeApp
import com.nonpta.bridge.model.ConversationSummary
import com.nonpta.bridge.model.ContactInfo
import com.nonpta.bridge.util.ContactResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ConversationsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BridgeApp
    private val dao = app.database.messageDao()

    val searchQuery = MutableStateFlow("")

    /**
     * FIX #3: Conversations search now also resolves contact names and matches
     * against them, not just threadNumber/body in the SQL query.
     * FIX #7: Room flow runs on Dispatchers.IO.
     */
    private val rawConversations: Flow<List<ConversationSummary>> = searchQuery
        .debounce(200)
        .flatMapLatest { q ->
            if (q.isBlank()) dao.getConversationsFlow()
            else dao.searchConversationsFlow(q)
        }
        .flowOn(Dispatchers.IO)

    val conversations: StateFlow<List<ConversationSummary>> = rawConversations
        .combine(searchQuery.debounce(200)) { convs, q ->
            if (q.isBlank()) convs
            else {
                // Also match against resolved contact names
                convs.filter { conv ->
                    val contact = ContactResolver.resolve(app, conv.threadNumber)
                    conv.threadNumber.contains(q, true) ||
                    conv.latestBody.contains(q, true) ||
                    (contact?.name?.contains(q, true) == true)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    suspend fun suspendResolve(number: String): ContactInfo? = ContactResolver.suspendResolve(app, number)

    fun deleteThread(number: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.database.messageDao().deleteThread(number)
        }
    }
}
