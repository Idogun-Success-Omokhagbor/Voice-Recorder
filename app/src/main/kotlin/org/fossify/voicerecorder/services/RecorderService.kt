package org.fossify.voicerecorder.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
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
import org.fossify.voicerecorder.extensions.createMediaStoreRecordingUri
import org.fossify.voicerecorder.extensions.createDocumentFile
import org.fossify.voicerecorder.extensions.finishPendingMediaStoreRecording
import org.fossify.voicerecorder.extensions.getFormattedFilename
import org.fossify.voicerecorder.extensions.shouldUseMediaStoreRecordings
import org.fossify.voicerecorder.extensions.updateWidgets
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.EMAIL_RECORDING
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDER_RUNNING_NOTIF_ID
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.helpers.email.BackendEmailSender
import org.fossify.voicerecorder.helpers.email.EmailSendRequest
import org.fossify.voicerecorder.helpers.email.EmailSender
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.recorder.MediaRecorderWrapper
import org.fossify.voicerecorder.recorder.Recorder
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class RecorderService : Service() {
    companion object {
        var isRunning = false
        var currentStatus = RECORDING_STOPPED

        private const val AMPLITUDE_UPDATE_MS = 75L
        private const val VIBRATION_DURATION_MS = 150L
    }


    private var recordingPath = ""
    private var resultUri: Uri? = null
    private var recordingFileName = ""
    private var recordingMimeType = ""
    private var isMediaStoreRecording = false

    private var duration = 0
    private var status = RECORDING_STOPPED
    private var shouldEmailRecording = false
    private var isFinalizingRecording = false
    private var durationTimer = Timer()
    private var amplitudeTimer = Timer()
    private var recorder: Recorder? = null
    private val emailSender: EmailSender by lazy { BackendEmailSender(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent.action) {
            GET_RECORDER_INFO -> broadcastRecorderInfo()
            STOP_AMPLITUDE_UPDATE -> amplitudeTimer.cancel()
            TOGGLE_PAUSE -> togglePause()
            CANCEL_RECORDING -> cancelRecording()
            EMAIL_RECORDING -> {
                if (status == RECORDING_STOPPED || isFinalizingRecording) {
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
        currentStatus = RECORDING_STOPPED
        updateWidgets(false)
    }

    // MP4 output with AAC produces valid M4A recordings.
    @Suppress("CyclomaticComplexMethod")
    @SuppressLint("DiscouragedApi")
    private fun startRecording() {
        shouldEmailRecording = false
        if (status != RECORDING_STOPPED || isFinalizingRecording || recorder != null) {
            return
        }

        isRunning = true
        updateWidgets(true)

        val defaultFolder = File(config.saveRecordingsFolder)
        if (!defaultFolder.exists()) {
            defaultFolder.mkdirs()
        }

        val recordingFolder = defaultFolder.absolutePath
        recordingFileName = "${getFormattedFilename()}.${config.getExtension()}"
        recordingPath = "$recordingFolder/$recordingFileName"
        recordingMimeType = recordingPath.getMimeType()
        resultUri = null
        isMediaStoreRecording = false

        try {
            recorder = MediaRecorderWrapper(this)

            if (shouldUseMediaStoreRecordings()) {
                val fileUri = createMediaStoreRecordingUri(recordingFileName, recordingMimeType)
                    ?: error("Failed to create MediaStore recording")
                resultUri = fileUri
                recordingPath = fileUri.toString()
                isMediaStoreRecording = true
                contentResolver.openFileDescriptor(fileUri, "w")!!
                    .use { recorder?.setOutputFile(it) }
            } else if (isRPlus()) {
                val fileUri = createDocumentFile(recordingPath)
                    ?: run {
                        createSAFFileSdk30(recordingPath)
                        createDocumentFile(recordingPath)
                    }
                    ?: error("Failed to create recording file")
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
            setStatus(RECORDING_RUNNING)
            broadcastRecorderInfo()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())

            durationTimer = Timer()
            durationTimer.scheduleAtFixedRate(getDurationUpdateTask(), 1000, 1000)

            startAmplitudeUpdates()
        } catch (e: Exception) {
            showErrorToast(e)
            deleteCurrentRecordingOutput()
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (isFinalizingRecording) {
            return
        }

        durationTimer.cancel()
        amplitudeTimer.cancel()
        setStatus(RECORDING_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)

        val shouldEmail = shouldEmailRecording
        shouldEmailRecording = false
        val activeRecorder = recorder ?: return
        recorder = null
        isFinalizingRecording = true

        var stoppedSuccessfully = false
        try {
            activeRecorder.stop()
            stoppedSuccessfully = true
        } catch (
            @Suppress(
                "TooGenericExceptionCaught",
                "SwallowedException"
            ) e: RuntimeException
        ) {
            toast(R.string.recording_too_short)
        } catch (e: Exception) {
            showErrorToast(e)
        } finally {
            try {
                activeRecorder.release()
            } catch (_: Exception) {
            }
        }

        ensureBackgroundThread {
            try {
                if (stoppedSuccessfully && isCurrentRecordingReadable()) {
                    finalizeRecording(shouldEmail)
                } else {
                    deleteCurrentRecordingOutput()
                    EventBus.getDefault().post(Events.RecordingCompleted())
                }
            } finally {
                isFinalizingRecording = false
                if (!stoppedSuccessfully) {
                    stopSelf()
                }
            }
        }
    }

    private fun cancelRecording() {
        shouldEmailRecording = false
        durationTimer.cancel()
        amplitudeTimer.cancel()
        setStatus(RECORDING_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)

        recorder?.apply {
            try {
                stop()
                release()
            } catch (ignored: Exception) {
            }
        }

        recorder = null
        deleteCurrentRecordingOutput()

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
                setStatus(RECORDING_PAUSED)
            } else if (status == RECORDING_PAUSED) {
                recorder?.resume()
                setStatus(RECORDING_RUNNING)
            }
            broadcastStatus()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun finalizeRecording(shouldEmail: Boolean) {
        if (isMediaStoreRecording) {
            val savedUri = resultUri ?: run {
                stopSelf()
                return
            }
            finishPendingMediaStoreRecording(savedUri)
            recordingSavedSuccessfully(savedUri, shouldEmail, shouldExit = !shouldEmail)
            EventBus.getDefault().post(Events.RecordingCompleted())
            if (shouldEmail) {
                sendRecordingByEmail(savedUri)
            } else {
                stopSelf()
            }
            return
        }

        scanRecording(shouldEmail)
    }

    private fun scanRecording(shouldEmail: Boolean = false) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(recordingPath),
            arrayOf(recordingMimeType.ifBlank { recordingPath.getMimeType() })
        ) { _, uri ->
            val finalRecordingUri = resultUri ?: uri
            if (finalRecordingUri == null) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                stopSelf()
                return@scanFile
            }

            recordingSavedSuccessfully(finalRecordingUri, shouldEmail, shouldExit = !shouldEmail)
            EventBus.getDefault().post(Events.RecordingCompleted())

            if (shouldEmail) {
                sendRecordingByEmail(finalRecordingUri)
            } else {
                stopSelf()
            }
        }
    }

    private fun recordingSavedSuccessfully(savedUri: Uri, isEmail: Boolean, shouldExit: Boolean) {
        if (!isEmail) {
            toast(R.string.recording_saved_successfully)
        }
        EventBus.getDefault().post(Events.RecordingSaved(savedUri, isEmail, shouldExit))
    }

    private fun sendRecordingByEmail(recordingUri: Uri) {
        val timestamp = getRecordingEmailTimestamp()
        val subject = getString(R.string.recording_email_subject, timestamp)
        val result = emailSender.send(
            EmailSendRequest(
                recipient = config.recordingEmailAddress,
                subject = subject,
                recordingUri = recordingUri,
                fileName = recordingFileName,
                mimeType = recordingMimeType.ifBlank { recordingPath.getMimeType() },
                timestamp = timestamp
            )
        )

        if (result.success) {
            vibrateDevice()
            EventBus.getDefault().post(Events.RecordingSaved(recordingUri, isEmail = true, shouldExit = true))
        } else {
            EventBus.getDefault().post(
                Events.RecordingSaved(
                    uri = recordingUri,
                    isEmail = true,
                    shouldExit = false,
                    errorMessage = result.message
                )
            )
        }
        stopSelf()
    }

    private fun getRecordingEmailTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun vibrateDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(VIBRATION_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VIBRATION_DURATION_MS)
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

    private fun setStatus(newStatus: Int) {
        status = newStatus
        currentStatus = newStatus
        isRunning = newStatus != RECORDING_STOPPED
        updateWidgets(isRunning)
    }

    private fun isCurrentRecordingReadable(): Boolean {
        val uri = resultUri
        return if (uri != null) {
            isUriReadable(uri)
        } else {
            File(recordingPath).length() > 0L
        }
    }

    private fun isUriReadable(uri: Uri): Boolean {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize > 0L) {
                    return true
                }
            }
            contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.read() >= 0
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteCurrentRecordingOutput() {
        try {
            val uri = resultUri
            if (!isMediaStoreRecording && !isRPlus() && recordingPath.isNotBlank()) {
                File(recordingPath).delete()
            } else if (uri != null && uri.scheme == ContentResolver.SCHEME_CONTENT) {
                contentResolver.delete(uri, null, null)
            } else if (recordingPath.isNotBlank()) {
                File(recordingPath).delete()
            }
        } catch (_: Exception) {
        }
    }
}
