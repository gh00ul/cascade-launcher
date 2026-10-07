package com.gh00ul.cascade.util

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gh00ul.cascade.testing.answerPermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When a permission request means Android has stopped asking ("blocked", so callers send the user to App info): never
 * for a dialog closed with Back, which leaves no rationale just as a block does; for a silent refusal after one with a
 * rationale, after a refusal noted in an earlier session, or after a silent refusal right before it.
 *
 * Android's side is Robolectric's: requestPermissions starts the permission screen for a result, [answerPermissionRequest]
 * returns its answer through the activity, and whether a rationale is due is set on the package manager before and
 * after each answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PermissionPromptTest {
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
    /** Where the prompt notes each permission refused at least once. */
    private val refusals get() = app.getSharedPreferences("permission_prompts", Context.MODE_PRIVATE)
    private lateinit var prompt: PermissionPrompt
    private val answers = mutableListOf<Boolean>()

    private fun show() {
        compose.setContent { prompt = rememberPermissionPrompt(CALENDAR) { granted -> answers += granted } }
        compose.waitForIdle()
    }

    /** Whether Android would show a rationale for the calendar now: after one refusal, until it stops asking. */
    private fun rationaleDue(due: Boolean) = shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(CALENDAR, due)

    /**
     * Asks, and has Android answer: [granted] or refused, with a rationale due afterwards ([rationaleAfter]) or not.
     * Whether one was due before asking is whatever [rationaleDue] set last (none at first).
     */
    private fun ask(granted: Boolean = false, rationaleAfter: Boolean = false) {
        val before = shadowOf(compose.activity).lastRequestedPermission
        val answered = answers.size
        compose.runOnIdle { prompt.launch() }
        assertNotSame("Android was asked", before, shadowOf(compose.activity).lastRequestedPermission)
        // Only Android's answer below may arrive: an answer now would be the activity cancelling a request made while it
        // still waits on the last one.
        assertEquals("No answer before Android's", answered, answers.size)
        rationaleDue(rationaleAfter)
        compose.runOnIdle { answerPermissionRequest(compose.activity, granted) }
        compose.waitForIdle()
        assertEquals("One answer per request", answered + 1, answers.size)
    }

    @Test fun blockedOnlyForARefusalWithNoRationaleAfterAnEarlierRefusal() {
        // Closing the first dialog: refused, no rationale before or after, nothing refused earlier.
        assertFalse(permissionBlocked(granted = false, rationaleBefore = false, rationaleNow = false, deniedOnce = false))
        val bools = listOf(false, true)
        val blocked = buildSet {
            for (granted in bools) for (before in bools) for (now in bools) for (denied in bools) {
                if (permissionBlocked(granted, before, now, denied)) add(listOf(granted, before, now, denied))
            }
        }
        assertEquals(
            setOf(
                // A rationale was due before asking and isn't now: the second "Don't allow".
                listOf(false, true, false, false),
                // Refused before (noted, or a silent refusal just before), and no rationale now.
                listOf(false, false, false, true),
                listOf(false, true, false, true),
            ),
            blocked,
        )
    }

    /**
     * Back on the first dialog leaves no rationale, just as a block does. Before the fix the widget took it for a block,
     * and the next tap went to App info instead of asking again.
     */
    @Test fun closingTheFirstDialogIsntABlock() {
        show()
        ask()
        assertFalse("A closed dialog isn't a block", prompt.blocked)
        assertEquals(listOf(false), answers)
        assertNull("Nothing was surely refused, so nothing is noted", refusals.all[CALENDAR])
    }

    /** A second silent refusal in a row can only be the block (or an earlier session's, before refusals were noted). */
    @Test fun aSecondSilentRefusalInARowIsTheBlock() {
        show()
        ask()
        ask()
        assertTrue(prompt.blocked)
        assertEquals("Noted for later sessions", true, refusals.all[CALENDAR])
    }

    @Test fun aRefusalWithARationaleIsNotedAndTheNextSilentOneIsTheBlock() {
        show()
        ask(rationaleAfter = true)
        assertFalse(prompt.blocked)
        assertEquals("Noted for later sessions", true, refusals.all[CALENDAR])
        // "Don't allow" again: Android stops asking, and no longer has a rationale to show.
        ask(rationaleAfter = false)
        assertTrue(prompt.blocked)
    }

    /** An earlier session noted a refusal: the first silent refusal now is the block, so App info isn't a tap away. */
    @Test fun aRefusalNotedInAnEarlierSessionMakesTheFirstSilentOneTheBlock() {
        refusals.edit().putBoolean(CALENDAR, true).commit()
        show()
        ask()
        assertTrue(prompt.blocked)
    }

    @Test fun allowingItInAppInfoClearsTheBlock() {
        show()
        ask()
        ask()
        assertTrue(prompt.blocked)
        shadowOf(app).grantPermissions(CALENDAR)
        compose.runOnIdle { prompt.launch() }
        // Already granted: the answer comes at once, posted to the main thread, with no dialog.
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertFalse(prompt.blocked)
        assertEquals(listOf(false, false, true), answers)
    }

    /** A note of another type (a hand-edited file) isn't taken for a refusal, and reading it doesn't throw. */
    @Test fun aNoteOfAnotherTypeIsntTakenForARefusal() {
        refusals.edit().putString(CALENDAR, "yes").commit()
        show()
        ask()
        assertFalse(prompt.blocked)
        ask()
        assertTrue(prompt.blocked)
        assertEquals("Written over with a proper note", true, refusals.all[CALENDAR])
    }

    private companion object {
        const val CALENDAR = Manifest.permission.READ_CALENDAR
    }
}
