package com.jedrama.fork

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JeDramaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
