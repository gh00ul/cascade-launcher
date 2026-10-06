package com.gh00ul.cascade.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases: finds a newer release, downloads its APK, checks that it is Cascade signed
 * with the same key, and hands it to PackageInstaller. Android asks for confirmation unless it lets Cascade update
 * itself silently; once installed, the system restarts the home screen on the new version.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/gh00ul/cascade-launcher/releases/latest"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    /** Offline or rate-limited, a check fails without writing LAST_CHECK; this keeps it from retrying on every return home. */
    private const val RETRY_INTERVAL_MS = 30 * 60 * 1000L
    private const val PREFS = "updater"
    private const val LAST_CHECK = "last_check"
    private const val DISMISSED = "dismissed_tag"

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

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false
    /** When the last check of any kind started, successful or not; in memory, so a restart may check once more. */
    @Volatile private var lastAttempt = 0L

    private val _dismissed = MutableStateFlow<String?>(null)
    @Volatile private var dismissedLoaded = false

    /** The release tag the user hid from the home screen card; Settings still offers it. */
    fun dismissed(context: Context): StateFlow<String?> {
        if (!dismissedLoaded) {
            dismissedLoaded = true
            _dismissed.value = prefs(context).getString(DISMISSED, null)
        }
        return _dismissed.asStateFlow()
    }

    fun dismiss(context: Context, release: Release) {
        prefs(context).edit().putString(DISMISSED, release.tag).apply()
        dismissedLoaded = true
        _dismissed.value = release.tag
    }

    /**
     * Checks GitHub at most every 6 hours after a completed check and every 30 minutes after a failed one, or right
     * away when [force]d from Settings.
     */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        if (busy) return
        if (!force && !autoCheckDue(now, prefs.getLong(LAST_CHECK, 0L), lastAttempt)) return
        // Don't knock down an offer or an install that's under way with a background check.
        if (!force && _state.value.let { it is State.Available || it is State.Downloading || it is State.Installing }) return
        lastAttempt = now
        busy = true
        _state.value = State.Checking
        scope.launch {
            _state.value = try {
                val json = request(LATEST, "application/vnd.github+json").inputStream.bufferedReader().use { JSONObject(it.readText()) }
                prefs.edit().putLong(LAST_CHECK, now).apply()
                val tag = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                val installed = installedInfo(app)
                newerRelease(tag, apk, PackageInfoCompat.getLongVersionCode(installed))?.let { State.Available(it) }
                    ?: State.UpToDate(installed.versionName.orEmpty())
            } catch (_: FileNotFoundException) {
                // 404: no release published yet. That's an answer too, so wait the full interval before asking again.
                prefs.edit().putLong(LAST_CHECK, now).apply()
                State.UpToDate(installedInfo(app).versionName.orEmpty())
            } catch (e: Exception) {
                State.Failed("Couldn't check for updates (${e.javaClass.simpleName}).", null)
            }
            busy = false
        }
    }

    fun install(context: Context, release: Release) {
        val app = context.applicationContext
        if (busy) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = State.Failed("Allow Cascade to install apps, then tap Update again.", release)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            runCatching { context.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        busy = true
        _state.value = State.Downloading(release, null)
        scope.launch {
            val file = File(app.cacheDir, "update.apk")
            var sessionId = -1
            val installer = app.packageManager.packageInstaller
            try {
                download(release, file)
                verify(app, file, release)
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
                    // Mutable so the installer can add its result extras; the intent is explicit.
                    val result = PendingIntent.getBroadcast(
                        app, sessionId, Intent(app, InstallResultReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    _state.value = State.Installing(release)
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                _state.value = State.Failed(e.message ?: "Update failed (${e.javaClass.simpleName}).", release)
            } finally {
                file.delete()
                busy = false
            }
        }
    }

    internal fun finished(state: State) {
        _state.value = state
    }

    internal fun current(): Release? = when (val s = _state.value) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Installing -> s.release
        is State.Failed -> s.release
        else -> null
    }

    private fun download(release: Release, file: File) {
        val connection = request(release.apkUrl, "application/octet-stream")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) error("Download failed (HTTP ${connection.responseCode}).")
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    val percent = if (total > 0) (done * 100 / total).toInt() else null
                    if (percent != null && percent != shown) {
                        shown = percent
                        _state.value = State.Downloading(release, percent)
                    }
                }
            }
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
            error("${release.tag} is signed with a different key than this install (a debug or self-built copy?). Uninstall Cascade once and install it from GitHub; updates work after that.")
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<Signature> = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }?.toSet().orEmpty()
    } else {
        info.signatures?.toSet().orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(context: Context, signatures: Boolean = false): PackageInfo {
        val flags = if (!signatures) 0 else if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(context.packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageArchiveInfo(file.path, flags)
    }

    /**
     * Whether a background check is due: 6 hours after the last completed one ([lastCheck]) and 30 minutes after the
     * last attempt. A timestamp in the future means the clock moved back, which counts as due.
     */
    internal fun autoCheckDue(now: Long, lastCheck: Long, lastAttempt: Long): Boolean =
        now - lastCheck !in 0 until AUTO_CHECK_INTERVAL_MS && now - lastAttempt !in 0 until RETRY_INTERVAL_MS

    /** The release [tag] offers when it has an APK and is newer than [installedCode]; null otherwise. */
    internal fun newerRelease(tag: String, apkUrl: String?, installedCode: Long): Release? {
        val code = versionCode(tag) ?: return null
        if (apkUrl == null || code <= installedCode) return null
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Receives PackageInstaller results and opens Android's confirmation screen when it asks. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val release = Updater.current()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Updater.finished(Updater.State.Failed("Android sent no confirmation screen. Tap Update again.", release))
                    return
                }
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: RuntimeException) {
                    Updater.finished(Updater.State.Failed("Tap Update again to finish installing.", release))
                }
            }
            // The new version replaces this process and the system restarts the home screen; nothing to show.
            PackageInstaller.STATUS_SUCCESS -> Updater.finished(Updater.State.Idle)
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                val message = when {
                    // Play Protect rejects apps from developers it hasn't seen unless the user overrides it,
                    // and Android reports that as STATUS_FAILURE_ABORTED, so match the detail.
                    detail.contains("VERIFICATION_FAILURE") ->
                        "Play Protect blocked the update. Tap Update again; when it warns, choose More details, then Install anyway."
                    status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Android refused the update ($detail). If this copy wasn't installed from GitHub, uninstall it once and install the release."
                    status == PackageInstaller.STATUS_FAILURE_ABORTED ->
                        "Update canceled. If you just allowed installs from Cascade, tap Update again."
                    else -> "Update failed: $detail."
                }
                Updater.finished(Updater.State.Failed(message, release))
            }
        }
    }
}
