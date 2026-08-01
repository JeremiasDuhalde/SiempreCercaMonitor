package com.siemprecerca.monitor.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.siemprecerca.monitor.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captura crashes no manejados y los envia al servidor.
 * Tambien guarda el ultimo crash en SharedPreferences para envio posterior
 * si no hay conexion en el momento del crash.
 */
class CrashReporter(private val context: Context) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashReporter"
        private const val PREF_KEY = "pending_crash_report"

        fun install(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashReporter(context).apply {
                this.defaultHandler = defaultHandler
            })
            // Intentar enviar crash pendiente de sesion anterior
            sendPendingCrash(context)
        }

        private fun sendPendingCrash(context: Context) {
            val prefs = context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
            val pending = prefs.getString(PREF_KEY, null) ?: return

            Thread {
                try {
                    val client = OkHttpClient()
                    val token = Preferences(context).getServerConfig().authToken
                    val request = Request.Builder()
                        .url("${Config.BASE_URL}/api/webhooks/crash-report")
                        .header("Authorization", "Bearer $token")
                        .header("X-Webhook-Secret", Config.WEBHOOK_SECRET)
                        .post(pending.toRequestBody("application/json".toMediaType()))
                        .build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        prefs.edit().remove(PREF_KEY).apply()
                        Log.i(TAG, "Crash pendiente enviado OK")
                    }
                    response.close()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo enviar crash pendiente: ${e.message}")
                }
            }.start()
        }
    }

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val report = JSONObject().apply {
                put("app_version", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", "API ${Build.VERSION.SDK_INT}")
                put("thread", thread.name)
                put("exception", throwable.javaClass.simpleName)
                put("message", throwable.message ?: "")
                put("stacktrace", sw.toString().take(2000))
                put("timestamp", System.currentTimeMillis())
            }

            // Guardar en prefs por si no hay conexion
            context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY, report.toString())
                .apply()

            // Intentar enviar inmediatamente (best effort)
            val client = OkHttpClient.Builder()
                .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val token = Preferences(context).getServerConfig().authToken
            val request = Request.Builder()
                .url("${Config.BASE_URL}/api/webhooks/crash-report")
                .header("Authorization", "Bearer $token")
                .header("X-Webhook-Secret", Config.WEBHOOK_SECRET)
                .post(report.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // Si falla, queda guardado en prefs para la proxima vez
        }

        // Delegar al handler original para que Android muestre el dialog de crash
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
