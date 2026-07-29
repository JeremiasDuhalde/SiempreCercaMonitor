package com.siemprecerca.monitor

import android.content.*
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private val df = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val dfFull = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
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

        findViewById<Button>(R.id.btnTestSos).setOnClickListener {
            try {
                AlertManager(this).sendTestAlert()
                Toast.makeText(this, "Alerta de prueba enviada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

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

            // Hero subtitle
            val patientName = dc?.clientName ?: "--"
            val deviceSerial = dc?.serialNumber ?: "--"
            findViewById<TextView>(R.id.tvHeroSubtitle)?.text = "$patientName  ·  $deviceSerial"

            // Connection status
            var connected = false; var stateText = "Iniciando..."
            try {
                val btns = Flic2Manager.getInstance().buttons
                if (btns.isEmpty()) stateText = "Sin botones"
                else {
                    val b = btns[0]
                    connected = b.connectionState == 3
                    stateText = when (b.connectionState) { 0 -> "Desconectado"; 1 -> "Conectando..."; 2 -> "Conectando..."; 3 -> "Conectado"; else -> "Estado ${b.connectionState}" }
                }
            } catch (_: Exception) {}

            findViewById<TextView>(R.id.tvStatus)?.text = stateText

            // Status badge
            val badge = findViewById<TextView>(R.id.tvStatusBadge)
            if (connected) {
                badge?.text = "  CONECTADO  "
                badge?.setTextColor(ContextCompat.getColor(this, R.color.green))
                badge?.setBackgroundResource(R.drawable.status_badge_connected)
            } else {
                badge?.text = "  DESCONECTADO  "
                badge?.setTextColor(ContextCompat.getColor(this, R.color.red))
                badge?.setBackgroundResource(R.drawable.status_badge_disconnected)
            }

            // Bluetooth metric
            findViewById<TextView>(R.id.tvBluetooth)?.text = if (connected) "Activo" else "Inactivo"

            // Last click metric
            try {
                val lc = prefs.lastClickTime
                findViewById<TextView>(R.id.tvLastClick)?.text = if (lc > 0) df.format(Date(lc)) else "--"
            } catch (_: Exception) {}

            // FLIC Battery
            try {
                val flicVoltage = prefs.flicBatteryVoltage
                val tvFlicBattery = findViewById<TextView>(R.id.tvFlicBattery)
                val tvFlicStatus = findViewById<TextView>(R.id.tvFlicBatteryStatus)
                if (flicVoltage > 0f) {
                    val voltageText = String.format(Locale.US, "%.2fv", flicVoltage)
                    tvFlicBattery?.text = voltageText
                    if (flicVoltage < 2.5f) {
                        tvFlicBattery?.setTextColor(ContextCompat.getColor(this, R.color.red))
                        tvFlicStatus?.text = "BAJA"
                        tvFlicStatus?.setTextColor(ContextCompat.getColor(this, R.color.red))
                    } else {
                        tvFlicBattery?.setTextColor(ContextCompat.getColor(this, R.color.green))
                        tvFlicStatus?.text = "OK"
                        tvFlicStatus?.setTextColor(ContextCompat.getColor(this, R.color.green))
                    }
                } else {
                    tvFlicBattery?.text = "--"
                    tvFlicBattery?.setTextColor(themedColor(R.color.text_secondary, R.color.text_secondary_light))
                    tvFlicStatus?.text = ""
                }
            } catch (_: Exception) {}

            // Phone Battery
            try {
                val phoneBattery = prefs.phoneBatteryLevel
                val tvPhoneBattery = findViewById<TextView>(R.id.tvPhoneBattery)
                val tvPhoneStatus = findViewById<TextView>(R.id.tvPhoneBatteryStatus)
                if (phoneBattery >= 0) {
                    tvPhoneBattery?.text = "$phoneBattery%"
                    if (phoneBattery < 15) {
                        tvPhoneBattery?.setTextColor(ContextCompat.getColor(this, R.color.red))
                        tvPhoneStatus?.text = "CRITICA"
                        tvPhoneStatus?.setTextColor(ContextCompat.getColor(this, R.color.red))
                    } else if (phoneBattery < 30) {
                        tvPhoneBattery?.setTextColor(ContextCompat.getColor(this, R.color.warning))
                        tvPhoneStatus?.text = "BAJA"
                        tvPhoneStatus?.setTextColor(ContextCompat.getColor(this, R.color.warning))
                    } else {
                        tvPhoneBattery?.setTextColor(ContextCompat.getColor(this, R.color.green))
                        tvPhoneStatus?.text = "OK"
                        tvPhoneStatus?.setTextColor(ContextCompat.getColor(this, R.color.green))
                    }
                } else {
                    tvPhoneBattery?.text = "--"
                    tvPhoneBattery?.setTextColor(themedColor(R.color.text_secondary, R.color.text_secondary_light))
                    tvPhoneStatus?.text = ""
                }
            } catch (_: Exception) {}

            // Health
            val lh = prefs.lastHealthTime
            findViewById<TextView>(R.id.tvHealthStatus)?.text = if (prefs.isHealthCheckEnabled) (if (lh > 0) "Ultimo: ${dfFull.format(Date(lh))}" else "Esperando...") else "Desactivado"

            // Alerts
            val la = prefs.lastAlertTime
            findViewById<TextView>(R.id.tvLastAlert)?.text = if (la > 0) df.format(Date(la)) else "Ninguna"
            findViewById<TextView>(R.id.tvAlertCount)?.text = "${prefs.alertCount}"

            try {
                val pendingCount = AlertManager(this).getPendingCount()
                val tvPending = findViewById<TextView>(R.id.tvPendingAlerts)
                tvPending?.text = "$pendingCount"
                if (pendingCount > 0) {
                    tvPending?.setTextColor(ContextCompat.getColor(this, R.color.warning))
                } else {
                    tvPending?.setTextColor(themedColor(R.color.text_secondary, R.color.text_secondary_light))
                }
            } catch (_: Exception) {}

            // SMS/Contacts
            val smsEnabled = prefs.isSmsEnabled
            val smsText = if (smsEnabled) {
                if (ct.isEmpty()) "SMS activado, sin contactos"
                else "SMS activo (${ct.size} contactos)\n${ct.joinToString("\n") { "  ${it.name}: ${it.phone}" }}"
            } else "SMS desactivado"
            findViewById<TextView>(R.id.tvContacts)?.text = smsText
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
                    btn.text = "Actualizar"
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
                            btn.text = "Actualizar"
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
                            btn.text = "Actualizar"
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
                    btn.text = "Actualizar"
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
                            btn.text = "Actualizar"
                            btn.isEnabled = true
                            installApk(apkFile)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            btn.text = "Actualizar"
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

    private fun themedColor(darkColorRes: Int, lightColorRes: Int): Int {
        return ContextCompat.getColor(this, if (prefs.themeMode == "light") lightColorRes else darkColorRes)
    }

    private fun applyTheme() {
        val isLight = prefs.themeMode == "light"
        val scroll = findViewById<ScrollView>(R.id.mainScroll)
        val btnLight = findViewById<Button>(R.id.btnThemeLight)
        val btnDark = findViewById<Button>(R.id.btnThemeDark)

        val bgColor = ContextCompat.getColor(this, if (isLight) R.color.background_light else R.color.background)
        val textPrimary = ContextCompat.getColor(this, if (isLight) R.color.text_primary_light else R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, if (isLight) R.color.text_secondary_light else R.color.text_secondary)
        val textMuted = ContextCompat.getColor(this, if (isLight) R.color.text_muted_light else R.color.text_muted)
        val primaryLight = ContextCompat.getColor(this, R.color.primary_light)
        val greenColor = ContextCompat.getColor(this, R.color.green)

        // Background + system bars
        scroll.setBackgroundColor(bgColor)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        if (isLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.decorView.systemUiVisibility = 0
        }

        // Theme toggle
        if (isLight) {
            btnLight.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            btnLight.setTextColor(0xFFFFFFFF.toInt())
            btnDark.backgroundTintList = ColorStateList.valueOf(0x00000000)
            btnDark.setTextColor(textMuted)
        } else {
            btnDark.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            btnDark.setTextColor(0xFFFFFFFF.toInt())
            btnLight.backgroundTintList = ColorStateList.valueOf(0x00000000)
            btnLight.setTextColor(textMuted)
        }

        // Title
        findViewById<TextView>(R.id.tvTitle)?.setTextColor(textPrimary)
        findViewById<TextView>(R.id.tvSubtitle)?.setTextColor(textMuted)

        // Hero card
        val heroCard = findViewById<LinearLayout>(R.id.heroCard)
        heroCard?.setBackgroundResource(if (isLight) R.drawable.hero_background_light else R.drawable.hero_background)
        findViewById<TextView>(R.id.tvStatus)?.setTextColor(textPrimary)
        findViewById<TextView>(R.id.tvHeroSubtitle)?.setTextColor(textMuted)

        // Metric cards inside hero
        val metricDrawable = if (isLight) R.drawable.metric_card_light else R.drawable.metric_card
        findViewById<View>(R.id.metricBluetooth)?.setBackgroundResource(metricDrawable)
        findViewById<View>(R.id.metricLastClick)?.setBackgroundResource(metricDrawable)

        // Battery cards
        val cardDrawable = if (isLight) R.drawable.card_background_light else R.drawable.card_background
        findViewById<View>(R.id.cardFlicBattery)?.setBackgroundResource(cardDrawable)
        findViewById<View>(R.id.cardPhoneBattery)?.setBackgroundResource(cardDrawable)
        findViewById<TextView>(R.id.tvFlicBatteryLabel)?.setTextColor(textMuted)
        findViewById<TextView>(R.id.tvPhoneBatteryLabel)?.setTextColor(textMuted)

        // Other cards
        val cardIds = listOf(R.id.cardAlerts, R.id.cardHealth, R.id.cardContacts)
        for (id in cardIds) {
            findViewById<View>(id)?.setBackgroundResource(cardDrawable)
        }

        // Alerts
        findViewById<TextView>(R.id.tvAlertsTitle)?.setTextColor(primaryLight)

        // Health
        findViewById<TextView>(R.id.tvHealthTitle)?.setTextColor(greenColor)
        findViewById<TextView>(R.id.tvHealthDesc)?.setTextColor(textMuted)
        findViewById<TextView>(R.id.tvHealthStatus)?.setTextColor(textSecondary)
        try {
            val sw = findViewById<Switch>(R.id.switchHealth)
            sw.thumbTintList = ColorStateList.valueOf(primaryLight)
            sw.trackTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (isLight) R.color.btn_muted_light else R.color.btn_muted))
        } catch (_: Exception) {}

        // Contacts
        findViewById<TextView>(R.id.tvContactsTitle)?.setTextColor(primaryLight)
        findViewById<TextView>(R.id.tvContacts)?.setTextColor(textSecondary)

        // Version
        findViewById<TextView>(R.id.tvVersion)?.setTextColor(textMuted)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) { svc = (s as? FlicBleService.LocalBinder)?.getService(); bound = true; updateUI() }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null; bound = false }
    }
    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { updateUI() } }
}
