package org.fossify.voicerecorder.helpers.email

import org.json.JSONObject

internal data class EmailRelayResponse(
    val success: Boolean,
    val code: String? = null,
    val failure: EmailRelayResponseFailure? = null
)

internal enum class EmailRelayResponseFailure {
    HTTP_STATUS,
    INVALID_JSON,
    MISSING_SUCCESS,
    INVALID_SUCCESS_TYPE,
    REJECTED
}

internal object EmailRelayResponseParser {
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299

    fun parse(statusCode: Int, responseBody: String): EmailRelayResponse {
        if (statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            return EmailRelayResponse(
                success = false,
                failure = EmailRelayResponseFailure.HTTP_STATUS
            )
        }

        val response = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            return EmailRelayResponse(
                success = false,
                failure = EmailRelayResponseFailure.INVALID_JSON
            )
        }

        if (!response.has("success")) {
            return EmailRelayResponse(
                success = false,
                failure = EmailRelayResponseFailure.MISSING_SUCCESS
            )
        }

        val success = response.opt("success")
        if (success !is Boolean) {
            return EmailRelayResponse(
                success = false,
                failure = EmailRelayResponseFailure.INVALID_SUCCESS_TYPE
            )
        }

        val code = response.optString("code").takeIf { it.isNotBlank() }
        return if (success) {
            EmailRelayResponse(success = true, code = code)
        } else {
            EmailRelayResponse(
                success = false,
                code = code,
                failure = EmailRelayResponseFailure.REJECTED
            )
        }
    }
}
