package org.fossify.voicerecorder.services

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import org.fossify.voicerecorder.activities.BackgroundRecordingWarningActivity
import org.fossify.voicerecorder.activities.SplashActivity
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.createDocumentFile
import org.fossify.voicerecorder.extensions.createMediaStoreRecordingUri
import org.fossify.voicerecorder.extensions.finishPendingMediaStoreRecording
import org.fossify.voicerecorder.extensions.getFormattedFilename
import org.fossify.voicerecorder.extensions.shouldUseMediaStoreRecordings
import org.fossify.voicerecorder.extensions.updateWidgets
import org.fossify.voicerecorder.helpers.AppVisibilityTracker
import org.fossify.voicerecorder.helpers.BackgroundWarningPermission
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.CONTINUE_RECORDING_AFTER_WARNING
import org.fossify.voicerecorder.helpers.EMAIL_RECORDING
import org.fossify.voicerecorder.helpers.EDIT_EMAIL_BEFORE_SENDING_EXTRA
import org.fossify.voicerecorder.helpers.EXIT_RECORDING_AFTER_WARNING
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDER_RUNNING_NOTIF_ID
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.SAVE_RECORDING
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.helpers.TOGGLE_RECORDING
import org.fossify.voicerecorder.helpers.email.EmailSendResult
import org.fossify.voicerecorder.helpers.email.RecordingEmailDelivery
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.recorder.MediaRecorderWrapper
import org.fossify.voicerecorder.recorder.Recorder
import org.fossify.voicerecorder.recorder.RecorderCleanup
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.Timer
import java.util.TimerTask

@Suppress("LargeClass")
class RecorderService : Service() {
    companion object {
        private const val AMPLITUDE_UPDATE_MS = 75L
        private const val PROCESS_EXIT_DELAY_MS = 500L
        private const val RECORDING_NOTIFICATION_CHANNEL_ID = "simple_recorder"
        private const val WARNING_NOTIFICATION_CHANNEL_ID = "background_recording_warning_v2"
        private const val BACKGROUND_WARNING_NOTIFICATION_ID = 10005
        private const val CONTINUE_WARNING_REQUEST_CODE = 10001
        private const val SAVE_WARNING_REQUEST_CODE = 10002
        private const val EXIT_WARNING_REQUEST_CODE = 10003
        private const val OPEN_WARNING_REQUEST_CODE = 10004
        private const val WARNING_VIBRATION_MS = 500L
        private const val WARNING_VIBRATION_PAUSE_MS = 250L
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
    private val backgroundWarningController = BackgroundRecordingWarningController(
        BuildConfig.BACKGROUND_WARNING_THRESHOLD_SECONDS
    )
    private val emailDelivery: RecordingEmailDelivery by lazy { RecordingEmailDelivery(this) }

    @Volatile
    private var backgroundWarningPending = false

    private var editEmailBeforeSending = false

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
            EMAIL_RECORDING -> requestStop(
                RecorderStopRequest.EMAIL,
                intent.getBooleanExtra(EDIT_EMAIL_BEFORE_SENDING_EXTRA, false)
            )
            CONTINUE_RECORDING_AFTER_WARNING -> continueAfterBackgroundWarning()
            EXIT_RECORDING_AFTER_WARNING -> cancelRecording(exitAfterCancellation = true)
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
        clearBackgroundWarning()
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
            broadcastRecorderInfo()
            return
        }

        resetRecordingOutput()
        backgroundWarningController.reset()
        clearBackgroundWarning()
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

    private fun requestStop(
        request: RecorderStopRequest,
        editEmailBeforeSending: Boolean = false
    ) {
        if (!session.requestStop(request)) {
            if (session.currentState() == RecorderSessionState.STOPPED) {
                finishService()
            }
            return
        }

        this.editEmailBeforeSending = request == RecorderStopRequest.EMAIL && editEmailBeforeSending

        clearBackgroundWarning()
        cancelRecordingTimers()
        setStatus(RECORDING_STOPPED)
        broadcastStatus()
        if (request == RecorderStopRequest.EMAIL && !editEmailBeforeSending) {
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

    private fun cancelRecording(exitAfterCancellation: Boolean = false) {
        if (!session.requestStop(RecorderStopRequest.CANCEL)) {
            if (session.currentState() == RecorderSessionState.STOPPED) {
                finishService()
            }
            return
        }

        clearBackgroundWarning()
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
                if (exitAfterCancellation) {
                    EventBus.getDefault().post(Events.ExitApplication())
                }
            }
            finishService()
            if (exitAfterCancellation) {
                terminateProcessAfterDelay()
            }
        }
    }

    private fun broadcastRecorderInfo() {
        broadcastDuration()
        broadcastStatus()
        if (backgroundWarningPending) {
            EventBus.getDefault().post(Events.BackgroundRecordingWarning())
        }
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
            RecorderStopRequest.EMAIL -> {
                if (editEmailBeforeSending) {
                    completeEmailComposer(recordingUri)
                } else {
                    beginEmailUpload(recordingUri)
                }
            }
            RecorderStopRequest.CANCEL -> failFinalization()
        }
    }

    private fun completeEmailComposer(recordingUri: Uri) {
        if (session.completeEmailComposer()) {
            EventBus.getDefault().post(
                Events.RecordingSaved(
                    uri = recordingUri,
                    isEmail = true,
                    shouldExit = false,
                    shouldOpenEmailComposer = true
                )
            )
        }
        editEmailBeforeSending = false
        finishService()
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

        ensureBackgroundThread {
            val result = emailDelivery.send(
                recordingUri = recordingUri,
                fileName = recordingFileName,
                mimeType = recordingMimeType.ifBlank { recordingPath.getMimeType() }
            )
            completeEmail(recordingUri, result)
        }
    }

    private fun completeEmail(recordingUri: Uri, result: EmailSendResult) {
        val completion = session.completeEmail(result.success) ?: return
        if (completion.shouldVibrate) {
            emailDelivery.vibrate()
        }

        EventBus.getDefault().post(
            Events.RecordingSaved(
                uri = recordingUri,
                isEmail = true,
                shouldExit = completion.shouldExit,
                shouldOpenEmailComposer = !result.success,
                errorMessage = result.message.takeUnless { result.success }
            )
        )
        editEmailBeforeSending = false
        finishService()
        if (completion.shouldExit) {
            terminateProcessAfterDelay()
        }
    }

    private fun showBackgroundRecordingWarning() {
        backgroundWarningPending = true
        val openWarningIntent = getWarningActivityIntent()
        val warningNotification = showBackgroundRecordingWarningNotification(openWarningIntent)
        try {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(BACKGROUND_WARNING_NOTIFICATION_ID, warningNotification)
        } catch (_: SecurityException) {
            // The direct activity launch remains available when notifications are blocked.
        }
        if (BackgroundWarningPermission.isGranted(this)) {
            launchBackgroundRecordingWarning(openWarningIntent)
        }
        EventBus.getDefault().post(Events.BackgroundRecordingWarning())
    }

    private fun continueAfterBackgroundWarning() {
        if (!backgroundWarningPending) {
            if (session.currentState() == RecorderSessionState.STOPPED) {
                finishService()
            }
            return
        }

        clearBackgroundWarning()
        if (session.isRecording()) {
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())
        }
    }

    private fun failFinalization() {
        if (!session.fail()) {
            return
        }

        deleteCurrentRecordingOutput()
        clearBackgroundWarning()
        EventBus.getDefault().post(Events.RecordingCompleted())
        finishService()
    }

    private fun finishService() {
        clearBackgroundWarning()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearBackgroundWarning() {
        backgroundWarningPending = false
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(BACKGROUND_WARNING_NOTIFICATION_ID)
    }

    private fun getDurationUpdateTask() = object : TimerTask() {
        override fun run() {
            if (status == RECORDING_RUNNING) {
                duration++
                broadcastDuration()
            }

            val shouldShowWarning = backgroundWarningController.onSecond(
                isRecording = status == RECORDING_RUNNING,
                isAppInForeground = AppVisibilityTracker.isInForeground(),
                isEnabled = config.backgroundRecordingWarning
            )
            if (shouldShowWarning) {
                showBackgroundRecordingWarning()
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
        val label = getString(R.string.app_name)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        NotificationChannel(
            RECORDING_NOTIFICATION_CHANNEL_ID,
            label,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }

        val text = when {
            sendingEmail -> getString(R.string.sending_recording)
            status == RECORDING_PAUSED -> "${getString(R.string.recording)} (${getString(R.string.paused)})"
            else -> getString(R.string.recording)
        }

        return NotificationCompat.Builder(this, RECORDING_NOTIFICATION_CHANNEL_ID)
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

    private fun showBackgroundRecordingWarningNotification(
        openWarningIntent: PendingIntent
    ): Notification {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val warningAudioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        NotificationChannel(
            WARNING_NOTIFICATION_CHANNEL_ID,
            getString(R.string.background_recording_warning),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setSound(sound, warningAudioAttributes)
            notificationManager.createNotificationChannel(this)
        }

        val warningMessage = getString(R.string.background_recording_warning_message)
        return NotificationCompat.Builder(this, WARNING_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.background_recording_warning))
            .setContentText(warningMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(warningMessage))
            .setSmallIcon(R.drawable.ic_graphic_eq_vector)
            .setContentIntent(openWarningIntent)
            .setFullScreenIntent(openWarningIntent, true)
            .addAction(
                R.drawable.ic_start_recording_vector,
                getString(R.string.continue_recording),
                getRecorderActionIntent(
                    CONTINUE_RECORDING_AFTER_WARNING,
                    CONTINUE_WARNING_REQUEST_CODE
                )
            )
            .addAction(
                R.drawable.ic_save_recording_vector,
                getString(R.string.save_and_exit),
                getRecorderActionIntent(SAVE_RECORDING, SAVE_WARNING_REQUEST_CODE)
            )
            .addAction(
                R.drawable.ic_cancel_recording_vector,
                getString(R.string.exit_without_saving),
                getRecorderActionIntent(
                    EXIT_RECORDING_AFTER_WARNING,
                    EXIT_WARNING_REQUEST_CODE
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(sound)
            .setVibrate(
                longArrayOf(
                    0L,
                    WARNING_VIBRATION_MS,
                    WARNING_VIBRATION_PAUSE_MS,
                    WARNING_VIBRATION_MS
                )
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun getWarningActivityIntent(): PendingIntent {
        val intent = Intent(this, BackgroundRecordingWarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val creatorOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().apply {
                @Suppress("DEPRECATION")
                pendingIntentCreatorBackgroundActivityStartMode =
                    getBackgroundActivityStartMode()
            }.toBundle()
        } else {
            null
        }
        return PendingIntent.getActivity(
            this,
            OPEN_WARNING_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions
        )
    }

    private fun launchBackgroundRecordingWarning(pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntent.send()
                return
            }

            val senderOptions = ActivityOptions.makeBasic().apply {
                @Suppress("DEPRECATION")
                pendingIntentBackgroundActivityStartMode =
                    getBackgroundActivityStartMode()
            }
            pendingIntent.send(
                this,
                0,
                null,
                null,
                null,
                null,
                senderOptions.toBundle()
            )
        } catch (_: PendingIntent.CanceledException) {
            // The persistent notification remains available as a fallback.
        }
    }

    @SuppressLint("NewApi")
    private fun getBackgroundActivityStartMode(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
    }

    private fun getRecorderActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RecorderService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    private fun terminateProcessAfterDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, PROCESS_EXIT_DELAY_MS)
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
