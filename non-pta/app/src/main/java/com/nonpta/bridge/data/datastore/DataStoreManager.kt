package com.nonpta.bridge.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bridge_settings")

class DataStoreManager(private val context: Context) {

    companion object {
        private val KEY_SAVED_IPS = stringSetPreferencesKey("saved_ips")
        private val KEY_LAST_CONNECTED = stringPreferencesKey("last_connected_ip")
        private val KEY_CALL_RECORDING = booleanPreferencesKey("call_recording_enabled")
    }

    val savedIps: Flow<List<String>> = context.dataStore.data.map { prefs ->
        (prefs[KEY_SAVED_IPS] ?: emptySet()).toList().sorted()
    }

    val lastConnectedIp: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_CONNECTED]
    }

    val callRecordingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CALL_RECORDING] ?: false
    }

    suspend fun addIp(ip: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SAVED_IPS] ?: emptySet()
            prefs[KEY_SAVED_IPS] = current + ip
        }
    }

    suspend fun removeIp(ip: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SAVED_IPS] ?: emptySet()
            prefs[KEY_SAVED_IPS] = current - ip
        }
    }

    suspend fun setLastConnected(ip: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_CONNECTED] = ip
            val current = prefs[KEY_SAVED_IPS] ?: emptySet()
            prefs[KEY_SAVED_IPS] = current + ip
        }
    }

    suspend fun setCallRecordingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CALL_RECORDING] = enabled
        }
    }
}
