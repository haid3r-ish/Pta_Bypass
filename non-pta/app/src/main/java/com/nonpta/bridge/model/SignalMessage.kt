package com.nonpta.bridge.model

import androidx.compose.runtime.Immutable

/**
 * All JSON messages exchanged over the TCP signaling socket.
 * Serialized/deserialized via Gson. Unused fields are null for a given type.
 */
@Immutable
data class SignalMessage(
    val type: String,
    val sender: String? = null,
    val target: String? = null,
    val body: String? = null,
    val caller: String? = null,
    val number: String? = null,   // used by block_number, call_hold, call_dtmf
    val digit: String? = null     // DTMF digit
)
