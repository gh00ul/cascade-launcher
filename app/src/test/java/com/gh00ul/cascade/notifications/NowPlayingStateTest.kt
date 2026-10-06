package com.gh00ul.cascade.notifications

import android.app.Application
import com.gh00ul.cascade.testing.MediaFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NowPlayingStateTest {
    private val media = MediaFixtures(RuntimeEnvironment.getApplication())

    @After fun release() = media.release()

    @Test fun playingPositionAdvancesWithSpeedAndClampsToDuration() {
        val state = media.state(withArt = false, durationMs = 200_000, positionMs = 10_000).copy(positionUpdatedAt = 1_000, speed = 2f)
        assertEquals(10_000L, state.positionAt(1_000))
        assertEquals(30_000L, state.positionAt(11_000))
        assertEquals(200_000L, state.positionAt(1_000_000))
        // Rewinding never goes below zero.
        assertEquals(0L, state.copy(speed = -4f).positionAt(11_000))
    }

    @Test fun pausedPositionStaysPut() {
        val state = media.state(PlaybackStatus.PAUSED, withArt = false, positionMs = 42_000).copy(positionUpdatedAt = 0)
        assertEquals(42_000L, state.positionAt(500_000))
    }

    @Test fun liveMeansPlayingWithoutDurationOrSeeking() {
        assertTrue(media.live().isLive)
        assertFalse(media.live().hasDuration)
        assertFalse(media.state(withArt = false).isLive)
        // A stream that is still loading, or paused, isn't "live".
        assertFalse(media.live().copy(status = PlaybackStatus.BUFFERING).isLive)
        assertFalse(media.live().copy(status = PlaybackStatus.PAUSED).isLive)
    }

    @Test fun streamPositionHasNoUpperBound() {
        val state = media.live().copy(positionMs = 5_000, positionUpdatedAt = 0)
        assertEquals(65_000L, state.positionAt(60_000))
    }

    @Test fun commandsOnARealSessionDoNotThrow() {
        val state = media.state(withArt = false)
        state.play()
        state.pause()
        state.next()
        state.previous()
        state.seekTo(-5)
        state.seekTo(Long.MAX_VALUE)
    }
}
