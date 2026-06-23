package com.aura

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.aura.notify.AppForegroundTracker
import com.aura.security.StartupMigrations
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AuroraApplication : Application() {

    @Inject lateinit var foregroundTracker: AppForegroundTracker
    @Inject lateinit var startupMigrations: StartupMigrations

    override fun onCreate() {
        super.onCreate()
        // One-time clean break: reset a pre-FIPS identity + its dead contacts on first launch
        // of this build (idempotent; a no-op on normal launches and fresh installs).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { startupMigrations.run() }
        }
        // Track foreground state so backgrounded-only events (message + call
        // notifications) know when the in-app UI is already covering them.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { foregroundTracker.onActivityStarted() }
            override fun onActivityStopped(activity: Activity) { foregroundTracker.onActivityStopped() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
