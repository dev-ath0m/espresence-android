package dev.espresense.node

import android.util.Log
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
 */
class MqttPublisher(
    private val prefs: Prefs,
    private val onSettingChanged: (setting: String, value: String) -> Unit
) {
    private var client: IMqttAsyncClient? = null
    private val room get() = prefs.room

    val isConnected: Boolean get() = client?.isConnected == true

    fun connect() {
        if (prefs.mqttHost.isBlank()) return
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
            c.subscribe(arrayOf("espresense/rooms/$room/+/set", "espresense/rooms/*/+/set"), intArrayOf(1, 1))

            publishRetained("espresense/rooms/$room/status", "online")
            publishRetained("espresense/rooms/$room/name", room)
            publishRetained("espresense/rooms/$room/max_distance", prefs.maxDistance.toString())
            publishRetained("espresense/rooms/$room/ref_rssi", prefs.refRssi.toString())
            publishRetained("espresense/rooms/$room/absorption", prefs.absorption.toString())

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
        // espresense/rooms/<room-or-*>/<setting>/set
        val parts = topic.split("/")
        if (parts.size == 5 && parts[0] == "espresense" && parts[1] == "rooms" && parts[4] == "set") {
            val targetRoom = parts[2]
            if (targetRoom != room && targetRoom != "*") return
            val setting = parts[3]
            val value = String(message.payload)
            onSettingChanged(setting, value)
            publishRetained("espresense/rooms/$room/$setting", value)
        }
    }

    fun publishDevice(beacon: DetectedBeacon, distance: Double) {
        val c = client ?: return
        if (!c.isConnected) return
        val json = JSONObject().apply {
            put("id", beacon.id)
            put("distance", distance)
            put("rssi", beacon.rssi)
            put("mac", beacon.mac)
            if (beacon.name != null) put("name", beacon.name)
        }
        try {
            val msg = MqttMessage(json.toString().toByteArray()).apply { qos = 0; isRetained = false }
            c.publish("espresense/devices/${beacon.id}/$room", msg)
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

    fun disconnect() {
        try {
            client?.let { c ->
                if (c.isConnected) {
                    val msg = MqttMessage("offline".toByteArray()).apply { qos = 1; isRetained = true }
                    c.publish("espresense/rooms/$room/status", msg)
                    c.disconnect()
                }
                c.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error", e)
        } finally {
            client = null
        }
    }

    companion object {
        private const val TAG = "MqttPublisher"
    }
}
