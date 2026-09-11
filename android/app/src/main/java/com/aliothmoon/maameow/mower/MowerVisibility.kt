package com.aliothmoon.maameow.mower

import android.app.Activity
import android.app.Application
import android.os.Bundle

object MowerVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile var visible = false; private set
    override fun onActivityResumed(activity: Activity) { visible = true; MowerScreenSaver.hide() }
    override fun onActivityPaused(activity: Activity) { visible = false }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
