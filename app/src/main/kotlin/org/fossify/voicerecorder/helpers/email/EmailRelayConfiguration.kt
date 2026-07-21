package org.fossify.voicerecorder.helpers.email

import java.net.URL

internal sealed interface EmailRelayConfigurationResult {
    data class Valid(
        val endpoint: URL,
        val secret: String
    ) : EmailRelayConfigurationResult

    data class Invalid(
        val error: EmailRelayConfigurationError
    ) : EmailRelayConfigurationResult
}

internal enum class EmailRelayConfigurationError {
    MISSING_URL,
    INVALID_URL,
    INSECURE_URL,
    UNSUPPORTED_ENDPOINT,
    MISSING_SECRET,
    INVALID_SECRET
}

internal object EmailRelayConfigurationValidator {
    private val webAppPath = Regex("^/macros/s/[A-Za-z0-9_-]+/exec$")

    @Suppress("ReturnCount")
    fun validate(endpointUrl: String, secret: String): EmailRelayConfigurationResult {
        if (endpointUrl.isBlank()) {
            return EmailRelayConfigurationResult.Invalid(EmailRelayConfigurationError.MISSING_URL)
        }

        val endpoint = try {
            URL(endpointUrl.trim())
        } catch (_: Exception) {
            return EmailRelayConfigurationResult.Invalid(EmailRelayConfigurationError.INVALID_URL)
        }

        if (endpoint.protocol != "https") {
            return EmailRelayConfigurationResult.Invalid(EmailRelayConfigurationError.INSECURE_URL)
        }

        if (endpoint.host.lowercase() != "script.google.com" || !webAppPath.matches(endpoint.path)) {
            return EmailRelayConfigurationResult.Invalid(
                EmailRelayConfigurationError.UNSUPPORTED_ENDPOINT
            )
        }

        val normalizedSecret = secret.trim()
        if (normalizedSecret.isEmpty()) {
            return EmailRelayConfigurationResult.Invalid(EmailRelayConfigurationError.MISSING_SECRET)
        }

        if (normalizedSecret.any { it.isWhitespace() || it.isISOControl() }) {
            return EmailRelayConfigurationResult.Invalid(EmailRelayConfigurationError.INVALID_SECRET)
        }

        return EmailRelayConfigurationResult.Valid(endpoint, normalizedSecret)
    }
}
