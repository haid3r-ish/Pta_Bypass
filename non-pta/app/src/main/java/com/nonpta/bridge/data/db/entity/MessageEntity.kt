package com.nonpta.bridge.data.db.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index("threadNumber")])
@Immutable
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadNumber: String,
    val sender: String,
    val body: String,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = true
)
