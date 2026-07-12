package org.fossify.voicerecorder.recorder

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderCleanupTest {
    @Test
    fun `release always runs when stop throws`() {
        val recorder = FakeRecorder(failStop = true)

        val result = RecorderCleanup.stopAndRelease(recorder)

        assertFalse(result.stopped)
        assertTrue(result.released)
        assertNotNull(result.stopFailure)
        assertTrue(recorder.releaseCalled)
    }

    @Test
    fun `successful stop and release are reported`() {
        val recorder = FakeRecorder()

        val result = RecorderCleanup.stopAndRelease(recorder)

        assertTrue(result.stopped)
        assertTrue(result.released)
        assertTrue(recorder.releaseCalled)
    }

    private class FakeRecorder(
        private val failStop: Boolean = false
    ) : Recorder {
        var releaseCalled = false

        override fun setOutputFile(path: String) = Unit

        override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) = Unit

        override fun prepare() = Unit

        override fun start() = Unit

        override fun stop() {
            check(!failStop) { "stop failed" }
        }

        override fun pause() = Unit

        override fun resume() = Unit

        override fun release() {
            releaseCalled = true
        }

        override fun getMaxAmplitude() = 0
    }
}
