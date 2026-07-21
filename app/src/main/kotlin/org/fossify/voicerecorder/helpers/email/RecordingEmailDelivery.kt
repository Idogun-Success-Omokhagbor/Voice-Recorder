package org.fossify.voicerecorder.helpers.email

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.extensions.config

class RecordingEmailDelivery(
    private val context: Context,
    private val emailSender: EmailSender = GoogleAppsScriptEmailSender(context)
) {
    companion object {
        private const val VIBRATION_DURATION_MS = 150L
    }

    fun send(
        recordingUri: Uri,
        fileName: String,
        mimeType: String
    ): EmailSendResult {
        val emailAddress = context.config.recordingEmailAddress
        if (!EmailAddressValidator.isValid(emailAddress)) {
            return EmailSendResult(false, context.getString(R.string.invalid_email_address))
        }

        val timestamp = EmailSubjectFormatter.timestamp()
        return emailSender.send(
            EmailSendRequest(
                recipient = emailAddress,
                subject = EmailSubjectFormatter.subject(timestamp),
                recordingUri = recordingUri,
                fileName = fileName,
                mimeType = mimeType,
                timestamp = timestamp
            )
        )
    }

    fun vibrate() {
        try {
            val effect = VibrationEffect.createOneShot(
                VIBRATION_DURATION_MS,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(effect, attributes)
            } else {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(effect, attributes)
            }
        } catch (_: SecurityException) {
        }
    }
}
