package dev.espresense.node

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/** A single currently-visible BLE/iBeacon device, as shown in the local web UI. */
data class LiveDeviceInfo(
    val id: String,
    val mac: String,
    val name: String?,
    val rssi: Int,
    val distance: Double,
    val ageSeconds: Long
)

/**
 * Minimal built-in HTTP server exposing a small configuration UI, similar in spirit
 * to ESPresense-Pi's web UI (Network / Settings / Devices).
 *
 * Note: Android does not allow regular (non-root) apps to bind privileged ports, so
 * this listens on [port] (default 8080) rather than port 80 - browse to
 * http://<device-ip>:8080 manually. ESPresense-companion's Nodes overview will show
 * the IP (published via telemetry) but its "visit" link may assume port 80 and not
 * reach this server directly.
 */
class ConfigWebServer(
    private val port: Int,
    private val prefs: Prefs,
    private val isMqttConnected: () -> Boolean,
    private val getDevices: () -> List<LiveDeviceInfo>,
    private val onSettingsSaved: (mqttSettingsChanged: Boolean) -> Unit
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "ConfigWebServer") {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (running) {
                    val socket = try {
                        ss.accept()
                    } catch (e: IOException) {
                        if (running) continue else break
                    }
                    thread(name = "ConfigWebServer-client") { handleClient(socket) }
                }
            } catch (e: IOException) {
                // Port already in use, or server was stopped; nothing more to do.
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val tokens = requestLine.split(" ")
                if (tokens.size < 2) return
                val method = tokens[0]
                val path = tokens[1].substringBefore("?")

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).equals("Content-Length", ignoreCase = true)) {
                        contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                    }
                }

                var body = ""
                if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = reader.read(buf, readTotal, contentLength - readTotal)
                        if (n < 0) break
                        readTotal += n
                    }
                    body = String(buf, 0, readTotal)
                }

                val output = s.getOutputStream()
                when {
                    method == "GET" && path == "/" -> respond(output, 200, "text/html; charset=utf-8", renderIndex())
                    method == "POST" && path == "/save" -> {
                        val restartNeeded = applySettings(parseForm(body))
                        onSettingsSaved(restartNeeded)
                        respondRedirect(output, "/")
                    }
                    method == "GET" && path == "/json" -> respond(output, 200, "application/json; charset=utf-8", renderJson())
                    else -> respond(output, 404, "text/plain; charset=utf-8", "Not found")
                }
            } catch (e: Exception) {
                // Client disconnected mid-request or sent a malformed request; nothing to do.
            }
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&")
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx < 0) null else
                    URLDecoder.decode(it.substring(0, idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            }.toMap()

    /** Writes form fields into [prefs]. Returns true if a broker/room field changed (needs MQTT reconnect). */
    private fun applySettings(params: Map<String, String>): Boolean {
        val oldHost = prefs.mqttHost
        val oldPort = prefs.mqttPort
        val oldUser = prefs.mqttUser
        val oldPass = prefs.mqttPass
        val oldTls = prefs.mqttTls
        val oldRoom = prefs.room

        params["mqtt_host"]?.let { prefs.mqttHost = it.trim() }
        params["mqtt_port"]?.toIntOrNull()?.let { prefs.mqttPort = it }
        params["mqtt_user"]?.let { prefs.mqttUser = it.trim() }
        params["mqtt_pass"]?.let { prefs.mqttPass = it }
        prefs.mqttTls = params.containsKey("mqtt_tls")
        params["room"]?.let { if (it.isNotBlank()) prefs.room = Prefs.sanitizeRoom(it) }
        params["ref_rssi"]?.toIntOrNull()?.let { prefs.refRssi = it }
        params["absorption"]?.toFloatOrNull()?.let { prefs.absorption = it }
        params["max_distance"]?.toFloatOrNull()?.let { prefs.maxDistance = it }
        params["skip_ms"]?.toLongOrNull()?.let { prefs.skipMs = it }
        params["skip_distance"]?.toFloatOrNull()?.let { prefs.skipDistance = it }
        prefs.includeGenericDevices = params.containsKey("include_generic")
        prefs.discoveryEnabled = params.containsKey("discovery")

        return oldHost != prefs.mqttHost || oldPort != prefs.mqttPort || oldUser != prefs.mqttUser ||
            oldPass != prefs.mqttPass || oldTls != prefs.mqttTls || oldRoom != prefs.room
    }

    private fun respond(output: OutputStream, status: Int, contentType: String, body: String) {
        val statusText = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun respondRedirect(output: OutputStream, location: String) {
        val header = "HTTP/1.1 303 See Other\r\n" +
            "Location: $location\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun esc(s: String?): String =
        (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun renderJson(): String {
        val devices = getDevices().joinToString(",") { d ->
            "{\"id\":\"${esc(d.id)}\",\"mac\":\"${esc(d.mac)}\"," +
                "\"name\":${if (d.name != null) "\"${esc(d.name)}\"" else "null"}," +
                "\"rssi\":${d.rssi},\"distance\":${d.distance},\"ageSeconds\":${d.ageSeconds}}"
        }
        return "{\"room\":\"${esc(prefs.room)}\",\"mqttConnected\":${isMqttConnected()},\"devices\":[$devices]}"
    }

    private fun renderIndex(): String {
        val connected = isMqttConnected()
        val statusColor = if (connected) "#2e7d32" else "#c62828"
        val statusText = if (connected) "connected" else "disconnected"
        val devices = getDevices().sortedBy { it.distance }
        val rows = if (devices.isEmpty()) {
            "<tr><td colspan=\"5\" style=\"text-align:center;color:#888\">No devices seen yet</td></tr>"
        } else {
            devices.joinToString("\n") { d ->
                "<tr><td>${esc(d.id)}</td><td>${esc(d.name ?: "")}</td><td>${"%.2f".format(d.distance)} m</td>" +
                    "<td>${d.rssi} dBm</td><td>${d.ageSeconds}s ago</td></tr>"
            }
        }

        return """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>ESPresense Node - ${esc(prefs.room)}</title>
<style>
body{font-family:sans-serif;max-width:720px;margin:0 auto;padding:16px;background:#fafafa;color:#222}
h1{font-size:1.4em} h2{font-size:1.1em;margin-top:2em}
.status{display:inline-block;padding:2px 10px;border-radius:12px;color:#fff;background:$statusColor}
label{display:block;margin-top:10px;font-size:0.85em;color:#555}
input[type=text],input[type=number],input[type=password]{width:100%;padding:8px;box-sizing:border-box;font-size:1em}
input[type=checkbox]{margin-right:6px}
table{width:100%;border-collapse:collapse;margin-top:8px}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #ddd;font-size:0.9em}
button{margin-top:16px;padding:10px 18px;font-size:1em;background:#1565C0;color:#fff;border:none;border-radius:4px}
fieldset{border:1px solid #ddd;border-radius:6px;margin-top:16px}
</style></head>
<body>
<h1>ESPresense Node</h1>
<p>Room <b>${esc(prefs.room)}</b> &middot; MQTT <span class="status">$statusText</span></p>

<form method="post" action="/save">
<fieldset><legend>MQTT broker</legend>
<label>Host<input type="text" name="mqtt_host" value="${esc(prefs.mqttHost)}"></label>
<label>Port<input type="number" name="mqtt_port" value="${prefs.mqttPort}"></label>
<label>Username<input type="text" name="mqtt_user" value="${esc(prefs.mqttUser)}"></label>
<label>Password<input type="password" name="mqtt_pass" value="${esc(prefs.mqttPass)}"></label>
<label><input type="checkbox" name="mqtt_tls" ${if (prefs.mqttTls) "checked" else ""}> Use TLS</label>
</fieldset>

<fieldset><legend>Room</legend>
<label>Room name (avoid "/" - it breaks MQTT topics)<input type="text" name="room" value="${esc(prefs.room)}"></label>
</fieldset>

<fieldset><legend>Calibration</legend>
<label>ref_rssi<input type="number" name="ref_rssi" value="${prefs.refRssi}"></label>
<label>absorption<input type="number" step="0.1" name="absorption" value="${prefs.absorption}"></label>
<label>max_distance (m)<input type="number" step="0.1" name="max_distance" value="${prefs.maxDistance}"></label>
<label>skip_ms<input type="number" name="skip_ms" value="${prefs.skipMs}"></label>
<label>skip_distance (m)<input type="number" step="0.1" name="skip_distance" value="${prefs.skipDistance}"></label>
<label><input type="checkbox" name="include_generic" ${if (prefs.includeGenericDevices) "checked" else ""}> Also publish non-iBeacon BLE devices</label>
<label><input type="checkbox" name="discovery" ${if (prefs.discoveryEnabled) "checked" else ""}> Home Assistant MQTT discovery</label>
</fieldset>

<button type="submit">Save</button>
</form>

<h2>Currently seen devices</h2>
<table>
<tr><th>Id</th><th>Name</th><th>Distance</th><th>RSSI</th><th>Last seen</th></tr>
$rows
</table>
<p style="margin-top:2em;color:#999;font-size:0.8em">ESPresense Node for Android &middot; <a href="/json">JSON status</a></p>
</body></html>
""".trimIndent()
    }
}
