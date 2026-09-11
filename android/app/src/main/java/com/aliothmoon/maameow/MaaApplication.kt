package com.aliothmoon.maameow
import android.app.Application
import com.aliothmoon.maameow.manager.RemoteServiceManager
class MaaApplication : Application() {
    override fun onCreate() { super.onCreate(); RemoteServiceManager.initialize(this); registerActivityLifecycleCallbacks(com.aliothmoon.maameow.mower.MowerVisibility) }
    suspend fun awaitReady() = Unit
}
