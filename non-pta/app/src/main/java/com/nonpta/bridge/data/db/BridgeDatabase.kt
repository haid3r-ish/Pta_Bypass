package com.nonpta.bridge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nonpta.bridge.data.db.dao.BlockedNumberDao
import com.nonpta.bridge.data.db.dao.CallLogDao
import com.nonpta.bridge.data.db.dao.MessageDao
import com.nonpta.bridge.data.db.entity.BlockedNumberEntity
import com.nonpta.bridge.data.db.entity.CallLogEntity
import com.nonpta.bridge.data.db.entity.MessageEntity

@Database(
    entities = [CallLogEntity::class, MessageEntity::class, BlockedNumberEntity::class],
    version = 1,
    exportSchema = true
)
abstract class BridgeDatabase : RoomDatabase() {

    abstract fun callLogDao(): CallLogDao
    abstract fun messageDao(): MessageDao
    abstract fun blockedNumberDao(): BlockedNumberDao

    companion object {
        @Volatile private var INSTANCE: BridgeDatabase? = null

        fun getInstance(context: Context): BridgeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BridgeDatabase::class.java,
                    "bridge.db"
                ).build().also { INSTANCE = it }
            }
    }
}
