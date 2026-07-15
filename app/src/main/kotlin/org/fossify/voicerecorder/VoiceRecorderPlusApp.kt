package org.fossify.voicerecorder

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.fossify.commons.FossifyApp
import org.fossify.voicerecorder.helpers.AppVisibilityTracker

class VoiceRecorderPlusApp : FossifyApp(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        AppVisibilityTracker.activityStarted()
    }

    override fun onActivityStopped(activity: Activity) {
        AppVisibilityTracker.activityStopped()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
