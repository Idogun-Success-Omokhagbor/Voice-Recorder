package org.fossify.voicerecorder.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.TextView
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
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.adapters.ViewPagerAdapter
import org.fossify.voicerecorder.databinding.ActivityMainBinding
import org.fossify.voicerecorder.dialogs.AboutDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteExpiredTrashedRecordings
import org.fossify.voicerecorder.extensions.ensureStoragePermission
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlin.system.exitProcess

class MainActivity : SimpleActivity() {
    companion object {
        private const val EXIT_AFTER_SAVE_DELAY_MS = 500L
        private const val EXIT_AFTER_EMAIL_SAVE_DELAY_MS = 1500L
    }

    private var bus: EventBus? = null
    private var handledRecordAfterLaunch = false

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

        tryInitVoiceRecorder()

        bus = EventBus.getDefault()
        bus!!.register(this)
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
    }

    override fun onPause() {
        super.onPause()
        config.lastUsedViewPagerPage = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
        getPagerAdapter()?.onDestroy()

        Intent(this@MainActivity, RecorderService::class.java).apply {
            action = STOP_AMPLITUDE_UPDATE
            try {
                startService(this)
            } catch (ignored: Exception) {
            }
        }
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
        if (isRPlus()) {
            val shouldConfirmFolder = shouldConfirmRecordingFolderOnLaunch()
            ensureStoragePermission(
                forceDefaultFolderConfirmation = shouldConfirmFolder
            ) { granted ->
                if (granted) {
                    if (shouldConfirmFolder) {
                        config.defaultRecordingFolderConfirmed = true
                        config.openedSettingsOnFirstUse = false
                    }
                    handleAudioPermissionAndSetup()
                } else {
                    toast(org.fossify.commons.R.string.no_storage_permissions)
                    finish()
                }
            }
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
                setupViewPager()
            } else {
                toast(org.fossify.commons.R.string.no_audio_permissions)
                finish()
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

        startRecordingAfterLaunchIfNeeded()
    }

    private fun startRecordingAfterLaunchIfNeeded() {
        if (handledRecordAfterLaunch) {
            return
        }

        handledRecordAfterLaunch = true
        if (config.recordAfterLaunch && !RecorderService.isRunning) {
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
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        AboutDialog(this)
    }

    private fun isThirdPartyIntent() = intent?.action == MediaStore.Audio.Media.RECORD_SOUND_ACTION

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingSaved(event: Events.RecordingSaved) {
        if (isThirdPartyIntent()) {
            Intent().apply {
                data = event.uri!!
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        } else {
            val exitDelay = if (event.isEmail) EXIT_AFTER_EMAIL_SAVE_DELAY_MS else EXIT_AFTER_SAVE_DELAY_MS
            exitAppProcessAfterSave(exitDelay)
        }
    }

    private fun exitAppProcessAfterSave(delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            finishAndRemoveTask()
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }, delayMs)
    }
}
