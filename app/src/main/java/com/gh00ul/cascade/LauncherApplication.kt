package com.gh00ul.cascade

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.gh00ul.cascade.data.AppRepository
import com.gh00ul.cascade.data.Prefs
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
        prefs = Prefs(this)
        repository = AppRepository(this, prefs, scope)
    }

    // The repository outlives the activity, which a language or display size change merely recreates.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::repository.isInitialized) repository.onConfigurationChanged(newConfig)
    }
}

val Context.launcher: LauncherApplication get() = applicationContext as LauncherApplication
