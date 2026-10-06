package com.gh00ul.cascade.notifications

import android.app.Application
import android.graphics.Bitmap
import com.gh00ul.cascade.testing.MediaFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test fun thumbnailShrinksLargeArtAndLeavesItUsable() {
        val cover = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        val thumb = NowPlaying.thumbnail(cover)
        assertEquals(192 to 96, thumb.width to thumb.height)
        // The cover belongs to the session's metadata, and the thumbnail is what the player draws.
        assertFalse(cover.isRecycled)
        assertFalse(thumb.isRecycled)
    }

    @Test fun smallArtIsItsOwnThumbnail() {
        val cover = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        assertSame(cover, NowPlaying.thumbnail(cover))
        assertFalse(cover.isRecycled)
    }

    @Test fun hardwareArtGetsASoftwareThumbnail() {
        val cover = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.HARDWARE, false)
        val thumb = NowPlaying.thumbnail(cover)
        // Only the full-size software copy in between is freed.
        assertEquals(Bitmap.Config.ARGB_8888, thumb.config)
        assertEquals(192, thumb.width)
        assertFalse(cover.isRecycled)
        assertFalse(thumb.isRecycled)
    }

    @Test fun seedColorLeavesTheArtUsable() {
        // 24 px art is what createScaledBitmap may hand back unscaled.
        for (art in listOf(media.art, Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888))) {
            seedColor(art)
            assertFalse(art.isRecycled)
        }
    }
}
