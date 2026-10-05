package com.gh00ul.cascade

import android.app.Application
import android.content.Context
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
}

val Context.launcher: LauncherApplication get() = applicationContext as LauncherApplication
