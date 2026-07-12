package org.fossify.voicerecorder.helpers.storage

import org.fossify.voicerecorder.models.Recording
import java.io.File

internal data class LegacyMoveExpectation(
    val source: File,
    val destination: File,
    val expectedSize: Long
)

internal object LegacyMoveVerifier {
    fun createExpectations(
        recordings: Collection<Recording>,
        destinationParent: String
    ): List<LegacyMoveExpectation> {
        return recordings.map { recording ->
            val source = File(recording.path)
            LegacyMoveExpectation(
                source = source,
                destination = File(destinationParent, source.name),
                expectedSize = source.length()
            )
        }
    }

    fun canCopy(expectations: Collection<LegacyMoveExpectation>): Boolean {
        return expectations.all { expectation ->
            expectation.source.isFile && !expectation.destination.exists()
        }
    }

    fun copiesVerified(expectations: Collection<LegacyMoveExpectation>): Boolean {
        return expectations.all { expectation ->
            expectation.source.isFile &&
                expectation.destination.isFile &&
                expectation.destination.length() == expectation.expectedSize
        }
    }

    fun moveVerified(expectations: Collection<LegacyMoveExpectation>): Boolean {
        return expectations.all { expectation ->
            !expectation.source.exists() &&
                expectation.destination.isFile &&
                expectation.destination.length() == expectation.expectedSize
        }
    }
}
