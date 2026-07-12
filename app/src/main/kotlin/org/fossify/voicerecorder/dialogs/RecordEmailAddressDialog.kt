package org.fossify.voicerecorder.dialogs

import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import android.util.Patterns
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.DialogRecordEmailAddressBinding
import org.fossify.voicerecorder.activities.SimpleActivity

class RecordEmailAddressDialog(
    private val activity: SimpleActivity,
    initialAddress: String,
    private val callback: (String) -> Unit,
    private val onDismiss: () -> Unit
) {
    private var didSaveAddress = false
    private var dialog: AlertDialog? = null
    private val binding = DialogRecordEmailAddressBinding.inflate(activity.layoutInflater).apply {
        recordEmailAddressValue.setText(initialAddress)
        recordEmailAddressValue.doAfterTextChanged {
            updatePositiveButtonState()
        }
    }

    init {
        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .setTitle(R.string.recording_email_address)
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this
                ) { alertDialog: AlertDialog ->
                    dialog = alertDialog
                    updatePositiveButtonState()
                    alertDialog.setOnDismissListener {
                        if (!didSaveAddress) {
                            onDismiss()
                        }
                    }

                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val emailAddress = binding.recordEmailAddressValue.text.toString().trim()
                        if (emailAddress.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(emailAddress).matches()) {
                            binding.recordEmailAddressValue.error = activity.getString(R.string.invalid_email_address)
                            return@setOnClickListener
                        }

                        didSaveAddress = true
                        callback(emailAddress)
                        alertDialog.dismiss()
                    }
                }
            }
    }

    private fun updatePositiveButtonState() {
        val emailAddress = binding.recordEmailAddressValue.text.toString().trim()
        val isValid = emailAddress.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(emailAddress).matches()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = isValid
        binding.recordEmailAddressValue.error = null
    }
}
