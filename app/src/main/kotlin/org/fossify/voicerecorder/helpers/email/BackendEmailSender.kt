package org.fossify.voicerecorder.helpers.email

import android.content.Context
import android.provider.OpenableColumns
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

private const val MULTIPART_PREFIX = "--"
private const val LINE_END = "\r\n"

class BackendEmailSender(
    private val context: Context,
    private val endpointUrl: String = BuildConfig.EMAIL_BACKEND_URL,
    private val backendToken: String = BuildConfig.EMAIL_BACKEND_TOKEN,
    private val allowUnauthenticated: Boolean = BuildConfig.EMAIL_ALLOW_UNAUTHENTICATED
) : EmailSender {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MAX_UPLOAD_SIZE_BYTES = 25L * 1024L * 1024L
        private const val HTTP_UNPROCESSABLE_ENTITY = 422
        private const val HTTP_CONTENT_TOO_LARGE = 413
        private const val HTTP_SUCCESS_MIN = 200
        private const val HTTP_SUCCESS_MAX = 299
        private val supportedMimeTypes = setOf(
            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a",
            "audio/ogg",
            "audio/opus",
            "application/ogg"
        )
    }

    override fun send(request: EmailSendRequest): EmailSendResult {
        val configuration = BackendConfigurationValidator.validate(
            endpointUrl = endpointUrl,
            token = backendToken,
            allowUnauthenticated = allowUnauthenticated
        )
        if (configuration is BackendConfigurationResult.Invalid) {
            return configurationFailure(configuration.error)
        }

        configuration as BackendConfigurationResult.Valid
        if (request.mimeType.lowercase() !in supportedMimeTypes) {
            return failure(R.string.email_backend_unsupported_attachment)
        }

        val attachmentSize = resolveFileSize(request)
        if (attachmentSize > MAX_UPLOAD_SIZE_BYTES) {
            return failure(R.string.email_backend_attachment_too_large)
        }

        return try {
            val boundary = "VoiceRecorderPlus-${UUID.randomUUID()}"
            val connection = (configuration.endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                setChunkedStreamingMode(0)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                configuration.authorizationHeader?.let {
                    setRequestProperty("Authorization", it)
                }
            }

            try {
                BufferedOutputStream(connection.outputStream).use { output ->
                    output.writeFormField(boundary, "recipient", request.recipient)
                    output.writeFormField(boundary, "subject", request.subject)
                    output.writeFormField(boundary, "timestamp", request.timestamp)
                    output.writeFormField(boundary, "mimeType", request.mimeType)
                    output.writeFilePart(
                        context = context,
                        boundary = boundary,
                        fieldName = "recording",
                        fileName = request.fileName.ifBlank { resolveFileName(request) },
                        mimeType = request.mimeType,
                        request = request
                    )
                    output.writeString("$MULTIPART_PREFIX$boundary$MULTIPART_PREFIX$LINE_END")
                    output.flush()
                }

                val responseCode = connection.responseCode
                val responseBody = connection.readResponseBody(responseCode)
                parseResponse(responseCode, responseBody)
            } finally {
                connection.disconnect()
            }
        } catch (_: SocketTimeoutException) {
            failure(R.string.email_backend_timeout)
        } catch (_: UnknownHostException) {
            failure(R.string.email_backend_network_error)
        } catch (_: IOException) {
            failure(R.string.email_backend_network_error)
        } catch (_: Exception) {
            failure(R.string.email_backend_failed)
        }
    }

    private fun HttpURLConnection.readResponseBody(responseCode: Int): String {
        val stream = if (responseCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) inputStream else errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun parseResponse(responseCode: Int, responseBody: String): EmailSendResult {
        val parsedResponse = EmailBackendResponseParser.parse(responseCode, responseBody)
        return when {
            parsedResponse.success -> {
                EmailSendResult(true, context.getString(R.string.email_sent_successfully))
            }
            responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                responseCode == HttpURLConnection.HTTP_FORBIDDEN -> {
                failure(R.string.email_backend_auth_failed)
            }
            responseCode == HttpURLConnection.HTTP_BAD_REQUEST ||
                responseCode == HTTP_UNPROCESSABLE_ENTITY -> {
                failure(R.string.email_backend_invalid_recipient)
            }
            responseCode == HTTP_CONTENT_TOO_LARGE -> {
                failure(R.string.email_backend_attachment_too_large)
            }
            responseCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX -> {
                failure(R.string.email_backend_server_error)
            }
            parsedResponse.failure == EmailBackendResponseFailure.REJECTED -> {
                failure(R.string.email_backend_failed)
            }
            else -> failure(R.string.email_backend_invalid_response)
        }
    }

    private fun configurationFailure(error: BackendConfigurationError): EmailSendResult {
        val messageId = when (error) {
            BackendConfigurationError.MISSING_URL -> R.string.email_backend_not_configured
            BackendConfigurationError.INVALID_URL -> R.string.email_backend_invalid_url
            BackendConfigurationError.INSECURE_URL -> R.string.email_backend_requires_https
            BackendConfigurationError.MISSING_TOKEN -> R.string.email_backend_token_not_configured
            BackendConfigurationError.INVALID_TOKEN -> R.string.email_backend_token_invalid
        }
        return failure(messageId)
    }

    private fun resolveFileName(request: EmailSendRequest): String {
        return context.contentResolver.query(
            request.recordingUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        } ?: "recording"
    }

    private fun resolveFileSize(request: EmailSendRequest): Long {
        val queriedSize = context.contentResolver.query(
            request.recordingUri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        if (queriedSize != null && queriedSize >= 0L) {
            return queriedSize
        }

        return try {
            context.contentResolver.openAssetFileDescriptor(request.recordingUri, "r")
                ?.use { it.length }
                ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun failure(messageId: Int) = EmailSendResult(false, context.getString(messageId))

}

private fun BufferedOutputStream.writeFormField(
    boundary: String,
    name: String,
    value: String
) {
    writeString("$MULTIPART_PREFIX$boundary$LINE_END")
    writeString("Content-Disposition: form-data; name=\"$name\"$LINE_END")
    writeString("Content-Type: text/plain; charset=UTF-8$LINE_END$LINE_END")
    writeString(value)
    writeString(LINE_END)
}

private fun BufferedOutputStream.writeFilePart(
    context: Context,
    boundary: String,
    fieldName: String,
    fileName: String,
    mimeType: String,
    request: EmailSendRequest
) {
    writeString("$MULTIPART_PREFIX$boundary$LINE_END")
    val safeFileName = fileName.sanitizeHeaderValue()
    writeString(
        "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$safeFileName\"$LINE_END"
    )
    writeString("Content-Type: $mimeType$LINE_END$LINE_END")
    context.contentResolver.openInputStream(request.recordingUri)?.use { input ->
        input.copyTo(this)
    } ?: throw IOException("Recording could not be opened")
    writeString(LINE_END)
}

private fun BufferedOutputStream.writeString(value: String) {
    write(value.toByteArray(Charsets.UTF_8))
}

private fun String.sanitizeHeaderValue() = replace("\"", "'").replace("\r", "").replace("\n", "")
