package org.fossify.voicerecorder.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.createSAFFileSdk30
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLaunchIntent
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SplashActivity
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.createDocumentFile
import org.fossify.voicerecorder.extensions.getFormattedFilename
import org.fossify.voicerecorder.extensions.updateWidgets
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.EXTENSION_MP3
import org.fossify.voicerecorder.helpers.EMAIL_RECORDING
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDER_RUNNING_NOTIF_ID
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.recorder.MediaRecorderWrapper
import org.fossify.voicerecorder.recorder.Mp3Recorder
import org.fossify.voicerecorder.recorder.Recorder
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.system.exitProcess

class RecorderService : Service() {
    companion object {
        var isRunning = false

        private const val AMPLITUDE_UPDATE_MS = 75L
        private const val EXIT_AFTER_EMAIL_DELAY_MS = 700L
    }


    private var recordingPath = ""
    private var resultUri: Uri? = null

    private var duration = 0
    private var status = RECORDING_STOPPED
    private var shouldEmailRecording = false
    private var durationTimer = Timer()
    private var amplitudeTimer = Timer()
    private var recorder: Recorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent.action) {
            GET_RECORDER_INFO -> broadcastRecorderInfo()
            STOP_AMPLITUDE_UPDATE -> amplitudeTimer.cancel()
            TOGGLE_PAUSE -> togglePause()
            CANCEL_RECORDING -> cancelRecording()
            EMAIL_RECORDING -> {
                if (status == RECORDING_STOPPED) {
                    stopSelf()
                } else {
                    shouldEmailRecording = true
                    stopRecording()
                }
            }
            else -> startRecording()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        isRunning = false
        updateWidgets(false)
    }

    // mp4 output format with aac encoding should produce good enough m4a files according to https://stackoverflow.com/a/33054794/1967672
    @SuppressLint("DiscouragedApi")
    private fun startRecording() {
        shouldEmailRecording = false
        isRunning = true
        updateWidgets(true)
        if (status == RECORDING_RUNNING) {
            return
        }

        val defaultFolder = File(config.saveRecordingsFolder)
        if (!defaultFolder.exists()) {
            defaultFolder.mkdirs()
        }

        val recordingFolder = defaultFolder.absolutePath
        recordingPath = "$recordingFolder/${getFormattedFilename()}.${config.getExtension()}"
        resultUri = null

        try {
            recorder = if (recordMp3()) {
                Mp3Recorder(this)
            } else {
                MediaRecorderWrapper(this)
            }

            if (isRPlus()) {
                val fileUri = createDocumentFile(recordingPath)
                    ?: createDocumentUriUsingFirstParentTreeUri(recordingPath).also {
                        createSAFFileSdk30(recordingPath)
                    }
                resultUri = fileUri
                contentResolver.openFileDescriptor(fileUri, "w")!!
                    .use { recorder?.setOutputFile(it) }
            } else if (isPathOnSD(recordingPath)) {
                var document = getDocumentFile(recordingPath.getParentPath())
                document = document?.createFile("", recordingPath.getFilenameFromPath())
                check(document != null) { "Failed to create document on SD Card" }
                resultUri = document.uri
                contentResolver.openFileDescriptor(document.uri, "w")!!
                    .use { recorder?.setOutputFile(it) }
            } else {
                recorder?.setOutputFile(recordingPath)
                resultUri = FileProvider.getUriForFile(
                    this, "${BuildConfig.APPLICATION_ID}.provider", File(recordingPath)
                )
            }

            recorder?.prepare()
            recorder?.start()
            duration = 0
            status = RECORDING_RUNNING
            broadcastRecorderInfo()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())

            durationTimer = Timer()
            durationTimer.scheduleAtFixedRate(getDurationUpdateTask(), 1000, 1000)

            startAmplitudeUpdates()
        } catch (e: Exception) {
            showErrorToast(e)
            stopRecording()
        }
    }

    private fun stopRecording() {
        durationTimer.cancel()
        amplitudeTimer.cancel()
        status = RECORDING_STOPPED

        val shouldEmail = shouldEmailRecording
        shouldEmailRecording = false

        recorder?.apply {
            try {
                stop()
                release()
            } catch (
                @Suppress(
                    "TooGenericExceptionCaught",
                    "SwallowedException"
                ) e: RuntimeException
            ) {
                toast(R.string.recording_too_short)
            } catch (e: Exception) {
                showErrorToast(e)
                e.printStackTrace()
            }

            ensureBackgroundThread {
                scanRecording(shouldEmail)
                EventBus.getDefault().post(Events.RecordingCompleted())
            }
        }
        recorder = null
    }

    private fun cancelRecording() {
        shouldEmailRecording = false
        durationTimer.cancel()
        amplitudeTimer.cancel()
        status = RECORDING_STOPPED

        recorder?.apply {
            try {
                stop()
                release()
            } catch (ignored: Exception) {
            }
        }

        recorder = null
        if (isRPlus()) {
            val recordingUri = createDocumentUriUsingFirstParentTreeUri(recordingPath)
            DocumentsContract.deleteDocument(contentResolver, recordingUri)
        } else {
            File(recordingPath).delete()
        }

        EventBus.getDefault().post(Events.RecordingCompleted())
        stopSelf()
    }

    private fun broadcastRecorderInfo() {
        broadcastDuration()
        broadcastStatus()
        startAmplitudeUpdates()
    }

    @SuppressLint("DiscouragedApi")
    private fun startAmplitudeUpdates() {
        amplitudeTimer.cancel()
        amplitudeTimer = Timer()
        amplitudeTimer.scheduleAtFixedRate(getAmplitudeUpdateTask(), 0, AMPLITUDE_UPDATE_MS)
    }

    @SuppressLint("NewApi")
    private fun togglePause() {
        try {
            if (status == RECORDING_RUNNING) {
                recorder?.pause()
                status = RECORDING_PAUSED
            } else if (status == RECORDING_PAUSED) {
                recorder?.resume()
                status = RECORDING_RUNNING
            }
            broadcastStatus()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun scanRecording(shouldEmail: Boolean = false) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(recordingPath),
            arrayOf(recordingPath.getMimeType())
        ) { _, uri ->
            if (uri == null) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                if (shouldEmail) {
                    stopSelf()
                }
                return@scanFile
            }

            val finalRecordingUri = resultUri ?: uri
            recordingSavedSuccessfully(finalRecordingUri, shouldEmail)

            if (shouldEmail) {
                sendRecordingByEmail(finalRecordingUri)
            }
        }
    }

    private fun recordingSavedSuccessfully(savedUri: Uri, isEmail: Boolean) {
        if (!isEmail) {
            toast(R.string.recording_saved_successfully)
        }
        EventBus.getDefault().post(Events.RecordingSaved(savedUri, isEmail))
    }

    private fun sendRecordingByEmail(recordingUri: Uri) {
        val email = config.recordingEmailAddress
        val subject = getRecordingEmailSubject()

        val emailPackages = getEmailPackages()
        if (emailPackages.isEmpty()) {
            vibrateDevice()
            stopSelf()
            exitAppProcess(EXIT_AFTER_EMAIL_DELAY_MS)
            return
        }

        emailPackages.forEach {
            grantUriPermission(it, recordingUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val defaultEmailPackage = getDefaultEmailPackage(emailPackages)
        val emailIntent = if (defaultEmailPackage != null || emailPackages.size == 1) {
            buildEmailIntent(
                recordingUri = recordingUri,
                subject = subject,
                recipient = email,
                emailPackage = defaultEmailPackage ?: emailPackages.first()
            )
        } else {
            buildEmailChooserIntent(recordingUri, subject, email, emailPackages)
        }

        try {
            startActivity(emailIntent)
            vibrateDevice()
            stopSelf()
            exitAppProcess(EXIT_AFTER_EMAIL_DELAY_MS)
        } catch (e: ActivityNotFoundException) {
            vibrateDevice()
            stopSelf()
            exitAppProcess(EXIT_AFTER_EMAIL_DELAY_MS)
        } catch (e: Exception) {
            vibrateDevice()
            stopSelf()
            exitAppProcess(EXIT_AFTER_EMAIL_DELAY_MS)
        }
    }

    private fun getRecordingEmailSubject(): String {
        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return getString(R.string.recording_email_subject, dateTime)
    }

    private fun buildEmailChooserIntent(
        recordingUri: Uri,
        subject: String,
        recipient: String,
        emailPackages: List<String>
    ): Intent {
        val targetedIntents = emailPackages.map {
            buildEmailIntent(
                recordingUri = recordingUri,
                subject = subject,
                recipient = recipient,
                emailPackage = it
            )
        }

        return Intent.createChooser(
            targetedIntents.first(),
            getString(R.string.choose_email_app)
        ).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedIntents.drop(1).toTypedArray())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun buildEmailIntent(
        recordingUri: Uri,
        subject: String,
        recipient: String,
        emailPackage: String
    ) = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        setPackage(emailPackage)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_STREAM, recordingUri)
        clipData = ClipData.newUri(contentResolver, subject, recordingUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (recipient.isNotBlank()) {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
        }
    }

    private fun getDefaultEmailPackage(emailPackages: List<String>): String? {
        val packageName = packageManager.resolveActivity(
            getEmailResolverIntent(),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName

        return packageName?.takeIf {
            it in emailPackages && !it.isResolverPackage()
        }
    }

    private fun getEmailPackages(): List<String> {
        return packageManager.queryIntentActivities(getEmailResolverIntent(), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filterNot { it.isResolverPackage() }
            .distinct()
    }

    private fun getEmailResolverIntent() = Intent(Intent.ACTION_SENDTO).setData("mailto:".toUri())

    private fun String.isResolverPackage() =
        this == "android" || contains("resolver", ignoreCase = true)

    private fun exitAppProcess(delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }, delayMs)
    }

    private fun vibrateDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(150)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun getDurationUpdateTask() = object : TimerTask() {
        override fun run() {
            if (status == RECORDING_RUNNING) {
                duration++
                broadcastDuration()
            }
        }
    }

    private fun getAmplitudeUpdateTask() = object : TimerTask() {
        override fun run() {
            if (recorder != null) {
                try {
                    EventBus.getDefault()
                        .post(Events.RecordingAmplitude(recorder!!.getMaxAmplitude()))
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun showNotification(): Notification {
        val channelId = "simple_recorder"
        val label = getString(R.string.app_name)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        NotificationChannel(channelId, label, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }

        val icon = R.drawable.ic_graphic_eq_vector
        val title = label
        val visibility = NotificationCompat.VISIBILITY_PUBLIC
        var text = getString(R.string.recording)
        if (status == RECORDING_PAUSED) {
            text += " (${getString(R.string.paused)})"
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(getOpenAppIntent())
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(visibility)
            .setSound(null)
            .setOngoing(true)
            .setAutoCancel(true)

        return builder.build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
        return PendingIntent.getActivity(
            this,
            RECORDER_RUNNING_NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcastDuration() {
        EventBus.getDefault().post(Events.RecordingDuration(duration))
    }

    private fun broadcastStatus() {
        EventBus.getDefault().post(Events.RecordingStatus(status))
    }

    private fun recordMp3(): Boolean {
        return config.extension == EXTENSION_MP3
    }
}
