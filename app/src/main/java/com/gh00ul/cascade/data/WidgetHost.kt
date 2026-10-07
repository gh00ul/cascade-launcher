package com.gh00ul.cascade.data

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.gh00ul.cascade.launcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Lint's StaticFieldLeak: the host holds only the application context, and the activity in [viewsOwner] (and the views
// made for it) are let go in that activity's onDestroy, so no destroyed activity is kept.
/**
 * Android app widgets in the widget stack. One AppWidgetHost for the process, under a fixed host id, so the ids it
 * hands out stay good across restarts; the stack keeps them as [WIDGET_APP_PREFIX] + id.
 *
 * Widgets update only while the host listens: MainActivity calls [onStart] and [onStop], and it listens while home is
 * started and the stack holds an app widget (see BATTERY.md). Views are made for the activity that shows them and kept
 * while it lives, so swiping between widgets or scrolling the home page away and back doesn't rebuild them; they're
 * made with what pruning last found each widget to be, so showing one needn't ask the system on the main thread first.
 *
 * Adding one: [begin] allocates an id and binds it, or hands back the system's dialog that asks the user first
 * ([onBindResult]); [setUp] then runs the widget's own setup screen when it has one, whose result comes back through
 * MainActivity.onActivityResult to [onActivityResult]; a widget that's ready goes at the end of the stack, and a
 * cancelled or failed step frees the id. The setup screen's id is kept on disk until it answers, so a prune after a
 * process death leaves it alone. Taking one out of the stack ([remove]) frees its id too. Every call is on the main
 * thread except pruning, which runs on the IO pool.
 */
@SuppressLint("StaticFieldLeak")
object WidgetHost {
    /** The id the system files Cascade's widgets under. Never change it: every placed widget would be orphaned. */
    private const val HOST_ID = 0x0CA5
    /** The request code a widget's setup screen answers to in onActivityResult. */
    const val REQUEST_CONFIGURE = 0x0CA6
    /** The file that keeps the setup screen's id and when it was asked for ([setupRecord]). */
    private const val SETUP_FILE = "widget_setup"
    private const val SETUP_ID = "id"
    private const val SETUP_AT = "at"

    private var host: Host? = null
    private var started = false
    private var listening = false
    private var pruned = false
    /** The start that follows [pruneOnce], whose prune has just looked for dead widgets. */
    private var justPruned = false

    /** The add in progress: its id, which pruning leaves alone, and the widget it's for. */
    @Volatile private var pending: Int? = null
    private var pendingInfo: AppWidgetProviderInfo? = null
    /** Held while [begin] allocates an id and makes it [pending], so pruning never finds the id between the two. */
    private val allocating = Any()
    /** The id whose setup screen is open, waiting for its result. Pruning reads it too, off the main thread. */
    @Volatile private var configuring: Int? = null
    /** The activity paused since the setup screen was asked for, so its next resume is the return from it. */
    private var setupShown = false
    /** The activity that asked for the setup screen: only its own return counts, not another screen's resume. */
    private var setupOwner: WeakReference<Activity>? = null

    /** The activity [views] were made for, and the views, by id. */
    private var viewsOwner: Context? = null
    private val views = HashMap<Int, WidgetHostView>()
    /** What each app widget in the stack is, as pruning last looked it up off the main thread, by id. */
    private val infos = ConcurrentHashMap<Int, AppWidgetProviderInfo>()

    @Synchronized
    private fun host(context: Context): Host = host ?: Host(context.applicationContext).also { host = it }

    /** Home started: listen for updates when the stack has an app widget, and catch up on what changed meanwhile. */
    fun onStart(context: Context) {
        started = true
        listen(context)
        // The providers-changed callback only comes while listening, so an app uninstalled while home was away is
        // caught here, on every start but the one right after pruneOnce, which has just looked. Only dead widgets go:
        // freeing ids no widget holds is left to pruneOnce and to providers changing.
        if (justPruned) {
            justPruned = false
        } else if (context.launcher.prefs.settings.value.widgetStack.any { appWidgetId(it) != null }) {
            prune(context, freeUnused = false)
        }
    }

    /** Home stopped: no widget updates until it starts again. */
    fun onStop() {
        started = false
        if (listening) {
            listening = false
            runCatching { host?.stopListening() }
        }
    }

    private fun listen(context: Context) {
        if (!started || listening || context.launcher.prefs.settings.value.widgetStack.none { appWidgetId(it) != null }) return
        // A busy system can refuse (TransactionTooLargeException with many widgets); the views then just don't update.
        listening = runCatching { host(context).startListening() }.isSuccess
    }

    /**
     * Drops stack entries whose app widget the system no longer has (its app was uninstalled, or the id was lost in a
     * restore) and frees ids the host holds that no entry uses (an add that never finished), leaving an add still under
     * way alone, even one whose setup screen outlived the process. Once per process, from MainActivity.onCreate; the
     * host runs it again whenever the installed widget providers change.
     */
    fun pruneOnce(context: Context) {
        if (pruned) return
        pruned = true
        justPruned = true
        prune(context)
    }

    private fun prune(context: Context, freeUnused: Boolean = true) {
        val app = context.applicationContext
        val launcher = app.launcher
        launcher.scope.launch(Dispatchers.IO) {
            val manager = AppWidgetManager.getInstance(app)
            // Every lookup is made here, before the update, whose transform must stay quick: an id added meanwhile
            // wasn't looked up, so it stays.
            val gone = HashSet<Int>()
            for (id in launcher.prefs.settings.value.widgetStack.mapNotNull(::appWidgetId)) {
                // A failed lookup keeps the widget: only a definite "gone" drops it.
                val lookup = runCatching { manager.getAppWidgetInfo(id) }
                if (lookup.isFailure) continue
                val info = lookup.getOrNull()
                if (info != null) {
                    infos[id] = info
                } else {
                    infos.remove(id)
                    gone += id
                }
            }
            if (gone.isNotEmpty()) {
                launcher.prefs.update { it.copy(widgetStack = dropDeadAppWidgets(it.widgetStack) { id -> id !in gone }) }
            }
            if (!freeUnused) return@launch
            val host = host(app)
            val setup = setupRecord(app)
            for (id in runCatching { host.appWidgetIds }.getOrNull() ?: IntArray(0)) {
                // What's being added is read before the stack, for each id: commit() puts an id in the stack before it
                // lets go of these, so an id no longer being added is in the stack by the time this reads it.
                val adding = synchronized(allocating) { id == pending || id == configuring } || id == recordedSetup(setup, fresh = true)
                val used = adding || launcher.prefs.settings.value.widgetStack.any { appWidgetId(it) == id }
                if (!used) runCatching { host.deleteAppWidgetId(id) }
            }
        }
    }

    /**
     * The setup screen last asked for, kept on disk: a process death while it's up forgets [configuring], and then only
     * its answer, to home or to Settings, knows the id. A prune before that answer (home's onCreate, which comes first,
     * or a visit home with Settings' setup still up) must leave the id alone. The answer clears it; one that never
     * comes (Settings' task was cleared under it) stops counting after a day ([setupMayAnswer]).
     */
    private fun setupRecord(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SETUP_FILE, Context.MODE_PRIVATE)

    /** The id in [record], or null; with [fresh], only while its setup screen may still answer. */
    private fun recordedSetup(record: SharedPreferences, fresh: Boolean): Int? {
        val id = record.getInt(SETUP_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        return if (!fresh || setupMayAnswer(record.getLong(SETUP_AT, 0), SystemClock.elapsedRealtime())) id else null
    }

    /** What [id] is, or null when the system no longer has it. */
    fun info(context: Context, id: Int): AppWidgetProviderInfo? =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(id) }.getOrNull()

    /**
     * Starts adding [info], shown [widthDp] by [heightDp]: allocates an id and binds it if Cascade may without asking
     * (the user allowed it before, with "Always allow"). Null when it's bound, so go on with [setUp]; otherwise the
     * system's "Allow Cascade to create widgets" dialog, to launch for a result that goes to [onBindResult]. Null too
     * when the system won't hand out an id, with nothing to go on with.
     */
    fun begin(context: Context, info: AppWidgetProviderInfo, widthDp: Int, heightDp: Int): Intent? {
        // An add that never came back (its dialog was dismissed by a config change) gives way to this one.
        pending?.let { discard(context, it) }
        val id = synchronized(allocating) {
            runCatching { host(context).allocateAppWidgetId() }.getOrNull()?.also { pending = it }
        } ?: return null
        pendingInfo = info
        val options = sizeOptions(widthDp, heightDp)
        val bound = runCatching { AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, info.profile, info.provider, options) }
            .getOrDefault(false)
        return if (bound) null else Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
    }

    /** The bind dialog's answer: on to [setUp] when the user allowed it, else the id is freed. */
    fun onBindResult(activity: Activity, resultCode: Int): Boolean {
        val id = pending ?: return true
        if (resultCode != Activity.RESULT_OK) {
            discard(activity, id)
            return true
        }
        return setUp(activity)
    }

    /**
     * The bound widget in progress: opens its setup screen when it has one, through the system, which can start it
     * even when its app doesn't export it; the answer comes to [activity]'s onActivityResult with [REQUEST_CONFIGURE].
     * A widget without one goes straight into the stack. False, with the id freed, when the setup screen couldn't be
     * opened.
     */
    fun setUp(activity: Activity): Boolean {
        val id = pending ?: return true
        val info = pendingInfo
        if (info == null || !needsSetup(info)) {
            commit(activity, id)
            return true
        }
        configuring = id
        setupShown = false
        setupOwner = WeakReference(activity)
        setupRecord(activity).edit { putInt(SETUP_ID, id).putLong(SETUP_AT, SystemClock.elapsedRealtime()) }
        return runCatching { host(activity).startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null) }
            .onFailure { discard(activity, id) }
            .isSuccess
    }

    /** Whether [info] has a setup screen to run before it's placed. Android 12 lets a widget make it optional. */
    private fun needsSetup(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        if (Build.VERSION.SDK_INT >= 31) {
            val optional = AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL or AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
            if (info.widgetFeatures and optional == optional) return false
        }
        return true
    }

    /**
     * A setup screen's answer, from MainActivity.onActivityResult: places the widget when it was set up, frees its id
     * when it was cancelled. False for any other request.
     */
    fun onActivityResult(context: Context, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CONFIGURE) return false
        // After a process death the id comes back only in the answer, or, when the answer has none (the system closed
        // the screen, or it returned no data), in the setup's record.
        val id = configuring
            ?: data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)?.takeIf { it != -1 }
            ?: recordedSetup(setupRecord(context), fresh = false)
            ?: return true
        if (resultCode == Activity.RESULT_OK) commit(context, id) else discard(context, id)
        return true
    }

    /** The activity adding a widget paused: with a setup screen asked for, that's it coming up. */
    fun onPause() {
        if (configuring != null) setupShown = true
    }

    /**
     * The activity adding a widget resumed. Back from a setup screen with no answer delivered (Settings doesn't pass
     * results on), done can't be told from cancelled, so the widget is placed. MainActivity delivers answers before it
     * resumes, so there this finds nothing waiting; nor does the resume that follows asking for the setup screen.
     */
    fun onResume(context: Context) {
        val id = configuring
        // A setup screen abandoned along with its activity (Settings reopened from home clears its task) never returns:
        // another activity's resume mustn't place it unconfigured.
        if (id != null && setupShown && setupOwner?.get() === context) commit(context, id)
    }

    /** Puts [id] at the end of the stack, or frees it when the stack has filled up meanwhile. */
    private fun commit(context: Context, id: Int) {
        val entry = WIDGET_APP_PREFIX + id
        context.launcher.prefs.update { it.copy(widgetStack = addWidget(it.widgetStack, entry)) }
        finished(context, id)
        if (entry !in context.launcher.prefs.settings.value.widgetStack) discard(context, id) else listen(context)
    }

    /** Frees [id]: a failed or cancelled add, or a widget taken out of the stack. */
    private fun discard(context: Context, id: Int) {
        finished(context, id)
        views.remove(id)
        infos.remove(id)
        runCatching { host(context).deleteAppWidgetId(id) }
    }

    /** [id]'s add is over: pruning stops leaving it alone, so a placed widget must be in the stack before this. */
    private fun finished(context: Context, id: Int) {
        if (pending == id) {
            pending = null
            pendingInfo = null
        }
        if (configuring == id) {
            configuring = null
            setupShown = false
        }
        val record = setupRecord(context)
        if (recordedSetup(record, fresh = false) == id) record.edit { clear() }
    }

    /** Takes [entry] out of the stack; an app widget's id is freed with it. */
    fun remove(context: Context, entry: String) {
        context.launcher.prefs.update { it.copy(widgetStack = it.widgetStack - entry) }
        appWidgetId(entry)?.let { discard(context, it) }
    }

    /**
     * [id]'s view for [activity], made once and kept while that activity lives, or null when the system no longer has
     * the widget or the device has no widget service. It's made with what pruning last found the widget to be, so it's
     * looked up here, on the main thread, only when no prune has yet. The caller detaches it from its last parent
     * before adding it anywhere.
     */
    internal fun view(activity: Context, id: Int): WidgetHostView? {
        if (viewsOwner !== activity) {
            views.clear()
            viewsOwner = activity
            (activity as? LifecycleOwner)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    if (viewsOwner !== activity) return
                    views.clear()
                    viewsOwner = null
                    host?.forgetViews()
                }
            })
        }
        views[id]?.let { return it }
        val info = infos[id] ?: info(activity, id)?.also { infos[id] = it } ?: return null
        val view = runCatching { host(activity).createView(activity, id, info) as? WidgetHostView }.getOrNull() ?: return null
        views[id] = view
        return view
    }

    private class Host(context: Context) : AppWidgetHost(context, HOST_ID) {
        private val app = context

        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            WidgetHostView(context)

        // An app with widgets was installed, updated or removed: drop the widgets that went with it.
        override fun onProvidersChanged() = prune(app)

        /** Lets go of the views made for an activity that's gone; the next one makes its own. */
        fun forgetViews() = clearViews()
    }
}

/** Options that tell a provider the size it's shown at, in dp. */
private fun sizeOptions(widthDp: Int, heightDp: Int): Bundle = Bundle().apply {
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
    if (Build.VERSION.SDK_INT >= 31) {
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
    }
}

/**
 * An app widget on a stack page. It tells its provider the size it's laid out at, and a long press anywhere on it,
 * buttons included, calls [onLongPress] (the stack's edit sheet), as on other launchers. Areas that take no touches
 * leave the press to the stack itself.
 */
internal class WidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var reportedWidth = -1
    private var reportedHeight = -1
    private val longPress = Runnable {
        longPressed = true
        onLongPress?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        // Nothing here takes the press, so no more events come: the stack watches for the long press instead.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !handled) removeCallbacks(longPress)
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        // After a long press the rest of the gesture is ours, so the button under the finger isn't clicked too.
        return longPressed
    }

    // Lint's ClickableViewAccessibility: this performs no click. It only keeps the rest of a long-pressed gesture from
    // reaching the widget's own views; taps go to super, which calls performClick as usual.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = longPressed || super.onTouchEvent(event)

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        longPressed = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        val width = (w / density).roundToInt()
        val height = (h / density).roundToInt()
        if (width <= 0 || height <= 0 || (width == reportedWidth && height == reportedHeight)) return
        reportedWidth = width
        reportedHeight = height
        // After this layout pass: the provider may answer with new views at once.
        post {
            if (Build.VERSION.SDK_INT >= 31) {
                updateAppWidgetSize(Bundle(), listOf(SizeF(width.toFloat(), height.toFloat())))
            } else {
                @Suppress("DEPRECATION")
                updateAppWidgetSize(Bundle(), width, height, width, height)
            }
        }
    }
}

/** The app widget id in a stack [entry], or null for a built-in widget. */
fun appWidgetId(entry: String): Int? = if (entry.startsWith(WIDGET_APP_PREFIX)) entry.substring(WIDGET_APP_PREFIX.length).toIntOrNull() else null

/** Whether [entry] is a widget the stack knows how to show. */
fun isStackWidget(entry: String): Boolean = entry == WIDGET_CALENDAR || entry == WIDGET_WEATHER || appWidgetId(entry) != null

/** [stack] with [entry] at the end; unchanged when it's already there, the stack is full, or it isn't a widget. */
fun addWidget(stack: List<String>, entry: String): List<String> =
    if (entry in stack || stack.size >= MAX_STACK_WIDGETS || !isStackWidget(entry)) stack else stack + entry

/** [stack] with the widget at [from] moved to [to]; unchanged when either is out of range. */
fun moveWidget(stack: List<String>, from: Int, to: Int): List<String> =
    if (from !in stack.indices || to !in stack.indices || from == to) stack else stack.toMutableList().apply { add(to, removeAt(from)) }

/** How long a setup screen asked for is waited for, to keep its id from pruning across a process death. */
private const val SETUP_KEPT_MS = 24 * 60 * 60 * 1000L

/**
 * Whether a setup screen asked for at [askedAt] may still answer at [now], both [SystemClock.elapsedRealtime]: for a
 * day. A restart in between usually shows as the clock going back, and the day bounds the rest.
 */
internal fun setupMayAnswer(askedAt: Long, now: Long): Boolean = now - askedAt in 0..SETUP_KEPT_MS

/** [stack] without the app widgets [isAlive] says the system no longer has; built-in widgets always stay. */
fun dropDeadAppWidgets(stack: List<String>, isAlive: (Int) -> Boolean): List<String> =
    stack.filter { entry -> appWidgetId(entry)?.let(isAlive) ?: true }
