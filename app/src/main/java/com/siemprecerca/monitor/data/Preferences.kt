package com.siemprecerca.monitor.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Almacena la configuracion del dispositivo y servidor en SharedPreferences.
 * Se configura una sola vez durante el setup y persiste entre reinicios.
 */
class Preferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("siemprecerca_monitor", Context.MODE_PRIVATE)

    private val gson = Gson()

    companion object {
        private const val KEY_DEVICE_SERIAL = "device_serial"
        private const val KEY_DEVICE_MAC = "device_mac"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_NAME = "client_name"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_WEBHOOK_SECRET = "webhook_secret"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CONTACTS = "emergency_contacts"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_ALERT_COUNT = "alert_count"
        private const val KEY_LAST_ALERT_TIME = "last_alert_time"
        private const val KEY_SMS_ENABLED = "sms_enabled"
        private const val KEY_SMS_MESSAGE_TEMPLATE = "sms_message_template"
        private const val KEY_HEALTH_CHECK_ENABLED = "health_check_enabled"
        private const val KEY_LAST_HEALTH_TIME = "last_health_time"
        private const val KEY_FLIC_BATTERY_VOLTAGE = "flic_battery_voltage"
        private const val KEY_FLIC_BATTERY_TIMESTAMP = "flic_battery_timestamp"
        private const val KEY_PHONE_BATTERY_LEVEL = "phone_battery_level"
        private const val KEY_LAST_CLICK_TIME = "last_click_time"
        private const val KEY_PENDING_ALERTS = "pending_alerts"
    }

    var isSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    // --- Device ---

    fun saveDeviceConfig(config: DeviceConfig) {
        prefs.edit()
            .putString(KEY_DEVICE_SERIAL, config.serialNumber)
            .putString(KEY_DEVICE_MAC, config.macAddress)
            .putInt(KEY_CLIENT_ID, config.clientId)
            .putString(KEY_CLIENT_NAME, config.clientName)
            .apply()
    }

    fun getDeviceConfig(): DeviceConfig? {
        val serial = prefs.getString(KEY_DEVICE_SERIAL, null) ?: return null
        return DeviceConfig(
            serialNumber = serial,
            macAddress = prefs.getString(KEY_DEVICE_MAC, "") ?: "",
            clientId = prefs.getInt(KEY_CLIENT_ID, 0),
            clientName = prefs.getString(KEY_CLIENT_NAME, "") ?: ""
        )
    }

    // --- Server ---

    fun saveServerConfig(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_WEBHOOK_SECRET, config.webhookSecret)
            .putString(KEY_AUTH_TOKEN, config.authToken)
            .apply()
    }

    fun getServerConfig(): ServerConfig {
        return ServerConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "https://app.siemprecercasrl.net") ?: "",
            webhookSecret = prefs.getString(KEY_WEBHOOK_SECRET, "") ?: "",
            authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        )
    }

    // --- Emergency Contacts ---

    fun saveContacts(contacts: List<EmergencyContact>) {
        prefs.edit()
            .putString(KEY_CONTACTS, gson.toJson(contacts))
            .apply()
    }

    fun getContacts(): List<EmergencyContact> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        val type = object : TypeToken<List<EmergencyContact>>() {}.type
        return gson.fromJson(json, type)
    }

    // --- SMS ---

    var isSmsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SMS_ENABLED, value).apply()

    var smsMessageTemplate: String
        get() = prefs.getString(
            KEY_SMS_MESSAGE_TEMPLATE,
            "ALERTA SOS - {nombre} necesita asistencia urgente. Contactar central Siempre Cerca."
        ) ?: ""
        set(value) = prefs.edit().putString(KEY_SMS_MESSAGE_TEMPLATE, value).apply()

    // --- Stats ---

    var alertCount: Int
        get() = prefs.getInt(KEY_ALERT_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_ALERT_COUNT, value).apply()

    var lastAlertTime: Long
        get() = prefs.getLong(KEY_LAST_ALERT_TIME, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_ALERT_TIME, value).apply()

    // --- Health Check ---

    var isHealthCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEALTH_CHECK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HEALTH_CHECK_ENABLED, value).apply()

    var lastHealthTime: Long
        get() = prefs.getLong(KEY_LAST_HEALTH_TIME, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_HEALTH_TIME, value).apply()

    // --- FLIC Battery ---

    var flicBatteryVoltage: Float
        get() = prefs.getFloat(KEY_FLIC_BATTERY_VOLTAGE, 0f)
        set(value) = prefs.edit().putFloat(KEY_FLIC_BATTERY_VOLTAGE, value).apply()

    var flicBatteryTimestamp: Long
        get() = prefs.getLong(KEY_FLIC_BATTERY_TIMESTAMP, 0)
        set(value) = prefs.edit().putLong(KEY_FLIC_BATTERY_TIMESTAMP, value).apply()

    // --- Phone Battery ---

    var phoneBatteryLevel: Int
        get() = prefs.getInt(KEY_PHONE_BATTERY_LEVEL, -1)
        set(value) = prefs.edit().putInt(KEY_PHONE_BATTERY_LEVEL, value).apply()

    // --- Last Click ---

    var lastClickTime: Long
        get() = prefs.getLong(KEY_LAST_CLICK_TIME, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_CLICK_TIME, value).apply()

    // --- Pending Alerts (offline queue) ---

    var pendingAlerts: String
        get() = prefs.getString(KEY_PENDING_ALERTS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_PENDING_ALERTS, value).apply()
}
