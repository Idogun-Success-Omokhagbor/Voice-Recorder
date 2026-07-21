package org.fossify.voicerecorder.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `repeated email tap starts one upload`() {
        val controller = recordingController()

        assertTrue(controller.requestStop(RecorderStopRequest.EMAIL))
        assertFalse(controller.requestStop(RecorderStopRequest.EMAIL))
        assertTrue(controller.beginUpload())
        assertFalse(controller.beginUpload())
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
    fun `successful email requests vibration and exit`() {
        val controller = uploadingController()

        val completion = controller.completeEmail(success = true)

        assertEquals(EmailCompletion(shouldVibrate = true, shouldExit = true), completion)
        assertNull(controller.completeEmail(success = true))
    }

    @Test
    fun `failed email requests no vibration or exit`() {
        val controller = uploadingController()

        val completion = controller.completeEmail(success = false)

        assertEquals(EmailCompletion(shouldVibrate = false, shouldExit = false), completion)
        assertNull(controller.completeEmail(success = false))
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

    private fun uploadingController() = recordingController().apply {
        assertTrue(requestStop(RecorderStopRequest.EMAIL))
        assertTrue(beginUpload())
    }
}
