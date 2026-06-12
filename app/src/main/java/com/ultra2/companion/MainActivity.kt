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
        val denied = grants.filter { !it.value }.keys
        if (denied.isEmpty()) {
            appendLog("SYS", "Permisos concedidos ✓")
            ble.startScan()
        } else {
            appendLog("ERR", "Permisos denegados: $denied")
            appendLog("ERR", "Ve a Ajustes > Apps > Ultra2 > Permisos y activa Bluetooth")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = BleManager(applicationContext)
        setupButtons()
        observeState()
        observeLog()
        observeData()

        // Pre-fill MAC of the watch
        binding.hexInput.setText("27:E2:F7:00:08:ED")
        appendLog("SYS", "Ultra2 Companion v1.0 · LS7076 / Jieli")
        appendLog("SYS", "Pulsa CONECTAR con el reloj en MODO INTELIGENTE")
        appendLog("SYS", "O usa CONECTAR POR MAC si el scan no lo encuentra")
    }

    override fun onDestroy() {
        super.onDestroy()
        ble.dispose()
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            if (!hasPermissions()) {
                appendLog("SYS", "Solicitando permisos Bluetooth...")
                permLauncher.launch(permissions)
            } else {
                ble.startScan()
            }
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
            appendLog("CMD", "Midiendo HR...")
            ble.startHeartRate()
        }

        binding.btnEcgStart.setOnClickListener {
            appendLog("CMD", "ECG inicio...")
            ble.startECG()
        }

        binding.btnEcgStop.setOnClickListener {
            appendLog("CMD", "ECG stop")
            ble.stopECG()
        }

        binding.btnSpo2.setOnClickListener {
            appendLog("CMD", "Midiendo SpO2...")
            ble.startSpO2()
        }

        binding.btnContinuousHr.setOnClickListener {
            appendLog("CMD", "HR continua activada (30s)")
            ble.enableContinuousHR()
        }

        // btnSendHex repurposed: connect by MAC or send hex
        binding.btnSendHex.setOnClickListener {
            val input = binding.hexInput.text.toString().trim()
            if (input.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
                // It's a MAC address
                appendLog("CMD", "Conectando por MAC: $input")
                ble.connectByMac(input)
            } else {
                // It's hex data to send
                val channel = when (binding.channelSpinner.selectedItemPosition) {
                    0    -> FitProProtocol.CHANNEL_FFFF
                    1    -> FitProProtocol.CHANNEL_NUS
                    else -> "3802"
                }
                ble.sendRawHex(channel, input)
            }
        }

        binding.btnClearLog.setOnClickListener {
            binding.logView.text = ""
        }
    }

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
                        binding.btnSendHex.isEnabled = true  // allow MAC connect always
                    }
                    is BleState.Scanning -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_scanning)
                        binding.statusText.text = "Escaneando BLE..."
                        binding.btnConnect.isEnabled = false
                    }
                    is BleState.Connecting -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_scanning)
                        binding.statusText.text = "Conectando..."
                        binding.btnConnect.isEnabled = false
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
                when (entry) {
                    is LogEntry.Event -> appendLog("EV", entry.msg)
                    is LogEntry.Error -> appendLog("ER", entry.msg)
                    is LogEntry.Tx    -> appendLog("TX", "${entry.label} [${entry.bytes.toHex()}]")
                    is LogEntry.Rx    -> appendLog("RX", "${entry.label} [${entry.bytes.toHex()}]")
                }
            }
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            ble.data.collect { data ->
                when (data) {
                    is WatchData.Health -> {
                        if (data.heartRate > 0) binding.valueHr.text = "${data.heartRate} bpm"
                        if (data.steps > 0)     binding.valueSteps.text = "${data.steps}"
                        if (data.battery > 0)   binding.valueBattery.text = "${data.battery}%"
                    }
                    is WatchData.Raw -> {}
                }
            }
        }
    }

    private fun setCommandsEnabled(enabled: Boolean) {
        listOf(
            binding.btnDisconnect, binding.btnPhoneType, binding.btnSyncTime,
            binding.btnHeartRate, binding.btnEcgStart, binding.btnEcgStop,
            binding.btnSpo2, binding.btnContinuousHr
        ).forEach { it.isEnabled = enabled }
        binding.btnSendHex.isEnabled = true // always enabled
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun appendLog(tag: String, msg: String) {
        runOnUiThread {
            val time = timeFmt.format(Date())
            binding.logView.append("$time $tag $msg\n")
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
}
