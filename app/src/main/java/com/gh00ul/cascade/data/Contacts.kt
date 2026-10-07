package com.gh00ul.cascade.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * A contact search found: its [name], the [id] and [lookupKey] that open it, its small [photo] (a content URI) if it
 * has one, and the [phone] number to call or text, or null when it has none.
 */
data class Contact(val id: Long, val lookupKey: String?, val name: String, val photo: String?, val phone: String?)

fun hasContactsAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** How many contacts search shows. */
const val MAX_CONTACT_RESULTS = 4

/** How many of the provider's matches are ranked; it matches numbers, emails and nicknames too, not only names. */
private const val CONTACT_CANDIDATES = 20

/** The provider's own contact search for [query]: names, numbers, emails and nicknames, at most [CONTACT_CANDIDATES]. */
internal fun contactsFilterUri(query: String): Uri = Contacts.CONTENT_FILTER_URI.buildUpon()
    .appendPath(query)
    .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, CONTACT_CANDIDATES.toString())
    .build()

private val ContactColumns = arrayOf(
    Contacts._ID,
    Contacts.LOOKUP_KEY,
    Contacts.DISPLAY_NAME_PRIMARY,
    Contacts.PHOTO_THUMBNAIL_URI,
    Contacts.HAS_PHONE_NUMBER,
    Contacts.STARRED,
)
private val PhoneColumns = arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.IS_PRIMARY)

/** One row of the filter query, before its number is known. */
internal class ContactMatch(
    val id: Long,
    val lookupKey: String?,
    val name: String,
    val photo: String?,
    val hasPhone: Boolean,
    val starred: Boolean,
)

/** One row of the phone query. */
internal class PhoneRow(val contactId: Long, val number: String, val superPrimary: Boolean, val primary: Boolean)

/**
 * Up to [limit] contacts matching [query], each with the number to call or text. Empty without READ_CONTACTS, and when
 * the provider refuses (access revoked meanwhile) or [signal] cancels the query. Blocking: call off the main thread.
 */
fun queryContacts(context: Context, query: String, limit: Int = MAX_CONTACT_RESULTS, signal: CancellationSignal? = null): List<Contact> {
    val q = query.trim()
    if (q.isEmpty() || !hasContactsAccess(context)) return emptyList()
    val resolver = context.contentResolver
    return runCatching {
        val matches = resolver.query(contactsFilterUri(q), ContactColumns, null, null, null, signal)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(2)?.trim()
                    if (name.isNullOrEmpty()) continue
                    add(ContactMatch(c.getLong(0), c.getString(1), name, c.getString(3), c.getInt(4) != 0, c.getInt(5) != 0))
                }
            }
        }.orEmpty()
        val top = rankContacts(matches, q, limit)
        // One query for every shown contact's numbers, and none when no shown contact has one.
        val ids = top.filter { it.hasPhone }.map { it.id.toString() }
        val phones = if (ids.isEmpty()) emptyMap() else {
            resolver.query(
                Phone.CONTENT_URI,
                PhoneColumns,
                "${Phone.CONTACT_ID} IN (${ids.joinToString(",") { "?" }})",
                ids.toTypedArray(),
                null,
                signal,
            )?.use { c ->
                choosePhones(buildList { while (c.moveToNext()) add(PhoneRow(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2) != 0, c.getInt(3) != 0)) })
            }.orEmpty()
        }
        top.map { Contact(it.id, it.lookupKey, it.name, it.photo, phones[it.id]) }
    }.getOrDefault(emptyList())
}

/**
 * [queryContacts] on the IO dispatcher. Cancelling the caller (the next keystroke) cancels the provider's query too,
 * rather than leaving it to run to the end for nothing.
 */
suspend fun findContacts(context: Context, query: String, limit: Int = MAX_CONTACT_RESULTS): List<Contact> = coroutineScope {
    val signal = CancellationSignal()
    val found = async(Dispatchers.IO) { queryContacts(context, query, limit, signal) }
    try {
        found.await()
    } catch (e: CancellationException) {
        signal.cancel()
        throw e
    }
}

/**
 * The best [limit] of [matches], kept in the provider's order otherwise: names matching [query] the way app labels do
 * (prefix, word start, initials, ...), then the ones the provider matched by something else (a number, an email, a
 * nickname); starred contacts first within each. A contact listed twice shows once.
 */
internal fun rankContacts(matches: List<ContactMatch>, query: String, limit: Int): List<ContactMatch> = matches
    .distinctBy { it.id }
    .sortedWith(compareByDescending<ContactMatch> { matchScore(it.name, query) }.thenByDescending { it.starred })
    .take(limit)

/** Each contact's number to call: its default (set as such, then primary), else the first one listed. */
internal fun choosePhones(rows: List<PhoneRow>): Map<Long, String> {
    val best = HashMap<Long, PhoneRow>()
    for (row in rows) {
        if (row.number.isBlank()) continue
        val current = best[row.contactId]
        if (current == null || row.rank > current.rank) best[row.contactId] = row
    }
    return best.mapValues { it.value.number.trim() }
}

private val PhoneRow.rank get() = if (superPrimary) 2 else if (primary) 1 else 0

/** The letter a contact without a photo shows: the first letter of its name, or null when it has none ("+1 555"). */
fun contactInitial(name: String): String? {
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        if (Character.isLetter(cp)) return String(Character.toChars(cp)).uppercase()
        i += Character.charCount(cp)
    }
    return null
}

/** Opens the contact in the contacts app. */
fun Contact.viewIntent(): Intent {
    val uri = lookupKey?.let { Contacts.getLookupUri(id, it) } ?: ContentUris.withAppendedId(Contacts.CONTENT_URI, id)
    return Intent(Intent.ACTION_VIEW, uri)
}

/** The dialer with [number] filled in: dialing rather than calling, so it needs no CALL_PHONE. */
fun dialIntent(number: String) = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))

/** A new text message to [number]. */
fun messageIntent(number: String) = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))

/**
 * The contact photo at [uri] (a thumbnail), scaled down to [sizePx] on its shorter side, or null when it can't be read.
 * Blocking: call off the main thread.
 */
fun loadContactPhoto(context: Context, uri: String, sizePx: Int): ImageBitmap? = runCatching {
    val decoded = context.contentResolver.openInputStream(uri.toUri())?.use { BitmapFactory.decodeStream(it) } ?: return null
    val short = minOf(decoded.width, decoded.height)
    val bitmap = if (short <= sizePx || sizePx <= 0) decoded else {
        val scale = sizePx.toFloat() / short
        val w = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val h = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        decoded.scale(w, h).also { if (it !== decoded) decoded.recycle() }
    }
    bitmap.toHardware().asImageBitmap()
}.getOrNull()
