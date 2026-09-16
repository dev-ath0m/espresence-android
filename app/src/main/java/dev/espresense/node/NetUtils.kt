package dev.espresense.node

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Small networking helpers shared by the web UI and MQTT telemetry. */
object NetUtils {
    /** Best-effort local IPv4 address (Wi-Fi/Ethernet), skipping loopback and link-local addresses. */
    fun getLocalIpAddress(): String? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it != null && !it.startsWith("169.254.") }
        } catch (e: Exception) {
            null
        }
    }
}
