package com.shengjidaguai.goutoujunshi

import android.app.Application
import android.os.Process
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JunshiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Private runtime belongs to the App sandbox. Isolated OCR must start without
        // accessing preferences, Controller, keys, notifications or screen receivers.
        if(Process.isApplicationUid(Process.myUid())) com.shengjidaguai.goutoujunshi.privacy.PrivacyRuntime.install(this)
    }
}
