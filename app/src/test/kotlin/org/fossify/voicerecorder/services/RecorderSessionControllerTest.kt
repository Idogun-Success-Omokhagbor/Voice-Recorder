package org.fossify.voicerecorder.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderSessionControllerTest {
    @Test
    fun `running recording can be saved once`() {
        val controller = recordingController()

        assertTrue(controller.requestStop(RecorderStopRequest.SAVE))
        assertFalse(controller.requestStop(RecorderStopRequest.SAVE))
        assertEquals(RecorderSessionState.FINALIZING_SAVE, controller.currentState())
        assertTrue(controller.completeSave())
        assertFalse(controller.completeSave())
        assertEquals(RecorderSessionState.STOPPED, controller.currentState())
    }

    @Test
    fun `repeated email tap finalizes once`() {
        val controller = recordingController()

        assertTrue(controller.requestStop(RecorderStopRequest.EMAIL))
        assertFalse(controller.requestStop(RecorderStopRequest.EMAIL))
        assertTrue(controller.completeEmail())
        assertFalse(controller.completeEmail())
        assertEquals(RecorderSessionState.STOPPED, controller.currentState())
    }

    @Test
    fun `save cancel and email cannot overlap`() {
        val controller = recordingController()

        assertTrue(controller.requestStop(RecorderStopRequest.EMAIL))
        assertFalse(controller.requestStop(RecorderStopRequest.SAVE))
        assertFalse(controller.requestStop(RecorderStopRequest.CANCEL))
    }

    @Test
    fun `running recording can be cancelled without save completion`() {
        val controller = recordingController()

        assertTrue(controller.requestStop(RecorderStopRequest.CANCEL))
        assertTrue(controller.completeCancellation())
        assertFalse(controller.completeSave())
        assertEquals(RecorderSessionState.STOPPED, controller.currentState())
    }

    @Test
    fun `stop failure returns session to stopped`() {
        val controller = recordingController()
        assertTrue(controller.requestStop(RecorderStopRequest.SAVE))

        assertTrue(controller.fail())
        assertFalse(controller.fail())
        assertEquals(RecorderSessionState.STOPPED, controller.currentState())
        assertTrue(controller.beginRecording())
    }

    @Test
    fun `start command is idempotent while recording`() {
        val controller = RecorderSessionController()

        assertTrue(controller.beginRecording())
        assertFalse(controller.beginRecording())
        assertTrue(controller.isRecording())
    }

    private fun recordingController() = RecorderSessionController().apply {
        assertTrue(beginRecording())
    }
}
