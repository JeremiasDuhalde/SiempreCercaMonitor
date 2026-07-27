package com.siemprecerca.monitor.service

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.siemprecerca.monitor.MainActivity
import com.siemprecerca.monitor.R
import com.siemprecerca.monitor.data.AlertManager
import com.siemprecerca.monitor.data.Preferences
import java.util.*

/**
 * ForegroundService que mantiene la conexion BLE con el reloj FLIC.
 * Corre 24/7 con una notificacion permanente. Se reconecta automaticamente
 * si se pierde la conexion.
 */
class FlicBleService : Service() {

    companion object {
        private const val TAG = "FlicBleService"
        const val NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
        const val CHANNEL_ID = "siemprecerca_monitor"
        const val ALERT_CHANNEL_ID = "siemprecerca_alerts"

        // UUIDs del servicio FLIC (obtenidos del escaneo nRF Connect)
        val FLIC_SERVICE_UUID: UUID = UUID.fromString("00420000-8F59-4420-870D-84F3-B617E493")
        val FLIC_NOTIFY_UUID: UUID = UUID.fromString("00420002-8F59-4420-870D-84F3-B617E493")
        val FLIC_WRITE_UUID: UUID = UUID.fromString("00420001-8F59-4420-870D-84F3-B617E493")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Reconexion
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L

        const val ACTION_START = "com.siemprecerca.monitor.START"
        const val ACTION_STOP = "com.siemprecerca.monitor.STOP"

        fun start(context: Context) {
            val intent = Intent(context, FlicBleService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FlicBleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var prefs: Preferences
    private lateinit var alertManager: AlertManager

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isConnected = false
    private var reconnectDelay = RECONNECT_DELAY_MS

    private val handler = Handler(Looper.getMainLooper())

    // Binder para que la Activity pueda consultar el estado
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FlicBleService = this@FlicBleService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
        alertManager = AlertManager(this)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        createNotificationChannels()
        Log.i(TAG, "Servicio creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildStatusNotification("Iniciando..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
                startMonitoring()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        disconnect()
        super.onDestroy()
        Log.i(TAG, "Servicio destruido")
    }

    // --- BLE Scanning ---

    private fun startMonitoring() {
        val deviceConfig = prefs.getDeviceConfig()
        if (deviceConfig == null) {
            Log.e(TAG, "No hay dispositivo configurado")
            updateNotification("Error: sin dispositivo configurado")
            return
        }

        val mac = deviceConfig.macAddress
        if (mac.isNotBlank()) {
            // Conexion directa por MAC (mas rapido y confiable)
            connectToDevice(mac)
        } else {
            // Escanear por nombre
            startScan(deviceConfig.serialNumber)
        }
    }

    private fun startScan(serialNumber: String) {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner no disponible")
            updateNotification("Error: Bluetooth no disponible")
            scheduleReconnect()
            return
        }

        bleScanner = scanner
        isScanning = true
        updateNotification("Buscando reloj $serialNumber...")

        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceName("Flic $serialNumber")
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            Log.i(TAG, "Escaneo BLE iniciado")

            // Timeout del escaneo: 30 segundos
            handler.postDelayed({
                if (isScanning && !isConnected) {
                    stopScan()
                    Log.w(TAG, "Timeout de escaneo, reintentando...")
                    scheduleReconnect()
                }
            }, 30_000)
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permisos BLE: ${e.message}")
            updateNotification("Error: sin permisos Bluetooth")
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {}
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            val device = result.device
            Log.i(TAG, "Dispositivo encontrado: ${device.name} [${device.address}]")

            // Guardar MAC para conexiones futuras directas
            val config = prefs.getDeviceConfig()
            if (config != null && config.macAddress.isBlank()) {
                prefs.saveDeviceConfig(config.copy(macAddress = device.address))
            }

            connectToDevice(device.address)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Error en escaneo BLE: $errorCode")
            isScanning = false
            scheduleReconnect()
        }
    }

    // --- BLE Connection ---

    private fun connectToDevice(macAddress: String) {
        val device = bluetoothAdapter?.getRemoteDevice(macAddress)
        if (device == null) {
            Log.e(TAG, "Dispositivo no encontrado: $macAddress")
            scheduleReconnect()
            return
        }

        updateNotification("Conectando a ${device.name ?: macAddress}...")
        Log.i(TAG, "Conectando a $macAddress")

        try {
            bluetoothGatt = device.connectGatt(
                this, true, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permisos para conectar: ${e.message}")
        }
    }

    private fun disconnect() {
        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) {}
        bluetoothGatt = null
        isConnected = false
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    reconnectDelay = RECONNECT_DELAY_MS
                    Log.i(TAG, "Conectado al FLIC")
                    updateNotification("Conectado - Monitoreando SOS")
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error descubriendo servicios: ${e.message}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    Log.w(TAG, "Desconectado del FLIC (status: $status)")
                    updateNotification("Desconectado - Reconectando...")
                    try { gatt.close() } catch (_: SecurityException) {}
                    bluetoothGatt = null
                    scheduleReconnect()
                }
            }
            broadcastState()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error descubriendo servicios: $status")
                return
            }

            val flicService = gatt.getService(FLIC_SERVICE_UUID)
            if (flicService == null) {
                Log.e(TAG, "Servicio FLIC no encontrado")
                return
            }

            val notifyChar = flicService.getCharacteristic(FLIC_NOTIFY_UUID)
            if (notifyChar == null) {
                Log.e(TAG, "Caracteristica Notify no encontrada")
                return
            }

            // Activar notificaciones BLE
            try {
                gatt.setCharacteristicNotification(notifyChar, true)
                val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    Log.i(TAG, "Notificaciones BLE activadas")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Error activando notificaciones: ${e.message}")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == FLIC_NOTIFY_UUID) {
                val data = characteristic.value
                Log.i(TAG, "EVENTO SOS RECIBIDO: ${data.toHexString()}")

                // Vibrar para confirmar recepcion
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))

                // Mostrar notificacion de alerta
                showAlertNotification()

                // Procesar alerta (HTTP + SMS)
                alertManager.processAlert(data)

                broadcastState()
            }
        }
    }

    // --- Reconnection ---

    private fun scheduleReconnect() {
        Log.i(TAG, "Reconexion programada en ${reconnectDelay / 1000}s")
        handler.postDelayed({
            if (!isConnected) {
                startMonitoring()
            }
        }, reconnectDelay)

        // Backoff exponencial hasta 60s max
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    // --- Notifications ---

    private fun createNotificationChannels() {
        val statusChannel = NotificationChannel(
            CHANNEL_ID,
            "Monitor SiempreCerca",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Estado de la conexion con el reloj"
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Alertas SOS",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas de emergencia"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(alertChannel)
    }

    private fun buildStatusNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SiempreCerca Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_monitor)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildStatusNotification(text))
    }

    private fun showAlertNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deviceConfig = prefs.getDeviceConfig()
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("ALERTA SOS")
            .setContentText("${deviceConfig?.clientName ?: "Paciente"} presiono el boton de emergencia")
            .setSmallIcon(R.drawable.ic_alert)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    // --- State broadcasting ---

    fun getMonitorState(): com.siemprecerca.monitor.data.MonitorState {
        val deviceConfig = prefs.getDeviceConfig()
        return com.siemprecerca.monitor.data.MonitorState(
            isConnected = isConnected,
            isScanning = isScanning,
            lastAlertSent = prefs.lastAlertTime,
            bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
            deviceName = deviceConfig?.serialNumber ?: "",
            alertCount = prefs.alertCount
        )
    }

    private fun broadcastState() {
        val intent = Intent("com.siemprecerca.monitor.STATE_CHANGED")
        sendBroadcast(intent)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }
}
