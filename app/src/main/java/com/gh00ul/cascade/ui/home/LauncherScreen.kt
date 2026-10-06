package com.gh00ul.cascade.ui.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.SwipeDownAction
import com.gh00ul.cascade.data.TextColor
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.notifications.NowPlaying
import com.gh00ul.cascade.settings.SettingsActivity
import com.gh00ul.cascade.ui.theme.LauncherStyle
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.colorScheme
import com.gh00ul.cascade.ui.theme.rememberWallpaperSupportsDarkText
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.sendFromLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Index of the first A–Z row: the home page and the all-apps header come before it. */
private const val FIRST_APP_ROW = 2

private sealed interface Row {
    val key: String

    data class Section(val letter: String) : Row {
        override val key: String get() = "section:$letter"
    }

    data class App(val app: AppEntry) : Row {
        override val key: String get() = "app:${app.key}"
    }
}

private fun buildRows(apps: List<AppEntry>): List<Row> = buildList {
    var section: String? = null
    for (app in apps) {
        if (app.section != section) {
            section = app.section
            add(Row.Section(app.section))
        }
        add(Row.App(app))
    }
}

/**
 * One continuous list, like Niagara: the home page (clock + favorites) fills the first screen and the full
 * A–Z app list continues below it. The alphabet wave on the right edge jumps anywhere in the list.
 */
@Composable
fun LauncherScreen(homePresses: Flow<Unit>) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val launcher = context.launcher
    val apps by launcher.repository.apps.collectAsStateWithLifecycle()
    val icons by launcher.repository.icons.collectAsStateWithLifecycle()
    val settings by launcher.prefs.settings.collectAsStateWithLifecycle()
    val notifications by NotificationStore.byApp.collectAsStateWithLifecycle()
    val nowPlaying by NowPlaying.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sheetApp by remember { mutableStateOf<AppEntry?>(null) }
    var renameApp by remember { mutableStateOf<AppEntry?>(null) }
    var homeMenuOpen by remember { mutableStateOf(false) }
    // Row whose notifications are swiped open; "fav:" and "all:" prefixes keep the two lists apart.
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val toggleExpand: (String) -> Unit = { key -> expandedKey = if (expandedKey == key) null else key }

    val wallpaperDarkText = rememberWallpaperSupportsDarkText()
    val darkText = when (settings.textColor) {
        TextColor.AUTO -> wallpaperDarkText
        TextColor.LIGHT -> false
        TextColor.DARK -> true
    }
    val accent = colorScheme(dark = !darkText).primary
    val style = remember(darkText, accent) { LauncherStyle(darkText, accent) }
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkText
            isAppearanceLightNavigationBars = darkText
        }
    }

    var isDefault by remember { mutableStateOf(LauncherActions.isDefaultLauncher(context)) }
    var hasNotificationAccess by remember { mutableStateOf(LauncherActions.hasNotificationAccess(context)) }
    var defaultPromptHidden by rememberSaveable { mutableStateOf(false) }
    // Music player: the playing app's favorite row turns into it. A player that is paused when you come home rests.
    val media = nowPlaying?.takeIf { settings.showMediaControls }
    val mediaApp = remember(apps, media?.packageName) {
        media?.let { m -> apps.firstOrNull { it.packageName == m.packageName && !it.isWork } }
    }
    var mediaResting by remember { mutableStateOf(false) }
    LaunchedEffect(media?.isPaused) { if (media?.isPaused == false) mediaResting = false }
    // The temporary player's notification list belongs to one session; don't carry it over to the next.
    LaunchedEffect(media?.sessionId) { if (expandedKey == "media") expandedKey = null }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isDefault = LauncherActions.isDefaultLauncher(context)
        hasNotificationAccess = LauncherActions.hasNotificationAccess(context)
        NowPlaying.refresh()
        mediaResting = NowPlaying.state.value?.isPaused == true
    }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = LauncherActions.isDefaultLauncher(context)
        // The role dialog is skipped silently after repeated refusals; fall back to the settings page.
        if (!isDefault) LauncherActions.openHomeSettings(context)
    }

    val byKey = remember(apps) { apps.associateBy { it.key } }
    val favorites = remember(byKey, settings.favorites) { settings.favorites.mapNotNull { byKey[it] } }
    val rows = remember(apps, settings.hidden) { buildRows(apps.filter { it.key !in settings.hidden }) }
    val letterRows = remember(rows) {
        buildMap { rows.forEachIndexed { i, row -> if (row is Row.Section) put(row.letter, i + FIRST_APP_ROW) } }
    }
    val letters = remember(letterRows) { letterRows.keys.toList() }
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }

    val launch: (AppEntry, Rect?) -> Unit = { app, bounds -> LauncherActions.launch(context, app, view, bounds) }
    val openNotification: (AppEntry, AppNotification) -> Unit = { app, n ->
        if (!NotificationStore.open(context, n)) launch(app, null)
    }

    LaunchedEffect(homePresses) {
        homePresses.collect {
            searchOpen = false
            sheetApp = null
            renameApp = null
            homeMenuOpen = false
            expandedKey = null
            if (listState.firstVisibleItemIndex > 4) listState.scrollToItem(0) else listState.animateScrollToItem(0)
        }
    }
    BackHandler(enabled = !atTop && !searchOpen) { scope.launch { listState.animateScrollToItem(0) } }

    val swipeDownAction by rememberUpdatedState(settings.swipeDownAction)
    val pullDown = remember {
        PullDownConnection(threshold = with(density) { 64.dp.toPx() }) {
            if (swipeDownAction == SwipeDownAction.SEARCH || !LauncherActions.expandNotifications(context)) searchOpen = true
        }
    }

    CompositionLocalProvider(LocalLauncherStyle provides style, LocalContentColor provides style.content) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val homeHeight = maxHeight - statusTop
            val homeHeightPx = with(density) { homeHeight.toPx() }.coerceAtLeast(1f)
            // 0 on the home page, 1 once the app list covers the screen. Read only while drawing.
            val progress = {
                if (listState.firstVisibleItemIndex > 0) 1f
                else (listState.firstVisibleItemScrollOffset / homeHeightPx).coerceIn(0f, 1f)
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val p = progress()
                        drawRect(style.scrim, alpha = 0.12f + 0.58f * p)
                        // A soft wash behind the clock so it reads on bright skies; fades as the list scrolls up.
                        drawRect(
                            Brush.verticalGradient(listOf(style.scrim.copy(alpha = 0.22f), Color.Transparent), endY = size.height * 0.4f),
                            alpha = 1f - p,
                        )
                    },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(pullDown)
                    // Fade rows out as they slide under the status bar.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val top = statusTop.toPx()
                        val fadeEnd = top + 20.dp.toPx()
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = top * 0.5f, endY = fadeEnd),
                            size = Size(size.width, fadeEnd),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                contentPadding = PaddingValues(top = statusTop, bottom = navBottom + 16.dp),
            ) {
                item(key = "home", contentType = "home") {
                    HomePage(
                        minHeight = homeHeight,
                        bottomInset = navBottom,
                        favorites = favorites,
                        icons = icons,
                        notifications = notifications,
                        settings = settings,
                        onLaunch = launch,
                        onAppLongPress = { sheetApp = it },
                        onOpenNotification = openNotification,
                        onEmptyLongPress = { homeMenuOpen = true },
                        expandedKey = expandedKey,
                        onToggleExpand = toggleExpand,
                        media = media,
                        mediaApp = mediaApp,
                        mediaResting = mediaResting,
                        onOpenMedia = {
                            val opened = media?.sessionActivity?.sendFromLauncher(context) ?: false
                            if (!opened) mediaApp?.let { launch(it, null) }
                        },
                        onHideMedia = NowPlaying::hide,
                        onboarding = {
                            when {
                                !isDefault && !defaultPromptHidden -> OnboardingCard(
                                    title = "Make Cascade your home screen",
                                    body = "Set it as your default home app so the Home button brings you here.",
                                    action = "Set as default",
                                    onAction = { LauncherActions.requestDefaultLauncher(context, roleRequest) },
                                    onDismiss = { defaultPromptHidden = true },
                                )
                                !hasNotificationAccess && !settings.notificationPromptDismissed -> OnboardingCard(
                                    title = "See notifications beside your apps",
                                    body = "Allow notification access to show a dot and the latest message under each favorite.",
                                    action = "Allow",
                                    onAction = { LauncherActions.openNotificationAccess(context) },
                                    onDismiss = { launcher.prefs.update { it.copy(notificationPromptDismissed = true) } },
                                )
                            }
                        },
                    )
                }
                item(key = "header", contentType = "header") {
                    AllAppsHeader(onSearch = { searchOpen = true }, onSettings = { SettingsActivity.open(context) })
                }
                items(rows, key = { it.key }, contentType = { if (it is Row.Section) 0 else 1 }) { row ->
                    when (row) {
                        is Row.Section -> SectionHeader(row.letter)
                        is Row.App -> AppRow(
                            app = row.app,
                            icon = icons[row.app.key],
                            notifications = notifications[row.app.notificationKey].orEmpty(),
                            showIcon = settings.showIcons,
                            showPreview = false,
                            large = false,
                            onClick = { launch(row.app, it) },
                            onLongClick = { sheetApp = row.app },
                            onNotificationClick = { openNotification(row.app, it) },
                            modifier = Modifier.padding(start = 20.dp, end = 44.dp),
                            expanded = expandedKey == "all:${row.app.key}",
                            onToggleExpand = { toggleExpand("all:${row.app.key}") },
                        )
                    }
                }
            }

            // Keeps the status bar readable once the list scrolls under it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(statusTop + 16.dp)
                    .drawBehind {
                        drawRect(Brush.verticalGradient(listOf(style.scrim.copy(alpha = 0.5f), Color.Transparent)), alpha = progress())
                    },
            )

            if (letters.isNotEmpty() && !searchOpen) {
                AlphabetWave(
                    letters = letters,
                    onLetter = { letter -> letterRows[letter]?.let { index -> scope.launch { listState.scrollToItem(index) } } },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = statusTop, bottom = navBottom)
                        .fillMaxHeight(0.8f),
                )
            }

            if (searchOpen) {
                SearchOverlay(
                    apps = apps,
                    icons = icons,
                    showIcons = settings.showIcons,
                    onLaunch = { app, bounds ->
                        launch(app, bounds)
                        searchOpen = false
                    },
                    onLongPress = { sheetApp = it },
                    onDismiss = { searchOpen = false },
                )
            }
        }
    }

    sheetApp?.let { app ->
        AppActionsSheet(
            app = app,
            icon = icons[app.key],
            isFavorite = app.key in settings.favorites,
            isHidden = app.key in settings.hidden,
            notifications = notifications[app.notificationKey].orEmpty(),
            onOpenNotification = { openNotification(app, it) },
            onRename = { renameApp = app },
            onDismiss = { sheetApp = null },
            onHidePlayer = if (media != null && !media.isPlaying && app.packageName == media.packageName) NowPlaying::hide else null,
        )
    }
    renameApp?.let { app -> RenameDialog(app) { renameApp = null } }
    if (homeMenuOpen) HomeMenuSheet(onDismiss = { homeMenuOpen = false })
}

/** Fires once per drag when the user keeps pulling down while the list is already at the top. */
private class PullDownConnection(private val threshold: Float, private val onPull: () -> Unit) : NestedScrollConnection {
    private var pulled = 0f
    private var fired = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y > 0f) {
            pulled += available.y
            if (!fired && pulled > threshold) {
                fired = true
                onPull()
            }
        } else if (consumed.y != 0f) {
            pulled = 0f
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        pulled = 0f
        fired = false
        return Velocity.Zero
    }
}
