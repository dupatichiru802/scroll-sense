package com.dupati.scrollsense

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ScrollSenseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        SyncAlarmReceiver.scheduleNext(this)
    }
}
