package org.fossify.voicerecorder.dialogs

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.DialogAboutCustomBinding

class AboutDialog(private val activity: SimpleActivity) {
    companion object {
        private const val SOURCE_CODE_URL = "https://github.com/FossifyOrg/Voice-Recorder"
    }

    private val binding = DialogAboutCustomBinding.inflate(activity.layoutInflater).apply {
        aboutGithub.setOnClickListener {
            openSourceCode()
        }
    }

    init {
        activity.getAlertDialogBuilder().apply {
            activity.setupDialogStuff(binding.root, this)
        }
    }

    private fun openSourceCode() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            activity.toast(R.string.no_browser_found)
        }
    }
}
