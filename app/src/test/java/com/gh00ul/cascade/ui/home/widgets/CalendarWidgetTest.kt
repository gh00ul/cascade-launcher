package com.gh00ul.cascade.ui.home.widgets

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.LauncherSettings
import com.gh00ul.cascade.testing.answerPermissionRequest
import com.gh00ul.cascade.ui.theme.LauncherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The calendar widget's messages: a calendar that can't be read says so, rather than passing for a clear one; and
 * closing the permission dialog without answering leaves the widget asking, rather than sending the next tap to App
 * info. The agenda is read on Dispatchers.IO, so its message is waited for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CalendarWidgetTest {
    /** Registers the bare activity the compose rule hosts content in (the app's manifest doesn't declare it). */
    @get:Rule(order = 0)
    val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = RuntimeEnvironment.getApplication()

    /** The widget at the stack's size, as home shows it. */
    private fun show() {
        compose.setContent {
            LauncherTheme {
                Box(Modifier.size(360.dp, 176.dp)) { CalendarWidget(LauncherSettings(), onLongPress = {}) }
            }
        }
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Calendar access, and the calendar's provider: one that fails unless told otherwise. */
    private fun calendar(): BrokenCalendar {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        return Robolectric.setupContentProvider(BrokenCalendar::class.java, CalendarContract.AUTHORITY)
    }

    /** Before the fix a failed read was taken for an empty calendar: "Nothing coming up", with events still to come. */
    @Test fun aCalendarThatCantBeReadSaysSo() {
        calendar()
        show()
        compose.waitUntil(5_000) { shows(READ_ERROR) || shows(CLEAR) }
        compose.onNodeWithText(READ_ERROR).assertExists()
        compose.onNodeWithText(CLEAR).assertDoesNotExist()
    }

    @Test fun aClearCalendarIsStillNothingComingUp() {
        calendar().broken = false
        show()
        compose.waitUntil(5_000) { shows(READ_ERROR) || shows(CLEAR) }
        compose.onNodeWithText(CLEAR).assertExists()
        compose.onNodeWithText(READ_ERROR).assertDoesNotExist()
    }

    /**
     * Back on the first permission dialog refuses nothing, but leaves no rationale, just as a block does. Before the
     * fix the widget took it for a block: it said access was blocked, and its button opened App info.
     */
    @Test fun closingThePermissionDialogKeepsAskingRatherThanSendingToAppInfo() {
        // Robolectric denies what isn't granted, and has no rationale to show for it.
        show()
        compose.onNodeWithText("Allow").performClick()
        compose.runOnIdle {
            val request = answerPermissionRequest(compose.activity, granted = false)
            assertEquals(listOf(Manifest.permission.READ_CALENDAR), request.requestedPermissions.toList())
        }
        compose.waitForIdle()
        compose.onNodeWithText("Allow").assertExists()
        compose.onNodeWithText("App info").assertDoesNotExist()
        compose.onNodeWithText("Calendar access is blocked", substring = true).assertDoesNotExist()
    }

    /** The calendar's provider: its query fails, as one revoked, busy or gone does; with [broken] off, it's empty. */
    class BrokenCalendar : ContentProvider() {
        /** Read on Dispatchers.IO, where the widget queries. */
        @Volatile var broken = true

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
            check(!broken) { "The calendar provider is busy" }
            return MatrixCursor(requireNotNull(projection))
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    private companion object {
        const val READ_ERROR = "Couldn't read your calendar. Tap to open it."
        const val CLEAR = "Nothing coming up"
    }
}
