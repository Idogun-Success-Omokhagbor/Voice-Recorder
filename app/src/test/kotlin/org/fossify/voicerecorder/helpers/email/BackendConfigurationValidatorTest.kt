package org.fossify.voicerecorder.helpers.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendConfigurationValidatorTest {
    @Test
    fun `missing url is rejected`() {
        assertInvalid("", "token", false, BackendConfigurationError.MISSING_URL)
    }

    @Test
    fun `invalid url is rejected`() {
        assertInvalid("not a url", "token", false, BackendConfigurationError.INVALID_URL)
    }

    @Test
    fun `public http url is rejected`() {
        assertInvalid(
            "http://example.com/email",
            "token",
            false,
            BackendConfigurationError.INSECURE_URL
        )
    }

    @Test
    fun `missing token is rejected by default`() {
        assertInvalid(
            "https://example.com/email",
            "",
            false,
            BackendConfigurationError.MISSING_TOKEN
        )
    }

    @Test
    fun `configured token produces bearer header`() {
        val result = BackendConfigurationValidator.validate(
            "https://example.com/email",
            "test-token",
            false
        )

        assertTrue(result is BackendConfigurationResult.Valid)
        assertEquals(
            "Bearer test-token",
            (result as BackendConfigurationResult.Valid).authorizationHeader
        )
    }

    @Test
    fun `unauthenticated mode must be explicitly enabled`() {
        val result = BackendConfigurationValidator.validate(
            "https://example.com/email",
            "",
            true
        )

        assertTrue(result is BackendConfigurationResult.Valid)
        assertNull((result as BackendConfigurationResult.Valid).authorizationHeader)
    }

    @Test
    fun `localhost and emulator http endpoints are allowed for development`() {
        listOf("localhost", "127.0.0.1", "10.0.2.2").forEach { host ->
            val result = BackendConfigurationValidator.validate(
                "http://$host:8080/email",
                "test-token",
                false
            )

            assertTrue("$host should be allowed", result is BackendConfigurationResult.Valid)
        }
    }

    private fun assertInvalid(
        endpointUrl: String,
        token: String,
        allowUnauthenticated: Boolean,
        expectedError: BackendConfigurationError
    ) {
        val result = BackendConfigurationValidator.validate(
            endpointUrl,
            token,
            allowUnauthenticated
        )

        assertTrue(result is BackendConfigurationResult.Invalid)
        assertEquals(expectedError, (result as BackendConfigurationResult.Invalid).error)
    }
}
