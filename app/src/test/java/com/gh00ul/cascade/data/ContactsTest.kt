package com.gh00ul.cascade.data

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.gh00ul.cascade.testing.FakeContactsProvider
import com.gh00ul.cascade.testing.FakeContactsProvider.Companion.contact
import com.gh00ul.cascade.testing.FakeContactsProvider.Companion.phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/** Contacts in search: the two provider queries, what's made of their rows, and the intents rows start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ContactsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun allowed(): FakeContactsProvider {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        return FakeContactsProvider.install()
    }

    @Test fun withoutPermissionNothingIsQueried() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        val provider = FakeContactsProvider.install()
        provider.contacts = listOf(contact(1, "Jane Doe"))
        assertEquals(emptyList<Contact>(), queryContacts(app, "ja"))
        assertTrue(provider.queries.isEmpty())
    }

    @Test fun blankQueryQueriesNothing() {
        val provider = allowed()
        assertEquals(emptyList<Contact>(), queryContacts(app, "  "))
        assertTrue(provider.queries.isEmpty())
    }

    @Test fun filtersByTheTrimmedQueryThenAsksForTheShownContactsNumbers() {
        val provider = allowed()
        provider.contacts = listOf(contact(1, "Jane Doe", hasPhone = true), contact(2, "Jake", hasPhone = false), contact(3, "Janet", hasPhone = true))
        queryContacts(app, "  ja ")

        val (filter, phones) = provider.queries
        assertEquals(ContactsContract.AUTHORITY, filter.uri.authority)
        assertEquals(listOf("contacts", "filter", "ja"), filter.uri.pathSegments)
        assertEquals("20", filter.uri.getQueryParameter(ContactsContract.LIMIT_PARAM_KEY))
        assertEquals(Phone.CONTENT_URI, phones.uri)
        // Only the contacts that have a number.
        assertEquals("contact_id IN (?,?)", phones.selection)
        assertEquals(listOf("1", "3"), phones.args)
        assertEquals(2, provider.queries.size)
    }

    @Test fun queryTextIsOnePathSegment() {
        val provider = allowed()
        queryContacts(app, "a/b c")
        assertEquals(listOf("contacts", "filter", "a/b c"), provider.queries.single().uri.pathSegments)
    }

    @Test fun noPhoneQueryWhenNoShownContactHasANumber() {
        val provider = allowed()
        provider.contacts = listOf(contact(1, "Jane Doe"), contact(2, "Jake"))
        val found = queryContacts(app, "ja")
        assertEquals(listOf("Jane Doe", "Jake"), found.map { it.name })
        assertTrue(found.all { it.phone == null })
        assertEquals(1, provider.queries.size)
    }

    @Test fun rowsBecomeContactsWithTheirDefaultNumber() {
        val provider = allowed()
        provider.contacts = listOf(
            contact(1, "Jane Doe", hasPhone = true, photo = "content://com.android.contacts/contacts/1/photo"),
            contact(2, "Jake Ng", hasPhone = true),
            contact(3, "Jay", hasPhone = true),
            contact(4, "Jo", hasPhone = false),
        )
        provider.phones = listOf(
            // Jane: the one set as default wins over a primary one and the first.
            phone(1, "555-0101"), phone(1, "555-0102", primary = true), phone(1, " 555-0103 ", superPrimary = true),
            // Jake: primary over first.
            phone(2, "555-0201"), phone(2, "555-0202", primary = true),
            // Jay: the first, skipping an empty one.
            phone(3, ""), phone(3, "555-0301"), phone(3, "555-0302"),
        )
        assertEquals(
            listOf(
                Contact(1, "lookup1", "Jane Doe", "content://com.android.contacts/contacts/1/photo", "555-0103"),
                Contact(2, "lookup2", "Jake Ng", null, "555-0202"),
                Contact(3, "lookup3", "Jay", null, "555-0301"),
                Contact(4, "lookup4", "Jo", null, null),
            ),
            queryContacts(app, "j"),
        )
    }

    @Test fun nameMatchesFirstThenStarredAtMostFour() {
        val provider = allowed()
        provider.contacts = listOf(
            // Matched by the provider on a number or an email, not the name.
            contact(1, "Alex Smith", starred = true),
            contact(2, "Bob Ma"), // word start
            contact(3, "Maria"), // prefix
            contact(4, "Mark", starred = true), // prefix, starred
            contact(5, "Tom Mallory"), // word start
            contact(6, "Amanda"), // substring
        )
        assertEquals(listOf("Mark", "Maria", "Bob Ma", "Tom Mallory"), queryContacts(app, "ma").map { it.name })
        assertEquals(listOf("Mark", "Maria", "Bob Ma", "Tom Mallory", "Amanda", "Alex Smith"), queryContacts(app, "ma", limit = 10).map { it.name })
    }

    @Test fun unnamedAndRepeatedContactsAreLeftOut() {
        val provider = allowed()
        provider.contacts = listOf(contact(1, null), contact(2, "  "), contact(3, "Ann"), contact(3, "Ann"), contact(4, " Anna "))
        assertEquals(listOf(3L, 4L), queryContacts(app, "an").map { it.id })
        assertEquals("Anna", queryContacts(app, "an").last().name)
    }

    @Test fun aRefusingProviderMeansNoContacts() {
        val provider = allowed()
        provider.contacts = listOf(contact(1, "Jane Doe"))
        provider.refuse = true
        assertEquals(emptyList<Contact>(), queryContacts(app, "ja"))
    }

    @Test fun cancellingTheSearchCancelsTheProvidersQuery() = runBlocking {
        val provider = allowed()
        provider.holdUntilCancelled = true
        val search = async(Dispatchers.Default) { findContacts(app, "ja") }
        assertTrue("the query never started", provider.held.await(5, TimeUnit.SECONDS))
        search.cancel()
        assertTrue("the query wasn't cancelled", provider.cancelled.await(5, TimeUnit.SECONDS))
        search.join()
        assertTrue(provider.queries.single().signal!!.isCanceled)
    }

    @Test fun intentsOpenDialAndText() {
        val jane = Contact(7, "abc", "Jane", null, "+1 555-0100")
        val view = jane.viewIntent()
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(Uri.parse("content://com.android.contacts/contacts/lookup/abc/7"), view.data)
        // Without a lookup key, the contact's plain URI.
        assertEquals(Uri.parse("content://com.android.contacts/contacts/7"), jane.copy(lookupKey = null).viewIntent().data)

        val dial = dialIntent("+1 555-0100")
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel", dial.data!!.scheme)
        assertEquals("+1 555-0100", dial.data!!.schemeSpecificPart)
        // A "#" is part of the number, not a fragment.
        assertEquals("*21#", dialIntent("*21#").data!!.schemeSpecificPart)

        val text = messageIntent("+1 555-0100")
        assertEquals(Intent.ACTION_SENDTO, text.action)
        assertEquals("smsto", text.data!!.scheme)
        assertEquals("+1 555-0100", text.data!!.schemeSpecificPart)
    }

    @Test fun initials() {
        assertEquals("J", contactInitial("jane"))
        assertEquals("M", contactInitial("(Mom)"))
        assertEquals("É", contactInitial("émile"))
        assertEquals("A", contactInitial("😀 Ann"))
        assertNull(contactInitial("+1 555 0100"))
        assertNull(contactInitial(""))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun photosAreScaledDownToTheIconSize() {
        val file = File.createTempFile("photo", ".png").apply { deleteOnExit() }
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val photo = loadContactPhoto(app, Uri.fromFile(file).toString(), 48)!!
        assertEquals(96, photo.width)
        assertEquals(48, photo.height)
        // Already small enough: kept as it is.
        assertEquals(200, loadContactPhoto(app, Uri.fromFile(file).toString(), 120)!!.width)
        assertNull(loadContactPhoto(app, Uri.fromFile(File(file.parent, "missing.png")).toString(), 48))
    }
}
