package com.gh00ul.cascade.testing

import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import java.util.concurrent.atomic.AtomicInteger

/**
 * LauncherApps answering any shortcut query (Robolectric's refuses manifest ones) with the [served] shortcuts of the
 * queried package, after [DELAY_MILLIS] of real time, as a binder call and its icons take a moment. Counts the queries.
 * Install it with `@Config(shadows = [ServingLauncherApps::class])`, and grant the shortcut host permission
 * (`setHasShortcutHostPermission(true)`), without which nothing asks.
 */
@Implements(LauncherApps::class)
class ServingLauncherApps : ShadowLauncherApps() {
    @Implementation
    override fun getShortcuts(query: LauncherApps.ShortcutQuery, user: UserHandle): List<ShortcutInfo> {
        asked.incrementAndGet()
        Thread.sleep(DELAY_MILLIS)
        val pkg = LauncherApps.ShortcutQuery::class.java.getDeclaredField("mPackage").apply { isAccessible = true }.get(query)
        return served.filter { it.`package` == pkg }
    }

    companion object {
        const val DELAY_MILLIS = 30L

        @Volatile
        var served: List<ShortcutInfo> = emptyList()

        /** Shortcut queries so far; they run on the IO dispatcher, so this is safe to read from the test. */
        val asked = AtomicInteger()
    }
}
