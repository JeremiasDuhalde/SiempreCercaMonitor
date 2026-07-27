package com.siemprecerca.monitor

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Pantalla principal que muestra el estado del monitoreo.
 * UI minima — este celular no se toca, solo se mira de vez en cuando.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Preferences
    private var bleService: FlicBleService? = null
    private var isBound = false

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvClientName: TextView
    private lateinit var tvBluetooth: TextView
    private lateinit var tvLastAlert: TextView
    private lateinit var tvAlertCount: TextView
    private lateinit var tvContacts: TextView
    private lateinit var tvHealthStatus: TextView
    private lateinit var switchHealth: android.widget.Switch
    private lateinit var statusIndicator: View
    private lateinit var btnReconnect: Button
    private lateinit var btnReset: Button

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Preferences(this)

        // Si no esta configurado, ir al setup
        if (!prefs.isSetupComplete) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        initViews()
    }

    override fun onStart() {
        super.onStart()
        if (!prefs.isSetupComplete) return

        // Bind al servicio para consultar estado
        Intent(this, FlicBleService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Escuchar cambios de estado
        registerReceiver(
            stateReceiver,
            IntentFilter("com.siemprecerca.monitor.STATE_CHANGED"),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvClientName = findViewById(R.id.tvClientName)
        tvBluetooth = findViewById(R.id.tvBluetooth)
        tvLastAlert = findViewById(R.id.tvLastAlert)
        tvAlertCount = findViewById(R.id.tvAlertCount)
        tvContacts = findViewById(R.id.tvContacts)
        tvHealthStatus = findViewById(R.id.tvHealthStatus)
        switchHealth = findViewById(R.id.switchHealth)
        statusIndicator = findViewById(R.id.statusIndicator)
        btnReconnect = findViewById(R.id.btnReconnect)
        btnReset = findViewById(R.id.btnReset)

        // Health check toggle
        switchHealth.isChecked = prefs.isHealthCheckEnabled
        switchHealth.setOnCheckedChangeListener { _, isChecked ->
            prefs.isHealthCheckEnabled = isChecked
            updateUI()
        }

        btnReconnect.setOnClickListener {
            FlicBleService.start(this)
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reconfigurar")
                .setMessage("Esto detendra el monitoreo y permitira cambiar la configuracion. Continuar?")
                .setPositiveButton("Si") { _, _ ->
                    FlicBleService.stop(this)
                    prefs.isSetupComplete = false
                    startActivity(Intent(this, SetupActivity::class.java))
                    finish()
                }
                .setNegativeButton("No", null)
                .show()
        }

        updateUI()
    }

    private fun updateUI() {
        val deviceConfig = prefs.getDeviceConfig()
        val contacts = prefs.getContacts()

        // Info del dispositivo
        tvDeviceName.text = "Reloj: ${deviceConfig?.serialNumber ?: "No configurado"}"
        tvClientName.text = "Paciente: ${deviceConfig?.clientName ?: "-"}"

        // Estado de conexion
        val state = bleService?.getMonitorState()
        if (state != null) {
            tvStatus.text = when {
                state.isConnected -> "CONECTADO"
                state.isScanning -> "BUSCANDO..."
                else -> "DESCONECTADO"
            }
            statusIndicator.setBackgroundResource(
                if (state.isConnected) R.drawable.indicator_green
                else R.drawable.indicator_red
            )
            tvBluetooth.text = "Bluetooth: ${if (state.bluetoothEnabled) "Encendido" else "APAGADO"}"
        } else {
            tvStatus.text = "INICIANDO..."
            statusIndicator.setBackgroundResource(R.drawable.indicator_red)
            tvBluetooth.text = "Bluetooth: --"
        }

        // Health check
        val lastHealth = prefs.lastHealthTime
        tvHealthStatus.text = if (prefs.isHealthCheckEnabled) {
            if (lastHealth > 0) "Ultimo health: ${dateFormat.format(Date(lastHealth))}"
            else "Health activado, esperando primer ping..."
        } else {
            "Health check desactivado"
        }

        // Alertas
        val lastAlert = prefs.lastAlertTime
        tvLastAlert.text = "Ultima alerta: ${if (lastAlert > 0) dateFormat.format(Date(lastAlert)) else "Ninguna"}"
        tvAlertCount.text = "Total alertas: ${prefs.alertCount}"

        // Contactos SMS
        tvContacts.text = if (contacts.isEmpty()) {
            "Contactos SMS: No configurados"
        } else {
            "Contactos SMS: ${contacts.joinToString(", ") { "${it.name} (${it.phone})" }}"
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FlicBleService.LocalBinder
            bleService = binder.getService()
            isBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isBound = false
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI()
        }
    }
}
