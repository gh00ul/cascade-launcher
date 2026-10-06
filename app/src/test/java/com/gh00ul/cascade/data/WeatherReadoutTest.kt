package com.gh00ul.cascade.data

import android.app.Application
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gh00ul.cascade.ui.home.WeatherReadout
import org.junit.Assert.assertEquals
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
 * The readout in a bare activity under a plain Application, as Settings or a test would host it. Only a fresh stored
 * reading is used, so nothing here reaches the network: a refresh finds it under 30 minutes old.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WeatherReadoutTest {
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
    private val seattle = WeatherPlace("Seattle, Washington, United States", 47.60621, -122.33207)
    private val spoken = "Weather: Sunny, 18 degrees, high 21, low 12"

    @Test fun showsTheStoredReadingAsOneButtonThatOpensTheForecast() {
        val reading = WeatherNow(18, 21, 12, 0, isDay = true, fahrenheit = false, fetchedAt = System.currentTimeMillis(), place = seattle)
        app.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().putString("now", weatherJson(reading)).commit()
        compose.setContent {
            Row {
                Text("Monday, October 5")
                WeatherReadout(LauncherSettings(showWeather = true, weatherPlace = seattle, tempUnit = TempUnit.CELSIUS))
            }
        }
        // Read on Weather's own thread, then published to the readout.
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(spoken).fetchSemanticsNodes().isNotEmpty() }

        val node = compose.onNodeWithContentDescription(spoken)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Open forecast", node.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        // The separator, icon and temperature are one node, not three.
        compose.onAllNodesWithText("18°").assertCountEquals(0)
        compose.onAllNodesWithText("  ·  ").assertCountEquals(0)

        node.performClick()
        val search = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_WEB_SEARCH, search.action)
        assertEquals("weather Seattle, Washington, United States", search.getStringExtra(SearchManager.QUERY))
    }

    @Test fun drawsNothingWhileWeatherIsOff() {
        compose.setContent {
            Row {
                Text("Monday, October 5")
                WeatherReadout(LauncherSettings(showWeather = false, weatherPlace = seattle), Modifier.testTag("weather"))
            }
        }
        compose.onNodeWithText("Monday, October 5").assertExists()
        compose.onNodeWithTag("weather").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Weather", substring = true).assertCountEquals(0)
    }
}
