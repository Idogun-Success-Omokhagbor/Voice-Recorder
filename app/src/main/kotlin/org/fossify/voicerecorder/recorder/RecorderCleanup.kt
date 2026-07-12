package org.fossify.voicerecorder.recorder

internal data class RecorderCleanupResult(
    val stopped: Boolean,
    val released: Boolean,
    val stopFailure: Exception? = null,
    val releaseFailure: Exception? = null
)

internal object RecorderCleanup {
    @Suppress("TooGenericExceptionCaught")
    fun stopAndRelease(recorder: Recorder): RecorderCleanupResult {
        var stopFailure: Exception? = null
        var releaseFailure: Exception? = null

        try {
            recorder.stop()
        } catch (error: RuntimeException) {
            stopFailure = error
        } finally {
            try {
                recorder.release()
            } catch (error: RuntimeException) {
                releaseFailure = error
            }
        }

        return RecorderCleanupResult(
            stopped = stopFailure == null,
            released = releaseFailure == null,
            stopFailure = stopFailure,
            releaseFailure = releaseFailure
        )
    }
}
