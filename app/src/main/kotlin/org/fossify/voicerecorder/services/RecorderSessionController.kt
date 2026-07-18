package org.fossify.voicerecorder.services

internal enum class RecorderSessionState {
    STOPPED,
    RECORDING,
    FINALIZING_SAVE,
    FINALIZING_EMAIL,
    CANCELLING
}

internal enum class RecorderStopRequest {
    SAVE,
    EMAIL,
    CANCEL
}

internal class RecorderSessionController {
    private var state = RecorderSessionState.STOPPED

    @Synchronized
    fun beginRecording(): Boolean {
        if (state != RecorderSessionState.STOPPED) {
            return false
        }

        state = RecorderSessionState.RECORDING
        return true
    }

    @Synchronized
    fun requestStop(request: RecorderStopRequest): Boolean {
        if (state != RecorderSessionState.RECORDING) {
            return false
        }

        state = when (request) {
            RecorderStopRequest.SAVE -> RecorderSessionState.FINALIZING_SAVE
            RecorderStopRequest.EMAIL -> RecorderSessionState.FINALIZING_EMAIL
            RecorderStopRequest.CANCEL -> RecorderSessionState.CANCELLING
        }
        return true
    }

    @Synchronized
    fun completeEmail(): Boolean {
        if (state != RecorderSessionState.FINALIZING_EMAIL) {
            return false
        }

        state = RecorderSessionState.STOPPED
        return true
    }

    @Synchronized
    fun completeSave(): Boolean {
        if (state != RecorderSessionState.FINALIZING_SAVE) {
            return false
        }

        state = RecorderSessionState.STOPPED
        return true
    }

    @Synchronized
    fun completeCancellation(): Boolean {
        if (state != RecorderSessionState.CANCELLING) {
            return false
        }

        state = RecorderSessionState.STOPPED
        return true
    }

    @Synchronized
    fun fail(): Boolean {
        if (state == RecorderSessionState.STOPPED) {
            return false
        }

        state = RecorderSessionState.STOPPED
        return true
    }

    @Synchronized
    fun isRecording() = state == RecorderSessionState.RECORDING

    @Synchronized
    fun currentState() = state
}
