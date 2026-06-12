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

/**
 * Manages BLE connection to the Ultra2 / LS7076 watch.
 *
 * Services used (confirmed via nRF Connect scan):
 *   NUS  : 6e400801-b5a3-f393-e0a9-e50e24dcca9d  (Nordic UART)
 *   FFFF : 0000ffff-0000-1000-8000-00805f9b34fb  (FitPro commands)
 *   3802 : 00003802-0000-1000-8000-00805f9b34fb  (secondary data)
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    // ── UUIDs ──────────────────────────────────────────────────────────────
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

    // ── State ──────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow<BleState>(BleState.Disconnected)
    val state: StateFlow<BleState> = _state

    private val _log   = MutableSharedFlow<LogEntry>(extraBufferCapacity = 100)
    val log: SharedFlow<LogEntry> = _log

    private val _data  = MutableSharedFlow<WatchData>(extraBufferCapacity = 50)
    val data: SharedFlow<WatchData> = _data

    private var gatt:      BluetoothGatt?           = null
    private var nusWrite:  BluetoothGattCharacteristic? = null
    private var ff22Write: BluetoothGattCharacteristic? = null
    private var char4a02:  BluetoothGattCharacteristic? = null

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uiHandler = Handler(Looper.getMainLooper())

    private val btAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // ── Scan ──────────────────────────────────────────────────────────────
    fun startScan() {
        val scanner = btAdapter?.bluetoothLeScanner ?: run {
            emitLog(LogEntry.Error("Bluetooth no disponible")); return
        }
        _state.value = BleState.Scanning
        emitLog(LogEntry.Event("Escaneando... buscando ULTRA2"))

        val filters = listOf(
            ScanFilter.Builder().setDeviceName("ULTRA2").build(),
            ScanFilter.Builder().setDeviceName("Ultra2").build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)

        // Auto-stop scan after 15 seconds
        uiHandler.postDelayed({
            scanner.stopScan(scanCallback)
            if (_state.value is BleState.Scanning) {
                _state.value = BleState.Disconnected
                emitLog(LogEntry.Error("Reloj no encontrado. ¿Está encendido y en modo inteligente?"))
            }
        }, 15_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            btAdapter?.bluetoothLeScanner?.stopScan(this)
            emitLog(LogEntry.Event("Encontrado: ${result.device.name} [${result.device.address}]"))
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            _state.value = BleState.Disconnected
            emitLog(LogEntry.Error("Scan falló: código $errorCode"))
        }
    }

    // ── Connect ────────────────────────────────────────────────────────────
    private fun connect(device: BluetoothDevice) {
        _state.value = BleState.Connecting
        emitLog(LogEntry.Event("Conectando GATT a ${device.address}..."))
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
                    _state.value = BleState.Disconnected
                    emitLog(LogEntry.Event("GATT desconectado"))
                    gatt?.close(); gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLog(LogEntry.Error("Service discovery falló: $status")); return
            }

            emitLog(LogEntry.Event("Servicios descubiertos — suscribiendo..."))
            scope.launch {
                setupCharacteristics(g)
                readDeviceInfo(g)

                // ── THE KEY FIX ──────────────────────────────────────────
                // Send PhoneType=Android immediately after connecting.
                // This ensures Classic BT portable mode works correctly.
                delay(500)
                emitLog(LogEntry.Event("Enviando PhoneType=Android..."))
                sendPhoneTypeAndroid()
                delay(300)
                syncTime()
                // ─────────────────────────────────────────────────────────

                _state.value = BleState.Connected(g.device.name ?: "ULTRA2")
                emitLog(LogEntry.Event("✓ Listo. Modo portátil Bluetooth arreglado."))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            emitLog(LogEntry.Rx("${char.uuid.toString().take(8)}", value))
            val parsed = FitProProtocol.parseNotification(value)
            parsed?.let { scope.launch { _data.emit(it) } }
        }

        // Legacy callback for Android < 13
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, char, char.value ?: return)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLog(LogEntry.Error("Write falló en ${char.uuid.toString().take(8)}: $status"))
            }
        }
    }

    // ── Service setup ──────────────────────────────────────────────────────
    private suspend fun setupCharacteristics(g: BluetoothGatt) {
        // NUS
        g.getService(NUS_SERVICE)?.let { svc ->
            nusWrite = svc.getCharacteristic(NUS_WRITE)
            svc.getCharacteristic(NUS_NOTIFY)?.let { enableNotify(g, it) }
            emitLog(LogEntry.Event("NUS service ✓"))
        } ?: emitLog(LogEntry.Error("NUS service no encontrado"))

        // 0xFFFF
        g.getService(FFFF_SERVICE)?.let { svc ->
            ff22Write = svc.getCharacteristic(FF22_WRITE)
            svc.getCharacteristic(FF11_NOTIFY)?.let { enableNotify(g, it) }
            emitLog(LogEntry.Event("0xFFFF service ✓"))
        } ?: emitLog(LogEntry.Error("0xFFFF service no encontrado"))

        // 0x3802
        g.getService(SVC_3802)?.let { svc ->
            char4a02 = svc.getCharacteristic(CHAR_4A02)
            char4a02?.let { enableNotify(g, it) }
            emitLog(LogEntry.Event("0x3802 service ✓"))
        }
    }

    private suspend fun enableNotify(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(char, true)
        char.getDescriptor(CCCD)?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(desc)
            delay(150) // give time between descriptor writes
        }
    }

    private suspend fun readDeviceInfo(g: BluetoothGatt) {
        g.getService(DEV_INFO_SVC)?.let { svc ->
            val fw  = readStringChar(g, svc, FW_REV)  ?: "—"
            val mfr = readStringChar(g, svc, MFR_NAME) ?: "—"
            emitLog(LogEntry.Event("DevInfo → FW: $fw | Mfr: $mfr"))
        }
    }

    private suspend fun readStringChar(
        g: BluetoothGatt,
        svc: BluetoothGattService,
        uuid: UUID
    ): String? {
        val char = svc.getCharacteristic(uuid) ?: return null
        return suspendCancellableCoroutine { cont ->
            val cb = object : BluetoothGattCallback() {
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    if (c.uuid == uuid) cont.resume(String(value), null)
                }
            }
            // We can just read synchronously with a coroutine delay here
            g.readCharacteristic(char)
            scope.launch { delay(300); cont.resume(null, null) }
        }
    }

    // ── Public commands ────────────────────────────────────────────────────

    fun sendPhoneTypeAndroid() {
        scope.launch {
            FitProProtocol.phoneTypeAndroid().forEach { (channel, bytes) ->
                writeToChannel(channel, bytes)
                delay(300)
            }
        }
    }

    fun syncTime() {
        scope.launch {
            FitProProtocol.syncTime().forEach { (channel, bytes) ->
                writeToChannel(channel, bytes)
                delay(200)
            }
        }
    }

    fun startHeartRate() {
        scope.launch {
            FitProProtocol.startHeartRate().forEach { (c, b) -> writeToChannel(c, b) }
        }
    }

    fun startECG() {
        scope.launch {
            FitProProtocol.startECG().forEach { (c, b) -> writeToChannel(c, b) }
        }
    }

    fun stopECG() {
        scope.launch {
            FitProProtocol.stopECG().forEach { (c, b) -> writeToChannel(c, b) }
        }
    }

    fun startSpO2() {
        scope.launch {
            FitProProtocol.startSpO2().forEach { (c, b) -> writeToChannel(c, b) }
        }
    }

    fun enableContinuousHR() {
        scope.launch {
            FitProProtocol.enableContinuousHR().forEach { (c, b) -> writeToChannel(c, b) }
        }
    }

    fun sendRawHex(channel: String, hexString: String) {
        try {
            val bytes = hexString.trim().split("\\s+".toRegex())
                .map { it.toInt(16).toByte() }.toByteArray()
            scope.launch { writeToChannel(channel, bytes) }
        } catch (e: Exception) {
            emitLog(LogEntry.Error("Hex inválido: ${e.message}"))
        }
    }

    // ── Core write ─────────────────────────────────────────────────────────
    private fun writeToChannel(channel: String, bytes: ByteArray) {
        val char = when (channel) {
            FitProProtocol.CHANNEL_NUS  -> nusWrite
            FitProProtocol.CHANNEL_FFFF -> ff22Write
            "3802"                       -> char4a02
            else                         -> ff22Write
        } ?: run {
            emitLog(LogEntry.Error("Canal $channel no disponible"))
            return
        }

        emitLog(LogEntry.Tx("→ $channel", bytes))

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

    // ── Helpers ────────────────────────────────────────────────────────────
    private fun emitLog(entry: LogEntry) {
        scope.launch { _log.emit(entry) }
    }

    fun dispose() {
        scope.cancel()
        disconnect()
    }
}

// ── State & Log models ─────────────────────────────────────────────────────────

sealed class BleState {
    object Disconnected                    : BleState()
    object Scanning                        : BleState()
    object Connecting                      : BleState()
    data class Connected(val name: String) : BleState()
}

sealed class LogEntry {
    data class Event(val msg: String)              : LogEntry()
    data class Tx(val label: String, val bytes: ByteArray) : LogEntry()
    data class Rx(val label: String, val bytes: ByteArray) : LogEntry()
    data class Error(val msg: String)              : LogEntry()
}
