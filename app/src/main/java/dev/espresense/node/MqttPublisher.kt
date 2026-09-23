package dev.espresense.node

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/** Enrollment for a known/mapped device, keyed by its raw fingerprint (e.g. "mac:aa:bb:..."). */
data class DeviceConfig(val id: String, val name: String?, val calRssi: Int? = null)

/**
 * Publishes to MQTT using the same topic layout as ESPresense / ESPresense-Pi, so this
 * node behaves like a drop-in additional room for ESPresense-companion / mqtt_room:
 *
 *   espresense/rooms/<room>/status              online / offline (retained, LWT)
 *   espresense/rooms/<room>/name                room display name (retained)
 *   espresense/rooms/<room>/telemetry           JSON uptime/battery (non-retained)
 *   espresense/rooms/<room>/<setting>           current value, e.g. max_distance (retained)
 *   espresense/rooms/<room>/<setting>/set       write a setting live
 *   espresense/rooms/ANY_ROOM/<setting>/set     fleet-wide write (room segment "*", also honored)
 *   espresense/devices/<id>/<room>              per-device JSON: id, distance, rssi, mac, name
 *   espresense/settings/<fingerprint>/config    retained enrollment: {"id":..,"name":..,"rssi@1m":..} -
 *                                                 maps a raw fingerprint (mac:.., iBeacon:.., irk:.., name:..)
 *                                                 to a friendly id/name and optional per-device RSSI@1m
 *                                                 calibration, the same way real ESPresense nodes resolve
 *                                                 enrolled devices (see espresense.com/guides/
 *                                                 enrolling-devices). We can't capture IRKs like the ESP32
 *                                                 firmware does, but we honor any mapping already published
 *                                                 by the companion/another node for MAC- or iBeacon-based ids,
 *                                                 and can publish our own mapping/calibration too so it's
 *                                                 shared to every other node.
 */
class MqttPublisher(
    private val prefs: Prefs,
    private val onSettingChanged: (setting: String, value: String) -> Unit
) {
    private var client: IMqttAsyncClient? = null

    /**
     * The room this connection belongs to - a snapshot of [Prefs.room] taken when
     * [connect] runs, deliberately NOT a live read of prefs.
     *
     * Renaming the room writes prefs first and reconnects afterwards. With a live
     * getter every cleanup publish (the "offline" status, the retained settings)
     * would go to the NEW topic while the LWT and the old retained values stayed
     * on the OLD one, leaving a room that is "online" forever with no node behind
     * it. Pinning the room to the connection keeps teardown aimed at the topics
     * this connection actually created.
     */
    var activeRoom: String = prefs.room
        private set

    private val room get() = activeRoom
    private val deviceConfigs = ConcurrentHashMap<String, DeviceConfig>()

    val isConnected: Boolean get() = client?.isConnected == true

    fun connect() {
        if (prefs.mqttHost.isBlank()) return
        activeRoom = prefs.room
        try {
            val scheme = if (prefs.mqttTls) "ssl" else "tcp"
            val serverUri = "$scheme://${prefs.mqttHost}:${prefs.mqttPort}"
            val clientId = "espresense-android-$room-${System.currentTimeMillis() % 100000}"

            val c = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
            client = c

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 15
                keepAliveInterval = 30
                if (prefs.mqttUser.isNotBlank()) userName = prefs.mqttUser
                if (prefs.mqttPass.isNotBlank()) password = prefs.mqttPass.toCharArray()
                setWill("espresense/rooms/$room/status", "offline".toByteArray(), 1, true)
            }

            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "MQTT connected (reconnect=$reconnect) to $serverURI")
                    onConnected()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost", cause)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleIncoming(topic, message)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            c.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT connect failed", exception)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "MQTT connect exception", e)
        }
    }

    private fun onConnected() {
        val c = client ?: return
        try {
            c.subscribe(
                arrayOf("espresense/rooms/$room/+/set", "espresense/rooms/*/+/set", "espresense/settings/+/config"),
                intArrayOf(1, 1, 1)
            )

            publishRetained("espresense/rooms/$room/status", "online")
            publishRetained("espresense/rooms/$room/name", room)
            publishRetained("espresense/rooms/$room/max_distance", prefs.maxDistance.toString())
            publishRetained("espresense/rooms/$room/ref_rssi", prefs.refRssi.toString())
            publishRetained("espresense/rooms/$room/absorption", prefs.absorption.toString())
            publishRetained("espresense/rooms/$room/rx_adj_rssi", prefs.rxAdjRssi.toString())

            if (prefs.discoveryEnabled) publishDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Post-connect publish failed", e)
        }
    }

    private fun publishDiscovery() {
        val configTopic = "homeassistant/binary_sensor/espresense_$room/config"
        val json = JSONObject().apply {
            put("name", "ESPresense $room")
            put("unique_id", "espresense_${room}_status")
            put("state_topic", "espresense/rooms/$room/status")
            put("payload_on", "online")
            put("payload_off", "offline")
            put("device_class", "connectivity")
        }
        publishRetained(configTopic, json.toString())
    }

    private fun handleIncoming(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return
        val parts = topic.split("/")
        // espresense/rooms/<room-or-*>/<setting>/set
        if (parts.size == 5 && parts[0] == "espresense" && parts[1] == "rooms" && parts[4] == "set") {
            val targetRoom = parts[2]
            if (targetRoom != room && targetRoom != "*") return
            val setting = parts[3]
            val value = String(message.payload)
            onSettingChanged(setting, value)
            publishRetained("espresense/rooms/$room/$setting", value)
            return
        }
        // espresense/settings/<fingerprint>/config - enrolled device id/name mapping (retained by companion/nodes)
        if (parts.size == 4 && parts[0] == "espresense" && parts[1] == "settings" && parts[3] == "config") {
            val fingerprint = parts[2]
            val payload = String(message.payload)
            if (payload.isBlank()) {
                deviceConfigs.remove(fingerprint)
                return
            }
            try {
                val obj = JSONObject(payload)
                val id = obj.optString("id").ifBlank { fingerprint }
                val name = obj.optString("name").ifBlank { null }
                val calRssi = if (obj.has("rssi@1m") && !obj.isNull("rssi@1m")) obj.optInt("rssi@1m") else null
                deviceConfigs[fingerprint] = DeviceConfig(id, name, calRssi)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid device config for $fingerprint: $payload", e)
            }
        }
    }

    /** Looks up an enrolled id/name mapping for a raw fingerprint (e.g. "mac:aa:bb:..."), if any. */
    fun resolveDevice(rawId: String): DeviceConfig? = deviceConfigs[rawId]

    /** Snapshot of every enrolled device mapping learned from the broker so far (for diagnostics/UI). */
    fun allDeviceConfigs(): Map<String, DeviceConfig> = deviceConfigs.toMap()

    /**
     * Publishes (or updates) a device's calibration/enrollment mapping to the shared
     * "espresense/settings/<fingerprint>/config" topic (retained), so it takes effect
     * on this node immediately and is picked up by every other ESPresense node too -
     * the same "rssi@1m" convention as real ESPresense firmware's DeviceConfig.calRssi.
     */
    fun publishDeviceConfig(fingerprint: String, id: String?, name: String?, calRssi: Int?) {
        val json = JSONObject().apply {
            if (!id.isNullOrBlank()) put("id", id)
            if (!name.isNullOrBlank()) put("name", name)
            if (calRssi != null) put("rssi@1m", calRssi)
        }
        publishRetained("espresense/settings/$fingerprint/config", json.toString())
    }

    fun publishDevice(beacon: DetectedBeacon, distance: Double, rxAdjRssi: Int = 0, refRssi: Int? = null) {
        val c = client ?: return
        if (!c.isConnected) return
        val config = deviceConfigs[beacon.id]
        val effectiveId = config?.id ?: beacon.id
        val effectiveName = config?.name ?: beacon.name
        val json = JSONObject().apply {
            put("id", effectiveId)
            // Round like the firmware does; publishing full double precision
            // produces 17-digit distances that differ in shape from every other node.
            put("distance", Math.round(distance * 100.0) / 100.0)
            // ESPresense's ESP32 firmware reports the *adjusted* RSSI here, so that
            // distance stays reproducible from the payload; "rxAdj" exposes the offset.
            // The firmware subtracts rx_adj_rssi - keep the same sign convention.
            put("rssi", beacon.rssi - rxAdjRssi)
            put("rxAdj", rxAdjRssi)
            // The reference actually used for the distance, so the payload is
            // self-consistent even when a device config overrides it.
            if (refRssi != null) put("rssi@1m", refRssi)
            // ESP32 and Pi nodes publish bare lowercase hex. Publishing the
            // colon-separated uppercase form would look like a different device.
            put("mac", beacon.mac.replace(":", "").lowercase())
            if (effectiveName != null) put("name", effectiveName)
        }
        try {
            val msg = MqttMessage(json.toString().toByteArray()).apply { qos = 0; isRetained = false }
            c.publish("espresense/devices/$effectiveId/$room", msg)
        } catch (e: Exception) {
            Log.w(TAG, "publishDevice failed", e)
        }
    }

    fun publishTelemetry(json: JSONObject) {
        val c = client ?: return
        if (!c.isConnected) return
        try {
            val msg = MqttMessage(json.toString().toByteArray()).apply { qos = 0; isRetained = false }
            c.publish("espresense/rooms/$room/telemetry", msg)
        } catch (e: Exception) {
            Log.w(TAG, "publishTelemetry failed", e)
        }
    }

    private fun publishRetained(topic: String, value: String) {
        val c = client ?: return
        try {
            val msg = MqttMessage(value.toByteArray()).apply { qos = 1; isRetained = true }
            c.publish(topic, msg)
        } catch (e: Exception) {
            Log.w(TAG, "publishRetained failed for $topic", e)
        }
    }

    /**
     * Disconnects, announcing "offline" for [activeRoom] first.
     *
     * Pass [clearRetained] when this room is going away for good (a rename): the
     * retained settings and the Home Assistant discovery config outlive the
     * connection, so without an explicit wipe the old room keeps showing up in
     * the companion and in Home Assistant with no node publishing to it.
     */
    fun disconnect(clearRetained: Boolean = false) {
        val c = client
        client = null
        if (c == null) return
        try {
            if (!c.isConnected) {
                c.close()
                return
            }
            val msg = MqttMessage("offline".toByteArray()).apply { qos = 1; isRetained = true }
            c.publish("espresense/rooms/$activeRoom/status", msg)
            if (clearRetained) clearRoom(c, activeRoom)
            // close() has to wait for the DISCONNECT to complete: closing straight
            // away can cut off the retained writes above before they reach the
            // broker, which is exactly the ghost room this is meant to prevent.
            c.disconnect(null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    runCatching { c.close() }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "disconnect failed", exception)
                    runCatching { c.close() }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error", e)
        }
    }

    /** Deletes every retained topic [onConnected] created for [slug]. */
    private fun clearRoom(c: IMqttAsyncClient, slug: String) {
        val topics = RETAINED_ROOM_KEYS.map { "espresense/rooms/$slug/$it" } +
            "homeassistant/binary_sensor/espresense_$slug/config"
        for (topic in topics) {
            try {
                // A zero-length retained payload is how MQTT deletes a retained message.
                c.publish(topic, MqttMessage(ByteArray(0)).apply { qos = 1; isRetained = true })
            } catch (e: Exception) {
                Log.w(TAG, "clearRoom failed for $topic", e)
            }
        }
        Log.i(TAG, "Cleared retained topics for old room $slug")
    }

    companion object {
        private const val TAG = "MqttPublisher"

        /** Every retained room topic published by [onConnected], so a rename can clear them all. */
        private val RETAINED_ROOM_KEYS = listOf(
            "status", "name", "max_distance", "ref_rssi", "absorption", "rx_adj_rssi"
        )
    }
}
