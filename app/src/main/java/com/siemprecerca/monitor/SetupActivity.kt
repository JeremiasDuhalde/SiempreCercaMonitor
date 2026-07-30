package com.siemprecerca.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.siemprecerca.monitor.data.*
import com.siemprecerca.monitor.service.FlicBleService
import com.siemprecerca.monitor.worker.MonitorWorker
import io.flic.flic2libandroid.Flic2Button
import io.flic.flic2libandroid.Flic2Manager
import io.flic.flic2libandroid.Flic2ScanCallback

class SetupActivity : AppCompatActivity() {

    private var prefs: Preferences? = null
    private var authManager: AuthManager? = null
    private var clientName: String? = null
    private var pairedButton: Flic2Button? = null

    private var tvScanStatus: TextView? = null
    private var tvSelectedDevice: TextView? = null
    private var btnScan: View? = null
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_setup)
            prefs = Preferences(this)
            authManager = AuthManager(prefs!!)
            prefs!!.saveServerConfig(ServerConfig(Config.BASE_URL, Config.WEBHOOK_SECRET, ""))

            try {
                val buttons = Flic2Manager.getInstance().buttons
                if (buttons.isNotEmpty()) {
                    pairedButton = buttons[0]
                }
            } catch (_: Exception) {}

            applyTheme()
            initViews()
            val needed = getPerms().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
            connectToServer()
        } catch (e: Exception) {
            showErr("Error: ${e.message}")
        }
    }

    private fun showErr(msg: String) {
        try { AlertDialog.Builder(this).setTitle("Error").setMessage(msg).setPositiveButton("OK", null).show() }
        catch (_: Exception) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun applyTheme() {
        val isLight = prefs?.themeMode == "light"
        val scroll = findViewById<ScrollView>(R.id.setupScroll)
        val bgColor = ContextCompat.getColor(this, if (isLight) R.color.background_light else R.color.background)

        scroll.setBackgroundColor(bgColor)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        if (isLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.decorView.systemUiVisibility = 0
        }

        val cardDrawable = if (isLight) R.drawable.card_background_light else R.drawable.card_background
        val inputDrawable = if (isLight) R.drawable.input_background_light else R.drawable.input_background
        val textPrimary = ContextCompat.getColor(this, if (isLight) R.color.text_primary_light else R.color.text_primary)
        val textMuted = ContextCompat.getColor(this, if (isLight) R.color.text_muted_light else R.color.text_muted)
        val primaryLight = ContextCompat.getColor(this, R.color.primary_light)

        // Title
        findViewById<TextView>(R.id.tvSetupTitle)?.setTextColor(primaryLight)
        findViewById<TextView>(R.id.tvSetupSubtitle)?.setTextColor(textMuted)

        // Cards
        val cardIds = listOf(R.id.cardStep1, R.id.cardStep2)
        for (id in cardIds) {
            findViewById<View>(id)?.setBackgroundResource(cardDrawable)
        }

        // Step labels
        findViewById<TextView>(R.id.tvStep1Label)?.setTextColor(primaryLight)
        findViewById<TextView>(R.id.tvStep2Label)?.setTextColor(primaryLight)

        // Input
        findViewById<EditText>(R.id.etClientId)?.apply {
            setBackgroundResource(inputDrawable)
            setTextColor(textPrimary)
            setHintTextColor(textMuted)
        }

        // Descriptions
        findViewById<TextView>(R.id.tvScanDesc)?.setTextColor(textMuted)

        // Scan label text
        findViewById<TextView>(R.id.tvScanLabel)?.setTextColor(textMuted)

        // Theme toggle
        val btnLight = findViewById<Button>(R.id.btnSetupThemeLight)
        val btnDark = findViewById<Button>(R.id.btnSetupThemeDark)
        if (isLight) {
            btnLight?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            btnLight?.setTextColor(0xFFFFFFFF.toInt())
            btnDark?.backgroundTintList = ColorStateList.valueOf(0x00000000)
            btnDark?.setTextColor(textMuted)
        } else {
            btnDark?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            btnDark?.setTextColor(0xFFFFFFFF.toInt())
            btnLight?.backgroundTintList = ColorStateList.valueOf(0x00000000)
            btnLight?.setTextColor(textMuted)
        }
    }

    private fun initViews() {
        val etClientId = findViewById<EditText>(R.id.etClientId)
        val tvClientName = findViewById<TextView>(R.id.tvClientName)
        btnScan = findViewById(R.id.btnScan)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        tvSelectedDevice = findViewById(R.id.tvSelectedDevice)
        progressBar = findViewById(R.id.progressBar)

        if (pairedButton != null) {
            val serial = pairedButton!!.serialNumber ?: pairedButton!!.bdAddr
            tvSelectedDevice?.text = "FLIC: $serial (ya vinculado)"
            tvSelectedDevice?.visibility = View.VISIBLE
            tvScanStatus?.text = "Boton ya vinculado"
            tvScanStatus?.setTextColor(ContextCompat.getColor(this, R.color.green))
        }

        // Theme toggles
        findViewById<Button>(R.id.btnSetupThemeLight)?.setOnClickListener {
            prefs?.themeMode = "light"
            recreate()
        }
        findViewById<Button>(R.id.btnSetupThemeDark)?.setOnClickListener {
            prefs?.themeMode = "dark"
            recreate()
        }

        // Buscar paciente
        findViewById<Button>(R.id.btnBuscarPaciente).setOnClickListener {
            val id = etClientId.text.toString().toIntOrNull()
            if (id == null || id <= 0) { etClientId.error = "Invalido"; return@setOnClickListener }
            tvClientName.text = "Buscando..."
            tvClientName.setTextColor(ContextCompat.getColor(this, R.color.warning))
            tvClientName.visibility = View.VISIBLE
            authManager?.fetchClient(id) { client ->
                runOnUiThread {
                    if (client != null) {
                        clientName = client.displayName()
                        tvClientName.text = "Paciente: $clientName"
                        tvClientName.setTextColor(ContextCompat.getColor(this, R.color.green))
                    } else {
                        tvClientName.text = "No encontrado"
                        tvClientName.setTextColor(ContextCompat.getColor(this, R.color.red))
                        clientName = null
                    }
                }
            }
        }

        // Escanear con SDK FLIC (animacion + scan)
        btnScan!!.setOnClickListener {
            val anim = AnimationUtils.loadAnimation(this, R.anim.btn_press)
            btnScan?.startAnimation(anim)
            btnScan?.postDelayed({ startFlicScan() }, 150)
        }

        // Activar
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            try {
                val clientId = etClientId.text.toString().toIntOrNull()
                if (clientId == null || clientId <= 0) { etClientId.error = "Invalido"; return@setOnClickListener }
                if (clientName == null) { showErr("Busca el paciente primero"); return@setOnClickListener }
                if (pairedButton == null) { showErr("Vincula el FLIC primero"); return@setOnClickListener }

                val btn = pairedButton!!
                val serial = btn.serialNumber ?: btn.name ?: btn.bdAddr
                prefs!!.saveDeviceConfig(DeviceConfig(
                    serialNumber = serial,
                    macAddress = btn.bdAddr,
                    clientId = clientId,
                    clientName = clientName!!
                ))
                prefs!!.isSetupComplete = true

                try { AlertManager(this).syncContacts() } catch (_: Exception) {}
                try { FlicBleService.start(this) } catch (_: Exception) {}
                try { MonitorWorker.schedule(this) } catch (_: Exception) {}
                btn.connect()

                Toast.makeText(this, "Monitoreo activado!", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                showErr("Error: ${e.message}")
            }
        }
    }

    private fun startFlicScan() {
        val manager: Flic2Manager
        try { manager = Flic2Manager.getInstance() }
        catch (e: Exception) { showErr("Flic2Manager: ${e.message}"); return }

        btnScan?.isEnabled = false
        btnScan?.alpha = 0.5f
        findViewById<TextView>(R.id.tvScanLabel)?.text = "Buscando..."
        tvScanStatus?.text = "Presiona tu boton FLIC ahora!"
        tvScanStatus?.setTextColor(ContextCompat.getColor(this, R.color.warning))
        progressBar?.visibility = View.VISIBLE

        try {
            manager.startScan(object : Flic2ScanCallback {
                override fun onDiscoveredAlreadyPairedButton(button: Flic2Button) {
                    runOnUiThread {
                        pairedButton = button
                        val serial = button.serialNumber ?: button.bdAddr
                        scanDone("Ya vinculado: $serial")
                        tvSelectedDevice?.text = "FLIC: $serial"
                        tvSelectedDevice?.visibility = View.VISIBLE
                        (application as? MonitorApp)?.addButtonListener(button)
                    }
                }
                override fun onDiscovered(bdAddr: String) {
                    runOnUiThread { tvScanStatus?.text = "Encontrado ($bdAddr)! Conectando..." }
                }
                override fun onConnected() {
                    runOnUiThread { tvScanStatus?.text = "Conectado! Vinculando..." }
                }
                override fun onAskToAcceptPairRequest() {
                    runOnUiThread {
                        tvScanStatus?.text = "Acepta la vinculacion!"
                        Toast.makeText(this@SetupActivity, "Acepta la vinculacion!", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onComplete(result: Int, subCode: Int, button: Flic2Button?) {
                    runOnUiThread {
                        if (result == Flic2ScanCallback.RESULT_SUCCESS && button != null) {
                            pairedButton = button
                            val serial = button.serialNumber ?: button.name ?: button.bdAddr
                            scanDone("FLIC vinculado: $serial")
                            tvSelectedDevice?.text = "FLIC: $serial (${button.bdAddr})"
                            tvSelectedDevice?.visibility = View.VISIBLE
                            (application as? MonitorApp)?.addButtonListener(button)
                            button.connect()
                            Toast.makeText(this@SetupActivity, "FLIC listo!", Toast.LENGTH_LONG).show()
                        } else {
                            val err = try { Flic2Manager.errorCodeToString(result) } catch (_: Exception) { "Codigo $result" }
                            scanDone("Error: $err")
                            tvScanStatus?.setTextColor(ContextCompat.getColor(this@SetupActivity, R.color.red))
                        }
                    }
                }
            })
        } catch (e: Exception) {
            scanDone("Error: ${e.message}")
            tvScanStatus?.setTextColor(ContextCompat.getColor(this, R.color.red))
        }
    }

    private fun scanDone(msg: String) {
        progressBar?.visibility = View.GONE
        btnScan?.isEnabled = true
        btnScan?.alpha = 1.0f
        findViewById<TextView>(R.id.tvScanLabel)?.text = "Toca para buscar"
        tvScanStatus?.text = msg
        tvScanStatus?.setTextColor(ContextCompat.getColor(this, R.color.green))
    }

    private fun connectToServer() {
        val tv = findViewById<TextView>(R.id.tvServerStatus)
        tv.text = "Conectando..."
        tv.setTextColor(ContextCompat.getColor(this, R.color.warning))
        authManager?.login { ok ->
            runOnUiThread {
                if (ok) { tv.text = "Servidor conectado"; tv.setTextColor(ContextCompat.getColor(this, R.color.green)) }
                else { tv.text = "Error. Toca para reintentar."; tv.setTextColor(ContextCompat.getColor(this, R.color.red)); tv.setOnClickListener { connectToServer() } }
            }
        }
    }

    private fun getPerms(): List<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p
    }

    override fun onDestroy() {
        try { Flic2Manager.getInstance().stopScan() } catch (_: Exception) {}
        super.onDestroy()
    }
}
