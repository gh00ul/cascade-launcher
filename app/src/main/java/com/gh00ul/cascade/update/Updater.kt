package com.gh00ul.cascade.update

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * Self-update from GitHub Releases: finds a newer release, downloads its APK, checks that it is Cascade signed
 * with the same key, and hands it to PackageInstaller. Android asks for confirmation unless it lets Cascade update
 * itself silently; once installed, the system restarts the home screen on the new version.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/gh00ul/cascade-launcher/releases/latest"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    /** Offline or rate-limited, a check fails without writing LAST_CHECK; this keeps it from retrying on every return home. */
    internal const val RETRY_INTERVAL_MS = 30 * 60 * 1000L
    /** The longest a rate limit holds off the next automatic check: GitHub's limit resets every hour. */
    private const val RATE_LIMIT_MAX_WAIT_MS = 61 * 60 * 1000L
    /** Far more than any latest-release answer; a longer one isn't GitHub's. */
    internal const val MAX_ANSWER_BYTES = 1024 * 1024
    /** Far more than any Cascade APK. */
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val MIB = 1024L * 1024
    private const val APK_FILE = "update.apk"
    private const val PREFS = "updater"
    private const val LAST_CHECK = "last_check"
    private const val DISMISSED = "dismissed_tag"
    /** The release, on the install's result broadcast, for a process started after Cascade died: see [InstallResultReceiver]. */
    internal const val EXTRA_TAG = "com.gh00ul.cascade.update.TAG"
    internal const val EXTRA_APK_URL = "com.gh00ul.cascade.update.APK_URL"
    private const val TOO_BIG = "The download is too big to be a Cascade update."
    private const val NO_SPACE = "Not enough storage for the update. Free up some space, then tap Try again."

    data class Release(val tag: String, val versionName: String, val versionCode: Long, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val versionName: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int?) : State
        /** Handed to Android; waiting for the user to confirm, or for the install to finish. */
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    /** What a check found out: a [CheckAnswer] when GitHub answered, a [CheckFailure] when it didn't. */
    internal sealed interface CheckOutcome

    /**
     * GitHub answered: [offer] is the newer release to offer, or null when Cascade is up to date. [settled] means the
     * answer holds for the 6-hour interval, so LAST_CHECK is written.
     */
    internal data class CheckAnswer(val offer: Release?, val settled: Boolean) : CheckOutcome

    /** A check that failed: what to tell the user, and how long the next automatic check waits. */
    internal data class CheckFailure(val message: String, val retryMs: Long) : CheckOutcome

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false
    /** When the last check of any kind started, successful or not; in memory, so a restart may check once more. */
    @Volatile private var lastAttempt = 0L
    /** How long after [lastAttempt] the next automatic check waits: 30 minutes, or up to an hour while GitHub rate-limits. */
    @Volatile private var retryInterval = RETRY_INTERVAL_MS
    /** Android's confirmation screen for an install, kept for [showPendingConfirm]. */
    @Volatile private var heldConfirm: HeldConfirm? = null

    private class HeldConfirm(val sessionId: Int, val intent: Intent)

    private val _dismissed = MutableStateFlow<String?>(null)
    private val dismissedRead = AtomicBoolean(false)
    /** [_dismissed] holds the stored tag, or a newer one from [dismiss]. */
    @Volatile private var dismissedLoaded = false

    /**
     * The release tag the user hid from the home screen card; Settings still offers it. Home asks for it in its first
     * composition, so the stored tag is read on [scope]. That can't let a hidden offer flash up: at cold start the
     * state is Idle, and [check] reads the tag before it publishes an offer.
     */
    fun dismissed(context: Context): StateFlow<String?> {
        if (!dismissedLoaded && dismissedRead.compareAndSet(false, true)) {
            val app = context.applicationContext
            scope.launch { loadDismissed(prefs(app)) }
        }
        return _dismissed.asStateFlow()
    }

    fun dismiss(context: Context, release: Release) {
        prefs(context).edit { putString(DISMISSED, release.tag) }
        dismissedLoaded = true
        _dismissed.value = release.tag
    }

    private fun loadDismissed(prefs: SharedPreferences) {
        // Not getString: a value of another type would throw, on a coroutine where nothing catches it.
        if (!dismissedLoaded) storedDismissed(prefs.all[DISMISSED] as? String)
    }

    /** The tag read from storage. A [dismiss] that landed while it was being read is newer, and wins. */
    internal fun storedDismissed(tag: String?) {
        _dismissed.compareAndSet(null, tag)
        dismissedLoaded = true
    }

    /**
     * Checks GitHub at most every 6 hours after a completed check and every 30 minutes after a failed one (up to an
     * hour while GitHub rate-limits), or right away when [force]d from Settings.
     */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (busy) return
        // autoCheckDue's in-memory half first, so most returns home stop here without touching the disk.
        if (!force && now - lastAttempt in 0 until retryInterval) return
        // Don't knock down an offer or an install that's under way with a background check.
        if (!force && _state.value.let { it is State.Available || it is State.Downloading || it is State.Installing }) return
        busy = true
        // LAST_CHECK is read on scope too: home's first check runs while its first frame is being composed.
        scope.launch {
            try {
                checkNow(app, now, force)
            } finally {
                busy = false
            }
        }
    }

    /** The check itself, on [scope]; [check] has made sure it's the only one running. */
    private fun checkNow(app: Context, now: Long, force: Boolean) {
        // busy keeps install() out, so an APK here was left by a run that died mid-download.
        File(app.cacheDir, APK_FILE).delete()
        val prefs = prefs(app)
        // Not getLong: a value of another type would throw, on a coroutine where nothing catches it.
        if (!force && !autoCheckDue(now, prefs.all[LAST_CHECK] as? Long ?: 0L, lastAttempt, retryInterval)) return
        lastAttempt = now
        _state.value = State.Checking
        val installed = installedInfo(app)
        val outcome = try {
            readLatest(request(LATEST, "application/vnd.github+json"), PackageInfoCompat.getLongVersionCode(installed), now)
        } catch (_: SSLException) {
            // Usually a Wi-Fi sign-in page answering in GitHub's place.
            CheckFailure("Couldn't connect to GitHub securely. If this Wi-Fi needs a sign-in, sign in and try again.", RETRY_INTERVAL_MS)
        } catch (_: IOException) {
            CheckFailure("Couldn't reach GitHub. Check your connection.", RETRY_INTERVAL_MS)
        } catch (_: SecurityException) {
            // What Android throws when Cascade's network access is blocked (a data restriction or a firewall app).
            CheckFailure("Couldn't reach GitHub. Check your connection.", RETRY_INTERVAL_MS)
        } catch (e: RuntimeException) {
            // The platform's HTTP stack (okhttp inside) can throw unchecked exceptions of its own. A background check
            // runs on a scope with no handler, where one would crash home, and again on every return home after.
            CheckFailure("Couldn't check for updates (${e.javaClass.simpleName}).", RETRY_INTERVAL_MS)
        }
        val result = when (outcome) {
            is CheckAnswer -> {
                if (outcome.settled) prefs.edit { putLong(LAST_CHECK, now) }
                retryInterval = RETRY_INTERVAL_MS
                outcome.offer?.let { State.Available(it) } ?: State.UpToDate(installed.versionName.orEmpty())
            }
            is CheckFailure -> {
                retryInterval = outcome.retryMs
                State.Failed(outcome.message, null)
            }
        }
        // A release the user hid stays hidden: home must know the stored tag before it sees the offer.
        loadDismissed(prefs)
        _state.value = result
    }

    fun install(context: Context, release: Release) {
        val app = context.applicationContext
        if (busy) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = State.Failed("Allow Cascade to install apps, then tap Try again.", release)
            // Android may restart Cascade once that's allowed, and the offer goes with it: the next return home then
            // checks again at once instead of waiting out the 6 hours.
            scope.launch { prefs(app).edit { remove(LAST_CHECK) } }
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
            runCatching { context.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        busy = true
        _state.value = State.Downloading(release, null)
        scope.launch {
            val file = File(app.cacheDir, APK_FILE)
            var sessionId = -1
            var downloaded = false
            val installer = app.packageManager.packageInstaller
            try {
                download(app, release, file)
                downloaded = true
                verify(app, file, release)
                abandonUncommitted(installer)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    // After Cascade has installed itself once, Android may let it update without asking again.
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("Cascade.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    // Mutable so the installer can add its result extras; the intent is explicit. The release rides
                    // along (the extras merge) for a new process, should Cascade die before the result comes.
                    val resultIntent = Intent(app, InstallResultReceiver::class.java)
                        .putExtra(EXTRA_TAG, release.tag)
                        .putExtra(EXTRA_APK_URL, release.apkUrl)
                    val result = PendingIntent.getBroadcast(
                        app, sessionId, resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    heldConfirm = null
                    _state.value = State.Installing(release)
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                _state.value = State.Failed(installFailure(e, downloaded), release)
            } finally {
                file.delete()
                busy = false
            }
        }
    }

    /**
     * Shows Android's install confirmation when the result receiver's own start may not have: Android can block a
     * start from a broadcast without a word while Cascade is in the background, leaving the install waiting on a
     * screen nobody sees. Call on the main thread from a foreground activity (home's ON_RESUME); safe any time, it does
     * nothing unless an install waits on a held confirmation whose session still exists. Each one is shown once.
     */
    fun showPendingConfirm(context: Context) {
        val installing = _state.value as? State.Installing ?: return
        val held = heldConfirm ?: return
        heldConfirm = null
        // No session means the install finished or was canceled; its result broadcast says which.
        if (context.packageManager.packageInstaller.getSessionInfo(held.sessionId) == null) return
        // Its own task, as the receiver starts it: in home's task (singleTask, cleared on launch) a Home press would
        // finish it, which the installer takes for a refusal.
        val confirm = Intent(held.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(confirm)
        } catch (_: ActivityNotFoundException) {
            _state.value = State.Failed("Tap Try again to finish installing.", installing.release)
        } catch (_: SecurityException) {
            _state.value = State.Failed("Tap Try again to finish installing.", installing.release)
        }
    }

    /**
     * Android asked for [confirm] to be shown for [sessionId]'s install, and the receiver is about to start it. While
     * Cascade is in front Android lets it, so nothing is held: holding it then would show it again when home resumes
     * after the user confirms, while the install runs. Otherwise the start may be blocked, so it's held for
     * [showPendingConfirm]. Android doesn't always block it (the lock service exempts Cascade, say), so the screen can
     * then come up a second time on the next return home, once; that beats an install left waiting on a screen nobody
     * saw. A process started after Cascade died learns of the install here.
     */
    internal fun awaitingConfirm(sessionId: Int, confirm: Intent, release: Release?) {
        if (release != null && _state.value !is State.Installing) _state.value = State.Installing(release)
        heldConfirm = if (sessionId < 0 || inFront()) null else HeldConfirm(sessionId, Intent(confirm))
    }

    /**
     * Whether one of Cascade's activities is in front, which lets it start an activity from a broadcast. A foreground
     * service or the bound notification listener rank just below, and show nothing.
     */
    private fun inFront(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** A final result from Android, or a confirmation that couldn't be shown; drops any held confirmation. */
    internal fun finished(state: State) {
        heldConfirm = null
        _state.value = state
    }

    internal fun current(): Release? = when (val s = _state.value) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Installing -> s.release
        is State.Failed -> s.release
        else -> null
    }

    /** The message for an install that failed with [e]; [downloaded] tells a download that broke off from what came after. */
    private fun installFailure(e: Exception, downloaded: Boolean): String = when {
        outOfSpace(e) -> NO_SPACE
        // Not the exception's own message: for the download it can be the signed link the APK came from.
        e is IOException && !downloaded -> "The download didn't finish. Check your connection and tap Try again."
        else -> e.message ?: "Update failed (${e.javaClass.simpleName})."
    }

    /** Whether [e], or anything that led to it, is the storage filling up (ENOSPC). */
    internal fun outOfSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { it.message?.contains("ENOSPC") == true }

    /** Downloads [release]'s APK to [file], refusing one too big to be Cascade or for the storage left. */
    private fun download(context: Context, release: Release, file: File) {
        val connection = request(release.apkUrl, "application/octet-stream")
        try {
            // The status before the body: for an error status, reading the body throws a FileNotFoundException that
            // names the signed link GitHub redirected to.
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("Download failed (HTTP $code).")
            val total = connection.contentLengthLong
            if (total > MAX_APK_BYTES) error(TOO_BIG)
            // Room for the APK and for Android's copy of it in the install session, with some to spare.
            val needed = 2 * total + 10 * MIB
            if (total > 0 && allocatableBytes(context, file) < needed) {
                error("Not enough free storage for the update (about ${(needed + MIB - 1) / MIB} MB needed).")
            }
            var done = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var shown = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        done += read
                        // Without a length up front, one that runs on past any real APK is stopped here.
                        if (done > MAX_APK_BYTES) error(TOO_BIG)
                        output.write(buffer, 0, read)
                        val percent = if (total > 0) (done * 100 / total).toInt() else null
                        if (percent != null && percent != shown) {
                            shown = percent
                            _state.value = State.Downloading(release, percent)
                        }
                    }
                }
            }
            if (total > 0 && done != total) error("The download was cut off. Tap Try again.")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The bytes Cascade can have where [file] goes, counting cached files Android would clear to make room. Unknown
     * (as much as asked) when the storage can't say: the download then just tries, rather than an update failing for
     * good on a question about space.
     */
    private fun allocatableBytes(context: Context, file: File): Long {
        val storage = context.getSystemService(StorageManager::class.java)
        return try {
            storage.getAllocatableBytes(storage.getUuidForPath(file.parentFile ?: context.cacheDir))
        } catch (_: IOException) {
            Long.MAX_VALUE
        }
    }

    /** Refuse anything that isn't a newer Cascade signed with the installed key, with a message that says why. */
    private fun verify(context: Context, file: File, release: Release) {
        val archive = archiveInfo(context, file) ?: error("The download isn't a valid APK.")
        if (archive.packageName != context.packageName) error("The download isn't Cascade.")
        if (PackageInfoCompat.getLongVersionCode(archive) < PackageInfoCompat.getLongVersionCode(installedInfo(context))) {
            error("${release.tag} is older than the installed version.")
        }
        val theirs = signers(archive)
        val ours = signers(installedInfo(context, signatures = true))
        if (theirs.isEmpty() || theirs != ours) {
            // Uninstalling wipes the home screen's setup, so the way back keeps it.
            error(
                "${release.tag} is signed with a different key than this copy (a debug or self-built one?). Back up your " +
                    "settings in Backup & restore, uninstall Cascade, install the release from GitHub, then restore.",
            )
        }
    }

    /**
     * Abandons Cascade's own install sessions that were never committed: a run that died between creating one and
     * committing it leaves it holding a copy of the APK until the system cleans up, days later. Only one install runs
     * at a time, so none is in use. Committed ones are Android's to finish, so they're left alone.
     */
    private fun abandonUncommitted(installer: PackageInstaller) {
        // isCommitted is API 29+; below it there's no telling, so nothing is abandoned.
        if (Build.VERSION.SDK_INT < 29) return
        for (info in installer.mySessions) {
            if (info.isCommitted) continue
            try {
                installer.abandonSession(info.sessionId)
            } catch (_: SecurityException) {
                // Gone already: it was abandoned or cleaned up since the list was made, which is what was wanted.
            }
        }
    }

    // signingInfo is API 28+; below it the deprecated signatures field is the only signer API. Version-guarded.
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<Signature> = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }?.toSet().orEmpty()
    } else {
        info.signatures?.toSet().orEmpty()
    }

    // PackageInfoFlags is API 33+ and GET_SIGNING_CERTIFICATES 28+; below those the int-flag overload and GET_SIGNATURES
    // are the only forms. Version-guarded.
    @Suppress("DEPRECATION")
    private fun installedInfo(context: Context, signatures: Boolean = false): PackageInfo {
        val flags = if (!signatures) 0 else if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(context.packageName, flags)
    }

    // PackageInfoFlags is API 33+ and GET_SIGNING_CERTIFICATES 28+; below those the int-flag overload and GET_SIGNATURES
    // are the only forms. Version-guarded.
    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(file.path, flags)
    }

    /**
     * Whether a background check is due: 6 hours after the last completed one ([lastCheck]) and [retryMs] after the
     * last attempt. A timestamp in the future means the clock moved back, which counts as due.
     */
    internal fun autoCheckDue(now: Long, lastCheck: Long, lastAttempt: Long, retryMs: Long): Boolean =
        now - lastCheck !in 0 until AUTO_CHECK_INTERVAL_MS && now - lastAttempt !in 0 until retryMs

    /**
     * GitHub's answer on [connection] to which release is latest, for an install at [installedCode]. The status is read
     * before the body (Android throws a FileNotFoundException for an error status's body), and [connection] is
     * disconnected either way. Throws an IOException when GitHub can't be reached.
     */
    internal fun readLatest(connection: HttpURLConnection, installedCode: Long, now: Long): CheckOutcome {
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                // No release published yet. That's an answer too, so wait the full interval before asking again.
                HttpURLConnection.HTTP_NOT_FOUND -> return CheckAnswer(null, settled = true)
                else -> return checkFailure(
                    code,
                    remaining = connection.header("x-ratelimit-remaining"),
                    reset = connection.header("x-ratelimit-reset"),
                    retryAfter = connection.header("retry-after"),
                    now = now,
                )
            }
            val (tag, apk) = connection.inputStream.use { readCapped(it, MAX_ANSWER_BYTES) }?.let(::parseLatest)
                ?: return CheckFailure("Couldn't read GitHub's answer.", RETRY_INTERVAL_MS)
            // A newer release without its APK may still be uploading: up to date for now, and asked again in 30 minutes.
            val settled = apk != null || (versionCode(tag) ?: 0L) <= installedCode
            return CheckAnswer(newerRelease(tag, apk, installedCode), settled)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * What a check answered with HTTP [code] (anything but 200 or 404) means. A 403 or 429 is GitHub's rate limit when
     * its headers say so: [remaining] at 0 until [reset] (epoch seconds), or [retryAfter] seconds. The next automatic
     * check then waits that long, but 30 minutes to 61 at most (the limit resets hourly). [now] is in epoch milliseconds.
     */
    internal fun checkFailure(code: Int, remaining: String?, reset: String?, retryAfter: String?, now: Long): CheckFailure {
        if (code == 403 || code == 429) {
            val limited = remaining?.trim()?.toLongOrNull() == 0L
            val untilReset = reset?.trim()?.toLongOrNull()?.takeIf { limited && it > 0 }?.let { it - now / 1000 }
            val retry = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            // When both come, GitHub asks to wait out each.
            val seconds = listOfNotNull(untilReset, retry).maxOrNull()
                ?: return CheckFailure("GitHub refused the update check (HTTP $code).", RETRY_INTERVAL_MS)
            // A day at most, whatever the headers say, so nothing overflows; a reset already past (the clock is off) is now.
            val waitMs = seconds.coerceIn(0L, 24 * 60 * 60L) * 1000
            val minutes = ((waitMs + 59_999) / 60_000).coerceAtLeast(1)
            val about = if (minutes == 1L) "about a minute" else "about $minutes minutes"
            return CheckFailure(
                "GitHub is limiting update checks from this network. Try again in $about.",
                waitMs.coerceIn(RETRY_INTERVAL_MS, RATE_LIMIT_MAX_WAIT_MS),
            )
        }
        if (code in 500..599) return CheckFailure("GitHub isn't answering right now (HTTP $code). Try again later.", RETRY_INTERVAL_MS)
        return CheckFailure("Couldn't check for updates (HTTP $code).", RETRY_INTERVAL_MS)
    }

    /**
     * The tag and APK link in GitHub's latest-release JSON, or null when it isn't one. Of several APKs, one named
     * cascade-* (as the CI names it) wins.
     */
    internal fun parseLatest(json: String): Pair<String, String?>? = try {
        val root = JSONObject(json)
        val assets = root.getJSONArray("assets")
        val apks = List(assets.length()) { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { it.getString("name").startsWith("cascade-", ignoreCase = true) } ?: apks.firstOrNull()
        root.getString("tag_name") to apk?.getString("browser_download_url")
    } catch (_: JSONException) {
        null
    }

    /** The release [tag] offers when it has an APK and is newer than [installedCode]; null otherwise. */
    internal fun newerRelease(tag: String, apkUrl: String?, installedCode: Long): Release? =
        releaseOf(tag, apkUrl)?.takeIf { it.versionCode > installedCode }

    /** The release for [tag] with its APK at [apkUrl]; null without either, or for a tag that doesn't fit. */
    internal fun releaseOf(tag: String?, apkUrl: String?): Release? {
        if (tag == null || apkUrl == null) return null
        val code = versionCode(tag) ?: return null
        return Release(tag, tag.removePrefix("v"), code, apkUrl)
    }

    /** "v1.2.3" to the versionCode app/build.gradle.kts gives that release; null for tags that don't fit. */
    internal fun versionCode(tag: String): Long? {
        val parts = Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(tag.trim())?.groupValues ?: return null
        // Digits too long for a Long don't fit either; toLong would throw and fail the whole check.
        val (major, minor, patch) = parts.drop(1).map { it.toLongOrNull() ?: return null }
        return (major * 10000 + minor * 100 + patch) * 1000
    }

    /** GitHub asset links redirect https to https, which HttpURLConnection follows. */
    private fun request(url: String, accept: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", accept)
        setRequestProperty("User-Agent", "Cascade-launcher")
    }

    /** The response header [name], whatever its case: HTTP/2 sends them lower-case, HTTP/1.1 as GitHub spells them. */
    private fun HttpURLConnection.header(name: String): String? =
        headerFields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** All of [input] as UTF-8 text, or null once it runs past [max] bytes. */
    private fun readCapped(input: InputStream, max: Int): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toString("UTF-8")
            if (out.size() + read > max) return null
            out.write(buffer, 0, read)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Receives PackageInstaller results and opens Android's confirmation screen when it asks. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A process started after Cascade died (say while Android's confirmation was up) has lost the release; the
        // result carries it.
        val release = Updater.current()
            ?: Updater.releaseOf(intent.getStringExtra(Updater.EXTRA_TAG), intent.getStringExtra(Updater.EXTRA_APK_URL))
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> showConfirmation(context, intent, release)
            // The new version replaces this process and the system restarts the home screen; nothing to show.
            PackageInstaller.STATUS_SUCCESS -> Updater.finished(Updater.State.Idle)
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                val message = when {
                    // Play Protect rejects apps from developers it hasn't seen unless the user overrides it,
                    // and Android reports that as STATUS_FAILURE_ABORTED, so match the detail.
                    detail.contains("VERIFICATION_FAILURE") ->
                        "Play Protect blocked the update. Tap Try again; when it warns, choose More details, then Install anyway."
                    status == PackageInstaller.STATUS_FAILURE_STORAGE ->
                        "Not enough storage to install the update. Free up some space, then tap Try again."
                    // Uninstalling wipes the home screen's setup, so the way back keeps it.
                    status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Android refused the update ($detail). If this copy wasn't installed from GitHub, back up your settings in " +
                            "Backup & restore, uninstall Cascade, install the release, then restore."
                    status == PackageInstaller.STATUS_FAILURE_ABORTED ->
                        "Update canceled. If you just allowed installs from Cascade, tap Try again."
                    else -> "Update failed: $detail."
                }
                Updater.finished(Updater.State.Failed(message, release))
            }
        }
    }

    /** Opens the confirmation screen Android asked for; Updater holds it too, in case Android blocks this start. */
    private fun showConfirmation(context: Context, intent: Intent, release: Updater.Release?) {
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm == null) {
            Updater.finished(Updater.State.Failed("Android didn't show the install screen. Tap Try again.", release))
            return
        }
        Updater.awaitingConfirm(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1), confirm, release)
        try {
            context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            Updater.finished(Updater.State.Failed("Tap Try again to finish installing.", release))
        }
    }
}
