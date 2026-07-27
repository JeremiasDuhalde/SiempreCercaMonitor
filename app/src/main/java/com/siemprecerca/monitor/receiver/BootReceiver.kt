package com.siemprecerca.monitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService

/**
 * Arranca el servicio BLE automaticamente cuando el celular se enciende.
 * Solo si el setup fue completado.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            val prefs = Preferences(context)
            if (prefs.isSetupComplete) {
                Log.i("BootReceiver", "Boot detectado, iniciando servicio BLE")
                FlicBleService.start(context)
            }
        }
    }
}
