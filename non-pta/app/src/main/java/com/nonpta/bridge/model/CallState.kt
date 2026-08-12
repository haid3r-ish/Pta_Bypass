package com.nonpta.bridge.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/** Call lifecycle FSM — driven by TCP signaling events. */
enum class CallState {
    IDLE, DIALING, RINGING, INCOMING, ACTIVE, HELD, ENDED, CALL_WAITING
}

/** TCP connection lifecycle. */
enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

/** Call direction for log entries. */
enum class CallType(val label: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing"),
    MISSED("missed")
}

/** Room DAO projection for the conversations list. */
@Immutable
data class ConversationSummary(
    val threadNumber: String,
    val latestBody: String,
    val latestTimestamp: Long,
    val unreadCount: Int
)

/** Resolved contact info from the device phonebook. */
@Stable
data class ContactInfo(
    val name: String,
    val photoUri: Uri? = null
)
