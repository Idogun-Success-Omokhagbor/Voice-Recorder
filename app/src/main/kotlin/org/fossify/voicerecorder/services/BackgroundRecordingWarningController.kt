package org.fossify.voicerecorder.services

private const val MINUTES_BEFORE_WARNING = 60
private const val SECONDS_PER_MINUTE = 60
private const val DEFAULT_WARNING_THRESHOLD_SECONDS = MINUTES_BEFORE_WARNING * SECONDS_PER_MINUTE

internal class BackgroundRecordingWarningController(
    private val warningThresholdSeconds: Int = DEFAULT_WARNING_THRESHOLD_SECONDS
) {
    private var backgroundRecordingSeconds = 0
    private var warningShown = false

    init {
        require(warningThresholdSeconds > 0)
    }

    @Synchronized
    fun reset() {
        backgroundRecordingSeconds = 0
        warningShown = false
    }

    @Synchronized
    fun onSecond(
        isRecording: Boolean,
        isAppInForeground: Boolean,
        isEnabled: Boolean
    ): Boolean {
        if (!isRecording || isAppInForeground || warningShown) {
            return false
        }

        backgroundRecordingSeconds++
        if (!isEnabled || backgroundRecordingSeconds < warningThresholdSeconds) {
            return false
        }

        warningShown = true
        return true
    }
}
