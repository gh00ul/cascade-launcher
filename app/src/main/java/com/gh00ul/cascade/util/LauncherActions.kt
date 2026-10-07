package com.gh00ul.cascade.util

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.renderTo
import com.gh00ul.cascade.notifications.NotificationListener
import kotlin.math.roundToInt

class AppShortcut(val info: ShortcutInfo, val label: String, val icon: ImageBitmap?)

object LauncherActions {
    /** Starts the app, animating it out of [bounds] (window coordinates) when given. */
    fun launch(context: Context, app: AppEntry, sourceView: View? = null, bounds: androidx.compose.ui.geometry.Rect? = null) {
        // Only finite bounds animate: roundToInt throws on NaN, and that would cost the launch itself.
        val rect = bounds?.takeIf { it.isFinite }?.let { Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt()) }
        val options = if (sourceView != null && rect != null && !rect.isEmpty) {
            ActivityOptions.makeClipRevealAnimation(sourceView, rect.left, rect.top, rect.width(), rect.height()).toBundle()
        } else null
        try {
            context.getSystemService(LauncherApps::class.java).startMainActivity(app.component, app.user, rect, options)
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens what a notification's tap opens, or, when it has none or it no longer works (common on "Uploading…" and
     * "Backing up" ones), the app that posted it, as a row does.
     */
    fun openNotified(context: Context, contentIntent: PendingIntent?, packageName: String) {
        if (contentIntent?.sendFromLauncher(context) == true) return
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null || !start(context, launch)) {
            Toast.makeText(context, "Couldn't open the app", Toast.LENGTH_SHORT).show()
        }
    }

    fun openAppInfo(context: Context, app: AppEntry) {
        // Through LauncherApps, so a work app opens its own profile's page.
        val opened = try {
            context.getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, app.user, null, null)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
        if (!opened) Toast.makeText(context, "Couldn't open App info for ${app.label}", Toast.LENGTH_SHORT).show()
    }

    fun uninstall(context: Context, app: AppEntry) {
        if (!start(context, Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))) {
            Toast.makeText(context, "Couldn't uninstall ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Static and dynamic shortcuts, like the ones Pixel Launcher shows on long-press. */
    fun loadShortcuts(context: Context, app: AppEntry): List<AppShortcut> = runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        val dpi = context.resources.displayMetrics.densityDpi
        val size = (32 * context.resources.displayMetrics.density).roundToInt()
        launcherApps.getShortcuts(query, app.user).orEmpty()
            .sortedWith(compareBy<ShortcutInfo> { if (it.isDeclaredInManifest) 0 else 1 }.thenBy { it.rank })
            .take(5)
            .map { info ->
                val icon = runCatching { launcherApps.getShortcutIconDrawable(info, dpi)?.renderTo(size)?.asImageBitmap() }.getOrNull()
                AppShortcut(info, (info.shortLabel ?: info.longLabel ?: "").toString(), icon)
            }
    }.getOrDefault(emptyList())

    fun launchShortcut(context: Context, shortcut: AppShortcut) {
        try {
            context.getSystemService(LauncherApps::class.java).startShortcut(shortcut.info, null, null)
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open ${shortcut.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // Lint's WrongConstant: "statusbar" is a real system service, just not one of Context's public names.
    /** Pulls down the notification shade. There is no public API for this, so it goes through StatusBarManager. */
    @SuppressLint("WrongConstant")
    fun expandNotifications(context: Context): Boolean = runCatching {
        val statusBar = context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(statusBar)
    }.isSuccess

    /** Pulls down quick settings, through StatusBarManager like [expandNotifications]. */
    @SuppressLint("WrongConstant") // see expandNotifications
    fun expandQuickSettings(context: Context): Boolean = runCatching {
        val statusBar = context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(statusBar)
    }.isSuccess

    /** Clock apps guard SHOW_ALARMS with SET_ALARM; if it is still refused, open the next alarm or the clock app itself. */
    fun openAlarms(context: Context) {
        val show = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        if (start(context, show)) return
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.showIntent
        if (next != null && next.sendFromLauncher(context)) return
        val pm = context.packageManager
        val clock = pm.resolveActivity(show, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" } // the chooser, not a clock app
        val launch = clock?.let { pm.getLaunchIntentForPackage(it) }
        if (launch == null || !start(context, launch)) {
            Toast.makeText(context, "Couldn't open the clock app", Toast.LENGTH_SHORT).show()
        }
    }

    fun openCalendar(context: Context) {
        val now = "content://com.android.calendar/time/${System.currentTimeMillis()}".toUri()
        if (!start(context, Intent(Intent.ACTION_VIEW, now)) &&
            !start(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
        ) {
            Toast.makeText(context, "Couldn't open the calendar", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens one event in the calendar app, falling back to the calendar at that time. */
    fun openCalendarEvent(context: Context, eventId: Long, begin: Long, end: Long) {
        val event = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        if (!start(context, event)) openCalendar(context)
    }

    fun openOwnAppInfo(context: Context) {
        if (!start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))) {
            Toast.makeText(context, "Couldn't open App info", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWallpaperPicker(context: Context) {
        start(context, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))
    }

    /** Returns false (after telling the user) when no app can search the web. */
    fun webSearch(context: Context, query: String): Boolean {
        val ok = start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            start(context, Intent(Intent.ACTION_VIEW, ("https://www.google.com/search?q=" + Uri.encode(query)).toUri()))
        if (!ok) Toast.makeText(context, "No app can search the web", Toast.LENGTH_SHORT).show()
        return ok
    }

    fun isDefaultLauncher(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }

    /** Shows the system "Set as default home app" dialog where available, otherwise the Home settings page. */
    fun requestDefaultLauncher(context: Context, roleRequest: ActivityResultLauncher<Intent>) {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                } catch (e: ActivityNotFoundException) {
                    // Nothing shows the role dialog on this phone: the setting takes the same choice.
                    openHomeSettings(context, hint = true)
                }
                return
            }
        }
        openHomeSettings(context)
    }

    /**
     * The home-app setting, or Default apps where Settings has no page of its own for it. With [hint], a toast says
     * what to do there: for when it opens instead of the dialog the user asked for.
     */
    fun openHomeSettings(context: Context, hint: Boolean = false) {
        val opened = start(context, Intent(Settings.ACTION_HOME_SETTINGS)) ||
            start(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        when {
            !opened -> Toast.makeText(
                context,
                "Couldn't open the home app setting. Look for Default apps in Settings.",
                Toast.LENGTH_LONG,
            ).show()
            hint -> Toast.makeText(context, "Choose Cascade as your home app here", Toast.LENGTH_SHORT).show()
        }
    }

    fun hasNotificationAccess(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun openNotificationAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            val component = ComponentName(context, NotificationListener::class.java).flattenToString()
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
            if (start(context, detail)) return true
        }
        if (start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return true
        Toast.makeText(context, "Couldn't open notification access settings", Toast.LENGTH_SHORT).show()
        return false
    }

    /**
     * The accessibility list, where [LockService] is turned on. Its own page (ACCESSIBILITY_DETAILS_SETTINGS) is a
     * system API that needs a privileged permission, so instead the list is asked to highlight it, as Settings' search
     * does; a Settings app that doesn't know these extras ignores them.
     */
    fun openAccessibilitySettings(context: Context) {
        val key = ComponentName(context, LockService::class.java).flattenToString()
        val opened = start(
            context,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", key)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) }),
        )
        if (!opened) Toast.makeText(context, "Couldn't open Accessibility settings", Toast.LENGTH_SHORT).show()
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
