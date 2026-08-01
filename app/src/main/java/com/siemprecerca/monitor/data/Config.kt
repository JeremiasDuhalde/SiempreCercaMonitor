package com.siemprecerca.monitor.data

import com.siemprecerca.monitor.BuildConfig

/**
 * Configuracion de la app.
 * Credenciales inyectadas via BuildConfig (definidas en gradle.properties).
 */
object Config {
    // Servidor
    val BASE_URL: String = BuildConfig.BASE_URL
    val WEBHOOK_SECRET: String = BuildConfig.WEBHOOK_SECRET

    // Credenciales para auto-login (la app renueva el JWT sola)
    val LOGIN_EMAIL: String = BuildConfig.MONITOR_EMAIL
    val LOGIN_PASSWORD: String = BuildConfig.MONITOR_PASSWORD

    // SMS
    const val DEFAULT_SMS_TEMPLATE =
        "ALERTA SOS - {nombre} necesita asistencia urgente. " +
        "Contactar central Siempre Cerca: 2246-529000"

    // BLE
    const val FLIC_DEVICE_PREFIX = "Flic"
}
