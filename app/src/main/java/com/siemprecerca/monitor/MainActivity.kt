package com.siemprecerca.monitor

import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.siemprecerca.monitor.data.AlertManager
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService
import io.flic.flic2libandroid.Flic2Manager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Preferences
    private var svc: FlicBleService? = null
    private var bound = false
    private val df = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Preferences(this)
        if (!prefs.isSetupComplete) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        setContentView(R.layout.activity_main)
        try { findViewById<TextView>(R.id.tvVersion)?.text = "v${packageManager.getPackageInfo(packageName, 0).versionName}" } catch (_: Exception) {}
        try { val sw = findViewById<Switch>(R.id.switchHealth); sw.isChecked = prefs.isHealthCheckEnabled; sw.setOnCheckedChangeListener { _, c -> prefs.isHealthCheckEnabled = c } } catch (_: Exception) {}
        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            try { for (b in Flic2Manager.getInstance().buttons) b.connect() } catch (_: Exception) {}
            FlicBleService.start(this)
        }

        // Feature 6: Boton Test SOS
        findViewById<Button>(R.id.btnTestSos).setOnClickListener {
            try {
                AlertManager(this).sendTestAlert()
                Toast.makeText(this, "Alerta de prueba enviada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Reconfigurar").setMessage("Detener y reconfigurar?")
                .setPositiveButton("Si") { _, _ ->
                    try { FlicBleService.stop(this); for (b in Flic2Manager.getInstance().buttons) Flic2Manager.getInstance().forgetButton(b) } catch (_: Exception) {}
                    prefs.isSetupComplete = false; startActivity(Intent(this, SetupActivity::class.java)); finish()
                }.setNegativeButton("No", null).show()
        }
        updateUI()
    }

    override fun onStart() {
        super.onStart()
        if (!prefs.isSetupComplete) return
        try { bindService(Intent(this, FlicBleService::class.java), conn, Context.BIND_AUTO_CREATE) } catch (_: Exception) {}
        try {
            val f = IntentFilter("com.siemprecerca.monitor.STATE_CHANGED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(rx, f, RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)
        } catch (_: Exception) {}
        handler.postDelayed(ref, 2000)
    }

    override fun onStop() {
        super.onStop(); handler.removeCallbacks(ref)
        try { if (bound) { unbindService(conn); bound = false } } catch (_: Exception) {}
        try { unregisterReceiver(rx) } catch (_: Exception) {}
    }

    private val ref = object : Runnable { override fun run() { updateUI(); handler.postDelayed(this, 2000) } }

    private fun updateUI() {
        try {
            val dc = prefs.getDeviceConfig(); val ct = prefs.getContacts()
            findViewById<TextView>(R.id.tvDeviceName)?.text = "Reloj: ${dc?.serialNumber ?: "-"}"
            findViewById<TextView>(R.id.tvClientName)?.text = "Paciente: ${dc?.clientName ?: "-"}"

            var connected = false; var stateText = "INICIANDO..."
            try {
                val btns = Flic2Manager.getInstance().buttons
                if (btns.isEmpty()) stateText = "Sin botones"
                else {
                    val b = btns[0]
                    connected = b.connectionState == 3
                    stateText = when (b.connectionState) { 0 -> "DESCONECTADO"; 1 -> "CONECTANDO..."; 2 -> "CONECTADO (init)"; 3 -> "CONECTADO"; else -> "Estado ${b.connectionState}" }
                }
            } catch (_: Exception) {}

            findViewById<TextView>(R.id.tvStatus)?.text = stateText
            findViewById<View>(R.id.statusIndicator)?.setBackgroundResource(if (connected) R.drawable.indicator_green else R.drawable.indicator_red)
            findViewById<TextView>(R.id.tvBluetooth)?.text = "Bluetooth: Activo"

            // Feature 2: Bateria FLIC
            try {
                val flicVoltage = prefs.flicBatteryVoltage
                val tvFlicBattery = findViewById<TextView>(R.id.tvFlicBattery)
                if (flicVoltage > 0f) {
                    val voltageText = String.format(Locale.US, "%.2f", flicVoltage)
                    if (flicVoltage < 2.5f) {
                        tvFlicBattery?.text = "Bateria FLIC: ${voltageText}v - BAJA"
                        tvFlicBattery?.setTextColor(0xFFFF4444.toInt())
                    } else {
                        tvFlicBattery?.text = "Bateria FLIC: ${voltageText}v - OK"
                        tvFlicBattery?.setTextColor(0xFF4CAF50.toInt())
                    }
                } else {
                    tvFlicBattery?.text = "Bateria FLIC: --"
                    tvFlicBattery?.setTextColor(0xFFCCCCCC.toInt())
                }
            } catch (_: Exception) {}

            // Feature 3: Bateria celular
            try {
                val phoneBattery = prefs.phoneBatteryLevel
                val tvPhoneBattery = findViewById<TextView>(R.id.tvPhoneBattery)
                if (phoneBattery >= 0) {
                    tvPhoneBattery?.text = "Bateria celular: $phoneBattery%"
                    if (phoneBattery < 15) {
                        tvPhoneBattery?.setTextColor(0xFFFF4444.toInt())
                    } else if (phoneBattery < 30) {
                        tvPhoneBattery?.setTextColor(0xFFFF9800.toInt())
                    } else {
                        tvPhoneBattery?.setTextColor(0xFF4CAF50.toInt())
                    }
                } else {
                    tvPhoneBattery?.text = "Bateria celular: --"
                    tvPhoneBattery?.setTextColor(0xFFCCCCCC.toInt())
                }
            } catch (_: Exception) {}

            val lh = prefs.lastHealthTime
            findViewById<TextView>(R.id.tvHealthStatus)?.text = if (prefs.isHealthCheckEnabled) (if (lh > 0) "Health: ${df.format(Date(lh))}" else "Esperando...") else "Desactivado"

            val la = prefs.lastAlertTime
            findViewById<TextView>(R.id.tvLastAlert)?.text = "Ultima alerta: ${if (la > 0) df.format(Date(la)) else "Ninguna"}"

            // Feature 4: Ultimo click
            try {
                val lc = prefs.lastClickTime
                findViewById<TextView>(R.id.tvLastClick)?.text = "Ultimo click: ${if (lc > 0) df.format(Date(lc)) else "--"}"
            } catch (_: Exception) {}

            findViewById<TextView>(R.id.tvAlertCount)?.text = "Total alertas: ${prefs.alertCount}"

            // Feature 7: Alertas pendientes
            try {
                val pendingCount = AlertManager(this).getPendingCount()
                val tvPending = findViewById<TextView>(R.id.tvPendingAlerts)
                if (pendingCount > 0) {
                    tvPending?.text = "Alertas pendientes: $pendingCount"
                    tvPending?.setTextColor(0xFFFF9800.toInt())
                    tvPending?.visibility = View.VISIBLE
                } else {
                    tvPending?.visibility = View.GONE
                }
            } catch (_: Exception) {}

            // SMS status
            val smsEnabled = prefs.isSmsEnabled
            val smsText = if (smsEnabled) {
                if (ct.isEmpty()) "SMS: Activado pero sin contactos"
                else "SMS: Activado (${ct.size} contactos)"
            } else "SMS: Desactivado"
            findViewById<TextView>(R.id.tvContacts)?.text = "$smsText\n${ct.joinToString("\n") { "  ${it.name}: ${it.phone}" }}"
        } catch (_: Exception) {}
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) { svc = (s as? FlicBleService.LocalBinder)?.getService(); bound = true; updateUI() }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null; bound = false }
    }
    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { updateUI() } }
}
