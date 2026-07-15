package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityStateTest {
    @Test
    fun `app stays foreground while any activity is started`() {
        val state = AppVisibilityState()

        assertFalse(state.isInForeground())
        state.activityStarted()
        state.activityStarted()
        state.activityStopped()
        assertTrue(state.isInForeground())
        state.activityStopped()
        assertFalse(state.isInForeground())
    }

    @Test
    fun `extra stop callback cannot make count negative`() {
        val state = AppVisibilityState()

        state.activityStopped()
        assertFalse(state.isInForeground())
        state.activityStarted()
        assertTrue(state.isInForeground())
    }
}
