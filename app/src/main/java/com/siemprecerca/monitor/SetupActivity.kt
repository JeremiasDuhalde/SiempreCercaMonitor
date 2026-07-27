package com.siemprecerca.monitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.siemprecerca.monitor.data.*
import com.siemprecerca.monitor.service.FlicBleService
import com.siemprecerca.monitor.worker.MonitorWorker

/**
 * Pantalla de configuracion simplificada para el usuario no-tecnico.
 * Solo necesita:
 *   1. Ingresar el ID del paciente (se lo dan al contratar)
 *   2. Escanear y seleccionar el reloj FLIC
 *   3. Tocar "Activar monitoreo"
 *
 * URL del servidor, webhook secret y credenciales vienen precargados.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var prefs: Preferences
    private lateinit var authManager: AuthManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false

    // UI
    private lateinit var etClientId: EditText
    private lateinit var btnBuscarPaciente: Button
    private lateinit var tvClientName: TextView
    private lateinit var btnScan: Button
    private lateinit var tvScanStatus: TextView
    private lateinit var lvDevices: ListView
    private lateinit var tvSelectedDevice: TextView
    private lateinit var btnActivar: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvServerStatus: TextView

    private val foundDevices = mutableListOf<Pair<String, String>>() // name, mac
    private var selectedMac: String? = null
    private var selectedSerial: String? = null
    private var clientName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        prefs = Preferences(this)
        authManager = AuthManager(prefs)

        // Precargar config del servidor
        prefs.saveServerConfig(ServerConfig(
            baseUrl = Config.BASE_URL,
            webhookSecret = Config.WEBHOOK_SECRET,
            authToken = "" // Se obtiene via login automatico
        ))

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        initViews()
        requestPermissions()
        connectToServer()
    }

    private fun initViews() {
        etClientId = findViewById(R.id.etClientId)
        btnBuscarPaciente = findViewById(R.id.btnBuscarPaciente)
        tvClientName = findViewById(R.id.tvClientName)
        btnScan = findViewById(R.id.btnScan)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        lvDevices = findViewById(R.id.lvDevices)
        tvSelectedDevice = findViewById(R.id.tvSelectedDevice)
        btnActivar = findViewById(R.id.btnSave)
        progressBar = findViewById(R.id.progressBar)
        tvServerStatus = findViewById(R.id.tvServerStatus)

        btnBuscarPaciente.setOnClickListener { buscarPaciente() }
        btnScan.setOnClickListener { toggleScan() }
        btnActivar.setOnClickListener { activarMonitoreo() }

        lvDevices.setOnItemClickListener { _, _, position, _ ->
            val (name, mac) = foundDevices[position]
            selectedMac = mac
            selectedSerial = name.removePrefix("Flic ").trim()
            tvSelectedDevice.text = "Reloj seleccionado: $name"
            tvSelectedDevice.visibility = View.VISIBLE
            stopScan()
        }
    }

    /**
     * Conectar al servidor automaticamente (login con credenciales precargadas)
     */
    private fun connectToServer() {
        tvServerStatus.text = "Conectando al servidor..."
        tvServerStatus.setTextColor(0xFFFFAA00.toInt())

        authManager.login { success ->
            runOnUiThread {
                if (success) {
                    tvServerStatus.text = "Servidor conectado"
                    tvServerStatus.setTextColor(0xFF4CAF50.toInt())
                } else {
                    tvServerStatus.text = "Error al conectar al servidor. Verificar conexion a internet."
                    tvServerStatus.setTextColor(0xFFF44336.toInt())
                }
            }
        }
    }

    /**
     * Busca el paciente en el servidor por ID y autocompleta el nombre.
     */
    private fun buscarPaciente() {
        val id = etClientId.text.toString().toIntOrNull()
        if (id == null || id <= 0) {
            etClientId.error = "Ingresa un numero de paciente valido"
            return
        }

        tvClientName.text = "Buscando paciente..."
        tvClientName.setTextColor(0xFFFFAA00.toInt())
        tvClientName.visibility = View.VISIBLE

        authManager.fetchClient(id) { client ->
            runOnUiThread {
                if (client != null) {
                    clientName = client.displayName()
                    tvClientName.text = "Paciente: $clientName"
                    tvClientName.setTextColor(0xFF4CAF50.toInt())
                } else {
                    tvClientName.text = "Paciente no encontrado. Verifica el numero."
                    tvClientName.setTextColor(0xFFF44336.toInt())
                    clientName = null
                }
            }
        }
    }

    // --- BLE Scan ---

    private fun toggleScan() {
        if (isScanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (!hasRequiredPermissions()) {
            requestPermissions()
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Activa Bluetooth primero", Toast.LENGTH_SHORT).show()
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        bleScanner = scanner
        isScanning = true
        foundDevices.clear()
        updateDeviceList()

        btnScan.text = "Detener busqueda"
        tvScanStatus.text = "Buscando relojes..."
        progressBar.visibility = View.VISIBLE

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, setupScanCallback)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Sin permisos Bluetooth", Toast.LENGTH_SHORT).show()
            isScanning = false
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        try { bleScanner?.stopScan(setupScanCallback) } catch (_: SecurityException) {}
        isScanning = false
        btnScan.text = "Buscar reloj"
        progressBar.visibility = View.GONE
        tvScanStatus.text = if (foundDevices.isEmpty()) "No se encontraron relojes"
            else "${foundDevices.size} reloj(es) encontrado(s)"
    }

    private val setupScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = try { device.name } catch (_: SecurityException) { null }
            if (name != null && name.startsWith(Config.FLIC_DEVICE_PREFIX)) {
                val mac = device.address
                if (foundDevices.none { it.second == mac }) {
                    foundDevices.add(name to mac)
                    runOnUiThread { updateDeviceList() }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                isScanning = false
                tvScanStatus.text = "Error buscando (codigo: $errorCode)"
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateDeviceList() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            foundDevices.map { it.first }
        )
        lvDevices.adapter = adapter
    }

    // --- Activar ---

    private fun activarMonitoreo() {
        val clientId = etClientId.text.toString().toIntOrNull()
        if (clientId == null || clientId <= 0) {
            etClientId.error = "Ingresa el numero de paciente"
            return
        }
        if (clientName == null) {
            Toast.makeText(this, "Primero busca el paciente con el boton 'Buscar'", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedMac == null || selectedSerial == null) {
            Toast.makeText(this, "Escanea y selecciona el reloj", Toast.LENGTH_SHORT).show()
            return
        }

        // Guardar configuracion del dispositivo
        prefs.saveDeviceConfig(DeviceConfig(
            serialNumber = selectedSerial!!,
            macAddress = selectedMac!!,
            clientId = clientId,
            clientName = clientName!!
        ))

        prefs.isSetupComplete = true

        // Sincronizar contactos de emergencia
        val alertManager = AlertManager(this)
        alertManager.syncContacts { success ->
            runOnUiThread {
                if (success) {
                    val contacts = prefs.getContacts()
                    Log.i(TAG, "${contacts.size} contactos sincronizados")
                }
            }
        }

        // Solicitar desactivar optimizacion de bateria
        requestBatteryOptimizationExemption()

        // Iniciar monitoreo
        FlicBleService.start(this)
        MonitorWorker.schedule(this)

        Toast.makeText(this, "Monitoreo activado", Toast.LENGTH_LONG).show()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {}
    }

    // --- Permissions ---

    private fun hasRequiredPermissions(): Boolean =
        getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun getRequiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }

    private fun requestPermissions() {
        val needed = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
