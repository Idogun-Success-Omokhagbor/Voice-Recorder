package org.fossify.voicerecorder.helpers.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailRelayResponseParserTest {
    @Test
    fun `accepts strict boolean success`() {
        val response = EmailRelayResponseParser.parse(200, "{\"success\":true}")

        assertTrue(response.success)
        assertNull(response.failure)
    }

    @Test
    fun `preserves relay rejection code`() {
        val response = EmailRelayResponseParser.parse(
            200,
            "{\"success\":false,\"code\":\"AUTH_FAILED\"}"
        )

        assertFalse(response.success)
        assertEquals("AUTH_FAILED", response.code)
        assertEquals(EmailRelayResponseFailure.REJECTED, response.failure)
    }

    @Test
    fun `rejects malformed and ambiguous responses`() {
        assertEquals(
            EmailRelayResponseFailure.INVALID_JSON,
            EmailRelayResponseParser.parse(200, "not-json").failure
        )
        assertEquals(
            EmailRelayResponseFailure.MISSING_SUCCESS,
            EmailRelayResponseParser.parse(200, "{}").failure
        )
        assertEquals(
            EmailRelayResponseFailure.INVALID_SUCCESS_TYPE,
            EmailRelayResponseParser.parse(200, "{\"success\":\"true\"}").failure
        )
    }

    @Test
    fun `rejects non success http status`() {
        val response = EmailRelayResponseParser.parse(500, "{\"success\":true}")

        assertFalse(response.success)
        assertEquals(EmailRelayResponseFailure.HTTP_STATUS, response.failure)
    }
}
