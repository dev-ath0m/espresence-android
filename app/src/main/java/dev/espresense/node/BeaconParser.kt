package dev.espresense.node

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import java.nio.ByteBuffer
import java.util.UUID

/** A single BLE/iBeacon advertisement, identified the way ESPresense identifies devices. */
data class DetectedBeacon(
    val id: String,
    val mac: String,
    val name: String?,
    val rssi: Int,
    val isIBeacon: Boolean,
    /** iBeacon's own calibrated RSSI @ 1m (the "measured power" byte), if this is an iBeacon. */
    val measuredPower: Int?
)

/**
 * Parses BLE advertisements into [DetectedBeacon]s, prioritizing iBeacon frames
 * (Apple manufacturer id 0x004C, type 0x02) the same way ESPresense's firmware does,
 * and falling back to a MAC-based id for any other BLE device.
 */
object BeaconParser {
    private const val APPLE_MANUFACTURER_ID = 0x004C

    fun parse(result: ScanResult): DetectedBeacon? {
        val device = result.device ?: return null
        val mac = device.address ?: return null
        val record: ScanRecord = result.scanRecord ?: return null
        val rssi = result.rssi

        val ibeacon = parseIBeacon(record)
        if (ibeacon != null) {
            // ESPresense's own firmware/companion publish and match iBeacon fingerprints with a
            // capital "iBeacon:" prefix (e.g. "iBeacon:<uuid>-<major>-<minor>") - matching that
            // exactly (case-sensitive) is required for resolveDevice()'s lookup against configs
            // learned from other nodes (e.g. other ESPresense rooms broadcasting as iBeacons).
            val id = "iBeacon:${ibeacon.uuid}-${ibeacon.major}-${ibeacon.minor}"
            return DetectedBeacon(
                id = id,
                mac = mac,
                name = record.deviceName,
                rssi = rssi,
                isIBeacon = true,
                measuredPower = ibeacon.measuredPower
            )
        }

        val id = "mac:${mac.lowercase()}"
        return DetectedBeacon(
            id = id,
            mac = mac,
            name = record.deviceName,
            rssi = rssi,
            isIBeacon = false,
            measuredPower = null
        )
    }

    private data class IBeaconData(val uuid: UUID, val major: Int, val minor: Int, val measuredPower: Int)

    private fun parseIBeacon(record: ScanRecord): IBeaconData? {
        val data = record.getManufacturerSpecificData(APPLE_MANUFACTURER_ID) ?: return null
        // type(1) + length(1) + uuid(16) + major(2) + minor(2) + measuredPower(1) = 23 bytes
        if (data.size < 23) return null
        if (data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null

        val uuidBytes = data.copyOfRange(2, 18)
        val bb = ByteBuffer.wrap(uuidBytes)
        val high = bb.long
        val low = bb.long
        val uuid = UUID(high, low)

        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        val measuredPower = data[22].toInt() // signed byte: calibrated RSSI @ 1m

        return IBeaconData(uuid, major, minor, measuredPower)
    }
}
