package com.siemprecerca.monitor.data

/**
 * Configuracion precargada de fabrica.
 * Estos valores vienen incorporados en la app para que el usuario
 * no-tecnico no tenga que ingresar datos del servidor.
 */
object Config {
    // Servidor
    const val BASE_URL = "https://app.siemprecercasrl.net"
    const val WEBHOOK_SECRET = "4d60192711902e66a26923bfc375cb73"

    // Credenciales para auto-login (la app renueva el JWT sola)
    const val LOGIN_EMAIL = "monitor@siemprecerca.app"
    const val LOGIN_PASSWORD = "M0n1t0rSC2026"

    // SMS
    const val DEFAULT_SMS_TEMPLATE =
        "ALERTA SOS - {nombre} necesita asistencia urgente. " +
        "Contactar central Siempre Cerca: 2246-529000"

    // BLE
    const val FLIC_DEVICE_PREFIX = "Flic"
}
