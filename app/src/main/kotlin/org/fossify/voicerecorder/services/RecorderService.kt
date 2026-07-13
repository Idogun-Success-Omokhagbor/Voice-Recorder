package org.fossify.voicerecorder.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
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
import org.fossify.voicerecorder.extensions.createDocumentFile
import org.fossify.voicerecorder.extensions.createMediaStoreRecordingUri
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
import org.fossify.voicerecorder.helpers.SAVE_RECORDING
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.helpers.TOGGLE_RECORDING
import org.fossify.voicerecorder.helpers.email.BackendEmailSender
import org.fossify.voicerecorder.helpers.email.EmailAddressValidator
import org.fossify.voicerecorder.helpers.email.EmailSendRequest
import org.fossify.voicerecorder.helpers.email.EmailSendResult
import org.fossify.voicerecorder.helpers.email.EmailSender
import org.fossify.voicerecorder.helpers.email.EmailSubjectFormatter
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.recorder.MediaRecorderWrapper
import org.fossify.voicerecorder.recorder.Recorder
import org.fossify.voicerecorder.recorder.RecorderCleanup
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.Timer
import java.util.TimerTask

class RecorderService : Service() {
    companion object {
        private const val AMPLITUDE_UPDATE_MS = 75L
        private const val VIBRATION_DURATION_MS = 150L
    }

    private var recordingPath = ""
    private var resultUri: Uri? = null
    private var recordingFileName = ""
    private var recordingMimeType = ""
    private var isMediaStoreRecording = false

    private var duration = 0

    @Volatile
    private var status = RECORDING_STOPPED

    private var durationTimer = Timer()
    private var amplitudeTimer = Timer()

    @Volatile
    private var recorder: Recorder? = null

    private val session = RecorderSessionController()
    private val emailSender: EmailSender by lazy { BackendEmailSender(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            GET_RECORDER_INFO -> {
                broadcastRecorderInfo()
                if (session.currentState() == RecorderSessionState.STOPPED) {
                    stopSelf(startId)
                }
            }
            STOP_AMPLITUDE_UPDATE -> {
                amplitudeTimer.cancel()
                if (session.currentState() == RecorderSessionState.STOPPED) {
                    stopSelf(startId)
                }
            }
            TOGGLE_PAUSE -> togglePause()
            TOGGLE_RECORDING -> toggleRecordingFromWidget()
            SAVE_RECORDING -> requestStop(RecorderStopRequest.SAVE)
            CANCEL_RECORDING -> cancelRecording()
            EMAIL_RECORDING -> requestStop(RecorderStopRequest.EMAIL)
            else -> startRecording()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelRecordingTimers()
        val abandonedRecorder = recorder
        recorder = null
        if (abandonedRecorder != null) {
            RecorderCleanup.stopAndRelease(abandonedRecorder)
            deleteCurrentRecordingOutput()
        }

        session.fail()
        status = RECORDING_STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateWidgets(false)
        super.onDestroy()
    }

    // MP4 output with AAC produces valid M4A recordings.
    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    @SuppressLint("DiscouragedApi")
    private fun startRecording() {
        if (!session.beginRecording()) {
            return
        }

        resetRecordingOutput()
        updateWidgets(true)

        val defaultFolder = File(config.saveRecordingsFolder)
        if (!defaultFolder.exists()) {
            defaultFolder.mkdirs()
        }

        val recordingFolder = defaultFolder.absolutePath
        recordingFileName = "${getFormattedFilename()}.${config.getExtension()}"
        recordingPath = "$recordingFolder/$recordingFileName"
        recordingMimeType = recordingPath.getMimeType()

        try {
            recorder = MediaRecorderWrapper(this)
            configureRecorderOutput()
            recorder?.prepare()
            recorder?.start()
            duration = 0
            setStatus(RECORDING_RUNNING)
            broadcastRecorderInfo()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())

            durationTimer = Timer()
            durationTimer.scheduleAtFixedRate(getDurationUpdateTask(), 1000, 1000)
            startAmplitudeUpdates()
        } catch (error: Exception) {
            failRecordingStart(error)
        }
    }

    private fun configureRecorderOutput() {
        when {
            shouldUseMediaStoreRecordings() -> configureMediaStoreOutput()
            isRPlus() -> configureDocumentOutput()
            isPathOnSD(recordingPath) -> configureSdCardOutput()
            else -> {
                recorder?.setOutputFile(recordingPath)
                resultUri = FileProvider.getUriForFile(
                    this,
                    "${BuildConfig.APPLICATION_ID}.provider",
                    File(recordingPath)
                )
            }
        }

    }

    private fun configureMediaStoreOutput() {
        val fileUri = createMediaStoreRecordingUri(recordingFileName, recordingMimeType)
            ?: error("Failed to create MediaStore recording")
        resultUri = fileUri
        recordingPath = fileUri.toString()
        isMediaStoreRecording = true
        contentResolver.openFileDescriptor(fileUri, "w")!!
            .use { recorder?.setOutputFile(it) }
    }

    private fun configureDocumentOutput() {
        val fileUri = createDocumentFile(recordingPath)
            ?: run {
                createSAFFileSdk30(recordingPath)
                createDocumentFile(recordingPath)
            }
            ?: error("Failed to create recording file")
        resultUri = fileUri
        contentResolver.openFileDescriptor(fileUri, "w")!!
            .use { recorder?.setOutputFile(it) }
    }

    private fun configureSdCardOutput() {
        var document = getDocumentFile(recordingPath.getParentPath())
        document = document?.createFile("", recordingPath.getFilenameFromPath())
        check(document != null) { "Failed to create document on SD Card" }
        resultUri = document.uri
        contentResolver.openFileDescriptor(document.uri, "w")!!
            .use { recorder?.setOutputFile(it) }
    }

    private fun failRecordingStart(error: Exception) {
        showErrorToast(error)
        val activeRecorder = recorder
        recorder = null
        if (activeRecorder != null) {
            RecorderCleanup.stopAndRelease(activeRecorder)
        }
        deleteCurrentRecordingOutput()
        session.fail()
        setStatus(RECORDING_STOPPED)
        broadcastRecorderInfo()
        finishService()
    }

    private fun toggleRecordingFromWidget() {
        when (session.currentState()) {
            RecorderSessionState.RECORDING -> requestStop(RecorderStopRequest.SAVE)
            RecorderSessionState.STOPPED -> startRecording()
            else -> Unit
        }
    }

    private fun requestStop(request: RecorderStopRequest) {
        if (!session.requestStop(request)) {
            return
        }

        cancelRecordingTimers()
        setStatus(RECORDING_STOPPED)
        broadcastStatus()
        if (request == RecorderStopRequest.EMAIL) {
            startForeground(
                RECORDER_RUNNING_NOTIF_ID,
                showNotification(sendingEmail = true)
            )
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        val activeRecorder = recorder
        recorder = null
        if (activeRecorder == null) {
            failFinalization()
            return
        }

        val cleanupResult = RecorderCleanup.stopAndRelease(activeRecorder)
        if (!cleanupResult.stopped) {
            reportStopFailure(cleanupResult.stopFailure)
        }

        ensureBackgroundThread {
            if (cleanupResult.stopped && isCurrentRecordingReadable()) {
                finalizeRecording(request)
            } else {
                failFinalization()
            }
        }
    }

    private fun reportStopFailure(error: Exception?) {
        if (error is RuntimeException) {
            toast(R.string.recording_too_short)
        } else if (error != null) {
            showErrorToast(error)
        }
    }

    private fun cancelRecording() {
        if (!session.requestStop(RecorderStopRequest.CANCEL)) {
            return
        }

        cancelRecordingTimers()
        setStatus(RECORDING_STOPPED)
        broadcastStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)

        val activeRecorder = recorder
        recorder = null
        if (activeRecorder != null) {
            RecorderCleanup.stopAndRelease(activeRecorder)
        }

        ensureBackgroundThread {
            deleteCurrentRecordingOutput()
            if (session.completeCancellation()) {
                EventBus.getDefault().post(Events.RecordingCompleted())
            }
            finishService()
        }
    }

    private fun broadcastRecorderInfo() {
        broadcastDuration()
        broadcastStatus()
        if (session.isRecording()) {
            startAmplitudeUpdates()
        } else {
            amplitudeTimer.cancel()
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun startAmplitudeUpdates() {
        if (recorder == null) {
            return
        }

        amplitudeTimer.cancel()
        amplitudeTimer = Timer()
        amplitudeTimer.scheduleAtFixedRate(getAmplitudeUpdateTask(), 0, AMPLITUDE_UPDATE_MS)
    }

    @SuppressLint("NewApi")
    @Suppress("TooGenericExceptionCaught")
    private fun togglePause() {
        if (!session.isRecording()) {
            return
        }

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
        } catch (error: Exception) {
            showErrorToast(error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun finalizeRecording(request: RecorderStopRequest) {
        try {
            if (isMediaStoreRecording) {
                val savedUri = resultUri ?: run {
                    failFinalization()
                    return
                }
                if (!finishPendingMediaStoreRecording(savedUri)) {
                    failFinalization()
                    return
                }
                recordingFinalized(savedUri, request)
            } else {
                scanRecording(request)
            }
        } catch (_: Exception) {
            failFinalization()
        }
    }

    private fun scanRecording(request: RecorderStopRequest) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(recordingPath),
            arrayOf(recordingMimeType.ifBlank { recordingPath.getMimeType() })
        ) { _, scannedUri ->
            val finalRecordingUri = resultUri ?: scannedUri
            if (finalRecordingUri == null) {
                failFinalization()
                return@scanFile
            }

            recordingFinalized(finalRecordingUri, request)
        }
    }

    private fun recordingFinalized(recordingUri: Uri, request: RecorderStopRequest) {
        EventBus.getDefault().post(Events.RecordingCompleted())
        when (request) {
            RecorderStopRequest.SAVE -> completeSave(recordingUri)
            RecorderStopRequest.EMAIL -> beginEmailUpload(recordingUri)
            RecorderStopRequest.CANCEL -> failFinalization()
        }
    }

    private fun completeSave(recordingUri: Uri) {
        if (session.completeSave()) {
            toast(R.string.recording_saved_successfully)
            EventBus.getDefault().post(
                Events.RecordingSaved(
                    uri = recordingUri,
                    isEmail = false,
                    shouldExit = true
                )
            )
        }
        finishService()
    }

    private fun beginEmailUpload(recordingUri: Uri) {
        if (!session.beginUpload()) {
            finishService()
            return
        }

        val emailAddress = config.recordingEmailAddress
        val result = if (EmailAddressValidator.isValid(emailAddress)) {
            sendRecordingByEmail(recordingUri, emailAddress)
        } else {
            EmailSendResult(false, getString(R.string.invalid_email_address))
        }
        completeEmail(recordingUri, result)
    }

    private fun sendRecordingByEmail(recordingUri: Uri, emailAddress: String): EmailSendResult {
        val timestamp = EmailSubjectFormatter.timestamp()
        return emailSender.send(
            EmailSendRequest(
                recipient = emailAddress,
                subject = EmailSubjectFormatter.subject(timestamp),
                recordingUri = recordingUri,
                fileName = recordingFileName,
                mimeType = recordingMimeType.ifBlank { recordingPath.getMimeType() },
                timestamp = timestamp
            )
        )
    }

    private fun completeEmail(recordingUri: Uri, result: EmailSendResult) {
        val completion = session.completeEmail(result.success) ?: return
        if (completion.shouldVibrate) {
            vibrateDevice()
        }

        EventBus.getDefault().post(
            Events.RecordingSaved(
                uri = recordingUri,
                isEmail = true,
                shouldExit = completion.shouldExit,
                errorMessage = result.message.takeUnless { result.success }
            )
        )
        finishService()
    }

    private fun failFinalization() {
        if (!session.fail()) {
            return
        }

        deleteCurrentRecordingOutput()
        EventBus.getDefault().post(Events.RecordingCompleted())
        finishService()
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun vibrateDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        VIBRATION_DURATION_MS,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
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
            val activeRecorder = recorder ?: return
            try {
                EventBus.getDefault().post(Events.RecordingAmplitude(activeRecorder.getMaxAmplitude()))
            } catch (_: Exception) {
            }
        }
    }

    private fun showNotification(sendingEmail: Boolean = false): Notification {
        val channelId = "simple_recorder"
        val label = getString(R.string.app_name)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        NotificationChannel(channelId, label, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }

        val text = when {
            sendingEmail -> getString(R.string.sending_recording)
            status == RECORDING_PAUSED -> "${getString(R.string.recording)} (${getString(R.string.paused)})"
            else -> getString(R.string.recording)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(label)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_graphic_eq_vector)
            .setContentIntent(getOpenAppIntent())
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(null)
            .setOngoing(true)
            .setAutoCancel(true)
            .build()
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
        updateWidgets(newStatus != RECORDING_STOPPED)
    }

    private fun cancelRecordingTimers() {
        durationTimer.cancel()
        amplitudeTimer.cancel()
    }

    private fun resetRecordingOutput() {
        recordingPath = ""
        resultUri = null
        recordingFileName = ""
        recordingMimeType = ""
        isMediaStoreRecording = false
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
