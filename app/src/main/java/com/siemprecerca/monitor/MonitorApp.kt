package com.siemprecerca.monitor

import android.app.Application
import android.util.Log
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService
import com.siemprecerca.monitor.worker.MonitorWorker

class MonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val prefs = Preferences(this)
        if (prefs.isSetupComplete) {
            Log.i("MonitorApp", "App iniciada, arrancando servicio y worker")
            FlicBleService.start(this)
            MonitorWorker.schedule(this)
        }
    }
}
