package com.gh00ul.cascade.testing

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.os.UserHandle
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLauncherApps

/**
 * Installed apps as LauncherApps lists them, for AppRepository tests. An app labelled "Alpha" is the package
 * `com.example.alpha` with the activity `com.example.alpha.Main`, in the launcher's own profile.
 */
object FakeLauncherApps {
    fun pkg(label: String) = "com.example.${label.lowercase()}"

    /**
     * Lists apps with these [labels], each with a plain colour icon so it renders in either Robolectric graphics mode,
     * and returns them in the same order.
     */
    fun install(context: Context, vararg labels: String): List<LauncherActivityInfo> {
        val launcherApps = Shadow.extract<ShadowLauncherApps>(context.getSystemService(LauncherApps::class.java))
        val user = Process.myUserHandle()
        return labels.map { label ->
            shadowOf(context.packageManager).setUnbadgedApplicationIcon(pkg(label), ColorDrawable(0xFF3F51B5.toInt()))
            activity(context, label, user).also { launcherApps.addActivity(user, it) }
        }
    }

    /**
     * Lists [label]'s app under the activity [className] instead of its usual `.Main`, as an icon picker's alias swap
     * or an update that renames the activity leaves it.
     */
    fun installActivity(context: Context, label: String, className: String): LauncherActivityInfo {
        val launcherApps = Shadow.extract<ShadowLauncherApps>(context.getSystemService(LauncherApps::class.java))
        val user = Process.myUserHandle()
        shadowOf(context.packageManager).setUnbadgedApplicationIcon(pkg(label), ColorDrawable(0xFF3F51B5.toInt()))
        return activity(context, label, user, className).also { launcherApps.addActivity(user, it) }
    }

    /** Uninstalls every app. ShadowLauncherApps can't remove one, so [install] the ones that stay again after. */
    fun uninstallAll() = ShadowLauncherApps.reset()

    /** Built the way LauncherApps builds them, through the hidden constructors. */
    private fun activity(context: Context, label: String, user: UserHandle, className: String = "${pkg(label)}.Main"): LauncherActivityInfo {
        val info = ActivityInfo().apply {
            packageName = pkg(label)
            name = className
            nonLocalizedLabel = label
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg(label)
                nonLocalizedLabel = label
            }
        }
        val statesClass = Class.forName("android.content.pm.IncrementalStatesInfo")
        val states = statesClass.getConstructor(Boolean::class.java, Float::class.java, Long::class.java).newInstance(false, 1f, 0L)
        val internalClass = Class.forName("android.content.pm.LauncherActivityInfoInternal")
        val internal = internalClass.getConstructor(ActivityInfo::class.java, statesClass, UserHandle::class.java, Boolean::class.java)
            .newInstance(info, states, user, false)
        return LauncherActivityInfo::class.java.getDeclaredConstructor(Context::class.java, internalClass)
            .apply { isAccessible = true }
            .newInstance(context, internal)
    }
}
