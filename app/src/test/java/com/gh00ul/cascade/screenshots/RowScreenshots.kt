package com.gh00ul.cascade.screenshots

import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.IconSize
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NowPlayingState
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.ui.home.AppRow
import com.gh00ul.cascade.ui.home.MediaRow
import org.junit.Test

/** Single rows at full width over the wallpaper: app rows in their states, and the music player. */
class RowScreenshots : ScreenshotTest() {
    private val homeIcon = IconSize.MEDIUM.homeDp.dp
    private val listIcon = IconSize.MEDIUM.listDp.dp

    @androidx.compose.runtime.Composable
    private fun Row(app: AppEntry, notifications: List<AppNotification>, large: Boolean, preview: Boolean = large, expanded: Boolean = false) =
        AppRow(
            app = app,
            icon = icons[app.key],
            notifications = notifications,
            showIcon = true,
            showPreview = preview,
            large = large,
            iconSize = if (large) homeIcon else listIcon,
            onClick = {},
            onLongClick = {},
            onNotificationClick = {},
            expanded = expanded,
            onToggleExpand = {},
        )

    /** A favorite with a dot and the latest message underneath, "+2" for the rest; and one with a single mail. */
    @Test fun favoritePreview() = snap("AppRow_FavoritePreview", Frame.Component) {
        RowBackdrop {
            Row(FakeApps.messages, FakeNotifications.messages(), large = true)
            Row(FakeApps.mail, FakeNotifications.mail(), large = true)
            Row(FakeApps.phone, emptyList(), large = true)
        }
    }

    /** A login code under Messages, as a pill to copy it again, beside "+1". */
    @Test fun loginCode() = snap("AppRow_LoginCode", Frame.Component) {
        val code = FakeNotifications.notification("0|messages|9", "Google", "G-482913 is your Google verification code", 60_000L)
            .copy(code = "482913")
        RowBackdrop { Row(FakeApps.messages, listOf(code) + FakeNotifications.messages().take(1), large = true) }
    }

    @Test fun expanded() = snap("AppRow_Expanded", Frame.Component) {
        RowBackdrop { Row(FakeApps.messages, FakeNotifications.messages(), large = true, expanded = true) }
    }

    /** A–Z list rows: plain, with a notification dot, and the work-profile twin. */
    @Test fun list() = snap("AppRow_List", Frame.Component) {
        RowBackdrop {
            Row(FakeApps.byLabel("Maps"), emptyList(), large = false)
            Row(FakeApps.mail, FakeNotifications.mail(), large = false)
            Row(FakeApps.workMail, FakeNotifications.mail(), large = false)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Player(
        state: NowPlayingState,
        resting: Boolean = false,
        withIcon: Boolean = true,
        notifications: List<AppNotification> = emptyList(),
        expanded: Boolean = false,
    ) = RowBackdrop {
        MediaRow(
            state = state,
            appLabel = FakeApps.music.label,
            icon = if (withIcon) icons[FakeApps.music.key] else null,
            notifications = notifications,
            showArt = true,
            iconSize = homeIcon,
            monochrome = false,
            resting = resting,
            expanded = expanded,
            onOpen = {},
            onLongClick = {},
            onLongClickLabel = "App options",
            onToggleExpand = {},
            onNotificationClick = {},
            onHide = {},
        )
    }

    /** Art with the app badge, play/pause, previous / seek bar / next. */
    @Test fun mediaPlaying() {
        val state = media.playing()
        snap("Media_Playing", Frame.Component) { Player(state) }
    }

    /** Paused when home opened: one tier, with a line marking where playback stopped. */
    @Test fun mediaPausedResting() {
        val state = media.pausedResting()
        snap("Media_PausedResting", Frame.Component) { Player(state, resting = true) }
    }

    /** No art and no app icon: the music-note placeholder, and the app label in place of the artist. */
    @Test fun mediaNoArt() {
        val state = media.noArt()
        snap("Media_NoArt", Frame.Component) { Player(state, withIcon = false) }
    }

    @Test fun mediaLive() {
        val state = media.live()
        snap("Media_Live", Frame.Component) { Player(state) }
    }

    /** Swiped right: the player's notifications open underneath, their text on the title's column. */
    @Test fun mediaNotifications() {
        val state = media.playing()
        snap("Media_Notifications", Frame.Component) { Player(state, notifications = FakeNotifications.messages(), expanded = true) }
    }

    // At 2x font size: names and previews ellipsized on one line, pills and buttons whole.

    @Test fun favoritePreviewFont2x() = snap("AppRow_FavoritePreview_Font2x", Frame.Component) {
        FontScale(2f) {
            RowBackdrop {
                Row(FakeApps.messages, FakeNotifications.messages(), large = true)
                Row(FakeApps.mail, FakeNotifications.mail(), large = true)
                Row(FakeApps.phone, emptyList(), large = true)
            }
        }
    }

    @Test fun loginCodeFont2x() = snap("AppRow_LoginCode_Font2x", Frame.Component) {
        val code = FakeNotifications.notification("0|messages|9", "Google", "G-482913 is your Google verification code", 60_000L)
            .copy(code = "482913")
        FontScale(2f) { RowBackdrop { Row(FakeApps.messages, listOf(code) + FakeNotifications.messages().take(1), large = true) } }
    }

    @Test fun expandedFont2x() = snap("AppRow_Expanded_Font2x", Frame.Component) {
        FontScale(2f) { RowBackdrop { Row(FakeApps.messages, FakeNotifications.messages(), large = true, expanded = true) } }
    }

    @Test fun mediaPlayingFont2x() {
        val state = media.playing()
        snap("Media_Playing_Font2x", Frame.Component) { FontScale(2f) { Player(state) } }
    }
}
