package dev.espresense.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that continuously scans for BLE/iBeacon advertisements and
 * publishes them to MQTT using ESPresense's topic layout, turning this device into
 * an ESPresense-compatible "room" node. Also runs a small local web UI (see
 * [ConfigWebServer]) for configuration and live device visibility.
 */
class ScannerService : Service() {

    private lateinit var prefs: Prefs
    private var mqtt: MqttPublisher? = null
    private var webServer: ConfigWebServer? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastPublished = HashMap<String, Pair<Long, Double>>() // id -> (elapsedRealtimeMs, distance)
    private val liveDevices = ConcurrentHashMap<String, LiveDeviceState>() // id -> last-seen state, for the web UI

    private data class LiveDeviceState(val beacon: DetectedBeacon, val distance: Double, val lastSeenElapsed: Long)

    private val startTime = SystemClock.elapsedRealtime()
    private var scanning = false

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            publishTelemetry()
            mainHandler.postDelayed(this, TELEMETRY_INTERVAL_MS)
        }
    }

    private val restartScanRunnable = object : Runnable {
        override fun run() {
            restartScan()
            mainHandler.postDelayed(this, SCAN_RESTART_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createNotificationChannel()
        startForegroundWithNotification()
        acquireWakeLock()

        mqtt = MqttPublisher(prefs) { setting, value -> onRemoteSettingChanged(setting, value) }
        mqtt?.connect()

        webServer = ConfigWebServer(
            port = WEB_PORT,
            prefs = prefs,
            isMqttConnected = { mqtt?.isConnected == true },
            getDevices = { snapshotLiveDevices() },
            getKnownConfigs = { mqtt?.allDeviceConfigs() ?: emptyMap() },
            onSettingsSaved = { mqttSettingsChanged -> if (mqttSettingsChanged) reconnectMqtt() },
            publishDeviceConfig = { fingerprint, id, name, calRssi -> mqtt?.publishDeviceConfig(fingerprint, id, name, calRssi) }
        )
        webServer?.start()

        startScan()
        mainHandler.postDelayed(telemetryRunnable, TELEMETRY_INTERVAL_MS)
        mainHandler.postDelayed(restartScanRunnable, SCAN_RESTART_INTERVAL_MS)
    }

    private fun snapshotLiveDevices(): List<LiveDeviceInfo> {
        val now = SystemClock.elapsedRealtime()
        return liveDevices.values.map { st ->
            val config = mqtt?.resolveDevice(st.beacon.id)
            LiveDeviceInfo(
                id = config?.id ?: st.beacon.id,
                fingerprint = st.beacon.id,
                mac = st.beacon.mac,
                name = config?.name ?: st.beacon.name,
                calRssi = config?.calRssi,
                rssi = st.beacon.rssi,
                distance = st.distance,
                ageSeconds = (now - st.lastSeenElapsed) / 1000
            )
        }
    }

    private fun reconnectMqtt() {
        // Give the HTTP response time to flush before tearing down the old client.
        mainHandler.postDelayed({
            mqtt?.disconnect()
            mqtt = MqttPublisher(prefs) { setting, value -> onRemoteSettingChanged(setting, value) }
            mqtt?.connect()
        }, 300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopScan()
        mainHandler.removeCallbacks(telemetryRunnable)
        mainHandler.removeCallbacks(restartScanRunnable)
        webServer?.stop()
        mqtt?.disconnect()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- BLE scanning ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            scanning = false
            mainHandler.postDelayed({ startScan() }, 5000)
        }
    }

    private fun handleResult(result: ScanResult) {
        val parsed = try {
            BeaconParser.parse(result)
        } catch (e: SecurityException) {
            null
        } ?: return

        // If this isn't an iBeacon, check whether its (rotating) MAC resolves against a
        // known IRK enrollment learned via MQTT (e.g. a phone enrolled on another node) -
        // this lets us recognize the device by its stable enrolled id despite the MAC churn.
        val beacon = if (!parsed.isIBeacon) {
            val irkFingerprints = mqtt?.allDeviceConfigs()?.keys.orEmpty()
            val resolved = IrkResolver.tryResolve(parsed.mac, irkFingerprints)
            if (resolved != null) parsed.copy(id = resolved) else parsed
        } else {
            parsed
        }

        if (!beacon.isIBeacon && !prefs.includeGenericDevices && !beacon.id.startsWith("irk:")) return

        // A per-device rssi@1m calibration shared via MQTT config sync (from this node or any
        // other) takes priority over the iBeacon's own broadcast power or this node's local
        // fallback, matching real ESPresense's DeviceConfig.calRssi behavior.
        val calRssi = mqtt?.resolveDevice(beacon.id)?.calRssi
        val refRssi = calRssi ?: beacon.measuredPower ?: prefs.refRssi
        val rxAdj = prefs.rxAdjRssi
        val distance = DistanceCalculator.estimate(beacon.rssi, refRssi, prefs.absorption, rxAdj)
        if (distance < 0) return

        val now = SystemClock.elapsedRealtime()
        liveDevices[beacon.id] = LiveDeviceState(beacon, distance, now)

        if (distance > prefs.maxDistance) return

        val previous = lastPublished[beacon.id]
        val shouldPublish = previous == null ||
            (now - previous.first) >= prefs.skipMs ||
            kotlin.math.abs(distance - previous.second) >= prefs.skipDistance


        if (!shouldPublish) return

        lastPublished[beacon.id] = now to distance
        mqtt?.publishDevice(beacon, distance, rxAdj)
    }

    private fun startScan() {
        if (scanning) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available/enabled; retrying in 10s")
            mainHandler.postDelayed({ startScan() }, 10000)
            return
        }
        bluetoothLeScanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Passing a null filter list here silently returns zero scan results on some
        // Qualcomm Snapdragon Bluetooth stacks in SCAN_MODE_LOW_LATENCY (scan registers
        // fine, no errors, but onScanResult/onBatchScanResults never fire). An explicit,
        // permissive (empty-builder) ScanFilter matches everything but avoids that bug.
        val filters = listOf(ScanFilter.Builder().build())
        try {
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            scanning = true
            Log.i(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission", e)
        }
    }

    private fun stopScan() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // permission revoked mid-scan; nothing to do
        }
        scanning = false
    }

    private fun restartScan() {
        stopScan()
        startScan()
    }

    // ---- Telemetry ----

    private fun pruneLiveDevices() {
        val cutoff = SystemClock.elapsedRealtime() - LIVE_DEVICE_TIMEOUT_MS
        liveDevices.entries.removeIf { it.value.lastSeenElapsed < cutoff }
    }

    private fun publishTelemetry() {
        pruneLiveDevices()
        if (!prefs.telemetryEnabled) return
        val uptimeSec = (SystemClock.elapsedRealtime() - startTime) / 1000
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val json = JSONObject().apply {
            put("uptime", uptimeSec)
            put("battery", batteryPct)
            put("ver", BuildConfig.VERSION_NAME)
            put("firm", "android")
            NetUtils.getLocalIpAddress()?.let { put("ip", it) }
        }
        mqtt?.publishTelemetry(json)
    }

    // ---- Remote setting updates (espresense/rooms/<room>/<setting>/set) ----

    private fun onRemoteSettingChanged(setting: String, value: String) {
        try {
            when (setting) {
                "max_distance" -> prefs.maxDistance = value.toFloat()
                "ref_rssi" -> prefs.refRssi = value.toInt()
                "absorption" -> prefs.absorption = value.toFloat()
                "rx_adj_rssi" -> prefs.rxAdjRssi = value.toInt()
                "skip_ms" -> prefs.skipMs = value.toLong()
                "skip_distance" -> prefs.skipDistance = value.toFloat()
            }
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Invalid value for $setting: $value")
        }
    }

    // ---- Foreground notification ----

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Scanning for BLE beacons…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESPresense Node")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ESPresense Node Service", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the BLE beacon scanner running" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    // ---- Wake lock ----

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::scan").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // safety timeout, refreshed by periodic scan restarts
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "ScannerService"
        private const val CHANNEL_ID = "espresense_scanner"
        private const val NOTIFICATION_ID = 42
        private const val TELEMETRY_INTERVAL_MS = 60_000L
        private const val SCAN_RESTART_INTERVAL_MS = 20 * 60_000L
        private const val LIVE_DEVICE_TIMEOUT_MS = 120_000L
        const val WEB_PORT = 8080

        fun start(context: Context) {
            val intent = Intent(context, ScannerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScannerService::class.java))
        }
    }
}
