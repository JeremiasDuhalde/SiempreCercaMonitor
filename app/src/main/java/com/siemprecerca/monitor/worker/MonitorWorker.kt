package com.siemprecerca.monitor.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.siemprecerca.monitor.data.AlertManager
import com.siemprecerca.monitor.data.AuthManager
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService
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

        return Result.success()
    }
}
