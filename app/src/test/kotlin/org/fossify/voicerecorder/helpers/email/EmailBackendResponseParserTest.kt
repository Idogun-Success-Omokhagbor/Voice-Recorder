package org.fossify.voicerecorder.helpers.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailBackendResponseParserTest {
    @Test
    fun `accepts exact boolean success`() {
        val result = EmailBackendResponseParser.parse(200, "{\"success\":true}")

        assertTrue(result.success)
        assertNull(result.failure)
    }

    @Test
    fun `captures optional message without affecting success`() {
        val result = EmailBackendResponseParser.parse(
            201,
            "{\"success\":true,\"message\":\"Email sent\"}"
        )

        assertTrue(result.success)
        assertEquals("Email sent", result.message)
    }

    @Test
    fun `rejects boolean false`() {
        val result = EmailBackendResponseParser.parse(200, "{\"success\":false}")

        assertFalse(result.success)
        assertEquals(EmailBackendResponseFailure.REJECTED, result.failure)
    }

    @Test
    fun `retry true cannot override success false`() {
        val result = EmailBackendResponseParser.parse(
            200,
            "{\"success\":false,\"retry\":true}"
        )

        assertFalse(result.success)
        assertEquals(EmailBackendResponseFailure.REJECTED, result.failure)
    }

    @Test
    fun `rejects string success`() {
        val result = EmailBackendResponseParser.parse(200, "{\"success\":\"true\"}")

        assertFalse(result.success)
        assertEquals(EmailBackendResponseFailure.INVALID_SUCCESS_TYPE, result.failure)
    }

    @Test
    fun `rejects missing success`() {
        val result = EmailBackendResponseParser.parse(200, "{\"message\":\"Email sent\"}")

        assertFalse(result.success)
        assertEquals(EmailBackendResponseFailure.MISSING_SUCCESS, result.failure)
    }

    @Test
    fun `rejects malformed json`() {
        val result = EmailBackendResponseParser.parse(200, "{not-json")

        assertFalse(result.success)
        assertEquals(EmailBackendResponseFailure.INVALID_JSON, result.failure)
    }

    @Test
    fun `rejects empty body`() {
        val result = EmailBackendResponseParser.parse(200, "")

        assertFalse(result.success)
        assertEquals(EmailBackendResponseFailure.INVALID_JSON, result.failure)
    }

    @Test
    fun `all non success status codes fail`() {
        listOf(300, 400, 401, 403, 422, 500).forEach { statusCode ->
            val result = EmailBackendResponseParser.parse(statusCode, "{\"success\":true}")

            assertFalse("HTTP $statusCode must fail", result.success)
            assertEquals(EmailBackendResponseFailure.HTTP_STATUS, result.failure)
        }
    }
}
