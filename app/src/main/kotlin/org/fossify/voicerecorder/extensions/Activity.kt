package org.fossify.voicerecorder.extensions

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.net.toUri
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.createSAFDirectorySdk30
import org.fossify.commons.extensions.deleteFile
import org.fossify.commons.extensions.getDoesFilePathExistSdk30
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.hasProperStoredFirstParentUri
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.voicerecorder.dialogs.StoragePermissionDialog
import org.fossify.voicerecorder.models.Recording
import java.io.File

fun Activity.setKeepScreenAwake(keepScreenOn: Boolean) {
    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Suppress("CyclomaticComplexMethod")
fun BaseSimpleActivity.ensureStoragePermission(
    forceDefaultFolderConfirmation: Boolean = false,
    callback: (result: Boolean) -> Unit
) {
    if (isQPlus() && config.saveRecordingsFolder == getDefaultRecordingsFolder()) {
        ensureDefaultRecordingsFolderExists()
        config.defaultRecordingFolderConfirmed = true
        callback(true)
        return
    }

    if (
        isRPlus() &&
        (forceDefaultFolderConfirmation || !hasProperStoredFirstParentUri(config.saveRecordingsFolder))
    ) {
        val targetFolder = config.saveRecordingsFolder
        val defaultFolder = getDefaultRecordingsFolder()
        val pickerStartFolder = if (targetFolder == defaultFolder) {
            targetFolder.getParentPath()
        } else {
            targetFolder
        }

        StoragePermissionDialog(this) {
            if (forceDefaultFolderConfirmation && targetFolder == defaultFolder) {
                requestFolderAccess(pickerStartFolder) { granted ->
                    if (granted) {
                        if (!getDoesFilePathExistSdk30(targetFolder)) {
                            createSAFDirectorySdk30(targetFolder)
                        }
                        config.saveRecordingsFolder = targetFolder
                    }

                    callback(granted)
                }
            } else {
                launchFolderPicker(pickerStartFolder) { newPath ->
                    if (!newPath.isNullOrEmpty()) {
                        val confirmedFolder = if (targetFolder == defaultFolder && newPath == pickerStartFolder) {
                            if (!getDoesFilePathExistSdk30(targetFolder)) {
                                createSAFDirectorySdk30(targetFolder)
                            }
                            targetFolder
                        } else {
                            newPath
                        }
                        config.saveRecordingsFolder = confirmedFolder
                        callback(true)
                    } else {
                        callback(false)
                    }
                }
            }
        }
    } else {
        callback(true)
    }
}

private fun BaseSimpleActivity.requestFolderAccess(
    path: String,
    callback: (granted: Boolean) -> Unit
) {
    handleSAFDialog(path) { grantedSAF ->
        if (!grantedSAF) {
            callback(false)
            return@handleSAFDialog
        }

        handleSAFDialogSdk30(path, showRationale = false) { grantedSAF30 ->
            callback(grantedSAF30)
        }
    }
}

fun BaseSimpleActivity.launchFolderPicker(
    currentPath: String,
    callback: (newPath: String?) -> Unit
) {
    FilePickerDialog(
        activity = this,
        currPath = currentPath,
        pickFile = false,
        showFAB = true,
        showRationale = false
    ) { path ->
        handleSAFDialog(path) { grantedSAF ->
            if (!grantedSAF) {
                callback(null)
                return@handleSAFDialog
            }

            handleSAFDialogSdk30(path, showRationale = false) { grantedSAF30 ->
                if (!grantedSAF30) {
                    callback(null)
                    return@handleSAFDialogSdk30
                }

                callback(path)
            }
        }
    }
}

fun BaseSimpleActivity.deleteRecordings(
    recordingsToRemove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        var success = true
        recordingsToRemove.forEach {
            success = deleteRecording(it) && success
        }

        callback(success)
    }
}

private fun BaseSimpleActivity.deleteRecording(recording: Recording): Boolean {
    return try {
        if (recording.path.isContentUri()) {
            contentResolver.delete(recording.path.toUri(), null, null) > 0
        } else if (isRPlus()) {
            DocumentsContract.deleteDocument(contentResolver, recording.path.toUri())
        } else {
            val fileDirItem = File(recording.path).toFileDirItem(this)
            deleteFile(fileDirItem)
            !File(recording.path).exists()
        }
    } catch (_: Exception) {
        false
    }
}

private fun String.isContentUri() = startsWith("${ContentResolver.SCHEME_CONTENT}://")

private fun BaseSimpleActivity.updateMediaStoreTrashState(
    recordings: Collection<Recording>,
    trashed: Boolean
): Boolean {
    if (!isRPlus()) {
        return false
    }

    var success = true
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.IS_TRASHED, if (trashed) 1 else 0)
    }
    recordings.forEach {
        val updated = try {
            contentResolver.update(it.path.toUri(), values, null, null) > 0
        } catch (_: Exception) {
            false
        }
        success = updated && success
    }
    return success
}

private fun Collection<Recording>.allMediaStoreUris() =
    isNotEmpty() && all { it.path.isContentUri() }

fun BaseSimpleActivity.trashRecordings(
    recordingsToMove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToMove,
    sourceParent = config.saveRecordingsFolder,
    destinationParent = getOrCreateTrashFolder(),
    callback = callback
)

fun BaseSimpleActivity.restoreRecordings(
    recordingsToRestore: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToRestore,
    sourceParent = getOrCreateTrashFolder(),
    destinationParent = config.saveRecordingsFolder,
    callback = callback
)

fun BaseSimpleActivity.moveRecordings(
    recordingsToMove: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    if (recordingsToMove.allMediaStoreUris()) {
        val trashFolder = getOrCreateTrashFolder()
        val canTrashOrRestore = destinationParent == trashFolder || sourceParent == trashFolder
        callback(
            canTrashOrRestore &&
                updateMediaStoreTrashState(recordingsToMove, trashed = destinationParent == trashFolder)
        )
        return
    }

    if (isRPlus()) {
        moveRecordingsSAF(
            recordings = recordingsToMove,
            sourceParent = sourceParent,
            destinationParent = destinationParent,
            callback = callback
        )
    } else {
        moveRecordingsLegacy(
            recordings = recordingsToMove,
            sourceParent = sourceParent,
            destinationParent = destinationParent,
            callback = callback
        )
    }
}

private fun BaseSimpleActivity.moveRecordingsSAF(
    recordings: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        val contentResolver = contentResolver
        val sourceParentDocumentUri = createDocumentUriUsingFirstParentTreeUri(sourceParent)
        val destinationParentDocumentUri =
            createDocumentUriUsingFirstParentTreeUri(destinationParent)

        if (!getDoesFilePathExistSdk30(destinationParent)) {
            createSAFDirectorySdk30(destinationParent)
        }

        var success = true
        recordings.forEach { recording ->
            val moved = try {
                DocumentsContract.moveDocument(
                    contentResolver,
                    recording.path.toUri(),
                    sourceParentDocumentUri,
                    destinationParentDocumentUri
                ) != null
            } catch (@Suppress("SwallowedException") e: IllegalStateException) {
                copyThenDeleteSAFRecording(recording, destinationParent)
            } catch (_: Exception) {
                false
            }
            success = moved && success
        }

        callback(success)
    }
}

private fun BaseSimpleActivity.copyThenDeleteSAFRecording(
    recording: Recording,
    destinationParent: String
): Boolean {
    val sourceUri = recording.path.toUri()
    return try {
        val targetPath = File(destinationParent, recording.title).absolutePath
        val targetUri = createDocumentFile(targetPath) ?: return false
        val copied = contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
                true
            }
        } == true
        copied && DocumentsContract.deleteDocument(contentResolver, sourceUri)
    } catch (_: Exception) {
        false
    }
}

private fun BaseSimpleActivity.moveRecordingsLegacy(
    recordings: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    copyMoveFilesTo(
        fileDirItems = recordings
            .map { File(it.path).toFileDirItem(this) }
            .toMutableList() as ArrayList<FileDirItem>,
        source = sourceParent,
        destination = destinationParent,
        isCopyOperation = false,
        copyPhotoVideoOnly = false,
        copyHidden = false
    ) {
        callback(true)
    }
}

fun BaseSimpleActivity.deleteTrashedRecordings() {
    deleteRecordings(getAllRecordings(trashed = true)) {}
}

fun BaseSimpleActivity.deleteExpiredTrashedRecordings() {
    if (
        config.useRecycleBin &&
        config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000
    ) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                val recordingsToRemove = getAllRecordings(trashed = true)
                    .filter { it.timestamp < System.currentTimeMillis() - MONTH_SECONDS * 1000L }
                if (recordingsToRemove.isNotEmpty()) {
                    deleteRecordings(recordingsToRemove) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
