package com.gh00ul.cascade.ui.home

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
 * ReorderableColumn with a child that has no key (no String layoutId), which no favorite is today: it stacks in place
 * like the rest instead of failing the layout, it isn't one of the rows that move, and the rows around it still lift,
 * move and save an order of their own keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReorderKeylessChildTest {
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

    /** The two rows' keys, top to bottom; a save reorders them, as home's favorites do. */
    private var order by mutableStateOf(listOf("a", "b"))
    private val saved = ArrayList<List<String>>()
    private lateinit var state: FavoriteReorder

    @Test fun aChildWithoutAKeyStacksInPlace() {
        show()
        val a = bounds("a")
        val gap = bounds(GAP)
        val b = bounds("b")
        assertEquals(0.dp, a.top)
        assertEquals(a.bottom, gap.top)
        assertEquals(gap.bottom, b.top)
    }

    @Test fun theRowsAroundItStillMoveAndSaveWithoutIt() {
        show()
        val gapTop = bounds(GAP).top
        val rowHeight = with(compose.density) { ROW_HEIGHT.toPx() }
        // Held and dragged a row down, past the middle of the row below: the rows, not the child between, trade places.
        compose.runOnIdle {
            assertTrue("A row lifts", state.pick("a"))
            state.drag(rowHeight)
        }
        compose.waitForIdle()
        assertEquals("The child without a key stays where it was", gapTop, bounds(GAP).top)

        compose.runOnIdle { state.drop() }
        compose.waitForIdle()
        assertEquals(listOf(listOf("b", "a")), saved)
        val b = bounds("b")
        val gap = bounds(GAP)
        val a = bounds("a")
        assertEquals(0.dp, b.top)
        assertEquals(b.bottom, gap.top)
        assertEquals(gap.bottom, a.top)
    }

    private fun show() {
        compose.setContent {
            state = rememberFavoriteReorder { keys ->
                saved += keys
                order = keys
            }
            ReorderableColumn(state) {
                KeyedRow(order[0])
                Box(Modifier.testTag(GAP).size(width = 100.dp, height = 20.dp))
                KeyedRow(order[1])
            }
        }
        compose.waitForIdle()
    }

    /** A row as a favorite is one: keyed with its layoutId. */
    @Composable
    private fun KeyedRow(key: String) {
        Box(Modifier.layoutId(key).testTag(key).size(width = 100.dp, height = ROW_HEIGHT))
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    private companion object {
        /** The child with no layoutId, between the two rows. */
        const val GAP = "gap"
        val ROW_HEIGHT = 40.dp
    }
}
