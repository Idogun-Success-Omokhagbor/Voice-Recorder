package org.fossify.voicerecorder.helpers.email

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class EmailSubjectFormatterTest {
    @Test
    fun `formats exact recording subject`() {
        val clock = Clock.fixed(
            Instant.parse("2026-07-10T06:06:34Z"),
            ZoneId.of("Africa/Lagos")
        )

        assertEquals(
            "Recording - 2026-07-10 07:06:34",
            EmailSubjectFormatter.subject(clock)
        )
        assertEquals("2026-07-10 07:06:34", EmailSubjectFormatter.timestamp(clock))
    }
}
