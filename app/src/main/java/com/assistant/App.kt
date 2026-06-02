package com.assistant

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Hardware-level panic interception active app-wide
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this))
    }
}
