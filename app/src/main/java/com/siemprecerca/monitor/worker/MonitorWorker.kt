package com.siemprecerca.monitor.worker

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.siemprecerca.monitor.BuildConfig
import com.siemprecerca.monitor.R
import com.siemprecerca.monitor.data.AlertManager
import com.siemprecerca.monitor.data.AuthManager
import com.siemprecerca.monitor.data.Config
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodico que verifica cada 15 minutos que el servicio BLE
 * este corriendo. Si murio, lo revive. Tambien sincroniza contactos.
 * Sobrevive reinicios del celular.
 */
class MonitorWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "MonitorWorker"
        private const val WORK_NAME = "siemprecerca_monitor_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Worker periodico programado")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override fun doWork(): Result {
        val prefs = Preferences(applicationContext)

        if (!prefs.isSetupComplete) {
            Log.d(TAG, "Setup no completado, saltando")
            return Result.success()
        }

        // Renovar JWT (expira cada 8 horas, worker corre cada 15 min)
        val authManager = AuthManager(prefs)
        authManager.login()

        // Asegurar que el servicio BLE este corriendo
        Log.i(TAG, "Check periodico: reiniciando servicio BLE")
        FlicBleService.start(applicationContext)

        // Sincronizar contactos de emergencia desde el servidor
        val alertManager = AlertManager(applicationContext)
        alertManager.syncContacts()

        // Check de actualizacion en background (cada 15 min)
        checkForUpdate()

        return Result.success()
    }

    private fun checkForUpdate() {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("${Config.BASE_URL}/monitor/version.json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return }

            val json = JSONObject(response.body?.string() ?: "{}")
            response.close()

            val remoteCode = json.optInt("versionCode", 0)
            val remoteVersion = json.optString("version", "")
            val currentCode = BuildConfig.VERSION_CODE

            if (remoteCode > currentCode) {
                Log.i(TAG, "Actualizacion disponible: v$remoteVersion (code $remoteCode > $currentCode)")
                showUpdateNotification(remoteVersion)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error verificando actualizacion: ${e.message}")
        }
    }

    private fun showUpdateNotification(version: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, "monitor_status")
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("Actualizacion disponible")
            .setContentText("Version $version lista. Abri la app para actualizar.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(9999, notification)
    }
}
