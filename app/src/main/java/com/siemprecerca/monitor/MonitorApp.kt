package com.siemprecerca.monitor

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
        private const val ALARM_DURATION_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())

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

                    // Feature 4: guardar timestamp del ultimo click
                    prefs.lastClickTime = System.currentTimeMillis()

                    // Feature 5: reproducir sonido de alarma fuerte
                    playAlarmSound()

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

                // Feature 2: guardar bateria del FLIC
                try {
                    val batteryLevel = button.lastKnownBatteryLevel
                    if (batteryLevel != null) {
                        val voltage = batteryLevel.voltage
                        if (voltage > 0f) {
                            prefs.flicBatteryVoltage = voltage
                            prefs.flicBatteryTimestamp = System.currentTimeMillis()
                            Log.i(TAG, "Bateria FLIC guardada: ${voltage}v")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error leyendo bateria FLIC: ${e.message}")
                }

                // Actualizar serial, MAC y nombre real del FLIC en la config
                val deviceConfig = prefs.getDeviceConfig()
                if (deviceConfig != null) {
                    val realSerial = button.serialNumber
                    val realMac = button.bdAddr
                    var updated = false
                    var newConfig = deviceConfig

                    if (!realSerial.isNullOrBlank() && deviceConfig.serialNumber != realSerial) {
                        newConfig = newConfig.copy(serialNumber = realSerial)
                        updated = true
                        Log.i(TAG, "Serial actualizado a: $realSerial")
                    }
                    if (!realMac.isNullOrBlank() && deviceConfig.macAddress != realMac) {
                        newConfig = newConfig.copy(macAddress = realMac)
                        updated = true
                        Log.i(TAG, "MAC actualizada a: $realMac")
                    }
                    if (updated) {
                        prefs.saveDeviceConfig(newConfig)
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

    /**
     * Reproduce un sonido de alarma a volumen maximo durante 3 segundos.
     */
    private fun playAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return

            // Subir volumen al maximo
            val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 7
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(applicationContext, alarmUri)
                prepare()
                start()
            }

            // Vibrar
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.vibrate(VibrationEffect.createOneShot(ALARM_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) {
                Log.e(TAG, "Error vibrando: ${e.message}")
            }

            // Detener despues de 3 segundos
            handler.postDelayed({
                try {
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.stop()
                    }
                    mediaPlayer.release()
                } catch (_: Exception) {}
            }, ALARM_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo alarma: ${e.message}")
        }
    }
}
