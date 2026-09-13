package com.siemprecerca.monitor.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Gestiona el envio de alertas por HTTP al servidor y SMS a contactos de emergencia.
 * Doble via: internet + SMS como fallback.
 * Incluye cola offline: si HTTP falla, guarda la alerta y reintenta luego.
 */
class AlertManager(private val context: Context) {

    companion object {
        private const val TAG = "AlertManager"
        // Cooldown entre alertas para evitar spam (5 segundos)
        private const val ALERT_COOLDOWN_MS = 5_000L
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
     * Obtiene la ultima ubicacion conocida del celular.
     * Intenta FusedLocationProviderClient primero, luego LocationManager como fallback.
     * Retorna un par (lat, lng) como strings, o ("0","0") si no hay ubicacion.
     */
    private fun getLastLocation(): Pair<String, String> {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Sin permiso de ubicacion")
                return Pair("0", "0")
            }

            // Intentar con LocationManager (sincrono, no necesita Google Play Services)
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager != null) {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                var bestLocation: Location? = null
                for (provider in providers) {
                    try {
                        val loc = locationManager.getLastKnownLocation(provider)
                        if (loc != null && (bestLocation == null || loc.time > bestLocation.time)) {
                            bestLocation = loc
                        }
                    } catch (_: Exception) {}
                }
                if (bestLocation != null) {
                    Log.i(TAG, "Ubicacion obtenida: ${bestLocation.latitude}, ${bestLocation.longitude}")
                    return Pair(
                        String.format(Locale.US, "%.6f", bestLocation.latitude),
                        String.format(Locale.US, "%.6f", bestLocation.longitude)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo ubicacion: ${e.message}")
        }
        return Pair("0", "0")
    }

    /**
     * Procesa un evento SOS del reloj FLIC.
     * Envia alerta HTTP al servidor y SMS a contactos de emergencia.
     * Tiene cooldown de 5s para evitar alertas duplicadas por rebotes del boton.
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
        sendHttpAlert(deviceConfig, "sos", bleData)

        // Enviar SMS a contactos
        if (prefs.isSmsEnabled) {
            sendSmsAlerts(deviceConfig)
        }
    }

    /**
     * Envia una alerta de prueba (event="test"). No reproduce sonido ni vibra.
     */
    fun sendTestAlert() {
        val deviceConfig = prefs.getDeviceConfig() ?: run {
            Log.e(TAG, "No hay dispositivo configurado para test")
            return
        }
        Log.i(TAG, "Enviando alerta de prueba")
        sendHttpAlert(deviceConfig, "test", null)
    }

    /**
     * Envia una alerta de bateria baja del celular.
     */
    fun sendBatteryAlert(level: Int) {
        val deviceConfig = prefs.getDeviceConfig() ?: return
        Log.i(TAG, "Enviando alerta de bateria baja: $level%")
        sendHttpAlert(deviceConfig, "battery", null)
    }

    /**
     * POST al endpoint /api/webhooks/flic/alert con el mismo formato
     * que usa la app FLIC original. Headers con serial, nombre y GPS.
     */
    private fun sendHttpAlert(device: DeviceConfig, event: String, bleData: ByteArray?) {
        val serverConfig = prefs.getServerConfig()
        val url = "${serverConfig.baseUrl}/api/webhooks/flic/alert"
        val (lat, lng) = getLastLocation()

        val jsonBody = gson.toJson(AlertPayload(
            event = event,
            source = "siemprecerca_monitor",
            appVersion = "2.7.0"
        ))

        val requestBuilder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .addHeader("X-Webhook-Secret", serverConfig.webhookSecret)
            .addHeader("button-serial-number", device.serialNumber)
            .addHeader("button-name", "Flic ${device.serialNumber}")
            .addHeader("x-client-id", device.clientId.toString())
            .addHeader("flic-latitude", lat)
            .addHeader("flic-longitude", lng)

        if (bleData != null) {
            requestBuilder.addHeader("X-BLE-Raw", bleData.toHexString())
        }

        val request = requestBuilder.build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Error enviando alerta HTTP: ${e.message}")
                // Guardar en cola offline
                enqueueAlert(PendingAlert(
                    event = event,
                    serialNumber = device.serialNumber,
                    buttonName = "Flic ${device.serialNumber}",
                    latitude = lat,
                    longitude = lng,
                    timestamp = System.currentTimeMillis()
                ))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        Log.i(TAG, "Alerta HTTP enviada OK ($event): ${it.body?.string()}")
                        // Intentar enviar pendientes
                        flushPendingAlerts()
                    } else if (it.code == 401) {
                        Log.w(TAG, "JWT expirado, renovando...")
                        authManager.login { success ->
                            if (success) sendHttpAlert(device, event, bleData)
                        }
                    } else {
                        Log.e(TAG, "Alerta HTTP error ${it.code}: ${it.body?.string()}")
                        // Guardar en cola offline
                        enqueueAlert(PendingAlert(
                            event = event,
                            serialNumber = device.serialNumber,
                            buttonName = "Flic ${device.serialNumber}",
                            latitude = lat,
                            longitude = lng,
                            timestamp = System.currentTimeMillis()
                        ))
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

    /**
     * Envia health ping al servidor para confirmar que el dispositivo esta
     * conectado y funcionando. Se envia cada 1 hora.
     * Si el servidor no recibe health en 2h, genera alerta de inactividad.
     */
    fun sendHealthPing() {
        if (!prefs.isHealthCheckEnabled) return

        val deviceConfig = prefs.getDeviceConfig() ?: return

        // Auto-registrar dispositivo si no se hizo antes
        if (!prefs.isDeviceRegistered) {
            authManager.registerDevice(deviceConfig.clientId, deviceConfig.serialNumber) { ok ->
                if (ok) prefs.isDeviceRegistered = true
            }
        }
        val serverConfig = prefs.getServerConfig()
        val url = "${serverConfig.baseUrl}/api/webhooks/flic/alert"
        val (lat, lng) = getLastLocation()

        val jsonBody = gson.toJson(AlertPayload(
            event = "health",
            source = "siemprecerca_monitor",
            appVersion = "2.7.0"
        ))

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .addHeader("X-Webhook-Secret", serverConfig.webhookSecret)
            .addHeader("button-serial-number", deviceConfig.serialNumber)
            .addHeader("button-name", "Flic ${deviceConfig.serialNumber}")
            .addHeader("x-client-id", deviceConfig.clientId.toString())
            .addHeader("flic-latitude", lat)
            .addHeader("flic-longitude", lng)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Health ping fallido: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        prefs.lastHealthTime = System.currentTimeMillis()
                        Log.i(TAG, "Health ping OK")
                        // Intentar enviar pendientes
                        flushPendingAlerts()
                    } else if (it.code == 401) {
                        authManager.login { success ->
                            if (success) sendHealthPing()
                        }
                    } else {
                        Log.e(TAG, "Health ping error: ${it.code}")
                    }
                }
            }
        })
    }

    // --- Cola offline ---

    /**
     * Agrega una alerta a la cola de pendientes (offline).
     */
    private fun enqueueAlert(alert: PendingAlert) {
        try {
            val type = object : TypeToken<MutableList<PendingAlert>>() {}.type
            val queue: MutableList<PendingAlert> = try {
                gson.fromJson(prefs.pendingAlerts, type) ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
            queue.add(alert)
            // Limitar a 50 alertas pendientes para no desbordar SharedPreferences
            while (queue.size > 50) queue.removeAt(0)
            prefs.pendingAlerts = gson.toJson(queue)
            Log.i(TAG, "Alerta encolada. Pendientes: ${queue.size}")
            // Notificar UI
            context.sendBroadcast(android.content.Intent("com.siemprecerca.monitor.STATE_CHANGED"))
        } catch (e: Exception) {
            Log.e(TAG, "Error encolando alerta: ${e.message}")
        }
    }

    /**
     * Intenta enviar todas las alertas pendientes.
     */
    fun flushPendingAlerts() {
        try {
            val type = object : TypeToken<MutableList<PendingAlert>>() {}.type
            val queue: MutableList<PendingAlert> = try {
                gson.fromJson(prefs.pendingAlerts, type) ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
            if (queue.isEmpty()) return

            Log.i(TAG, "Intentando enviar ${queue.size} alertas pendientes")
            val serverConfig = prefs.getServerConfig()

            // Tomar una copia y limpiar la cola (si fallan, se re-encolan)
            val toSend = ArrayList(queue)
            queue.clear()
            prefs.pendingAlerts = "[]"

            for (pending in toSend) {
                val jsonBody = gson.toJson(AlertPayload(
                    event = pending.event,
                    source = "siemprecerca_monitor",
                    appVersion = "2.7.0"
                ))

                val request = Request.Builder()
                    .url("${serverConfig.baseUrl}/api/webhooks/flic/alert")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("X-Webhook-Secret", serverConfig.webhookSecret)
                    .addHeader("button-serial-number", pending.serialNumber)
                    .addHeader("button-name", pending.buttonName)
                    .addHeader("flic-latitude", pending.latitude)
                    .addHeader("flic-longitude", pending.longitude)
                    .build()

                httpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(TAG, "Alerta pendiente fallida: ${e.message}")
                        enqueueAlert(pending)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (it.isSuccessful) {
                                Log.i(TAG, "Alerta pendiente enviada OK (${pending.event})")
                                context.sendBroadcast(android.content.Intent("com.siemprecerca.monitor.STATE_CHANGED"))
                            } else {
                                Log.e(TAG, "Alerta pendiente error ${it.code}")
                                enqueueAlert(pending)
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando cola: ${e.message}")
        }
    }

    /**
     * Retorna la cantidad de alertas pendientes en la cola.
     */
    fun getPendingCount(): Int {
        return try {
            val type = object : TypeToken<List<PendingAlert>>() {}.type
            val queue: List<PendingAlert> = gson.fromJson(prefs.pendingAlerts, type) ?: emptyList()
            queue.size
        } catch (_: Exception) {
            0
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }
}
