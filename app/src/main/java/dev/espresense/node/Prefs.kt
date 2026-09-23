package dev.espresense.node

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences holding all node configuration.
 * Mirrors the settings exposed by ESPresense / ESPresense-Pi (room, MQTT
 * connection, calibration, filtering).
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("espresense_prefs", Context.MODE_PRIVATE)

    var mqttHost: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(value) = sp.edit().putString(KEY_HOST, value).apply()

    var mqttPort: Int
        get() = sp.getInt(KEY_PORT, 1883)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    var mqttUser: String
        get() = sp.getString(KEY_USER, "") ?: ""
        set(value) = sp.edit().putString(KEY_USER, value).apply()

    var mqttPass: String
        get() = sp.getString(KEY_PASS, "") ?: ""
        set(value) = sp.edit().putString(KEY_PASS, value).apply()

    var mqttTls: Boolean
        get() = sp.getBoolean(KEY_TLS, false)
        set(value) = sp.edit().putBoolean(KEY_TLS, value).apply()

    /** ESPresense "room" name; becomes part of every topic this node publishes to. */
    var room: String
        get() = sp.getString(KEY_ROOM, "android-tablet") ?: "android-tablet"
        set(value) = sp.edit().putString(KEY_ROOM, value).apply()

    /** Fallback RSSI @ 1m used for non-iBeacon devices (iBeacons use their own measured power). */
    var refRssi: Int
        get() = sp.getInt(KEY_REF_RSSI, -65)
        set(value) = sp.edit().putInt(KEY_REF_RSSI, value).apply()

    /** Path-loss exponent (environmental factor). ESPresense default 2.7. */
    var absorption: Float
        get() = sp.getFloat(KEY_ABSORPTION, 2.7f)
        set(value) = sp.edit().putFloat(KEY_ABSORPTION, value).apply()

    /**
     * Per-node receiver correction in dB, *subtracted* from every measured RSSI
     * before the distance is computed (ESPresense's "rx_adj_rssi"), so a node that
     * hears everything too loudly needs a positive value. Receivers differ by tens
     * of dB, so without it a node cannot be calibrated against a beacon whose
     * reference power is fleet-wide (an iBeacon's broadcast Measured Power).
     */
    var rxAdjRssi: Int
        get() = sp.getInt(KEY_RX_ADJ_RSSI, 0)
        set(value) = sp.edit().putInt(KEY_RX_ADJ_RSSI, value).apply()

    /** Reports farther than this (meters) are dropped. */
    var maxDistance: Float
        get() = sp.getFloat(KEY_MAX_DISTANCE, 16.0f)
        set(value) = sp.edit().putFloat(KEY_MAX_DISTANCE, value).apply()

    /** Minimum time between re-publishing the same device id, unless it moved skipDistance. */
    var skipMs: Long
        get() = sp.getLong(KEY_SKIP_MS, 5000L)
        set(value) = sp.edit().putLong(KEY_SKIP_MS, value).apply()

    var skipDistance: Float
        get() = sp.getFloat(KEY_SKIP_DISTANCE, 0.5f)
        set(value) = sp.edit().putFloat(KEY_SKIP_DISTANCE, value).apply()

    var includeGenericDevices: Boolean
        get() = sp.getBoolean(KEY_INCLUDE_GENERIC, false)
        set(value) = sp.edit().putBoolean(KEY_INCLUDE_GENERIC, value).apply()

    var discoveryEnabled: Boolean
        get() = sp.getBoolean(KEY_DISCOVERY, true)
        set(value) = sp.edit().putBoolean(KEY_DISCOVERY, value).apply()

    var telemetryEnabled: Boolean
        get() = sp.getBoolean(KEY_TELEMETRY, true)
        set(value) = sp.edit().putBoolean(KEY_TELEMETRY, value).apply()

    /** Whether the user has asked the node to run; used to auto-restart after boot / crash. */
    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    /** Which GitHub releases self-update will offer: stable only, or pre-releases too. */
    var updateChannel: UpdateChannel
        get() = UpdateChannel.fromKey(sp.getString(KEY_UPDATE_CHANNEL, null))
        set(value) = sp.edit().putString(KEY_UPDATE_CHANNEL, value.name).apply()

    /** Whether the node checks for a new release on its own (at most once a day). */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE_CHECK, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, value).apply()

    /** Wall-clock time of the last release check, to stay inside GitHub's rate limit. */
    var lastUpdateCheckMs: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    fun isConfigured(): Boolean = mqttHost.isNotBlank() && room.isNotBlank()

    companion object {
        /** Strips characters that would break MQTT topic segments (e.g. "/") out of a room name. */
        fun sanitizeRoom(raw: String): String {
            val cleaned = raw.trim().replace("/", "-").replace("+", "-").replace("#", "-")
            return cleaned.ifBlank { "android-tablet" }
        }

        private const val KEY_HOST = "mqtt_host"
        private const val KEY_PORT = "mqtt_port"
        private const val KEY_USER = "mqtt_user"
        private const val KEY_PASS = "mqtt_pass"
        private const val KEY_TLS = "mqtt_tls"
        private const val KEY_ROOM = "room"
        private const val KEY_REF_RSSI = "ref_rssi"
        private const val KEY_ABSORPTION = "absorption"
        private const val KEY_RX_ADJ_RSSI = "rx_adj_rssi"
        private const val KEY_MAX_DISTANCE = "max_distance"
        private const val KEY_SKIP_MS = "skip_ms"
        private const val KEY_SKIP_DISTANCE = "skip_distance"
        private const val KEY_INCLUDE_GENERIC = "include_generic"
        private const val KEY_DISCOVERY = "discovery_enabled"
        private const val KEY_TELEMETRY = "telemetry_enabled"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_UPDATE_CHANNEL = "update_channel"
        private const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
