package com.siemprecerca.monitor

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.siemprecerca.monitor.data.AlertManager
import com.siemprecerca.monitor.data.Config
import com.siemprecerca.monitor.data.Preferences
import com.siemprecerca.monitor.service.FlicBleService
import io.flic.flic2libandroid.Flic2Manager
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
        applyTheme()
        findViewById<Button>(R.id.btnThemeLight).setOnClickListener {
            prefs.themeMode = "light"
            recreate()
        }
        findViewById<Button>(R.id.btnThemeDark).setOnClickListener {
            prefs.themeMode = "dark"
            recreate()
        }
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

        // Boton actualizar
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkForUpdate() }

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

    // --- Auto-update ---

    private fun checkForUpdate() {
        val btn = findViewById<Button>(R.id.btnUpdate)
        btn.text = "Verificando..."
        btn.isEnabled = false

        val client = OkHttpClient()
        val url = "${Config.BASE_URL}/monitor/version.json"

        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    btn.text = "Buscar actualizacion"
                    btn.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error: sin conexion", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val json = JSONObject(it.body?.string() ?: "{}")
                        val remoteVersion = json.optString("version", "")
                        val remoteCode = json.optInt("versionCode", 0)
                        val apkUrl = json.optString("url", "")

                        val currentCode = try {
                            packageManager.getPackageInfo(packageName, 0).let { pi ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
                                else @Suppress("DEPRECATION") pi.versionCode
                            }
                        } catch (_: Exception) { 0 }

                        runOnUiThread {
                            btn.text = "Buscar actualizacion"
                            btn.isEnabled = true

                            if (remoteCode > currentCode && apkUrl.isNotBlank()) {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Actualizacion disponible")
                                    .setMessage("Version $remoteVersion disponible. Descargar e instalar?")
                                    .setPositiveButton("Actualizar") { _, _ ->
                                        downloadAndInstall("${Config.BASE_URL}$apkUrl")
                                    }
                                    .setNegativeButton("Despues", null)
                                    .show()
                            } else {
                                Toast.makeText(this@MainActivity, "Ya tenes la ultima version", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            btn.text = "Buscar actualizacion"
                            btn.isEnabled = true
                            Toast.makeText(this@MainActivity, "Error verificando: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun downloadAndInstall(apkUrl: String) {
        Toast.makeText(this, "Descargando actualizacion...", Toast.LENGTH_LONG).show()
        val btn = findViewById<Button>(R.id.btnUpdate)
        btn.text = "Descargando..."
        btn.isEnabled = false

        val client = OkHttpClient()
        client.newCall(Request.Builder().url(apkUrl).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    btn.text = "Buscar actualizacion"
                    btn.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error descargando", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "siemprecerca-update.apk")
                        FileOutputStream(apkFile).use { fos ->
                            resp.body?.byteStream()?.copyTo(fos)
                        }

                        runOnUiThread {
                            btn.text = "Buscar actualizacion"
                            btn.isEnabled = true
                            installApk(apkFile)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            btn.text = "Buscar actualizacion"
                            btn.isEnabled = true
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun installApk(file: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error instalando: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyTheme() {
        val scroll = findViewById<ScrollView>(R.id.mainScroll)
        val btnLight = findViewById<Button>(R.id.btnThemeLight)
        val btnDark = findViewById<Button>(R.id.btnThemeDark)

        if (prefs.themeMode == "light") {
            scroll.setBackgroundColor(0xFFF5F5F5.toInt())
            window.decorView.setBackgroundColor(0xFFF5F5F5.toInt())
            window.statusBarColor = 0xFFF5F5F5.toInt()
            btnLight.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1B6B5A.toInt())
            btnLight.setTextColor(0xFFFFFFFF.toInt())
            btnDark.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDDDDDD.toInt())
            btnDark.setTextColor(0xFF666666.toInt())
        } else {
            scroll.setBackgroundColor(0xFF1A1A2E.toInt())
            window.decorView.setBackgroundColor(0xFF1A1A2E.toInt())
            window.statusBarColor = 0xFF1A1A2E.toInt()
            btnDark.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE94560.toInt())
            btnDark.setTextColor(0xFFFFFFFF.toInt())
            btnLight.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333355.toInt())
            btnLight.setTextColor(0xFF999999.toInt())
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) { svc = (s as? FlicBleService.LocalBinder)?.getService(); bound = true; updateUI() }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null; bound = false }
    }
    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { updateUI() } }
}
