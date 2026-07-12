package org.fossify.voicerecorder.helpers.email

import org.json.JSONObject

internal data class EmailBackendResponse(
    val success: Boolean,
    val message: String? = null,
    val failure: EmailBackendResponseFailure? = null
)

internal enum class EmailBackendResponseFailure {
    HTTP_STATUS,
    INVALID_JSON,
    MISSING_SUCCESS,
    INVALID_SUCCESS_TYPE,
    REJECTED
}

internal object EmailBackendResponseParser {
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299

    fun parse(statusCode: Int, responseBody: String): EmailBackendResponse {
        val parsedObject = parseObject(responseBody)
        val message = parsedObject?.opt("message") as? String

        if (statusCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            return EmailBackendResponse(
                success = false,
                message = message,
                failure = EmailBackendResponseFailure.HTTP_STATUS
            )
        }

        if (parsedObject == null) {
            return EmailBackendResponse(
                success = false,
                failure = EmailBackendResponseFailure.INVALID_JSON
            )
        }

        if (!parsedObject.has("success")) {
            return EmailBackendResponse(
                success = false,
                message = message,
                failure = EmailBackendResponseFailure.MISSING_SUCCESS
            )
        }

        val successValue = parsedObject.opt("success")
        if (successValue !is Boolean) {
            return EmailBackendResponse(
                success = false,
                message = message,
                failure = EmailBackendResponseFailure.INVALID_SUCCESS_TYPE
            )
        }

        return if (successValue) {
            EmailBackendResponse(success = true, message = message)
        } else {
            EmailBackendResponse(
                success = false,
                message = message,
                failure = EmailBackendResponseFailure.REJECTED
            )
        }
    }

    private fun parseObject(responseBody: String): JSONObject? {
        if (responseBody.isBlank()) {
            return null
        }

        return try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            null
        }
    }
}
