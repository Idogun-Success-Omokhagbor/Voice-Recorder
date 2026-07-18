package org.fossify.voicerecorder.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.CONTINUE_RECORDING_AFTER_WARNING
import org.fossify.voicerecorder.helpers.EXIT_RECORDING_AFTER_WARNING
import org.fossify.voicerecorder.helpers.SAVE_RECORDING
import org.fossify.voicerecorder.services.RecorderService

class BackgroundRecordingWarningActivity : SimpleActivity() {
    private var warningDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWarning()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showWarning()
    }

    override fun onDestroy() {
        warningDialog?.dismiss()
        warningDialog = null
        super.onDestroy()
    }

    private fun showWarning() {
        if (warningDialog?.isShowing == true) {
            return
        }

        warningDialog = AlertDialog.Builder(this)
            .setTitle(R.string.background_recording_warning)
            .setMessage(R.string.background_recording_warning_message)
            .setPositiveButton(R.string.continue_recording) { _, _ ->
                performAction(CONTINUE_RECORDING_AFTER_WARNING, removeTask = false)
            }
            .setNegativeButton(R.string.save_and_exit) { _, _ ->
                performAction(SAVE_RECORDING, removeTask = true)
            }
            .setNeutralButton(R.string.exit_without_saving) { _, _ ->
                performAction(EXIT_RECORDING_AFTER_WARNING, removeTask = true)
            }
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }
    }

    private fun performAction(action: String, removeTask: Boolean) {
        Intent(this, RecorderService::class.java).apply {
            this.action = action
            try {
                startService(this)
            } catch (_: Exception) {
            }
        }

        warningDialog = null
        if (removeTask) {
            finishAndRemoveTask()
        } else {
            moveTaskToBack(true)
            finish()
        }
    }
}
