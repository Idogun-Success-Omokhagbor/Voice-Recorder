package org.fossify.voicerecorder.helpers.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailRelayConfigurationValidatorTest {
    @Test
    fun `accepts google apps script production endpoint`() {
        val result = EmailRelayConfigurationValidator.validate(
            endpointUrl = "https://script.google.com/macros/s/abc_123-XYZ/exec",
            secret = "a-secure-relay-secret"
        )

        assertTrue(result is EmailRelayConfigurationResult.Valid)
        result as EmailRelayConfigurationResult.Valid
        assertEquals("script.google.com", result.endpoint.host)
        assertEquals("a-secure-relay-secret", result.secret)
        assertNull(result.endpoint.query)
    }

    @Test
    fun `rejects missing configuration`() {
        assertInvalid("", "secret", EmailRelayConfigurationError.MISSING_URL)
        assertInvalid(
            "https://script.google.com/macros/s/abc/exec",
            "",
            EmailRelayConfigurationError.MISSING_SECRET
        )
    }

    @Test
    fun `rejects insecure and non google endpoints`() {
        assertInvalid(
            "http://script.google.com/macros/s/abc/exec",
            "secret",
            EmailRelayConfigurationError.INSECURE_URL
        )
        assertInvalid(
            "https://example.com/macros/s/abc/exec",
            "secret",
            EmailRelayConfigurationError.UNSUPPORTED_ENDPOINT
        )
    }

    @Test
    fun `rejects invalid deployment path and secret`() {
        assertInvalid(
            "https://script.google.com/home",
            "secret",
            EmailRelayConfigurationError.UNSUPPORTED_ENDPOINT
        )
        assertInvalid(
            "https://script.google.com/macros/s/abc/exec",
            "secret with spaces",
            EmailRelayConfigurationError.INVALID_SECRET
        )
    }

    private fun assertInvalid(
        endpointUrl: String,
        secret: String,
        expectedError: EmailRelayConfigurationError
    ) {
        val result = EmailRelayConfigurationValidator.validate(endpointUrl, secret)
        assertEquals(
            EmailRelayConfigurationResult.Invalid(expectedError),
            result
        )
    }
}
