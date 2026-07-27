package com.siemprecerca.monitor.receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService

/**
 * Detecta cambios en el estado de Bluetooth.
 * Si Bluetooth se vuelve a encender, reinicia la conexion BLE.
 */
class BluetoothStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        val prefs = Preferences(context)

        when (state) {
            BluetoothAdapter.STATE_ON -> {
                if (prefs.isSetupComplete) {
                    Log.i("BluetoothStateReceiver", "Bluetooth encendido, reiniciando servicio")
                    FlicBleService.start(context)
                }
            }
            BluetoothAdapter.STATE_OFF -> {
                Log.w("BluetoothStateReceiver", "Bluetooth apagado")
            }
        }
    }
}
