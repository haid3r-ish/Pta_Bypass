package com.nonpta.bridge

import android.app.Application
import com.nonpta.bridge.data.datastore.DataStoreManager
import com.nonpta.bridge.data.db.BridgeDatabase

/**
 * Application subclass providing process-wide singletons.
 * ViewModels access these via `(application as BridgeApp)`.
 */
class BridgeApp : Application() {
    val serviceConnection by lazy { BridgeServiceConnection() }
    val database by lazy { BridgeDatabase.getInstance(this) }
    val dataStore by lazy { DataStoreManager(this) }

    override fun onCreate() {
        super.onCreate()
        com.nonpta.bridge.util.NotificationHelper.createChannels(this)
    }
}
