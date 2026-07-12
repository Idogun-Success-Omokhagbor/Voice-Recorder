package org.fossify.voicerecorder.helpers.email

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailAddressValidatorTest {
    @Test
    fun `accepts valid addresses`() {
        listOf(
            "client@example.com",
            "first.last+recordings@example.co.uk",
            "user_123@sub.example.org"
        ).forEach { address ->
            assertTrue(address, EmailAddressValidator.isValid(address))
        }
    }

    @Test
    fun `rejects invalid addresses`() {
        listOf(
            "invalid",
            "missing-domain@",
            "@example.com",
            "double@@example.com",
            ".leading@example.com",
            "trailing.@example.com",
            "two..dots@example.com",
            "user@example"
        ).forEach { address ->
            assertFalse(address, EmailAddressValidator.isValid(address))
        }
    }

    @Test
    fun `rejects blank address`() {
        assertFalse(EmailAddressValidator.isValid("   "))
    }
}
