package org.fossify.voicerecorder.helpers

internal class AppVisibilityState {
    private var startedActivityCount = 0

    @Synchronized
    fun activityStarted() {
        startedActivityCount++
    }

    @Synchronized
    fun activityStopped() {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    }

    @Synchronized
    fun isInForeground() = startedActivityCount > 0
}

internal object AppVisibilityTracker {
    private val state = AppVisibilityState()

    fun activityStarted() = state.activityStarted()

    fun activityStopped() = state.activityStopped()

    fun isInForeground() = state.isInForeground()
}
