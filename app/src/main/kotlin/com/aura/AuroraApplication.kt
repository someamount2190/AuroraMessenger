package com.aura

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.aura.notify.AppForegroundTracker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AuroraApplication : Application() {

    @Inject lateinit var foregroundTracker: AppForegroundTracker

    override fun onCreate() {
        super.onCreate()
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
