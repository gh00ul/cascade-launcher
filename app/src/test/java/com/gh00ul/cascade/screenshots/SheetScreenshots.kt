package com.gh00ul.cascade.screenshots

import android.content.Intent
import android.content.pm.ShortcutInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.gh00ul.cascade.testing.FakeApps
import com.gh00ul.cascade.testing.FakeNotifications
import com.gh00ul.cascade.ui.home.AppActionsContent
import com.gh00ul.cascade.ui.home.HomeMenuContent
import com.gh00ul.cascade.util.AppShortcut
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * The long-press sheets. ModalBottomSheet opens in a window of its own, out of the capture's reach, so these draw the
 * sheets' content in a surface shaped like one. Sheets on the home screen are always dark (see LauncherTheme).
 */
class SheetScreenshots : ScreenshotTest() {
    private val dark = listOf(Variant.DarkTheme)

    /** Messages, a favorite: three conversations, two conversation shortcuts and the app actions. */
    @Test fun appActions() {
        val context = RuntimeEnvironment.getApplication()
        val shortcuts = listOf("Maya Chen" to 0xFF8E24AA, "Sam Ortiz" to 0xFF00897B).map { (label, color) ->
            val info = ShortcutInfo.Builder(context, label).setShortLabel(label).setIntent(Intent(Intent.ACTION_VIEW)).build()
            AppShortcut(info, label, FakeApps.iconBitmap(label, color, sizePx = 96).asImageBitmap())
        }
        snap("Sheet_AppActions", Frame.Component, variants = dark, wallpaper = false) {
            Sheet {
                AppActionsContent(
                    app = FakeApps.messages,
                    icon = icons[FakeApps.messages.key],
                    isFavorite = true,
                    isHidden = false,
                    notifications = FakeNotifications.messages(),
                    shortcuts = shortcuts,
                    canUninstall = true,
                    showHidePlayer = false,
                    onOpenNotification = {},
                    onClearNotifications = {},
                    onShortcut = {},
                    onHidePlayer = {},
                    onToggleFavorite = {},
                    onRename = {},
                    onToggleHidden = {},
                    onAppInfo = {},
                    onUninstall = {},
                )
            }
        }
    }

    @Test fun homeMenu() = snap("Sheet_HomeMenu", Frame.Component, variants = dark, wallpaper = false) {
        Sheet { HomeMenuContent(onWallpaper = {}, onEditFavorites = {}, onSettings = {}) }
    }
}

/** What ModalBottomSheet draws around its content: the sheet's shape and color, with the drag handle on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = BottomSheetDefaults.ExpandedShape,
        color = BottomSheetDefaults.ContainerColor,
    ) {
        Column(Modifier.fillMaxWidth()) {
            BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
            content()
        }
    }
}
