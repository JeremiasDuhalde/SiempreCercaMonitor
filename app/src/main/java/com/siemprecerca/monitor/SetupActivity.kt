package com.siemprecerca.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
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
    private var btnScan: Button? = null
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
            tvScanStatus?.setTextColor(0xFF4CAF50.toInt())
        }

        // Buscar paciente
        findViewById<Button>(R.id.btnBuscarPaciente).setOnClickListener {
            val id = etClientId.text.toString().toIntOrNull()
            if (id == null || id <= 0) { etClientId.error = "Invalido"; return@setOnClickListener }
            tvClientName.text = "Buscando..."
            tvClientName.setTextColor(0xFFFFAA00.toInt())
            tvClientName.visibility = View.VISIBLE
            authManager?.fetchClient(id) { client ->
                runOnUiThread {
                    if (client != null) {
                        clientName = client.displayName()
                        tvClientName.text = "Paciente: $clientName"
                        tvClientName.setTextColor(0xFF4CAF50.toInt())
                    } else {
                        tvClientName.text = "No encontrado"
                        tvClientName.setTextColor(0xFFF44336.toInt())
                        clientName = null
                    }
                }
            }
        }

        // Escanear con SDK FLIC
        btnScan!!.setOnClickListener { startFlicScan() }

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

        btnScan?.text = "Buscando..."
        btnScan?.isEnabled = false
        tvScanStatus?.text = "Presiona tu boton FLIC ahora!"
        tvScanStatus?.setTextColor(0xFFFFAA00.toInt())
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
                            tvScanStatus?.setTextColor(0xFFF44336.toInt())
                        }
                    }
                }
            })
        } catch (e: Exception) {
            scanDone("Error: ${e.message}")
            tvScanStatus?.setTextColor(0xFFF44336.toInt())
        }
    }

    private fun scanDone(msg: String) {
        progressBar?.visibility = View.GONE
        btnScan?.text = "Buscar y vincular FLIC"
        btnScan?.isEnabled = true
        tvScanStatus?.text = msg
        tvScanStatus?.setTextColor(0xFF4CAF50.toInt())
    }

    private fun connectToServer() {
        val tv = findViewById<TextView>(R.id.tvServerStatus)
        tv.text = "Conectando..."
        tv.setTextColor(0xFFFFAA00.toInt())
        authManager?.login { ok ->
            runOnUiThread {
                if (ok) { tv.text = "Servidor conectado"; tv.setTextColor(0xFF4CAF50.toInt()) }
                else { tv.text = "Error. Toca para reintentar."; tv.setTextColor(0xFFF44336.toInt()); tv.setOnClickListener { connectToServer() } }
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
