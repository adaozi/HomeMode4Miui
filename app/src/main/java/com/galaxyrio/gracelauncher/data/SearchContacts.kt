@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.galaxyrio.gracelauncher.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.homemode4miui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

internal data class SearchContact(
    val id: Long,
    val uri: Uri,
    val name: String,
    val photo: Uri?,
    val numbers: List<String>,
) {
    val searchName = SearchName(name)
    fun score(query: SearchQuery): Int? = searchName.score(query)
        ?: if (query.isPhone && numbers.any { it.contains(query.phone) }) 10 else null
}

/** Contacts stay in memory only while the permitted search surface is open. */
internal class SearchContacts(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    fun hasAccess(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun observe(): Flow<List<SearchContact>> = callbackFlow {
        if (!hasAccess()) { close(); return@callbackFlow }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().mapLatest { load() }.catch { emit(emptyList()) }

    private suspend fun load(): List<SearchContact> = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext emptyList()
        val contacts = linkedMapOf<Long, SearchContact>()
        resolver.query(Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.LOOKUP_KEY,
            Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_THUMBNAIL_URI), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getLong(0)
                val uri = cursor.getString(1)?.let { Contacts.getLookupUri(id, it) }
                    ?: ContentUris.withAppendedId(Contacts.CONTENT_URI, id)
                val name = cursor.getString(2)?.takeIf(String::isNotBlank) ?: context.getString(R.string.search_unnamed_contact)
                val photo = cursor.getString(3)?.toUri()?.takeIf { it.scheme == "content" }
                contacts[id] = SearchContact(id, uri, name, photo, emptyList())
            }
        }
        val numbers = mutableMapOf<Long, MutableSet<String>>()
        resolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val number = cursor.getString(1)?.filter(Char::isDigit).orEmpty()
                if (number.isNotEmpty()) numbers.getOrPut(cursor.getLong(0)) { linkedSetOf() }.add(number)
            }
        }
        contacts.values.map { it.copy(numbers = numbers[it.id]?.toList().orEmpty()) }
            .sortedWith(compareBy({ appSortKey(it.name) }, { it.id }))
    }

    suspend fun photo(contact: SearchContact): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!hasAccess() || contact.photo == null) return@withContext null
        runCatching { resolver.openInputStream(contact.photo)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
}
