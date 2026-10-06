package com.gh00ul.cascade.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import org.robolectric.Robolectric
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The contacts provider for tests: records each query, and answers the filter query with [contacts] and the phone
 * query with [phones], in order, ignoring the selection. Queries run on Dispatchers.IO, so everything here is safe to
 * read from the test thread.
 */
class FakeContactsProvider : ContentProvider() {
    class Query(val uri: Uri, val selection: String?, val args: List<String>, val signal: CancellationSignal?)

    val queries = CopyOnWriteArrayList<Query>()
    @Volatile var contacts: List<Map<String, Any?>> = emptyList()
    @Volatile var phones: List<Map<String, Any?>> = emptyList()
    /** Every query throws, as when access is revoked while search is open. */
    @Volatile var refuse = false
    /** Every query waits for its CancellationSignal (up to 5 s), then fails as cancelled. */
    @Volatile var holdUntilCancelled = false
    /** Counted down as each held query starts waiting. */
    val held = CountDownLatch(1)
    /** Counted down when a held query is cancelled. */
    val cancelled = CountDownLatch(1)

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor =
        query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        queries += Query(uri, selection, selectionArgs.orEmpty().toList(), cancellationSignal)
        if (refuse) throw SecurityException("Permission Denial: reading contacts")
        if (holdUntilCancelled) {
            cancellationSignal?.setOnCancelListener { cancelled.countDown() }
            held.countDown()
            cancelled.await(5, TimeUnit.SECONDS)
            cancellationSignal?.throwIfCanceled()
        }
        val rows = if (uri.pathSegments.firstOrNull() == "data") phones else contacts
        val columns = requireNotNull(projection)
        return MatrixCursor(columns).apply { for (row in rows) addRow(columns.map { row[it] }) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        /** A fresh provider for this test's contacts authority. */
        fun install(): FakeContactsProvider = Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)

        /** A row of the filter query. */
        fun contact(id: Long, name: String?, hasPhone: Boolean = false, starred: Boolean = false, photo: String? = null) = mapOf(
            Contacts._ID to id,
            Contacts.LOOKUP_KEY to "lookup$id",
            Contacts.DISPLAY_NAME_PRIMARY to name,
            Contacts.PHOTO_THUMBNAIL_URI to photo,
            Contacts.HAS_PHONE_NUMBER to if (hasPhone) 1 else 0,
            Contacts.STARRED to if (starred) 1 else 0,
        )

        /** A row of the phone query. */
        fun phone(contactId: Long, number: String, superPrimary: Boolean = false, primary: Boolean = false) = mapOf(
            Phone.CONTACT_ID to contactId,
            Phone.NUMBER to number,
            Phone.IS_SUPER_PRIMARY to if (superPrimary) 1 else 0,
            Phone.IS_PRIMARY to if (primary) 1 else 0,
        )
    }
}
