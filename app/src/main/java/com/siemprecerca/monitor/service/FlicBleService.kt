package com.siemprecerca.monitor.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.siemprecerca.monitor.MainActivity
import com.siemprecerca.monitor.R
import com.siemprecerca.monitor.data.AlertManager
import com.siemprecerca.monitor.data.Preferences
import io.flic.flic2libandroid.Flic2Manager

class FlicBleService : Service() {

    companion object {
        private const val TAG = "FlicService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "siemprecerca_monitor"
        const val ALERT_CHANNEL_ID = "siemprecerca_alerts"
        private const val HEALTH_INTERVAL_MS = 3_600_000L

        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, FlicBleService::class.java)) }
            catch (_: Exception) {}
        }
        fun stop(context: Context) {
            try { context.startService(Intent(context, FlicBleService::class.java).apply { action = "STOP" }) }
            catch (_: Exception) {}
        }
    }

    private lateinit var prefs: Preferences
    private val handler = Handler(Looper.getMainLooper())
    private var healthScheduled = false
    private val binder = LocalBinder()
    private var batteryReceiverRegistered = false
    private var batteryLowAlertSent = false

    inner class LocalBinder : Binder() { fun getService() = this@FlicBleService }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
        createChannels()
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            unregisterBatteryReceiver()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION_ID, notif(getStatus()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, notif(getStatus()))
        } catch (_: Exception) {
            try { startForeground(NOTIFICATION_ID, notif("Activo")) } catch (_: Exception) {}
        }
        startHealth()
        handler.postDelayed(statusUpdater, 5000)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterBatteryReceiver()
        super.onDestroy()
    }

    // --- Feature 3: Battery monitoring ---

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            try {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (scale > 0) (level * 100) / scale else level
                prefs.phoneBatteryLevel = pct
                Log.d(TAG, "Bateria celular: $pct%")

                // Enviar alerta si baja de 15%
                if (pct in 1..14 && !batteryLowAlertSent) {
                    batteryLowAlertSent = true
                    AlertManager(this@FlicBleService).sendBatteryAlert(pct)
                    Log.w(TAG, "Bateria baja: $pct% - alerta enviada")
                } else if (pct >= 15) {
                    batteryLowAlertSent = false
                }

                sendBroadcast(Intent("com.siemprecerca.monitor.STATE_CHANGED"))
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando bateria: ${e.message}")
            }
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, filter)
            batteryReceiverRegistered = true
            Log.i(TAG, "Battery receiver registrado")
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando battery receiver: ${e.message}")
        }
    }

    private fun unregisterBatteryReceiver() {
        if (!batteryReceiverRegistered) return
        try {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        } catch (_: Exception) {}
    }

    // --- Status & Health ---

    private fun getStatus(): String {
        return try {
            val buttons = Flic2Manager.getInstance().buttons
            if (buttons.isEmpty()) "Sin botones"
            else {
                val btn = buttons[0]
                val serial = btn.serialNumber ?: btn.bdAddr
                when (btn.connectionState) {
                    0 -> "Desconectado ($serial)"
                    1 -> "Conectando... ($serial)"
                    2 -> "Inicializando... ($serial)"
                    3 -> "Conectado - Monitoreando SOS ($serial)"
                    else -> "Estado ${btn.connectionState}"
                }
            }
        } catch (_: Exception) { "Activo" }
    }

    fun isConnected(): Boolean = try { Flic2Manager.getInstance().buttons.any { it.connectionState == 3 } } catch (_: Exception) { false }

    private val statusUpdater = object : Runnable {
        override fun run() {
            try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif(getStatus())) } catch (_: Exception) {}
            handler.postDelayed(this, 5000)
        }
    }

    private fun startHealth() {
        if (healthScheduled) return; healthScheduled = true
        handler.postDelayed({
            if (isConnected()) {
                val am = AlertManager(this)
                am.sendHealthPing()
                am.flushPendingAlerts()
            }
        }, 10_000)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isConnected() && prefs.isHealthCheckEnabled) {
                    val am = AlertManager(this@FlicBleService)
                    am.sendHealthPing()
                    am.flushPendingAlerts()
                }
                handler.postDelayed(this, HEALTH_INTERVAL_MS)
            }
        }, HEALTH_INTERVAL_MS)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Siempre Cerca", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "Alertas SOS", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Siempre Cerca").setContentText(text).setSmallIcon(R.drawable.ic_monitor).setOngoing(true).setContentIntent(pi).build()
    }
}
