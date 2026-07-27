package com.siemprecerca.monitor.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Gestiona el envio de alertas por HTTP al servidor y SMS a contactos de emergencia.
 * Doble via: internet + SMS como fallback.
 */
class AlertManager(private val context: Context) {

    companion object {
        private const val TAG = "AlertManager"
        // Cooldown entre alertas para evitar spam (30 segundos)
        private const val ALERT_COOLDOWN_MS = 30_000L
    }

    private val prefs = Preferences(context)
    private val authManager = AuthManager(prefs)
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var lastAlertTime = 0L

    /**
     * Procesa un evento SOS del reloj FLIC.
     * Envia alerta HTTP al servidor y SMS a contactos de emergencia.
     * Tiene cooldown de 30s para evitar alertas duplicadas por rebotes del boton.
     */
    fun processAlert(bleData: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < ALERT_COOLDOWN_MS) {
            Log.d(TAG, "Alerta ignorada por cooldown (${now - lastAlertTime}ms desde la ultima)")
            return
        }
        lastAlertTime = now

        val deviceConfig = prefs.getDeviceConfig() ?: run {
            Log.e(TAG, "No hay dispositivo configurado")
            return
        }

        // Actualizar stats
        prefs.alertCount = prefs.alertCount + 1
        prefs.lastAlertTime = now

        Log.i(TAG, "Procesando alerta SOS de ${deviceConfig.serialNumber} (BLE data: ${bleData.toHexString()})")

        // Enviar HTTP al servidor
        sendHttpAlert(deviceConfig, bleData)

        // Enviar SMS a contactos
        if (prefs.isSmsEnabled) {
            sendSmsAlerts(deviceConfig)
        }
    }

    /**
     * POST al endpoint /api/webhooks/flic/alert con el mismo formato
     * que usa la app FLIC original. Headers con serial, nombre y GPS.
     */
    private fun sendHttpAlert(device: DeviceConfig, bleData: ByteArray) {
        val serverConfig = prefs.getServerConfig()
        val url = "${serverConfig.baseUrl}/api/webhooks/flic/alert"

        val jsonBody = gson.toJson(AlertPayload(
            event = "sos",
            source = "siemprecerca_monitor",
            appVersion = "1.0.0"
        ))

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .addHeader("X-Webhook-Secret", serverConfig.webhookSecret)
            .addHeader("button-serial-number", device.serialNumber)
            .addHeader("button-name", "Flic ${device.serialNumber}")
            .addHeader("X-BLE-Raw", bleData.toHexString())
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Error enviando alerta HTTP: ${e.message}")
                // La alerta SMS ya fue enviada como fallback
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.i(TAG, "Alerta HTTP enviada OK: ${it.body?.string()}")
                    } else if (it.code == 401) {
                        Log.w(TAG, "JWT expirado, renovando...")
                        authManager.login { success ->
                            if (success) sendHttpAlert(device, bleData)
                        }
                    } else {
                        Log.e(TAG, "Alerta HTTP error ${it.code}: ${it.body?.string()}")
                    }
                }
            }
        })
    }

    /**
     * Envia SMS a todos los contactos de emergencia del paciente.
     * Reemplaza {nombre} en el template con el nombre del paciente.
     */
    private fun sendSmsAlerts(device: DeviceConfig) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Sin permiso SEND_SMS")
            return
        }

        val contacts = prefs.getContacts()
        if (contacts.isEmpty()) {
            Log.w(TAG, "No hay contactos de emergencia configurados")
            return
        }

        val message = prefs.smsMessageTemplate
            .replace("{nombre}", device.clientName)

        val smsManager = context.getSystemService(SmsManager::class.java)

        contacts.forEach { contact ->
            try {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(
                    contact.phone, null, parts, null, null
                )
                Log.i(TAG, "SMS enviado a ${contact.name} (${contact.phone})")
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando SMS a ${contact.name}: ${e.message}")
            }
        }
    }

    /**
     * Obtiene los contactos de emergencia del servidor y los guarda localmente.
     * Se ejecuta durante el setup y periodicamente para mantener sincronizados.
     */
    fun syncContacts(callback: ((Boolean) -> Unit)? = null) {
        val deviceConfig = prefs.getDeviceConfig()
        if (deviceConfig == null || deviceConfig.clientId == 0) {
            callback?.invoke(false)
            return
        }

        val serverConfig = prefs.getServerConfig()
        val url = "${serverConfig.baseUrl}/api/clients/${deviceConfig.clientId}/contacts"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer ${serverConfig.authToken}")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Error sincronizando contactos: ${e.message}")
                callback?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val body = it.body?.string() ?: "[]"
                        val contacts: List<EmergencyContact> = gson.fromJson(
                            body,
                            object : com.google.gson.reflect.TypeToken<List<EmergencyContact>>() {}.type
                        )
                        prefs.saveContacts(contacts)
                        Log.i(TAG, "Contactos sincronizados: ${contacts.size}")
                        callback?.invoke(true)
                    } else {
                        Log.e(TAG, "Error sync contactos ${it.code}")
                        callback?.invoke(false)
                    }
                }
            }
        })
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }
}
