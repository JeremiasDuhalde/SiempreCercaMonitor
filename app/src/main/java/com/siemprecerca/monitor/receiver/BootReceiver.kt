package com.siemprecerca.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService
import io.flic.flic2libandroid.Flic2Manager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")) {
            if (Preferences(context).isSetupComplete) {
                Log.i("BootReceiver", "Boot: arrancando")
                FlicBleService.start(context)
                try { for (b in Flic2Manager.getInstance().buttons) b.connect() } catch (_: Exception) {}
            }
        }
    }
}
