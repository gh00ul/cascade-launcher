package com.gh00ul.cascade.ui.home

import com.gh00ul.cascade.update.Updater
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.SwipeDownAction
import com.gh00ul.cascade.data.TextColor
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.notifications.NowPlaying
import com.gh00ul.cascade.settings.SettingsActivity
import com.gh00ul.cascade.ui.common.rememberEntry
import com.gh00ul.cascade.ui.common.rememberReplaySkip
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

/** A–Z list rows. Keys are built once per row, not each time the list asks for them while scrolling. */
private sealed interface Row {
    val key: String

    data class Section(val letter: String) : Row {
        override val key = "section:$letter"
    }

    data class App(val app: AppEntry) : Row {
        override val key = "app:${app.key}"
        /** [LauncherScreen]'s expanded key while this row's notifications are open. */
        val expandKey = "all:${app.key}"
    }
}

private fun buildRows(apps: List<AppEntry>): List<Row> = buildList {
    var section: String? = null
    // Sections come in one run each; should one ever repeat, a second header would be a duplicate list key.
    val seen = HashSet<String>()
    for (app in apps) {
        if (app.section != section) {
            section = app.section
            if (seen.add(app.section)) add(Row.Section(app.section))
        }
        add(Row.App(app))
    }
}

/** The A–Z rows' side padding; the alphabet strip takes the end. */
private val ListRowPadding = Modifier.padding(start = 20.dp, end = 44.dp)

/**
 * One app in the A–Z list. It looks up its own icon and notifications, so a new icon map or a notification regroup
 * recomposes only the rows whose entry changed: icons by identity, notification lists by equality.
 */
@Composable
internal fun ListAppRow(
    app: AppEntry,
    expandKey: String,
    icons: State<Map<String, IconImage>>,
    notifications: State<Map<String, List<AppNotification>>>,
    showIcon: Boolean,
    iconSize: Dp,
    expanded: Boolean,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    onOpenNotification: (AppEntry, AppNotification) -> Unit,
    onToggleExpand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon by rememberEntry(icons, app.key, referentialEqualityPolicy())
    val appNotifications by rememberEntry(notifications, app.notificationKey)
    AppRow(
        app = app,
        icon = icon,
        notifications = appNotifications.orEmpty(),
        showIcon = showIcon,
        showPreview = false,
        large = false,
        iconSize = iconSize,
        onClick = { onLaunch(app, it) },
        onLongClick = { onLongPress(app) },
        onNotificationClick = { onOpenNotification(app, it) },
        modifier = modifier.then(ListRowPadding),
        expanded = expanded,
        onToggleExpand = { onToggleExpand(expandKey) },
    )
}

/**
 * The tint over the wallpaper: light on the home page and heavier once the list covers it ([progress], read only while
 * drawing), with a soft wash behind the clock so it reads on bright skies. The wash fades as the list scrolls up.
 */
internal fun Modifier.homeScrim(scrim: Color, progress: () -> Float) = drawWithCache {
    val wash = Brush.verticalGradient(listOf(scrim.copy(alpha = 0.22f), Color.Transparent), endY = size.height * 0.4f)
    onDrawBehind {
        val p = progress()
        drawRect(scrim, alpha = 0.12f + 0.58f * p)
        drawRect(wash, alpha = 1f - p)
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
    // Kept as states: each row reads its own entry (rememberEntry), so a new icon map or a notification regroup
    // recomposes only the rows whose entry changed, never this whole screen.
    val icons = launcher.repository.icons.collectAsStateWithLifecycle()
    val settings by launcher.prefs.settings.collectAsStateWithLifecycle()
    val notifications = NotificationStore.byApp.collectAsStateWithLifecycle()
    val nowPlaying by NowPlaying.state.collectAsStateWithLifecycle()
    ReportDrawnWhen { apps.isNotEmpty() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sheetApp by remember { mutableStateOf<AppEntry?>(null) }
    var renameApp by remember { mutableStateOf<AppEntry?>(null) }
    var homeMenuOpen by remember { mutableStateOf(false) }
    // Row whose notifications are swiped open; "fav:" and "all:" prefixes keep the two lists apart.
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val toggleExpand: (String) -> Unit = { key -> expandedKey = if (expandedKey == key) null else key }
    val showSheet: (AppEntry) -> Unit = { sheetApp = it }

    val wallpaperDarkText = rememberWallpaperSupportsDarkText()
    val darkText = when (settings.textColor) {
        TextColor.AUTO -> wallpaperDarkText
        TextColor.LIGHT -> false
        TextColor.DARK -> true
    }
    // Only the primary color is needed, and the dynamic scheme reads dozens of system colors: build it when the text
    // mode or the configuration changes, not on every recomposition.
    val configuration = LocalConfiguration.current
    val accent = remember(darkText, context, configuration) { colorScheme(context, dark = !darkText).primary }
    val style = remember(darkText, accent) { LauncherStyle(darkText, accent) }
    // Set when the text mode changes, in the apply phase like a SideEffect, so the first frame already has it. It
    // registers nothing, so BATTERY.md's LifecycleStartEffect rule for listeners doesn't apply.
    DisposableEffect(darkText) {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = darkText
                isAppearanceLightNavigationBars = darkText
            }
        }
        onDispose {}
    }

    // Read during composition for the first frame. Composed while resumed, the ON_RESUME below is replayed in the same
    // frame, so it skips these two binder calls once; every later resume reads them again.
    var isDefault by remember { mutableStateOf(LauncherActions.isDefaultLauncher(context)) }
    var hasNotificationAccess by remember { mutableStateOf(LauncherActions.hasNotificationAccess(context)) }
    val replayedResume = rememberReplaySkip(Lifecycle.State.RESUMED)
    var defaultPromptHidden by rememberSaveable { mutableStateOf(false) }
    // Music player: the playing app's favorite row turns into it. A player that is paused when you come home rests.
    val media = nowPlaying?.takeIf { settings.showMediaControls }
    val mediaApp = remember(apps, media?.packageName) {
        media?.let { m -> apps.firstOrNull { it.packageName == m.packageName && !it.isWork } }
    }
    var mediaResting by remember { mutableStateOf(false) }
    // Home showed before any session was known (the listener connects late): the first one to appear decides.
    var restPending by remember { mutableStateOf(false) }
    // Reads the live state: on a recreated activity this runs after ON_RESUME's refresh(), while `media` is still stale.
    LaunchedEffect(media?.isPaused) {
        val live = NowPlaying.state.value
        if (restPending && live != null) {
            restPending = false
            mediaResting = live.isPaused
        } else if (live?.isPaused == false) {
            mediaResting = false
        }
    }
    // The temporary player's notification list belongs to one session; don't carry it over to the next.
    LaunchedEffect(media?.sessionId) { if (expandedKey == "media") expandedKey = null }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!replayedResume.consume()) {
            isDefault = LauncherActions.isDefaultLauncher(context)
            hasNotificationAccess = LauncherActions.hasNotificationAccess(context)
        }
        NowPlaying.refresh()
        if (launcher.prefs.settings.value.autoUpdateCheck) Updater.check(context)
        val live = NowPlaying.state.value
        mediaResting = live?.isPaused == true
        restPending = live == null
    }
    val update by Updater.state.collectAsStateWithLifecycle()
    val dismissedUpdate by remember { Updater.dismissed(context) }.collectAsStateWithLifecycle()
    val showUpdate = when (val u = update) {
        is Updater.State.Available -> u.release.tag != dismissedUpdate
        is Updater.State.Downloading, is Updater.State.Installing -> true
        is Updater.State.Failed -> u.release != null && u.release.tag != dismissedUpdate
        else -> false
    }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = LauncherActions.isDefaultLauncher(context)
        // The role dialog is skipped silently after repeated refusals; fall back to the settings page.
        if (!isDefault) LauncherActions.openHomeSettings(context)
    }

    val byKey = remember(apps) { apps.associateBy { it.key } }
    // Collapse for good once the expanded row has no notifications left, so the next one doesn't reopen it by itself.
    // Watched from inside the effect, so neither expanding a row nor a regroup recomposes this whole screen.
    val currentByKey by rememberUpdatedState(byKey)
    val currentMediaApp by rememberUpdatedState(mediaApp)
    LaunchedEffect(Unit) {
        snapshotFlow {
            val key = expandedKey ?: return@snapshotFlow false
            val app = if (key == "media") currentMediaApp else currentByKey[key.substringAfter(':')]
            app == null || notifications.value[app.notificationKey].isNullOrEmpty()
        }.collect { emptied -> if (emptied) expandedKey = null }
    }
    val favorites = remember(byKey, settings.favorites) { settings.favorites.mapNotNull { byKey[it] } }
    val rows = remember(apps, settings.hidden) { buildRows(apps.filter { it.key !in settings.hidden }) }
    val letterRows = remember(rows) {
        buildMap { rows.forEachIndexed { i, row -> if (row is Row.Section && row.letter !in this) put(row.letter, i + FIRST_APP_ROW) } }
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
            // Its own coroutine: a touch that interrupts the scroll would otherwise end this collector for good.
            scope.launch { if (listState.firstVisibleItemIndex > 4) listState.scrollToItem(0) else listState.animateScrollToItem(0) }
        }
    }
    // Back is always ours on the home screen: before Android 12 the default finishes the home activity.
    BackHandler(enabled = !searchOpen) {
        expandedKey = null
        if (!atTop) scope.launch { listState.animateScrollToItem(0) }
    }

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
            // Side nav bar (3-button, landscape) and side cutouts.
            val layoutDirection = LocalLayoutDirection.current
            val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
            val startInset = sideInsets.calculateStartPadding(layoutDirection)
            val endInset = sideInsets.calculateEndPadding(layoutDirection)
            val homeHeight = maxHeight - statusTop
            val homeHeightPx = with(density) { homeHeight.toPx() }.coerceAtLeast(1f)
            // 0 on the home page, 1 once the app list covers the screen. Read only while drawing.
            val progress = {
                if (listState.firstVisibleItemIndex > 0) 1f
                else (listState.firstVisibleItemScrollOffset / homeHeightPx).coerceIn(0f, 1f)
            }

            Box(Modifier.fillMaxSize().homeScrim(style.scrim, progress))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // Hidden from TalkBack while search covers it.
                    .then(if (searchOpen) Modifier.clearAndSetSemantics {} else Modifier)
                    .nestedScroll(pullDown)
                    // Fade rows out as they slide under the status bar. Hidden under search, whose scrim is see-through.
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = if (searchOpen) 0f else 1f
                    }
                    // Redrawn on every scroll frame; the gradient is built once per size.
                    .drawWithCache {
                        val top = statusTop.toPx()
                        val fadeEnd = top + 20.dp.toPx()
                        val fade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = top * 0.5f, endY = fadeEnd)
                        val fadeSize = Size(size.width, fadeEnd)
                        onDrawWithContent {
                            drawContent()
                            drawRect(fade, size = fadeSize, blendMode = BlendMode.DstIn)
                        }
                    },
                contentPadding = PaddingValues(start = startInset, top = statusTop, end = endInset, bottom = navBottom + 16.dp),
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
                        onAppLongPress = showSheet,
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
                                showUpdate -> UpdateCard(
                                    state = update,
                                    onUpdate = { Updater.install(context, it) },
                                    onDismiss = { Updater.dismiss(context, it) },
                                )
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
                        is Row.App -> ListAppRow(
                            app = row.app,
                            expandKey = row.expandKey,
                            icons = icons,
                            notifications = notifications,
                            showIcon = settings.showIcons,
                            iconSize = settings.iconSize.listDp.dp,
                            expanded = expandedKey == row.expandKey,
                            onLaunch = launch,
                            onLongPress = showSheet,
                            onOpenNotification = openNotification,
                            onToggleExpand = toggleExpand,
                        )
                    }
                }
            }

            // Keeps the status bar readable once the list scrolls under it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(statusTop + 16.dp)
                    .drawWithCache {
                        val scrim = Brush.verticalGradient(listOf(style.scrim.copy(alpha = 0.5f), Color.Transparent))
                        onDrawBehind { drawRect(scrim, alpha = if (searchOpen) 0f else progress()) }
                    },
            )

            if (letters.isNotEmpty() && !searchOpen) {
                AlphabetWave(
                    letters = letters,
                    onLetter = { letter -> letterRows[letter]?.let { index -> scope.launch { listState.scrollToItem(index) } } },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = statusTop, bottom = navBottom, end = endInset)
                        .fillMaxHeight(0.8f),
                )
            }

            if (searchOpen) {
                SearchOverlay(
                    apps = apps,
                    icons = icons.value,
                    showIcons = settings.showIcons,
                    iconSize = settings.iconSize.listDp.dp,
                    onLaunch = { app, bounds ->
                        launch(app, bounds)
                        searchOpen = false
                    },
                    onLongPress = showSheet,
                    onDismiss = { searchOpen = false },
                )
            }
        }
    }

    sheetApp?.let { app ->
        val icon by rememberEntry(icons, app.key, referentialEqualityPolicy())
        val appNotifications by rememberEntry(notifications, app.notificationKey)
        AppActionsSheet(
            app = app,
            icon = icon,
            isFavorite = app.key in settings.favorites,
            isHidden = app.key in settings.hidden,
            notifications = appNotifications.orEmpty(),
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
