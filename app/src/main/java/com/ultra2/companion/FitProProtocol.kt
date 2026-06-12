package com.ultra2.companion

/**
 * FitPro / Jieli protocol command encoder.
 *
 * Based on reverse-engineering of HiwatchPro APK (v1.3.67).
 * NUS TX (6e400002) and 0xFF22 are the two write channels.
 *
 * Packet format (phone → watch):
 *   [header: 0xEE] [flags: 0x00] [protobuf-encoded payload]
 *
 * Packet format (watch → phone):
 *   [header: 0xCD] [flags: 0x00] [protobuf-encoded payload]
 */
object FitProProtocol {

    // ── Channels ─────────────────────────────────────────────────────────────
    const val CHANNEL_NUS  = "nus"
    const val CHANNEL_FFFF = "ffff"

    // ── Packet headers ───────────────────────────────────────────────────────
    private const val HDR_TX: Byte = 0xEE.toByte()  // phone → watch
    private const val HDR_RX: Byte = 0xCD.toByte()  // watch → phone (notifications)

    // ── Build a command packet ───────────────────────────────────────────────
    private fun buildPacket(vararg payload: Byte): ByteArray =
        byteArrayOf(HDR_TX, 0x00) + payload

    // ── Protobuf varint encoder ──────────────────────────────────────────────
    private fun varint(value: Int): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value
        while (v > 0x7F) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add((v and 0x7F).toByte())
        return result.toByteArray()
    }

    // ── Protobuf field encoder ───────────────────────────────────────────────
    // Wire type 0 = varint
    private fun field(fieldNumber: Int, value: Int): ByteArray {
        val tag = (fieldNumber shl 3) or 0  // wire type 0 = varint
        return varint(tag) + varint(value)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // COMMANDS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Tell the watch it is connected to an Android phone.
     * Fixes Classic BT audio (portable mode) — this is the key fix.
     *
     * PBSmartBandCommandIdPhoneType:
     *   field 1 = commandId (0x17 = 23 = PhoneType in FitPro enum)
     *   field 2 = value (1 = Android, 2 = iOS)
     *
     * We send four format variants to maximize compatibility.
     */
    fun phoneTypeAndroid(): List<Pair<String, ByteArray>> = listOf(
        // Variant A: NUS, with header
        CHANNEL_NUS  to buildPacket(*field(1, 0x17), *field(2, 0x01)),
        // Variant B: 0xFF22, with header
        CHANNEL_FFFF to buildPacket(*field(1, 0x17), *field(2, 0x01)),
        // Variant C: 0xFF22, raw protobuf (no header — some chips skip it)
        CHANNEL_FFFF to (field(1, 0x17) + field(2, 0x01)),
        // Variant D: alternate command id 0x0E (seen in some FitPro builds)
        CHANNEL_FFFF to buildPacket(*field(1, 0x0E), *field(2, 0x01)),
    )

    /**
     * Sync current time to the watch.
     * PBSmartBandCommandIdSyncTime: field 1 = 0x03, field 2 = unix timestamp (4 bytes)
     */
    fun syncTime(): List<Pair<String, ByteArray>> {
        val ts = (System.currentTimeMillis() / 1000).toInt()
        val tsBytes = byteArrayOf(
            (ts shr 24).toByte(), (ts shr 16).toByte(),
            (ts shr 8).toByte(),  ts.toByte()
        )
        val payload = buildPacket(*field(1, 0x03)) + tsBytes
        return listOf(
            CHANNEL_FFFF to payload,
            CHANNEL_NUS  to payload,
        )
    }

    /**
     * Request device status (triggers watch to send a full status notification).
     */
    fun requestStatus(): List<Pair<String, ByteArray>> = listOf(
        CHANNEL_FFFF to buildPacket(*field(1, 0x01)),
        CHANNEL_NUS  to buildPacket(*field(1, 0x01)),
    )

    /**
     * Request heart rate reading.
     * PBSmartBandCommandIdHeartRate: start measurement
     */
    fun startHeartRate(): List<Pair<String, ByteArray>> = listOf(
        CHANNEL_FFFF to buildPacket(*field(1, 0x06), *field(2, 0x01)),
    )

    /**
     * Start ECG recording (hardware has this — app hides it).
     * PBSmartBandCommandIdECGBegin
     */
    fun startECG(): List<Pair<String, ByteArray>> = listOf(
        CHANNEL_FFFF to buildPacket(*field(1, 0x30), *field(2, 0x01)),
    )

    /**
     * Stop ECG recording.
     * PBSmartBandCommandIdECGEnd
     */
    fun stopECG(): List<Pair<String, ByteArray>> = listOf(
        CHANNEL_FFFF to buildPacket(*field(1, 0x30), *field(2, 0x00)),
    )

    /**
     * Request SpO2 (blood oxygen) reading.
     */
    fun startSpO2(): List<Pair<String, ByteArray>> = listOf(
        CHANNEL_FFFF to buildPacket(*field(1, 0x0A), *field(2, 0x01)),
    )

    /**
     * Enable continuous heart rate monitoring (every 30 seconds).
     * The app limits this artificially.
     */
    fun enableContinuousHR(): List<Pair<String, ByteArray>> = listOf(
        CHANNEL_FFFF to buildPacket(*field(1, 0x07), *field(2, 0x1E)), // 0x1E = 30 seconds
    )

    // ── Notification parser ───────────────────────────────────────────────────

    /**
     * Parse an incoming notification from the watch.
     * Returns a human-readable WatchData object or null if unrecognized.
     */
    fun parseNotification(bytes: ByteArray): WatchData? {
        if (bytes.isEmpty()) return null
        if (bytes[0] != HDR_RX) return null  // not a FitPro packet
        if (bytes.size < 4) return null

        // Simplified parser — reads first protobuf field to identify packet type
        return when {
            bytes.size >= 20 -> parseHealthPacket(bytes)
            else -> WatchData.Raw(bytes.toHex())
        }
    }

    private fun parseHealthPacket(bytes: ByteArray): WatchData.Health {
        // Notification: CD 00 [commandId] [data...]
        // Based on captured value: CD 00 11 15 01 0C 00 0C 32 21 00...
        //   bytes[4] = steps high byte portion
        //   bytes[5] = steps low byte
        //   bytes[7] = heart rate
        //   bytes[8] = battery
        return WatchData.Health(
            steps    = ((bytes.getOrElse(4){0}.toInt() and 0xFF) shl 8) or
                        (bytes.getOrElse(5){0}.toInt() and 0xFF),
            heartRate = bytes.getOrElse(7){0}.toInt() and 0xFF,
            battery   = bytes.getOrElse(8){0}.toInt() and 0xFF,
            raw       = bytes.toHex()
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }

    operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(size + other.size)
        copyInto(result)
        other.copyInto(result, size)
        return result
    }
}

// ── Data models ───────────────────────────────────────────────────────────────

sealed class WatchData {
    data class Health(
        val steps:     Int,
        val heartRate: Int,
        val battery:   Int,
        val raw:       String,
    ) : WatchData()

    data class Raw(val hex: String) : WatchData()
}
