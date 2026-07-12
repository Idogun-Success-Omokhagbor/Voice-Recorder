package org.fossify.voicerecorder.helpers.email

import android.content.Context
import android.provider.OpenableColumns
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID

class BackendEmailSender(
    private val context: Context,
    private val endpointUrl: String = BuildConfig.EMAIL_BACKEND_URL
) : EmailSender {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MULTIPART_PREFIX = "--"
        private const val LINE_END = "\r\n"
        private const val HTTP_UNPROCESSABLE_ENTITY = 422
        private const val HTTP_SUCCESS_MIN = 200
        private const val HTTP_SUCCESS_MAX = 299
    }

    override fun send(request: EmailSendRequest): EmailSendResult {
        if (endpointUrl.isBlank()) {
            return failure(R.string.email_backend_not_configured)
        }

        return try {
            val endpoint = URL(endpointUrl)
            if (!endpoint.isHttpsOrLocalhost()) {
                return failure(R.string.email_backend_requires_https)
            }

            val boundary = "VoiceRecorderPlus-${UUID.randomUUID()}"
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                setChunkedStreamingMode(0)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            try {
                BufferedOutputStream(connection.outputStream).use { output ->
                    output.writeFormField(boundary, "recipient", request.recipient)
                    output.writeFormField(boundary, "subject", request.subject)
                    output.writeFormField(boundary, "timestamp", request.timestamp)
                    output.writeFormField(boundary, "mimeType", request.mimeType)
                    output.writeFilePart(
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

    private fun HttpURLConnection.readResponseBody(responseCode: Int): String {
        val stream = if (responseCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) inputStream else errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun parseResponse(responseCode: Int, responseBody: String): EmailSendResult {
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
            return failure(R.string.email_backend_auth_failed)
        }

        if (responseCode == HttpURLConnection.HTTP_BAD_REQUEST || responseCode == HTTP_UNPROCESSABLE_ENTITY) {
            return failure(R.string.email_backend_invalid_recipient)
        }

        if (responseCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            return failure(R.string.email_backend_server_error)
        }

        val normalizedBody = responseBody.lowercase()
        return if (normalizedBody.contains("\"success\"") && normalizedBody.contains("true")) {
            EmailSendResult(true, context.getString(R.string.email_sent_successfully))
        } else {
            failure(R.string.email_backend_invalid_response)
        }
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

    private fun failure(messageId: Int) = EmailSendResult(false, context.getString(messageId))

    private fun URL.isHttpsOrLocalhost(): Boolean {
        return protocol == "https" || host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
    }

    private fun String.sanitizeHeaderValue() = replace("\"", "'")
}
