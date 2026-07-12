package org.fossify.voicerecorder.helpers.email

import android.net.Uri

data class EmailSendRequest(
    val recipient: String,
    val subject: String,
    val recordingUri: Uri,
    val fileName: String,
    val mimeType: String,
    val timestamp: String
)

data class EmailSendResult(
    val success: Boolean,
    val message: String
)

interface EmailSender {
    fun send(request: EmailSendRequest): EmailSendResult
}
