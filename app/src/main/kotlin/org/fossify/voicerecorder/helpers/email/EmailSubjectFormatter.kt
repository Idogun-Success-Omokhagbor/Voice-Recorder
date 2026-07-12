package org.fossify.voicerecorder.helpers.email

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object EmailSubjectFormatter {
    private const val SUBJECT_PREFIX = "Recording - "
    private val timestampFormatter = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm:ss",
        Locale.ROOT
    )

    fun timestamp(clock: Clock = Clock.systemDefaultZone()): String {
        return LocalDateTime.now(clock).format(timestampFormatter)
    }

    fun subject(clock: Clock = Clock.systemDefaultZone()): String {
        return subject(timestamp(clock))
    }

    fun subject(timestamp: String) = "$SUBJECT_PREFIX$timestamp"
}
