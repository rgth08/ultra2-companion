package com.ultra2.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ultra2.companion.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleManager

    // ── Permissions ────────────────────────────────────────────────────────
    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) ble.startScan()
        else appendLog("ER", "Permisos Bluetooth denegados")
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = BleManager(applicationContext)
        setupButtons()
        observeState()
        observeLog()
        observeData()

        appendLog("SYS", "Ultra2 Companion listo · FitPro/Jieli protocol")
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.dispose()
    }

    // ── Button setup ───────────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            if (!hasPermissions()) permLauncher.launch(permissions)
            else ble.startScan()
        }

        binding.btnDisconnect.setOnClickListener { ble.disconnect() }

        binding.btnPhoneType.setOnClickListener {
            appendLog("CMD", "Enviando PhoneType=Android...")
            ble.sendPhoneTypeAndroid()
        }

        binding.btnSyncTime.setOnClickListener {
            appendLog("CMD", "Sincronizando hora...")
            ble.syncTime()
        }

        binding.btnHeartRate.setOnClickListener {
            appendLog("CMD", "Midiendo frecuencia cardiaca...")
            ble.startHeartRate()
        }

        binding.btnEcgStart.setOnClickListener {
            appendLog("CMD", "Iniciando ECG...")
            ble.startECG()
        }

        binding.btnEcgStop.setOnClickListener {
            appendLog("CMD", "Deteniendo ECG")
            ble.stopECG()
        }

        binding.btnSpo2.setOnClickListener {
            appendLog("CMD", "Midiendo SpO2...")
            ble.startSpO2()
        }

        binding.btnContinuousHr.setOnClickListener {
            appendLog("CMD", "Activando HR continua (30s)")
            ble.enableContinuousHR()
        }

        binding.btnSendHex.setOnClickListener {
            val hex     = binding.hexInput.text.toString()
            val channel = when (binding.channelSpinner.selectedItemPosition) {
                0    -> FitProProtocol.CHANNEL_FFFF
                1    -> FitProProtocol.CHANNEL_NUS
                else -> "3802"
            }
            ble.sendRawHex(channel, hex)
        }

        binding.btnClearLog.setOnClickListener {
            binding.logView.text = ""
        }
    }

    // ── Observers ──────────────────────────────────────────────────────────
    private fun observeState() {
        lifecycleScope.launch {
            ble.state.collectLatest { state ->
                when (state) {
                    is BleState.Disconnected -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_disconnected)
                        binding.statusText.text = "Desconectado"
                        binding.deviceName.text = ""
                        setCommandsEnabled(false)
                        binding.btnConnect.isEnabled = true
                    }
                    is BleState.Scanning -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_scanning)
                        binding.statusText.text = "Buscando ULTRA2..."
                        binding.btnConnect.isEnabled = false
                    }
                    is BleState.Connecting -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_scanning)
                        binding.statusText.text = "Conectando..."
                    }
                    is BleState.Connected -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_connected)
                        binding.statusText.text = "Conectado"
                        binding.deviceName.text = state.name
                        setCommandsEnabled(true)
                        binding.btnConnect.isEnabled = false
                    }
                }
            }
        }
    }

    private fun observeLog() {
        lifecycleScope.launch {
            ble.log.collect { entry ->
                val (tag, msg, color) = when (entry) {
                    is LogEntry.Event -> Triple("EV", entry.msg, "#3b9eff")
                    is LogEntry.Error -> Triple("ER", entry.msg, "#ff4d6a")
                    is LogEntry.Tx    -> Triple("TX", "${entry.label} [${entry.bytes.toHex()}]", "#3b9eff")
                    is LogEntry.Rx    -> Triple("RX", "${entry.label} [${entry.bytes.toHex()}]", "#00e87a")
                }
                appendLog(tag, msg, color)
            }
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            ble.data.collect { data ->
                when (data) {
                    is WatchData.Health -> {
                        binding.valueHr.text      = if (data.heartRate > 0) "${data.heartRate} bpm" else "—"
                        binding.valueSteps.text   = if (data.steps > 0) "${data.steps}" else "—"
                        binding.valueBattery.text = if (data.battery > 0) "${data.battery}%" else "—"
                    }
                    is WatchData.Raw -> { /* logged separately */ }
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private fun setCommandsEnabled(enabled: Boolean) {
        listOf(
            binding.btnDisconnect, binding.btnPhoneType, binding.btnSyncTime,
            binding.btnHeartRate, binding.btnEcgStart, binding.btnEcgStop,
            binding.btnSpo2, binding.btnContinuousHr, binding.btnSendHex
        ).forEach { it.isEnabled = enabled }
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun appendLog(tag: String, msg: String, color: String = "#c9d6e3") {
        runOnUiThread {
            val time = timeFmt.format(Date())
            val current = binding.logView.text.toString()
            val line = "$time $tag $msg\n"
            binding.logView.text = current + line
            binding.logScroll.post {
                binding.logScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}
