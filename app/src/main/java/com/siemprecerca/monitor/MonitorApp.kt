package com.siemprecerca.monitor

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.siemprecerca.monitor.data.AlertManager
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService
import io.flic.flic2libandroid.Flic2Button
import io.flic.flic2libandroid.Flic2ButtonListener
import io.flic.flic2libandroid.Flic2Manager

class MonitorApp : Application() {

    companion object {
        private const val TAG = "MonitorApp"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Flic2Manager.initAndGetInstance(applicationContext, Handler(Looper.getMainLooper()))
            Log.i(TAG, "Flic2Manager inicializado")
        } catch (e: Exception) {
            Log.e(TAG, "Error init Flic2Manager: ${e.message}")
        }

        val prefs = Preferences(this)
        if (prefs.isSetupComplete) {
            try {
                for (button in Flic2Manager.getInstance().buttons) {
                    addButtonListener(button)
                    button.connect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reconectando: ${e.message}")
            }
            FlicBleService.start(this)
        }
    }

    fun addButtonListener(button: Flic2Button) {
        val prefs = Preferences(this)
        val alertManager = AlertManager(this)

        button.addListener(object : Flic2ButtonListener() {
            override fun onButtonUpOrDown(
                button: Flic2Button, wasQueued: Boolean, lastQueued: Boolean,
                timestamp: Long, isUp: Boolean, isDown: Boolean
            ) {
                if (wasQueued && button.readyTimestamp - timestamp > 30_000) return
                if (isDown) {
                    Log.i(TAG, "*** BOTON PRESIONADO *** serial=${button.serialNumber} name=${button.name}")
                    alertManager.processAlert("FLIC2_CLICK".toByteArray())
                    sendBroadcast(android.content.Intent("com.siemprecerca.monitor.STATE_CHANGED"))
                }
            }

            override fun onConnect(button: Flic2Button) {
                Log.i(TAG, "CONECTADO: ${button.bdAddr}")
                sendBroadcast(android.content.Intent("com.siemprecerca.monitor.STATE_CHANGED"))
            }

            override fun onReady(button: Flic2Button, timestamp: Long) {
                Log.i(TAG, "LISTO: serial=${button.serialNumber} name=${button.name} uuid=${button.uuid} battery=${button.lastKnownBatteryLevel}")

                // Actualizar serial y nombre real del FLIC en la config
                val deviceConfig = prefs.getDeviceConfig()
                if (deviceConfig != null) {
                    val realSerial = button.serialNumber
                    val realName = button.name
                    if (!realSerial.isNullOrBlank() && deviceConfig.serialNumber != realSerial) {
                        prefs.saveDeviceConfig(deviceConfig.copy(serialNumber = realSerial))
                        Log.i(TAG, "Serial actualizado a: $realSerial")
                    }
                }

                if (prefs.isHealthCheckEnabled) alertManager.sendHealthPing()
                sendBroadcast(android.content.Intent("com.siemprecerca.monitor.STATE_CHANGED"))
            }

            override fun onDisconnect(button: Flic2Button) {
                Log.i(TAG, "DESCONECTADO: ${button.bdAddr}")
                sendBroadcast(android.content.Intent("com.siemprecerca.monitor.STATE_CHANGED"))
            }

            override fun onUnpaired(button: Flic2Button) {
                Log.i(TAG, "DESVINCULADO")
            }
        })
    }
}
