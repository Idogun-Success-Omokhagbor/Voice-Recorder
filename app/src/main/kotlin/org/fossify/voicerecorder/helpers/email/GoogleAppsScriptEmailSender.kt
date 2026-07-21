package org.fossify.voicerecorder.helpers.email

import android.content.Context
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Base64OutputStream
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GoogleAppsScriptEmailSender(
    private val context: Context,
    private val endpointUrl: String = BuildConfig.EMAIL_RELAY_URL,
    private val relaySecret: String = BuildConfig.EMAIL_RELAY_SECRET
) : EmailSender {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 240_000
        private const val MAX_UPLOAD_SIZE_BYTES = 14L * 1024L * 1024L
        private const val JSON_CHUNK_SIZE_BYTES = 64 * 1024
        private const val HTTP_SUCCESS_MIN = 200
        private const val HTTP_SUCCESS_MAX = 299
        private val supportedMimeTypes = setOf(
            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a",
            "audio/mpeg",
            "audio/ogg",
            "audio/opus",
            "application/ogg",
            "audio/wav",
            "audio/x-wav",
            "audio/aac",
            "audio/flac"
        )
    }

    override fun send(request: EmailSendRequest): EmailSendResult {
        val configuration = EmailRelayConfigurationValidator.validate(endpointUrl, relaySecret)
        if (configuration is EmailRelayConfigurationResult.Invalid) {
            return configurationFailure(configuration.error)
        }

        configuration as EmailRelayConfigurationResult.Valid
        val mimeType = request.mimeType.lowercase()
        if (mimeType !in supportedMimeTypes) {
            return failure(R.string.email_relay_unsupported_attachment)
        }

        val attachmentSize = resolveFileSize(request)
        if (attachmentSize > MAX_UPLOAD_SIZE_BYTES) {
            return failure(R.string.email_relay_attachment_too_large)
        }

        return try {
            val connection = (configuration.endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                instanceFollowRedirects = true
                setChunkedStreamingMode(JSON_CHUNK_SIZE_BYTES)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            try {
                BufferedOutputStream(connection.outputStream).use { output ->
                    output.writeRelayRequest(
                        context = context,
                        request = request,
                        secret = configuration.secret,
                        mimeType = mimeType,
                        fileName = request.fileName.ifBlank { resolveFileName(request) }
                    )
                }

                val responseCode = connection.responseCode
                val responseBody = connection.readResponseBody(responseCode)
                parseResponse(responseCode, responseBody)
            } finally {
                connection.disconnect()
            }
        } catch (_: SocketTimeoutException) {
            failure(R.string.email_relay_timeout)
        } catch (_: UnknownHostException) {
            failure(R.string.email_relay_network_error)
        } catch (_: IOException) {
            failure(R.string.email_relay_network_error)
        } catch (_: Exception) {
            failure(R.string.email_relay_failed)
        }
    }

    private fun HttpURLConnection.readResponseBody(responseCode: Int): String {
        val stream = if (responseCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) inputStream else errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun parseResponse(responseCode: Int, responseBody: String): EmailSendResult {
        val response = EmailRelayResponseParser.parse(responseCode, responseBody)
        if (response.success) {
            return EmailSendResult(true, context.getString(R.string.email_sent_successfully))
        }

        val messageId = when (response.code) {
            "AUTH_FAILED" -> R.string.email_relay_auth_failed
            "INVALID_RECIPIENT" -> R.string.email_relay_invalid_recipient
            "INVALID_ATTACHMENT" -> R.string.email_relay_unsupported_attachment
            "ATTACHMENT_TOO_LARGE" -> R.string.email_relay_attachment_too_large
            "QUOTA_EXCEEDED" -> R.string.email_relay_quota_exceeded
            "INVALID_SUBJECT", "SEND_FAILED" -> R.string.email_relay_failed
            else -> when (response.failure) {
                EmailRelayResponseFailure.HTTP_STATUS -> R.string.email_relay_server_error
                EmailRelayResponseFailure.INVALID_JSON,
                EmailRelayResponseFailure.MISSING_SUCCESS,
                EmailRelayResponseFailure.INVALID_SUCCESS_TYPE -> R.string.email_relay_invalid_response
                EmailRelayResponseFailure.REJECTED,
                null -> R.string.email_relay_failed
            }
        }
        return failure(messageId)
    }

    private fun configurationFailure(error: EmailRelayConfigurationError): EmailSendResult {
        val messageId = when (error) {
            EmailRelayConfigurationError.MISSING_URL -> R.string.email_relay_not_configured
            EmailRelayConfigurationError.INVALID_URL -> R.string.email_relay_invalid_url
            EmailRelayConfigurationError.INSECURE_URL -> R.string.email_relay_requires_https
            EmailRelayConfigurationError.UNSUPPORTED_ENDPOINT -> R.string.email_relay_unsupported_endpoint
            EmailRelayConfigurationError.MISSING_SECRET -> R.string.email_relay_secret_not_configured
            EmailRelayConfigurationError.INVALID_SECRET -> R.string.email_relay_secret_invalid
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
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "recording.m4a"
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

private fun BufferedOutputStream.writeRelayRequest(
    context: Context,
    request: EmailSendRequest,
    secret: String,
    mimeType: String,
    fileName: String
) {
    writeUtf8(
        buildString {
            append("{\"secret\":")
            append(JSONObject.quote(secret))
            append(",\"to\":")
            append(JSONObject.quote(request.recipient))
            append(",\"subject\":")
            append(JSONObject.quote(request.subject))
            append(",\"text\":")
            append(JSONObject.quote("Voice Recorder Plus recording attached."))
            append(",\"attachment\":{\"filename\":")
            append(JSONObject.quote(fileName))
            append(",\"contentType\":")
            append(JSONObject.quote(mimeType))
            append(",\"content\":\"")
        }
    )

    val nonClosingOutput = object : FilterOutputStream(this) {
        override fun close() {
            flush()
        }
    }
    Base64OutputStream(nonClosingOutput, Base64.NO_WRAP).use { base64Output ->
        context.contentResolver.openInputStream(request.recordingUri)?.use { input ->
            input.copyTo(base64Output)
        } ?: throw IOException("Recording could not be opened")
    }
    writeUtf8("\"}}")
    flush()
}

private fun BufferedOutputStream.writeUtf8(value: String) {
    write(value.toByteArray(Charsets.UTF_8))
}
