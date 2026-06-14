package com.aura.notify

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether any Activity is currently in the foreground (started but not yet
 * stopped). Used to decide whether an inbound event needs a system notification:
 * when the app is already on screen its in-app cues (message pulse, call screen)
 * cover the event, so we stay quiet and only notify while backgrounded.
 */
@Singleton
class AppForegroundTracker @Inject constructor() {
    private val startedActivities = AtomicInteger(0)

    val isForeground: Boolean get() = startedActivities.get() > 0

    fun onActivityStarted() { startedActivities.incrementAndGet() }
    fun onActivityStopped() { startedActivities.updateAndGet { if (it > 0) it - 1 else 0 } }
}
