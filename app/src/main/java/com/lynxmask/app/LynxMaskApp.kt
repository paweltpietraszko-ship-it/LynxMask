package com.lynxmask.app

import android.app.Application

class LynxMaskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
