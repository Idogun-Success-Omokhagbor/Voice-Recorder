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
        
        // Get all available email packages
        val emailPackages = packageManager
            .queryIntentActivities(mailIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()

        // Find the default email app
        val defaultPackage = packageManager
            .resolveActivity(mailIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName

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

        // Use default email app if available, otherwise use first available
        val targetPackage = defaultPackage ?: emailPackages.firstOrNull()
        if (targetPackage != null) {
            return Intent(attachmentIntent).apply {
                setPackage(targetPackage)
            }
        }

        return null
    }
}
