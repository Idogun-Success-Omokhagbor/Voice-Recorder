package org.fossify.voicerecorder.activities

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import me.grantland.widget.AutofitHelper
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.getBottomNavigationBackgroundColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.onPageChangeListener
import org.fossify.commons.extensions.onTabSelectionChanged
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateBottomTabItemColors
import org.fossify.commons.helpers.PERMISSION_RECORD_AUDIO
import org.fossify.commons.helpers.PERMISSION_WRITE_STORAGE
import org.fossify.commons.helpers.isQPlus
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.adapters.ViewPagerAdapter
import org.fossify.voicerecorder.databinding.ActivityMainBinding
import org.fossify.voicerecorder.dialogs.AboutDialog
import org.fossify.voicerecorder.dialogs.StoragePermissionDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteExpiredTrashedRecordings
import org.fossify.voicerecorder.extensions.ensureDefaultRecordingsFolderExists
import org.fossify.voicerecorder.helpers.AppVisibilityTracker
import org.fossify.voicerecorder.helpers.BackgroundWarningPermission
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.helpers.email.EmailAddressValidator
import org.fossify.voicerecorder.helpers.email.EmailSubjectFormatter
import org.fossify.voicerecorder.helpers.email.RecordingEmailIntentFactory
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {
    companion object {
        private const val EXIT_AFTER_SAVE_DELAY_MS = 500L
        private const val EMAIL_COMPOSER_REQUEST_CODE = 4105
    }

    private var bus: EventBus? = null
    private var launchedSettings = false
    private var checkingAutoRecordNotificationPermission = false
    private var requestingBackgroundWarningPermission = false

    override var isSearchBarEnabled = true

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder))

        if (checkAppSideloading()) {
            return
        }

        if (savedInstanceState == null) {
            deleteExpiredTrashedRecordings()
        }

        bus = EventBus.getDefault()
        bus!!.register(this)

        tryInitVoiceRecorder()
    }

    override fun onResume() {
        super.onResume()
        updateMenuColors()
        val pagerAdapter = getPagerAdapter()
        if (pagerAdapter == null) {
            return
        }

        if (pagerAdapter.showRecycleBin != config.useRecycleBin) {
            setupViewPager()
        }
        setupTabColors()
        getPagerAdapter()?.onResume()
        selectRecorderAfterSettingsReturnIfNeeded()
        maybeRequestBackgroundWarningPermission()
        startRecordingIfConfigured()
        sendRecorderAction(GET_RECORDER_INFO)
    }

    override fun onPause() {
        super.onPause()
        config.lastUsedViewPagerPage = 0
    }

    override fun onDestroy() {
        bus?.unregister(this)
        getPagerAdapter()?.onDestroy()

        Intent(this@MainActivity, RecorderService::class.java).apply {
            action = STOP_AMPLITUDE_UPDATE
            try {
                startService(this)
            } catch (ignored: Exception) {
            }
        }
        super.onDestroy()
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else if (isThirdPartyIntent()) {
            setResult(Activity.RESULT_CANCELED, null)
            false
        } else {
            false
        }
    }

    private fun refreshMenuItems() {
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.more_apps_from_us).isVisible = !resources.getBoolean(
                org.fossify.commons.R.bool.hide_google_relations
            )
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.requireToolbar().inflateMenu(R.menu.menu)
        binding.mainMenu.toggleHideOnScroll(false)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchOpenListener = {
            if (binding.viewPager.currentItem == 0) {
                binding.viewPager.currentItem = 1
            }
        }

        binding.mainMenu.onSearchTextChangedListener = { text ->
            getPagerAdapter()?.searchTextChanged(text)
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun tryInitVoiceRecorder() {
        if (shouldConfirmRecordingFolderOnLaunch()) {
            StoragePermissionDialog(this) { granted ->
                if (!granted) {
                    toast(org.fossify.commons.R.string.no_storage_permissions)
                    finish()
                    return@StoragePermissionDialog
                }

                config.defaultRecordingFolderConfirmed = true
                config.openedSettingsOnFirstUse = false
                ensureDefaultRecordingsFolderExists()
                handleStorageAndAudioPermissionAndSetup()
            }
        } else {
            ensureDefaultRecordingsFolderExists()
            handleStorageAndAudioPermissionAndSetup()
        }
    }

    private fun handleStorageAndAudioPermissionAndSetup() {
        if (isQPlus()) {
            handleAudioPermissionAndSetup()
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE) {
                if (it) {
                    handleAudioPermissionAndSetup()
                } else {
                    toast(org.fossify.commons.R.string.no_storage_permissions)
                    finish()
                }
            }
        }
    }

    private fun handleAudioPermissionAndSetup() {
        handlePermission(PERMISSION_RECORD_AUDIO) {
            if (it) {
                requestNotificationPermissionAndSetup()
            } else {
                toast(org.fossify.commons.R.string.no_audio_permissions)
                finish()
            }
        }
    }

    private fun requestNotificationPermissionAndSetup() {
        Handler(Looper.getMainLooper()).post {
            if (isFinishing || isDestroyed) {
                return@post
            }

            handleNotificationPermission {
                setupViewPager()
            }
        }
    }

    private fun setupViewPager() {
        binding.mainTabsHolder.removeAllTabs()
        var tabDrawables = arrayOf(
            org.fossify.commons.R.drawable.ic_microphone_vector,
            R.drawable.ic_playlist_play_vector
        )
        var tabLabels = arrayOf(R.string.recorder, R.string.player)
        if (config.useRecycleBin) {
            tabDrawables += org.fossify.commons.R.drawable.ic_delete_vector
            tabLabels += org.fossify.commons.R.string.recycle_bin
        }

        tabDrawables.forEachIndexed { i, drawableId ->
            binding.mainTabsHolder.newTab()
                .setCustomView(org.fossify.commons.R.layout.bottom_tablayout_item).apply {
                    customView
                        ?.findViewById<ImageView>(org.fossify.commons.R.id.tab_item_icon)
                        ?.setImageDrawable(
                            AppCompatResources.getDrawable(
                                this@MainActivity,
                                drawableId
                            )
                        )

                    customView
                        ?.findViewById<TextView>(org.fossify.commons.R.id.tab_item_label)
                        ?.setText(tabLabels[i])

                    AutofitHelper.create(
                        customView?.findViewById(org.fossify.commons.R.id.tab_item_label)
                    )

                    binding.mainTabsHolder.addTab(this)
                }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(it.customView, false)
                if (it.position == 1 || it.position == 2) {
                    binding.mainMenu.closeSearch()
                }
            },
            tabSelectedAction = {
                binding.viewPager.currentItem = it.position
                updateBottomTabItemColors(it.customView, true)
            }
        )

        binding.viewPager.adapter = ViewPagerAdapter(this, config.useRecycleBin)
        binding.viewPager.offscreenPageLimit = 2
        binding.viewPager.onPageChangeListener {
            binding.mainTabsHolder.getTabAt(it)?.select()
            (binding.viewPager.adapter as ViewPagerAdapter).finishActMode()
        }

        if (isThirdPartyIntent()) {
            binding.viewPager.currentItem = 0
        } else {
            config.lastUsedViewPagerPage = 0
            binding.viewPager.currentItem = 0
            binding.mainTabsHolder.getTabAt(0)?.select()
            openSettingsOnFirstUse()
        }

        startRecordingIfConfigured()
    }

    private fun selectRecorderAfterSettingsReturnIfNeeded() {
        if (!launchedSettings) {
            return
        }

        launchedSettings = false
        binding.viewPager.currentItem = 0
        binding.mainTabsHolder.getTabAt(0)?.select()
    }

    private fun startRecordingIfConfigured() {
        if (!config.recordAfterLaunch || checkingAutoRecordNotificationPermission) {
            return
        }

        checkingAutoRecordNotificationPermission = true
        handleNotificationPermission { granted ->
            checkingAutoRecordNotificationPermission = false
            if (!granted || isFinishing || isDestroyed) {
                return@handleNotificationPermission
            }

            if (binding.viewPager.adapter != null) {
                binding.viewPager.currentItem = 0
                binding.mainTabsHolder.getTabAt(0)?.select()
            }
            Intent(this@MainActivity, RecorderService::class.java).apply {
                try {
                    startService(this)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun shouldOpenSettingsOnFirstUse() = !config.openedSettingsOnFirstUse && !isThirdPartyIntent()

    private fun shouldConfirmRecordingFolderOnLaunch() =
        !config.defaultRecordingFolderConfirmed && !isThirdPartyIntent()

    private fun openSettingsOnFirstUse() {
        if (!shouldOpenSettingsOnFirstUse()) {
            return
        }

        config.openedSettingsOnFirstUse = true
        Handler(Looper.getMainLooper()).post {
            if (!isFinishing && !isDestroyed) {
                launchSettings()
            }
        }
    }

    private fun maybeRequestBackgroundWarningPermission() {
        if (!config.backgroundRecordingWarning ||
            requestingBackgroundWarningPermission ||
            BackgroundWarningPermission.isGranted(this)
        ) {
            return
        }

        requestingBackgroundWarningPermission = true
        AlertDialog.Builder(this)
            .setTitle(R.string.allow_background_warning)
            .setMessage(R.string.allow_background_warning_description)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                if (!BackgroundWarningPermission.openSettings(this)) {
                    toast(R.string.warning_settings_unavailable)
                }
            }
            .setNegativeButton(R.string.not_now, null)
            .setOnDismissListener {
                requestingBackgroundWarningPermission = false
            }
            .show()
    }

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(activeView, true)
        for (i in 0 until binding.mainTabsHolder.tabCount) {
            if (i != binding.viewPager.currentItem) {
                val inactiveView = binding.mainTabsHolder.getTabAt(i)?.customView
                updateBottomTabItemColors(inactiveView, false)
            }
        }

        binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.select()
        val bottomBarColor = getBottomNavigationBackgroundColor()
        binding.mainTabsHolder.setBackgroundColor(bottomBarColor)
    }

    private fun getPagerAdapter() = (binding.viewPager.adapter as? ViewPagerAdapter)

    private fun launchSettings() {
        hideKeyboard()
        launchedSettings = true
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        AboutDialog(this)
    }

    private fun isThirdPartyIntent() = intent?.action == MediaStore.Audio.Media.RECORD_SOUND_ACTION

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingSaved(event: Events.RecordingSaved) {
        if (event.shouldOpenEmailComposer) {
            openEmailClient(event.uri)
            return
        }

        if (!event.errorMessage.isNullOrBlank()) {
            toast(event.errorMessage)
        }

        if (!event.shouldExit) {
            return
        }

        if (isThirdPartyIntent()) {
            Intent().apply {
                data = event.uri!!
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        } else {
            exitAppAfterSave()
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun backgroundRecordingWarning(event: Events.BackgroundRecordingWarning) {
        if (!AppVisibilityTracker.isInForeground() || isFinishing || isDestroyed) {
            return
        }

        Intent(this, BackgroundRecordingWarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(this)
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun exitApplication(event: Events.ExitApplication) {
        exitAppAfterSave()
    }

    private fun sendRecorderAction(recorderAction: String) {
        Intent(this, RecorderService::class.java).apply {
            action = recorderAction
            try {
                startService(this)
            } catch (_: Exception) {
            }
        }
    }

    private fun openEmailClient(recordingUri: Uri?) {
        val emailAddress = config.recordingEmailAddress
        val emailIntent = recordingUri
            ?.takeIf { EmailAddressValidator.isValid(emailAddress) }
            ?.let {
                RecordingEmailIntentFactory.create(
                    context = this,
                    recordingUri = it,
                    recipient = emailAddress,
                    subject = EmailSubjectFormatter.subject()
                )
            }

        if (emailIntent == null) {
            toast(R.string.no_email_app_available)
            return
        }

        try {
            launchEmailIntent(emailIntent)
            finish()
        } catch (_: Exception) {
            toast(R.string.no_email_app_available)
            moveTaskToBack(true)
        }
    }

    private fun launchEmailIntent(emailIntent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivity(emailIntent)
            return
        }

        val creatorOptions = ActivityOptions.makeBasic().apply {
            pendingIntentCreatorBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            EMAIL_COMPOSER_REQUEST_CODE,
            emailIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions.toBundle()
        )
        val senderOptions = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
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
    }

    private fun exitAppAfterSave() {
        Handler(Looper.getMainLooper()).postDelayed({
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
        }, EXIT_AFTER_SAVE_DELAY_MS)
    }
}
