package org.fossify.voicerecorder.helpers.email

import java.net.URL

internal sealed interface BackendConfigurationResult {
    data class Valid(
        val endpoint: URL,
        val authorizationHeader: String?
    ) : BackendConfigurationResult

    data class Invalid(val error: BackendConfigurationError) : BackendConfigurationResult
}

internal enum class BackendConfigurationError {
    MISSING_URL,
    INVALID_URL,
    INSECURE_URL,
    MISSING_TOKEN,
    INVALID_TOKEN
}

internal object BackendConfigurationValidator {
    private val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    @Suppress("ReturnCount")
    fun validate(
        endpointUrl: String,
        token: String,
        allowUnauthenticated: Boolean
    ): BackendConfigurationResult {
        if (endpointUrl.isBlank()) {
            return BackendConfigurationResult.Invalid(BackendConfigurationError.MISSING_URL)
        }

        val endpoint = try {
            URL(endpointUrl)
        } catch (_: Exception) {
            return BackendConfigurationResult.Invalid(BackendConfigurationError.INVALID_URL)
        }

        val isLocalEndpoint = endpoint.host.lowercase() in localHosts
        if (endpoint.protocol != "https" && !(endpoint.protocol == "http" && isLocalEndpoint)) {
            return BackendConfigurationResult.Invalid(BackendConfigurationError.INSECURE_URL)
        }

        val normalizedToken = token.trim()
        if (normalizedToken.isEmpty()) {
            return if (allowUnauthenticated) {
                BackendConfigurationResult.Valid(endpoint, authorizationHeader = null)
            } else {
                BackendConfigurationResult.Invalid(BackendConfigurationError.MISSING_TOKEN)
            }
        }

        if (normalizedToken.any { it.isWhitespace() || it.isISOControl() }) {
            return BackendConfigurationResult.Invalid(BackendConfigurationError.INVALID_TOKEN)
        }

        return BackendConfigurationResult.Valid(
            endpoint = endpoint,
            authorizationHeader = "Bearer $normalizedToken"
        )
    }
}
