package com.nonpta.bridge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_numbers")
data class BlockedNumberEntity(
    @PrimaryKey val number: String,
    val timestamp: Long = System.currentTimeMillis()
)
