package com.nonpta.bridge.data.db.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_logs")
@Immutable
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val type: String,        // "incoming" | "outgoing" | "missed"
    val duration: Int = 0,   // seconds
    val timestamp: Long = System.currentTimeMillis()
)
