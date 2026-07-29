package com.siemprecerca.monitor.data

data class DeviceConfig(
    val serialNumber: String,
    val macAddress: String,
    val clientId: Int,
    val clientName: String
)

data class ServerConfig(
    val baseUrl: String,
    val webhookSecret: String,
    val authToken: String
)

data class EmergencyContact(
    val id: Int,
    val order: Int,
    val name: String,
    val relationshipLabel: String,
    val phone: String
)

data class MonitorState(
    val isConnected: Boolean = false,
    val isScanning: Boolean = false,
    val lastEventTime: Long = 0,
    val lastAlertSent: Long = 0,
    val bluetoothEnabled: Boolean = false,
    val deviceName: String = "",
    val rssi: Int = 0,
    val alertCount: Int = 0,
    val errorMessage: String? = null
)

data class AlertPayload(
    val event: String = "sos",
    val source: String = "siemprecerca_monitor",
    val appVersion: String = "2.6.0"
)

data class PendingAlert(
    val event: String,
    val serialNumber: String,
    val buttonName: String,
    val latitude: String,
    val longitude: String,
    val timestamp: Long
)
