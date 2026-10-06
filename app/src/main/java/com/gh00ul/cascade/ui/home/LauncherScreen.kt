package com.gh00ul.cascade.ui.home

import com.gh00ul.cascade.update.Updater
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.DoubleTapAction
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.SwipeDownAction
import com.gh00ul.cascade.data.TextColor
import com.gh00ul.cascade.launcher
import com.gh00ul.cascade.notifications.AppNotification
import com.gh00ul.cascade.notifications.NotificationStore
import com.gh00ul.cascade.notifications.NowPlaying
import com.gh00ul.cascade.settings.SettingsActivity
import com.gh00ul.cascade.settings.SettingsScreen
import com.gh00ul.cascade.ui.common.rememberEntry
import com.gh00ul.cascade.notifications.LastPlayed
import com.gh00ul.cascade.notifications.LastPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.gh00ul.cascade.ui.common.rememberReplaySkip
import com.gh00ul.cascade.ui.theme.LauncherStyle
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import com.gh00ul.cascade.ui.theme.colorScheme
import com.gh00ul.cascade.ui.theme.rememberWallpaperSupportsDarkText
import com.gh00ul.cascade.util.LauncherActions
import com.gh00ul.cascade.util.LockService
import com.gh00ul.cascade.util.sendFromLauncher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Index of the first A–Z row: the home page and the all-apps header come before it. */
private const val FIRST_APP_ROW = 2

/** Which card the home page shows under the clock: AnimatedContent's key, small and stable, never the cards' state. */
private enum class HomeCardSlot { NONE, UPDATE, DEFAULT, ACCESS }

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
 * [dim] (Settings' wallpaper dim) is layered onto the light tint, and the list's tint rises from there to its usual
 * level, or stays at the dim if that's heavier, so the two never stack.
 */
internal fun Modifier.homeScrim(scrim: Color, dim: Float, progress: () -> Float) = drawWithCache {
    val wash = Brush.verticalGradient(listOf(scrim.copy(alpha = 0.22f), Color.Transparent), endY = size.height * 0.4f)
    val rest = 0.12f + 0.88f * dim
    val covered = maxOf(0.7f, rest)
    onDrawBehind {
        val p = progress()
        drawRect(scrim, alpha = rest + (covered - rest) * p)
        drawRect(wash, alpha = 1f - p)
    }
}

/** LocalHapticFeedback with haptics turned off in Settings. */
private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {}
}

/** How much smaller the list starts when home settles in, and how far its alpha starts below 1 (it never vanishes). */
private const val SettleShrink = 0.05f
private const val SettleFade = 0.7f
/** The share of the settle by which the list is fully opaque again; the rest only finishes the scale. */
private const val SettleOpaqueBy = 0.6f
/** Around the favorites, so the rows you reach for move least while the rest of the list grows in around them. */
private val SettleOrigin = TransformOrigin(0.5f, 0.75f)
/** How much a predictive back gesture shrinks the list at full progress. */
private const val ListBackShrink = 0.04f

/**
 * Home settling in as you come back to it: runs 0 to 1 over [Motion.Settle] on each ON_START after a stop (an app
 * closed, the screen turned on), read only in the list's layer. Not on the first composition (the start that composing
 * replays is skipped), a recomposition, or a pause without a stop (a dialog-like activity on top), so a cold start and
 * the role dialog don't play it. It starts undispatched, so the first frame after a return already draws the start.
 */
@Composable
internal fun rememberReturnSettle(): Animatable<Float, AnimationVector1D> {
    val settle = remember { Animatable(1f) }
    val replayedStart = rememberReplaySkip(Lifecycle.State.STARTED)
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!replayedStart.consume()) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                settle.snapTo(0f)
                settle.animateTo(1f, Motion.Settle)
            }
        }
    }
    return settle
}

/**
 * Home and back both come here. Close by and in view, the list scrolls up. Deep in the list, or while it isn't being
 * seen (home stopped, or just back and still settling in), it jumps instead; a jump in view plays [settle] again, so
 * the top grows in rather than cutting in.
 */
private suspend fun LazyListState.goToTop(settle: Animatable<Float, AnimationVector1D>, lifecycle: Lifecycle) {
    val seen = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !settle.isRunning
    if (seen && firstVisibleItemIndex <= 4) {
        animateScrollToItem(0)
    } else {
        scrollToItem(0)
        if (seen) {
            settle.snapTo(0f)
            settle.animateTo(1f, Motion.Settle)
        }
    }
}

/**
 * Back on the home screen while there is somewhere to go back to: [enabled] is read here, so only this recomposes as
 * it flips. On Android 13+ a predictive back gesture shrinks the list a little ([scale], read in its layer) as it's
 * dragged; letting go runs [onBack] and eases the list back, and cancelling springs it back.
 */
@Composable
internal fun ListBackHandler(enabled: () -> Boolean, scale: Animatable<Float, AnimationVector1D>, onBack: () -> Unit) {
    val on = enabled()
    if (Build.VERSION.SDK_INT >= 33) {
        val scope = rememberCoroutineScope()
        PredictiveBackHandler(on) { events ->
            try {
                events.collect { scale.snapTo(1f - ListBackShrink * it.progress) }
            } catch (e: CancellationException) {
                // This coroutine is cancelled, so the spring back is launched outside it.
                if (scale.value != 1f) scope.launch { scale.animateTo(1f, Motion.SwipeBack) }
                throw e
            }
            // Nothing here suspends: onBack disables this handler, which would cancel the rest. A plain back (no
            // gesture) left the scale alone and has nothing to ease.
            onBack()
            if (scale.value != 1f) scope.launch { scale.animateTo(1f, Motion.Settle) }
        }
    } else {
        BackHandler(on, onBack)
    }
}

/**
 * One continuous list, like Niagara: the home page (clock + favorites) fills the first screen and the full
 * A–Z app list continues below it. The alphabet wave on the right edge jumps anywhere in the list.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Both read only in the list's layer: the settle on each return home, and a predictive back gesture's shrink.
    val settle = rememberReturnSettle()
    val backScale = remember { Animatable(1f) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Drives search fading in over the home screen, and the list and alphabet strip getting out of its way.
    val searchTransition = updateTransition(searchOpen, label = "search")
    val listAlpha = searchTransition.animateHomeAlpha()
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
    // Applied again on each start: before Android 11 the system drops the hidden flag once another app is in front. It
    // registers nothing. A swipe from the top edge still shows the bar for a moment.
    LifecycleStartEffect(settings.hideStatusBar) {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (settings.hideStatusBar) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
            }
        }
        onStopOrDispose {}
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
    // The first-run hint follows the visible rows, but until the list first loads it trusts the stored keys so it doesn't flash.
    val showFavoritesHint = favorites.isEmpty() && (settings.favorites.isEmpty() || apps.isNotEmpty())
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

    // Listen mode: headphones on and nothing playing offers the app that played last. "Not now" holds for the devices
    // connected then; the next headphones (or the same ones, reconnected) bring it back.
    val lastPlayed by LastPlayer.state.collectAsStateWithLifecycle()
    val listening = rememberListeningDevices(settings.resumePrompt)
    val resumeApp = remember(apps, lastPlayed?.packageName) {
        lastPlayed?.let { p -> apps.firstOrNull { it.packageName == p.packageName && !it.isWork } }
    }
    var resumeDismissedFor by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var resuming by remember { mutableStateOf(false) }
    val showResume = resumeApp != null && media == null && nowPlaying?.isPlaying != true &&
        listening.any { it !in resumeDismissedFor }
    // The player replaces the row once the app plays. If it hasn't within a few seconds (the app ignored the request),
    // open the app so play is one tap away there.
    LaunchedEffect(resuming) {
        if (!resuming) return@LaunchedEffect
        val app = resumeApp
        val started = withTimeoutOrNull(4_000) { NowPlaying.state.first { it?.isPlaying == true } }
        if (started == null && app != null) launch(app, null)
        resuming = false
    }

    LaunchedEffect(homePresses) {
        homePresses.collect {
            searchOpen = false
            sheetApp = null
            renameApp = null
            homeMenuOpen = false
            expandedKey = null
            // Its own coroutine: a touch that interrupts the scroll would otherwise end this collector for good.
            scope.launch { listState.goToTop(settle, lifecycle) }
        }
    }
    // Back closes an open row and goes to the top. With nothing to do it falls through to MainActivity's own callback,
    // which keeps it on the home screen: before Android 12 the default finishes the home activity.
    ListBackHandler(
        enabled = { !searchOpen && (expandedKey != null || !atTop) },
        scale = backScale,
        onBack = {
            expandedKey = null
            scope.launch { listState.goToTop(settle, lifecycle) }
        },
    )

    val swipeDownAction by rememberUpdatedState(settings.swipeDownAction)
    val pullDown = remember {
        PullDownConnection(threshold = with(density) { 64.dp.toPx() }) {
            // A shade that won't open falls back to the next thing down: quick settings, notifications, then search.
            when (swipeDownAction) {
                SwipeDownAction.NOTIFICATIONS -> if (!LauncherActions.expandNotifications(context)) searchOpen = true
                SwipeDownAction.QUICK_SETTINGS ->
                    if (!LauncherActions.expandQuickSettings(context) && !LauncherActions.expandNotifications(context)) searchOpen = true
                SwipeDownAction.SEARCH -> searchOpen = true
                SwipeDownAction.NOTHING -> {}
            }
        }
    }
    // Locking needs Cascade's accessibility service; while it's off, the double-tap opens Settings to turn it on.
    val emptyDoubleTap: () -> Unit = {
        when (settings.doubleTapAction) {
            DoubleTapAction.NOTHING -> {}
            DoubleTapAction.LOCK_SCREEN -> if (!LockService.lock()) SettingsActivity.open(context, SettingsScreen.GESTURES)
            DoubleTapAction.NOTIFICATIONS -> LauncherActions.expandNotifications(context)
            DoubleTapAction.SEARCH -> searchOpen = true
        }
    }
    // With haptics off, one no-op stands in for every haptic on home: rows, the alphabet wave and the player all go
    // through LocalHapticFeedback. Sheets and dialogs are windows of their own, which provide their own (they play none).
    val haptics = if (settings.haptics) LocalHapticFeedback.current else NoHaptics

    CompositionLocalProvider(LocalLauncherStyle provides style, LocalContentColor provides style.content, LocalHapticFeedback provides haptics) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Where the status bar is even while it's hidden, so nothing moves as it hides or shows.
            val statusTop = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
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

            Box(Modifier.fillMaxSize().homeScrim(style.scrim, settings.wallpaperDim.alpha, progress))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // Hidden from TalkBack while search covers it.
                    .then(if (searchOpen) Modifier.clearAndSetSemantics {} else Modifier)
                    .nestedScroll(pullDown)
                    // Fade rows out as they slide under the status bar. Hidden under search, whose scrim is see-through.
                    // Settling in after a return, it grows from a little smaller and fainter around the favorites; a
                    // back gesture shrinks it. At rest every factor here is exactly 1.
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        val s = settle.value
                        val scale = (1f - SettleShrink * (1f - s)) * backScale.value
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = SettleOrigin
                        alpha = listAlpha.value * (1f - SettleFade * (1f - s / SettleOpaqueBy).coerceAtLeast(0f))
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
                        showFavoritesHint = showFavoritesHint,
                        icons = icons,
                        notifications = notifications,
                        settings = settings,
                        onLaunch = launch,
                        onAppLongPress = showSheet,
                        onOpenNotification = openNotification,
                        onEmptyLongPress = { homeMenuOpen = true },
                        onEmptyDoubleTap = emptyDoubleTap,
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
                        resume = {
                            // The last row and track shown, so the row still has something to draw while it folds away.
                            val shown = remember { Latest<Pair<AppEntry, LastPlayed>>() }.also {
                                val p = lastPlayed
                                if (showResume && resumeApp != null && p != null) it.value = resumeApp to p
                            }
                            AnimatedVisibility(showResume, enter = Motion.ExpandUp, exit = Motion.CollapseDown) {
                                shown.value?.let { (app, played) ->
                                    val icon by rememberEntry(icons, app.key, referentialEqualityPolicy())
                                    ResumeRow(
                                        appLabel = app.label,
                                        icon = icon,
                                        played = played,
                                        showIcon = settings.showIcons,
                                        iconSize = settings.iconSize.homeDp.dp,
                                        monochrome = settings.showIcons && settings.monochromeIcons,
                                        resuming = resuming,
                                        onResume = {
                                            if (!resuming) {
                                                resuming = true
                                                LastPlayer.resume(context, app.packageName)
                                            }
                                        },
                                        onDismiss = { resumeDismissedFor = listening.toList() },
                                    )
                                }
                            }
                        },
                        onboarding = {
                            // Read in here, so granting access or the default role recomposes only this slot, not the screen.
                            val cardSlot = when {
                                showUpdate -> HomeCardSlot.UPDATE
                                !isDefault && !defaultPromptHidden -> HomeCardSlot.DEFAULT
                                !hasNotificationAccess && !settings.notificationPromptDismissed -> HomeCardSlot.ACCESS
                                else -> HomeCardSlot.NONE
                            }
                            // The update card's latest shown state, so it still has something to draw while it fades out.
                            val lastUpdate = remember { Latest<Updater.State>() }.also { if (showUpdate) it.value = update }
                            // One card at a time, cross-faded as one is dismissed or granted and the next takes its place.
                            // The first composition shows its card without animating; the update card's own steps
                            // (download, install) change in place under one key.
                            AnimatedContent(
                                targetState = cardSlot,
                                transitionSpec = { Motion.swap() },
                                label = "homeCard",
                            ) { slot ->
                                when (slot) {
                                    HomeCardSlot.UPDATE -> UpdateCard(
                                        state = lastUpdate.value ?: update,
                                        onUpdate = { Updater.install(context, it) },
                                        onDismiss = { Updater.dismiss(context, it) },
                                    )
                                    HomeCardSlot.DEFAULT -> OnboardingCard(
                                        title = "Make Cascade your home screen",
                                        body = "Set it as your default home app so the Home button brings you here.",
                                        action = "Set as default",
                                        onAction = { LauncherActions.requestDefaultLauncher(context, roleRequest) },
                                        onDismiss = { defaultPromptHidden = true },
                                    )
                                    HomeCardSlot.ACCESS -> OnboardingCard(
                                        title = "See notifications beside your apps",
                                        body = "Allow notification access to show a dot and the latest message under each favorite.",
                                        action = "Allow",
                                        onAction = { LauncherActions.openNotificationAccess(context) },
                                        onDismiss = { launcher.prefs.update { it.copy(notificationPromptDismissed = true) } },
                                    )
                                    // Full width, so a card coming or going only animates its height.
                                    HomeCardSlot.NONE -> Spacer(Modifier.fillMaxWidth())
                                }
                            }
                        },
                    )
                }
                item(key = "header", contentType = "header") {
                    AllAppsHeader(
                        iconSize = settings.iconSize.listDp.dp,
                        showIcons = settings.showIcons,
                        onSearch = { searchOpen = true },
                        onSettings = { SettingsActivity.open(context) },
                    )
                }
                items(rows, key = { it.key }, contentType = { if (it is Row.Section) 0 else 1 }) { row ->
                    when (row) {
                        is Row.Section -> SectionHeader(row.letter, settings.iconSize.listDp.dp, settings.showIcons)
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
                        onDrawBehind { drawRect(scrim, alpha = progress() * listAlpha.value) }
                    },
            )

            if (letters.isNotEmpty()) {
                // Gone while search is open, like the list under it.
                searchTransition.AnimatedVisibility(
                    visible = { open -> !open },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = statusTop, bottom = navBottom, end = endInset)
                        .fillMaxHeight(0.8f),
                    enter = Motion.LayerIn,
                    exit = Motion.LayerOut,
                ) {
                    AlphabetWave(
                        letters = letters,
                        onLetter = { letter -> letterRows[letter]?.let { index -> scope.launch { listState.scrollToItem(index) } } },
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }

            searchTransition.AnimatedVisibility(visible = { open -> open }, enter = Motion.LayerIn, exit = Motion.LayerOut) {
                SearchOverlay(
                    apps = apps,
                    icons = icons.value,
                    showIcons = settings.showIcons,
                    iconSize = settings.iconSize.listDp.dp,
                    excluded = if (settings.hiddenInSearch) emptySet() else settings.hidden,
                    searchWeb = settings.searchWeb,
                    autoLaunchSingleMatch = settings.autoLaunchSingleMatch,
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
