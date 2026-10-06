package com.gh00ul.cascade.screenshots

import android.app.Application
import android.content.ComponentName
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.testing.FIXED_NOW
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.MediaFixtures
import com.gh00ul.cascade.testing.withFixedZone
import com.gh00ul.cascade.ui.home.LocalNow
import com.gh00ul.cascade.ui.theme.LauncherTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * How a shot is rendered; the suffix ends up in the PNG name. Launcher shots draw over a sample wallpaper with
 * white text (dusk wallpaper) or dark text (daylight wallpaper); Settings shots use the light or dark theme.
 */
enum class Variant(val suffix: String, val darkText: Boolean, val darkTheme: Boolean) {
    WhiteText("whiteText", darkText = false, darkTheme = true),
    DarkText("darkText", darkText = true, darkTheme = true),
    LightTheme("light", darkText = false, darkTheme = false),
    DarkTheme("dark", darkText = false, darkTheme = true);

    companion object {
        val Launcher = listOf(WhiteText, DarkText)
        val Settings = listOf(LightTheme, DarkTheme)
    }
}

/** [Component]: full phone width, height wraps the content. [Screen]: the whole 412x915 dp phone canvas. */
enum class Frame { Component, Screen }

/**
 * Base class for JVM screenshot tests (Robolectric native graphics + Roborazzi). Each @Test calls [snap] once.
 * The canvas is a 412x915 dp phone at xxhdpi (1236x2745 px). Run with `-PcascadeScreenshots` (see SCREENSHOTS.md).
 *
 * Tests run on a plain [Application], so AppRepository and the notification listener never start; pass fake
 * apps, icons and notifications in directly. Settings screenshots switch to LauncherApplication.
 *
 * Every shot is taken at [FIXED_NOW] in [com.gh00ul.cascade.testing.FIXED_ZONE]: the home clock reads it through
 * [LocalNow], and Robolectric's clock (which framework helpers such as DateUtils read) is set to it too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = ScreenshotTest.PHONE)
abstract class ScreenshotTest {
    /**
     * Runs before the compose rule starts the activity. Registers the bare ComponentActivity the compose rule hosts
     * content in (the app's manifest doesn't declare it), and pins the time zone and Robolectric's clock.
     */
    @get:Rule(order = 0)
    val hostActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                SystemClock.setCurrentTimeMillis(FIXED_NOW)
                withFixedZone { base.evaluate() }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected val apps get() = FakeApps.all
    protected val favorites get() = FakeApps.favorites
    protected val icons by lazy { FakeApps.icons() }

    private var mediaFixtures: MediaFixtures? = null
    /** Player states backed by a real (Robolectric) MediaSession, released after the test. */
    protected val media: MediaFixtures
        get() = mediaFixtures ?: MediaFixtures(RuntimeEnvironment.getApplication()).also { mediaFixtures = it }

    @After
    fun resetGlobals() {
        // Kotlin objects outlive a test inside the same Robolectric sandbox.
        NotificationStore.clear()
        FakeCalendarProvider.events = emptyList()
        mediaFixtures?.release()
        mediaFixtures = null
    }

    /**
     * Renders [content] once per [variants] entry and writes `<name>_<variant>.png`. [afterContent] runs after each
     * variant is composed and before capture (type text, scroll, wait for async data); `remember` state starts over
     * for every variant, so it runs every time.
     */
    protected fun snap(
        name: String,
        frame: Frame = Frame.Screen,
        variants: List<Variant> = Variant.Launcher,
        wallpaper: Boolean = true,
        afterContent: (AndroidComposeTestRule<*, *>.() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        assumeTrue("Not selected by the screenshot filter", Screenshots.selected(name))
        var variant by mutableStateOf(variants.first())
        compose.setContent { key(variant) { ShotFrame(variant, frame, wallpaper, content) } }
        for (v in variants) {
            compose.runOnIdle { variant = v }
            compose.waitForIdle()
            afterContent?.invoke(compose)
            compose.waitForIdle()
            val file = Screenshots.file(name, v)
            compose.onNodeWithTag(SHOT_TAG).captureRoboImage(file.path, options)
            println("Screenshot written: ${file.path}")
        }
    }

    companion object {
        /** 412x915 dp at xxhdpi (480 dpi): 1236x2745 px, a typical large phone. */
        const val PHONE = "en-rUS-w412dp-h915dp-normal-long-notround-any-xxhdpi-keyshidden-nonav"
        const val SHOT_TAG = "screenshot-root"

        @OptIn(ExperimentalRoborazziApi::class)
        private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}

@Composable
private fun ShotFrame(variant: Variant, frame: Frame, wallpaper: Boolean, content: @Composable () -> Unit) {
    val size = if (frame == Frame.Screen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
    CompositionLocalProvider(LocalNow provides { FIXED_NOW }) {
        LauncherTheme(dark = variant.darkTheme) {
            if (wallpaper) {
                // Rows sit on the lower half of the wallpaper, where favorites are on the home screen.
                LauncherSurface(variant.darkText, size.testTag(ScreenshotTest.SHOT_TAG), if (frame == Frame.Component) 0.55f else 0f, content)
            } else {
                Surface(size.testTag(ScreenshotTest.SHOT_TAG), color = MaterialTheme.colorScheme.background, content = content)
            }
        }
    }
}

internal object Screenshots {
    private val dir = File(System.getProperty("cascade.screenshots.dir") ?: "build/screenshots")
    /** Case-insensitive regexes, comma-separated (gradlew.bat would read `|` as a pipe); a name matching any is selected. */
    private val filter = System.getProperty("cascade.screenshots.filter").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }?.let { parts -> Regex(parts.joinToString("|") { "(?:$it)" }, RegexOption.IGNORE_CASE) }

    fun selected(name: String) = filter?.containsMatchIn(name) ?: true
    fun file(name: String, variant: Variant) = File(dir, "${name}_${variant.suffix}.png").apply { parentFile?.mkdirs(); delete() }
}
