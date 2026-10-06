package com.gh00ul.cascade.ui.home

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gh00ul.cascade.data.IconImage
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.calculate
import com.gh00ul.cascade.data.dialIntent
import com.gh00ul.cascade.data.messageIntent
import com.gh00ul.cascade.data.searchApps
import com.gh00ul.cascade.data.viewIntent
import com.gh00ul.cascade.ui.theme.LocalLauncherStyle
import com.gh00ul.cascade.ui.theme.Motion
import com.gh00ul.cascade.util.LauncherActions
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/** The search pills' start padding inside the pill: tight with icons, where [SearchGlyph] centers the glyph itself. */
internal fun searchPillStart(showIcons: Boolean) = if (showIcons) 8.dp else 16.dp

/**
 * The search pills' leading glyph and the gap after it. With icons, the glyph is centered in an [iconSize]-wide box
 * on the rows' icon column, so the pill's text starts where app labels do; without, the pill keeps its compact layout.
 */
@Composable
internal fun SearchGlyph(showIcons: Boolean, iconSize: Dp) {
    if (showIcons) {
        Box(Modifier.width(iconSize), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Search, contentDescription = null) }
        Spacer(Modifier.width(16.dp))
    } else {
        Icon(Icons.Filled.Search, contentDescription = null)
        Spacer(Modifier.width(12.dp))
    }
}

/** The rows' shape: their press ripple, and the tint on the row Go acts on. */
internal val EnterTargetShape = RoundedCornerShape(16.dp)

/** A soft tint on the row that Go opens, the same shape as the rows' press ripple. */
internal fun Modifier.enterTarget(content: Color) = background(content.copy(alpha = 0.08f), EnterTargetShape)

/** The pill drops in from a little above as search opens, and lifts away, shorter and quicker, as it closes. */
private val PillEnter = slideInVertically(tween(Motion.SCREEN, easing = Motion.Decelerate)) { -it / 3 } +
    fadeIn(tween(Motion.ENTER, easing = Motion.Decelerate))
private val PillExit = slideOutVertically(tween(Motion.QUICK, easing = Motion.Accelerate)) { -it / 6 } + fadeOut(tween(Motion.EXIT))

/** How much a predictive back gesture shrinks and dims the overlay at full progress. */
private const val OverlayBackShrink = 0.08f
private const val OverlayBackDim = 0.2f

/**
 * The home screen's alpha under search, from the search transition (target: open): it gets out of the way quickly as
 * search fades in over it, and fades back in as search closes. Read it only while drawing.
 */
@Composable
internal fun Transition<Boolean>.animateHomeAlpha(): State<Float> =
    animateFloat(transitionSpec = { if (targetState) Motion.LayerFadeOut else Motion.LayerFadeIn }, label = "homeAlpha") { open ->
        if (open) 0f else 1f
    }

/**
 * The first results after search opens fade in one row after another; anything later (another query, a row scrolled
 * in) shows at once, so typing never waits on an animation. Each row asks once, when it is first composed.
 */
private class ResultsStagger {
    /** The first non-empty results since search opened. */
    var batch: List<AppEntry>? = null
    /** Set a frame after [batch] is first laid out. */
    var done = false

    /** The fade-in delay of the row at [index] of [results], or -1 to show it at once. */
    fun delayFor(results: List<AppEntry>, index: Int) =
        if (done || results !== batch) -1 else min(index, Motion.STAGGER_ROWS) * Motion.STAGGER
}

/** Fades a result row in, [delay] ms after it is first composed (a spec delay, so it scales); negative shows it as is. */
@Composable
private fun Modifier.staggered(delay: Int): Modifier {
    if (delay < 0) return this
    val fade = remember { Animatable(0f) }
    LaunchedEffect(fade) { fade.animateTo(1f, tween(Motion.ENTER, delay, Motion.Decelerate)) }
    return graphicsLayer { alpha = fade.value }
}

/**
 * Full-screen search, inside the AnimatedVisibility of LauncherScreen's search transition: the pill drops in as it
 * opens, and the first results fade in one after another. Apps in [excluded] never show up (hidden apps, unless
 * Settings lets search find them). Enter opens the top hit, or searches the web with [searchWeb]; that row is tinted
 * while there's a query. With [autoLaunchSingleMatch], typing that leaves exactly one app opens it. On Android 13+ the
 * overlay shrinks with a predictive back gesture, and closes from there.
 *
 * With [searchCalculator], arithmetic gets its answer above the apps, and Enter copies it. With [searchContacts]
 * (and READ_CONTACTS), up to four contacts follow the apps, looked up once typing pauses.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimatedVisibilityScope.SearchOverlay(
    apps: List<AppEntry>,
    icons: Map<String, IconImage>,
    showIcons: Boolean,
    iconSize: Dp,
    excluded: Set<String>,
    searchWeb: Boolean,
    autoLaunchSingleMatch: Boolean,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    searchCalculator: Boolean = true,
    searchContacts: Boolean = false,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, apps, excluded) { searchApps(apps, query, excluded) }
    val calculation = remember(query, searchCalculator) { if (searchCalculator) calculate(query) else null }
    // Whether the last edit made the query longer: only typing opens a lone match, never a deletion, Clear, or a
    // query kept from before.
    var grew by remember { mutableStateOf(false) }
    if (autoLaunchSingleMatch) {
        LaunchedEffect(query) {
            // Arithmetic is answered, never launched.
            if (grew && calculation == null && query.trim().length >= 2) results.singleOrNull()?.let { onLaunch(it, null) }
        }
    }
    val style = LocalLauncherStyle.current
    val scope = rememberCoroutineScope()
    // False from the moment search starts to close, while it still fades out.
    val open = transition.targetState == EnterExitState.Visible
    // Photos only where icons show, at their size.
    val photoPx = if (showIcons) with(LocalDensity.current) { iconSize.roundToPx() } else 0
    val contacts = rememberContactResults(query, enabled = searchContacts, open = open, photoPx = photoPx)
    // Opening a contact, the dialer or a message closes search, as opening an app does.
    val startContact = { intent: Intent, failure: String -> if (startFromSearch(context, intent, failure)) onDismiss() }
    // 0 at rest; follows a predictive back gesture. Read only in the overlay's layer.
    val backProgress = remember { Animatable(0f) }
    val stagger = remember { ResultsStagger() }
    if (stagger.batch == null && results.isNotEmpty()) stagger.batch = results
    stagger.batch?.let { batch ->
        // Not at once: on a device this would run before the first results are laid out, and none would fade.
        LaunchedEffect(batch) {
            withFrameNanos {}
            stagger.done = true
        }
    }

    fun submit() {
        val top = results.firstOrNull()
        if (calculation != null) {
            // An answer is what was asked for, even when an app matches too.
            copyAnswer(context, calculation)
        } else if (top != null) {
            onLaunch(top, null)
        } else if (searchWeb && query.isNotBlank()) {
            // Stays open when no app could take the search, so the query isn't lost.
            if (LauncherActions.webSearch(context, query.trim())) onDismiss()
        }
    }

    if (Build.VERSION.SDK_INT >= 33) {
        PredictiveBackHandler(enabled = open) { events ->
            try {
                events.collect { backProgress.snapTo(it.progress) }
            } catch (e: CancellationException) {
                // This coroutine is cancelled, so the spring back is launched outside it.
                if (backProgress.value != 0f) scope.launch { backProgress.animateTo(0f, Motion.SwipeBack) }
                throw e
            }
            // The exit fades the overlay out from where the gesture left it.
            onDismiss()
        }
    } else {
        BackHandler(enabled = open, onBack = onDismiss)
    }
    // Typing starts at once, and the keyboard goes as soon as search starts to close, not once it has faded out.
    LaunchedEffect(open) {
        if (open) {
            focus.requestFocus()
            keyboard?.show()
            // Reopened while closing after a back gesture: grow back from where it was.
            if (backProgress.value != 0f) backProgress.animateTo(0f, Motion.SwipeBack)
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = backProgress.value
                scaleX = 1f - OverlayBackShrink * p
                scaleY = 1f - OverlayBackShrink * p
                alpha = 1f - OverlayBackDim * p
            }
            // A pane for TalkBack; it also keeps the home screen underneath out of reach.
            .semantics { paneTitle = "Search" }
            .background(style.scrim.copy(alpha = 0.94f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Where the status bar is even while it's hidden, so the pill stays put and clear of a camera cutout.
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .navigationBarsPadding()
                .imePadding()
                .padding(top = 12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = style.content.copy(alpha = 0.12f),
                contentColor = style.content,
                modifier = Modifier
                    .animateEnterExit(PillEnter, PillExit)
                    .fillMaxWidth()
                    // The results' side inset, so the field's edges line up with the rows below.
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 52.dp)
                    // Taps on the icon or padding go to the field, not through to the dismiss handler behind.
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focus.requestFocus()
                            keyboard?.show()
                        }
                    },
            ) {
                Row(Modifier.padding(start = searchPillStart(showIcons), end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchGlyph(showIcons, iconSize)
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            grew = autoLaunchSingleMatch && it.length > query.length
                            query = it
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = style.content, fontSize = 17.sp),
                        cursorBrush = SolidColor(style.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focus),
                        // The placeholder sits inside the field so TalkBack reads it as the field's name.
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        if (searchWeb) "Search apps or the web" else "Search apps",
                                        color = style.content.copy(alpha = 0.55f),
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                if (calculation != null) {
                    // Shows at once, as anything after the first results does: it changes with every keystroke.
                    item(key = "calculation") {
                        CalculationRow(
                            calculation = calculation,
                            showIcon = showIcons,
                            iconSize = iconSize,
                            onCopy = { copyAnswer(context, calculation) },
                            // Go copies it.
                            modifier = Modifier.enterTarget(style.content),
                        )
                    }
                }
                itemsIndexed(results, key = { _, app -> app.key }) { index, app ->
                    val delay = remember { stagger.delayFor(results, index) }
                    AppRow(
                        app = app,
                        icon = icons[app.key],
                        notifications = emptyList(),
                        showIcon = showIcons,
                        showPreview = false,
                        large = false,
                        iconSize = iconSize,
                        onClick = { onLaunch(app, it) },
                        onLongClick = { onLongPress(app) },
                        onNotificationClick = {},
                        // Go opens the top hit, unless there's an answer to copy; a blank query has no results, so this
                        // is only ever set with a query.
                        modifier = Modifier.staggered(delay).then(if (index == 0 && calculation == null) Modifier.enterTarget(style.content) else Modifier),
                    )
                }
                // They come a moment after the apps, so they show at once rather than fading in late.
                items(contacts, key = { "contact:${it.contact.id}" }) { result ->
                    ContactRow(
                        result = result,
                        showIcon = showIcons,
                        iconSize = iconSize,
                        onOpen = { startContact(result.contact.viewIntent(), "Couldn't open ${result.contact.name}") },
                        onMessage = { startContact(messageIntent(it), "No app can send a message") },
                        onCall = { startContact(dialIntent(it), "No app can make a call") },
                    )
                }
                if (searchWeb && query.isNotBlank()) {
                    item(key = "web") {
                        // Last of the first results, when it comes with them.
                        val delay = remember { stagger.delayFor(results, results.size) }
                        Row(
                            Modifier
                                .staggered(delay)
                                .fillMaxWidth()
                                // With no app hits (and no answer), Go searches the web.
                                .then(if (results.isEmpty() && calculation == null) Modifier.enterTarget(style.content) else Modifier)
                                .clip(EnterTargetShape)
                                .clickable { if (LauncherActions.webSearch(context, query.trim())) onDismiss() }
                                .padding(horizontal = 8.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = style.content.copy(alpha = 0.7f))
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "Search the web for “${query.trim()}”",
                                style = style.app.copy(fontSize = 17.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
