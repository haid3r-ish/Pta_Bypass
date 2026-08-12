package com.nonpta.bridge.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.collection.LruCache
import com.nonpta.bridge.model.ContactInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves phone numbers to contact names and photos via ContactsContract.
 * Results are cached in an LRU (50 entries). Returns null if READ_CONTACTS is denied.
 */
object ContactResolver {

    private val cache = LruCache<String, ContactInfo>(50)

    fun resolve(context: Context, number: String): ContactInfo? {
        val normalized = NumberValidator.normalize(number)
        cache[normalized]?.let { return it }

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(normalized)
            )
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0) ?: return null
                    val photoStr = cursor.getString(1)
                    val info = ContactInfo(name, photoStr?.let { Uri.parse(it) })
                    cache.put(normalized, info)
                    info
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Suspend wrapper to ensure resolution runs off the UI thread. */
    suspend fun suspendResolve(context: Context, number: String): ContactInfo? =
        withContext(Dispatchers.IO) { resolve(context, number) }

    /** Fetch all device contacts as a sorted list. Safely runs on IO. */
    suspend fun getAllContacts(context: Context): List<ContactInfo> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<ContactInfo>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val num = cursor.getString(numIdx) ?: continue
                    val photo = cursor.getString(photoIdx)?.let { Uri.parse(it) }
                    contacts.add(ContactInfo(name = "$name\n$num", photoUri = photo))
                }
            }
        } catch (_: Exception) { }
        contacts.distinctBy { it.name }
    }

    fun clearCache() { cache.evictAll() }
}
