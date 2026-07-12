package org.fossify.voicerecorder.helpers.storage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyMoveVerifierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `copy can start only with existing source and absent destination`() {
        val expectation = createExpectation("source.m4a", "destination.m4a", "audio")

        assertTrue(LegacyMoveVerifier.canCopy(listOf(expectation)))
        expectation.destination.writeText("existing")
        assertFalse(LegacyMoveVerifier.canCopy(listOf(expectation)))
    }

    @Test
    fun `verified copy requires matching destination and preserved source`() {
        val expectation = createExpectation("source.m4a", "destination.m4a", "audio")
        expectation.destination.writeText("audio")

        assertTrue(LegacyMoveVerifier.copiesVerified(listOf(expectation)))
        assertTrue(expectation.source.exists())
    }

    @Test
    fun `partial or truncated copy fails verification`() {
        val complete = createExpectation("one.m4a", "one-copy.m4a", "audio-one")
        val truncated = createExpectation("two.m4a", "two-copy.m4a", "audio-two")
        complete.destination.writeText("audio-one")
        truncated.destination.writeText("bad")

        assertFalse(LegacyMoveVerifier.copiesVerified(listOf(complete, truncated)))
    }

    @Test
    fun `completed move requires destination and removed source`() {
        val expectation = createExpectation("source.ogg", "destination.ogg", "audio")
        expectation.destination.writeText("audio")
        assertTrue(expectation.source.delete())

        assertTrue(LegacyMoveVerifier.moveVerified(listOf(expectation)))
        expectation.destination.delete()
        assertFalse(LegacyMoveVerifier.moveVerified(listOf(expectation)))
    }

    private fun createExpectation(
        sourceName: String,
        destinationName: String,
        contents: String
    ): LegacyMoveExpectation {
        val source = temporaryFolder.newFile(sourceName).apply { writeText(contents) }
        val destination = File(temporaryFolder.root, destinationName)
        return LegacyMoveExpectation(source, destination, source.length())
    }
}
