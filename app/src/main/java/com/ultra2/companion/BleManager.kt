package com.ultra2.companion

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        val NUS_SERVICE  = UUID.fromString("6e400801-b5a3-f393-e0a9-e50e24dcca9d")
        val NUS_WRITE    = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9d")
        val NUS_NOTIFY   = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9d")
        val FFFF_SERVICE = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
        val FF22_WRITE   = UUID.fromString("0000ff22-0000-1000-8000-00805f9b34fb")
        val FF11_NOTIFY  = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
        val SVC_3802     = UUID.fromString("00003802-0000-1000-8000-00805f9b34fb")
        val CHAR_4A02    = UUID.fromString("00004a02-0000-1000-8000-00805f9b34fb")
        val CCCD         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BATTERY_SVC  = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BAT_LEVEL    = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEV_INFO_SVC = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val FW_REV       = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val MFR_NAME     = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    }

    private val _state    = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state

    private val _log      = MutableSharedFlow<LogEntry>(extraBufferCapacity = 200)
    val log: SharedFlow<LogEntry> = _log

    private val _data     = MutableSharedFlow<WatchData>(extraBufferCapacity = 50)
    val data: SharedFlow<WatchData> = _data

    // Scan results — UI can show these for selection
    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults

    private var gatt:      BluetoothGatt?               = null
    private var nusWrite:  BluetoothGattCharacteristic? = null
    private var ff22Write: BluetoothGattCharacteristic? = null
    private var char4a02:  BluetoothGattCharacteristic? = null

    private val scope     = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uiHandler = Handler(Looper.getMainLooper())
    private val foundMacs = mutableSetOf<String>()

    private val btManager  by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val btAdapter  get() = btManager.adapter

    // ── Scan — NO filter, shows ALL nearby BLE devices ────────────────────
    fun startScan() {
        val adapter = btAdapter
        if (adapter == null || !adapter.isEnabled) {
            emitLog(LogEntry.Error("Bluetooth desactivado. Actívalo primero."))
            _state.value = BleState.Disconnected
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            emitLog(LogEntry.Error("BLE scanner no disponible"))
            return
        }

        foundMacs.clear()
        _scanResults.value = emptyList()
        _state.value = BleState.Scanning
        emitLog(LogEntry.Event("Escaneando TODOS los dispositivos BLE (15s)..."))
        emitLog(LogEntry.Event("Asegúrate que el reloj esté en MODO INTELIGENTE"))

        // NO name filter — scan everything
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        uiHandler.postDelayed({
            scanner.stopScan(scanCallback)
            val results = _scanResults.value
            if (results.isEmpty()) {
                _state.value = BleState.Disconnected
                emitLog(LogEntry.Error("No se encontraron dispositivos BLE. ¿Bluetooth activo? ¿Reloj encendido?"))
            } else {
                emitLog(LogEntry.Event("Scan terminado. ${results.size} dispositivo(s) encontrado(s)."))
                // Auto-connect if Ultra2 found
                val watch = results.firstOrNull {
                    val name = it.device.name?.uppercase() ?: ""
                    name.contains("ULTRA") || name.contains("LS7076") || name.contains("WATCH")
                }
                if (watch != null) {
                    emitLog(LogEntry.Event("✓ Reloj detectado: ${watch.device.name} — conectando..."))
                    connect(watch.device)
                } else {
                    _state.value = BleState.Disconnected
                    emitLog(LogEntry.Event("Reloj no identificado automáticamente."))
                    emitLog(LogEntry.Event("Dispositivos encontrados — toca 'Conectar' y elige el tuyo:"))
                    results.take(10).forEachIndexed { i, r ->
                        val name = r.device.name ?: "Sin nombre"
                        val mac  = r.device.address
                        val rssi = r.rssi
                        emitLog(LogEntry.Event("  [$i] $name  $mac  ${rssi}dBm"))
                    }
                }
            }
        }, 15_000)
    }

    // Connect directly to a MAC address (for manual selection)
    fun connectByMac(mac: String) {
        val adapter = btAdapter ?: run {
            emitLog(LogEntry.Error("Bluetooth no disponible")); return
        }
        try {
            val device = adapter.getRemoteDevice(mac)
            emitLog(LogEntry.Event("Conectando a $mac..."))
            connect(device)
        } catch (e: Exception) {
            emitLog(LogEntry.Error("MAC inválida: $mac"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac  = result.device.address
            val name = result.device.name ?: "?"
            if (foundMacs.add(mac)) {
                // Log every NEW device found
                emitLog(LogEntry.Event("BLE encontrado: \"$name\"  $mac  ${result.rssi}dBm"))
                _scanResults.value = _scanResults.value + result
            }
        }
        override fun onScanFailed(errorCode: Int) {
            val msg = when (errorCode) {
                1 -> "ALREADY_STARTED"
                2 -> "APP_REGISTRATION_FAILED"
                3 -> "INTERNAL_ERROR"
                4 -> "FEATURE_UNSUPPORTED"
                5 -> "OUT_OF_HARDWARE_RESOURCES"
                6 -> "SCANNING_TOO_FREQUENTLY — espera 30s y reintenta"
                else -> "código $errorCode"
            }
            _state.value = BleState.Disconnected
            emitLog(LogEntry.Error("Scan falló: $msg"))
        }
    }

    // ── Connect ────────────────────────────────────────────────────────────
    private fun connect(device: BluetoothDevice) {
        _state.value = BleState.Connecting
        emitLog(LogEntry.Event("Conectando GATT a ${device.name ?: device.address}..."))
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        nusWrite = null; ff22Write = null; char4a02 = null
        _state.value = BleState.Disconnected
        emitLog(LogEntry.Event("Desconectado"))
    }

    // ── GATT callback ──────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    emitLog(LogEntry.Event("GATT conectado ✓ — descubriendo servicios..."))
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status != 0) emitLog(LogEntry.Error("GATT desconectado (status=$status)"))
                    else emitLog(LogEntry.Event("GATT desconectado"))
                    _state.value = BleState.Disconnected
                    gatt?.close(); gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLog(LogEntry.Error("Service discovery falló: $status")); return
            }
            emitLog(LogEntry.Event("Servicios descubiertos:"))
            g.services.forEach { svc ->
                emitLog(LogEntry.Event("  SVC: ${svc.uuid}"))
            }
            scope.launch {
                setupCharacteristics(g)
                delay(500)
                emitLog(LogEntry.Event("→ Enviando PhoneType=Android (fix modo portátil)..."))
                sendPhoneTypeAndroid()
                delay(300)
                syncTime()
                _state.value = BleState.Connected(g.device.name ?: g.device.address)
                emitLog(LogEntry.Event("✓ Listo."))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray
        ) {
            emitLog(LogEntry.Rx(char.uuid.toString().take(8), value))
            FitProProtocol.parseNotification(value)?.let {
                scope.launch { _data.emit(it) }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, char, char.value ?: return)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS)
                emitLog(LogEntry.Error("Write falló (${char.uuid.toString().take(8)}): $status"))
        }
    }

    // ── Service setup ──────────────────────────────────────────────────────
    private suspend fun setupCharacteristics(g: BluetoothGatt) {
        g.getService(NUS_SERVICE)?.let { svc ->
            nusWrite = svc.getCharacteristic(NUS_WRITE)
            svc.getCharacteristic(NUS_NOTIFY)?.let { enableNotify(g, it) }
            emitLog(LogEntry.Event("NUS service ✓"))
        } ?: emitLog(LogEntry.Error("NUS no encontrado"))

        g.getService(FFFF_SERVICE)?.let { svc ->
            ff22Write = svc.getCharacteristic(FF22_WRITE)
            svc.getCharacteristic(FF11_NOTIFY)?.let { enableNotify(g, it) }
            emitLog(LogEntry.Event("0xFFFF service ✓"))
        } ?: emitLog(LogEntry.Error("0xFFFF no encontrado"))

        g.getService(SVC_3802)?.let { svc ->
            char4a02 = svc.getCharacteristic(CHAR_4A02)
            char4a02?.let { enableNotify(g, it) }
            emitLog(LogEntry.Event("0x3802 service ✓"))
        }

        // Read battery immediately
        g.getService(BATTERY_SVC)?.getCharacteristic(BAT_LEVEL)?.let {
            g.readCharacteristic(it)
        }
    }

    private suspend fun enableNotify(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(char, true)
        char.getDescriptor(CCCD)?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(desc)
            delay(200)
        }
    }

    // ── Commands ───────────────────────────────────────────────────────────
    fun sendPhoneTypeAndroid() {
        scope.launch {
            FitProProtocol.phoneTypeAndroid().forEach { (ch, b) ->
                writeToChannel(ch, b); delay(300)
            }
        }
    }

    fun syncTime() {
        scope.launch {
            FitProProtocol.syncTime().forEach { (ch, b) ->
                writeToChannel(ch, b); delay(200)
            }
        }
    }

    fun startHeartRate()    { scope.launch { FitProProtocol.startHeartRate().forEach   { (c,b) -> writeToChannel(c,b) } } }
    fun startECG()          { scope.launch { FitProProtocol.startECG().forEach         { (c,b) -> writeToChannel(c,b) } } }
    fun stopECG()           { scope.launch { FitProProtocol.stopECG().forEach          { (c,b) -> writeToChannel(c,b) } } }
    fun startSpO2()         { scope.launch { FitProProtocol.startSpO2().forEach        { (c,b) -> writeToChannel(c,b) } } }
    fun enableContinuousHR(){ scope.launch { FitProProtocol.enableContinuousHR().forEach { (c,b) -> writeToChannel(c,b) } } }

    fun sendRawHex(channel: String, hexString: String) {
        try {
            val bytes = hexString.trim().split("\\s+".toRegex())
                .filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }.toByteArray()
            scope.launch { writeToChannel(channel, bytes) }
        } catch (e: Exception) {
            emitLog(LogEntry.Error("Hex inválido: ${e.message}"))
        }
    }

    private fun writeToChannel(channel: String, bytes: ByteArray) {
        val char = when (channel) {
            FitProProtocol.CHANNEL_NUS  -> nusWrite
            FitProProtocol.CHANNEL_FFFF -> ff22Write
            "3802"                       -> char4a02
            else                         -> ff22Write
        } ?: run {
            emitLog(LogEntry.Error("Canal $channel no disponible (¿reloj conectado?)"))
            return
        }
        emitLog(LogEntry.Tx("→$channel", bytes))
        val g = gatt ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    private fun emitLog(entry: LogEntry) { scope.launch { _log.emit(entry) } }

    fun dispose() { scope.cancel(); disconnect() }
}

// ── Models ─────────────────────────────────────────────────────────────────────
sealed class BleState {
    object Disconnected                    : BleState()
    object Scanning                        : BleState()
    object Connecting                      : BleState()
    data class Connected(val name: String) : BleState()
}

sealed class LogEntry {
    data class Event(val msg: String)                           : LogEntry()
    data class Tx(val label: String, val bytes: ByteArray)     : LogEntry()
    data class Rx(val label: String, val bytes: ByteArray)     : LogEntry()
    data class Error(val msg: String)                          : LogEntry()
}
