package org.fossify.voicerecorder.helpers.email

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.fossify.voicerecorder.R

internal object RecordingEmailIntentFactory {
    @Suppress("DEPRECATION")
    fun create(
        context: Context,
        recordingUri: Uri,
        recipient: String,
        subject: String
    ): Intent? {
        val packageManager = context.packageManager
        val mailIntent = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("mailto", recipient, null)
        )
        val emailPackages = packageManager
            .queryIntentActivities(mailIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()

        val attachmentIntent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(recordingUri) ?: "audio/*"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, recordingUri)
            clipData = ClipData.newUri(
                context.contentResolver,
                recordingUri.lastPathSegment ?: "recording",
                recordingUri
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val targetedIntents = emailPackages.mapNotNull { packageName ->
            Intent(attachmentIntent)
                .setPackage(packageName)
                .takeIf { it.resolveActivity(packageManager) != null }
        }
        if (targetedIntents.isEmpty()) {
            return null
        }

        val defaultPackage = packageManager
            .resolveActivity(mailIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
        targetedIntents.firstOrNull { it.`package` == defaultPackage }?.let { return it }
        if (targetedIntents.size == 1) {
            return targetedIntents.single()
        }

        return Intent.createChooser(
            targetedIntents.first(),
            context.getString(R.string.choose_email_app)
        ).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedIntents.drop(1).toTypedArray())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
