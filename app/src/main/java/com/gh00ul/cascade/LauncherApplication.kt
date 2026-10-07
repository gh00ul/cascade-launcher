package com.gh00ul.cascade

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.StrictMode
import com.gh00ul.cascade.data.AppRepository
import com.gh00ul.cascade.data.Prefs
import com.gh00ul.cascade.notifications.LastPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LauncherApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var prefs: Prefs
        private set
    lateinit var repository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) logStrictModeViolations()
        prefs = Prefs(this)
        repository = AppRepository(this, prefs, scope)
        LastPlayer.init(this)
    }

    // The repository outlives the activity, which a language or display size change merely recreates.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::repository.isInitialized) repository.onConfigurationChanged(newConfig)
    }

    /**
     * Debug builds only: logs (tag StrictMode) disk and network access on the main thread, slow calls, leaked closeables
     * and the like, without crashing. Release builds aren't debuggable, so they never turn this on.
     */
    private fun logStrictModeViolations() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
}

val Context.launcher: LauncherApplication get() = applicationContext as LauncherApplication
