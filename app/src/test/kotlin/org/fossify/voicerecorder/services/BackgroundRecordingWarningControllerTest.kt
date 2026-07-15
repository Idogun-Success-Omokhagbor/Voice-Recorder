package org.fossify.voicerecorder.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRecordingWarningControllerTest {
    @Test
    fun `warning appears once after threshold in background`() {
        val controller = BackgroundRecordingWarningController(warningThresholdSeconds = 3)

        assertFalse(controller.onSecond(true, false, true))
        assertFalse(controller.onSecond(true, false, true))
        assertTrue(controller.onSecond(true, false, true))
        assertFalse(controller.onSecond(true, false, true))
    }

    @Test
    fun `foreground and paused time do not count`() {
        val controller = BackgroundRecordingWarningController(warningThresholdSeconds = 2)

        assertFalse(controller.onSecond(true, true, true))
        assertFalse(controller.onSecond(false, false, true))
        assertFalse(controller.onSecond(true, false, true))
        assertTrue(controller.onSecond(true, false, true))
    }

    @Test
    fun `disabled warning does not fire and can be enabled during a session`() {
        val controller = BackgroundRecordingWarningController(warningThresholdSeconds = 2)

        assertFalse(controller.onSecond(true, false, false))
        assertFalse(controller.onSecond(true, false, false))
        assertTrue(controller.onSecond(true, false, true))
    }

    @Test
    fun `new recording resets the one-warning limit`() {
        val controller = BackgroundRecordingWarningController(warningThresholdSeconds = 1)

        assertTrue(controller.onSecond(true, false, true))
        assertFalse(controller.onSecond(true, false, true))
        controller.reset()
        assertTrue(controller.onSecond(true, false, true))
    }
}
