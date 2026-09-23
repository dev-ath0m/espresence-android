package dev.espresense.node

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* user can retry via the button; no extra handling needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        prefs = prefs,
                        onRequestPermissions = { requestRuntimePermissions() },
                        onRequestBatteryExemption = { requestBatteryOptimizationExemption() },
                        onStart = {
                            prefs.serviceEnabled = true
                            ScannerService.start(this)
                        },
                        onSettingsSaved = { ScannerService.settingsChanged(this) },
                        onStop = {
                            prefs.serviceEnabled = false
                            ScannerService.stop(this)
                        },
                        onRequestInstallPermission = { requestInstallPermission() },
                        onOpenWebUi = { openWebUi() }
                    )
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Required on every API level: without a granted location permission, Android 12+
        // filters iBeacon/Eddystone advertisements out of scan results entirely.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun requestRuntimePermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun openWebUi() {
        val ip = NetUtils.getLocalIpAddress()
        if (ip == null) {
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip:${ScannerService.WEB_PORT}"))
        startActivity(intent)
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    /**
     * Opens the system page where the user allows this app to install packages.
     * There is no runtime-permission dialog for this one; it is a settings toggle.
     */
    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    onRequestPermissions: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onStart: () -> Unit,
    onSettingsSaved: () -> Unit,
    onStop: () -> Unit,
    onRequestInstallPermission: () -> Unit,
    onOpenWebUi: () -> Unit
) {
    val localIp = remember { NetUtils.getLocalIpAddress() }
    var host by remember { mutableStateOf(prefs.mqttHost) }
    var port by remember { mutableStateOf(prefs.mqttPort.toString()) }
    var user by remember { mutableStateOf(prefs.mqttUser) }
    var pass by remember { mutableStateOf(prefs.mqttPass) }
    var tls by remember { mutableStateOf(prefs.mqttTls) }
    var room by remember { mutableStateOf(prefs.room) }
    var refRssi by remember { mutableStateOf(prefs.refRssi.toString()) }
    var absorption by remember { mutableStateOf(prefs.absorption.toString()) }
    var rxAdjRssi by remember { mutableStateOf(prefs.rxAdjRssi.toString()) }
    var maxDistance by remember { mutableStateOf(prefs.maxDistance.toString()) }
    var includeGeneric by remember { mutableStateOf(prefs.includeGenericDevices) }
    var discovery by remember { mutableStateOf(prefs.discoveryEnabled) }

    // Reflect whether the service is genuinely alive, not merely enabled. Polling
    // also catches the service dying on its own while this screen is open.
    var running by remember { mutableStateOf(ScannerService.isRunning) }
    var autoStart by remember { mutableStateOf(prefs.serviceEnabled) }
    LaunchedEffect(Unit) {
        while (true) {
            running = ScannerService.isRunning
            autoStart = prefs.serviceEnabled
            delay(1000)
        }
    }

    // ---- self-update ----
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var beta by remember { mutableStateOf(prefs.updateChannel == UpdateChannel.PRERELEASE) }
    var autoCheck by remember { mutableStateOf(prefs.autoUpdateCheck) }
    var available by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateStatus by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var canInstall by remember { mutableStateOf(UpdateManager.canRequestInstall(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            // The "install unknown apps" toggle is granted in system settings, so the
            // only way to notice it flipped is to look again when we come back.
            canInstall = UpdateManager.canRequestInstall(context)
            InstallReceiver.lastResult?.let {
                updateStatus = it
                InstallReceiver.lastResult = null
            }
            delay(1000)
        }
    }

    // Checking on open means a published release shows up by just walking to the
    // tablet, instead of only after someone remembers to press the button.
    fun checkForUpdate(manual: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            if (manual) updateStatus = "Checking…"
            available = null
            try {
                val release = withContext(Dispatchers.IO) {
                    UpdateManager.fetchLatest(prefs.updateChannel)
                }
                prefs.lastUpdateCheckMs = System.currentTimeMillis()
                updateStatus = when {
                    release == null ->
                        if (manual) "No release published on this channel yet." else ""
                    !UpdateManager.isNewer(release.version) ->
                        if (manual) "Up to date (latest is ${release.version})." else ""
                    else -> {
                        available = release
                        "Version ${release.version} is available" +
                            if (release.isPrerelease) " (pre-release)." else "."
                    }
                }
            } catch (e: Exception) {
                // A silent background check must not nag about a flaky network.
                if (manual) updateStatus = "Check failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (prefs.autoUpdateCheck) checkForUpdate(manual = false)
    }

    fun save() {
        prefs.mqttHost = host.trim()
        prefs.mqttPort = port.toIntOrNull() ?: 1883
        prefs.mqttUser = user.trim()
        prefs.mqttPass = pass
        prefs.mqttTls = tls
        prefs.room = Prefs.sanitizeRoom(room)
        prefs.refRssi = refRssi.toIntOrNull() ?: -65
        prefs.absorption = absorption.toFloatOrNull() ?: 2.7f
        prefs.rxAdjRssi = rxAdjRssi.toIntOrNull() ?: 0
        prefs.maxDistance = maxDistance.toFloatOrNull() ?: 16.0f
        prefs.includeGenericDevices = includeGeneric
        prefs.discoveryEnabled = discovery
        // Prefs alone do not reach a running service; tell it to reconnect so a
        // changed broker or room takes effect (and the old room gets cleaned up).
        onSettingsSaved()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ESPresense Node", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Scans BLE / iBeacon advertisements and publishes them to your MQTT " +
                "broker using ESPresense's topic layout, so this device behaves like " +
                "an additional ESPresense room node.",
            style = MaterialTheme.typography.bodySmall
        )

        Divider()
        Text("MQTT Broker", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = port, onValueChange = { port = it }, label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, label = { Text("Password (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = tls, onCheckedChange = { tls = it })
            Spacer(Modifier.width(8.dp))
            Text("Use TLS (mqtts)")
        }

        Divider()
        Text("Room / Node", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("Room name") }, modifier = Modifier.fillMaxWidth())

        Divider()
        Text("Distance calibration", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = refRssi, onValueChange = { refRssi = it },
            label = { Text("ref_rssi (fallback RSSI @ 1m)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = absorption, onValueChange = { absorption = it },
            label = { Text("absorption (path-loss exponent)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = rxAdjRssi, onValueChange = { rxAdjRssi = it },
            label = { Text("rx_adj_rssi (dB correction for this receiver)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = maxDistance, onValueChange = { maxDistance = it },
            label = { Text("max_distance (m)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = includeGeneric, onCheckedChange = { includeGeneric = it })
            Spacer(Modifier.width(8.dp))
            Text("Also publish non-iBeacon BLE devices")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = discovery, onCheckedChange = { discovery = it })
            Spacer(Modifier.width(8.dp))
            Text("Home Assistant MQTT discovery")
        }

        Divider()
        Text("Permissions & battery", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("Grant Bluetooth / notification permissions")
        }
        Button(onClick = onRequestBatteryExemption, modifier = Modifier.fillMaxWidth()) {
            Text("Exempt from battery optimization")
        }

        Divider()
        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
            Text("Save settings")
        }

        Button(
            onClick = { save(); onStart(); running = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running
        ) { Text("Start node (runs 24/7)") }

        Button(
            onClick = { onStop(); running = false },
            modifier = Modifier.fillMaxWidth(),
            // Stay tappable when the service is dead but still flagged to auto-start,
            // so the user can clear that flag (and retry a stop) without reinstalling.
            enabled = running || autoStart
        ) { Text("Stop node") }

        Text(
            when {
                running && autoStart -> "Status: running. Will auto-start on boot."
                running -> "Status: running (auto-start on boot is off)."
                autoStart -> "Status: NOT running, but enabled — the service was killed " +
                    "(reinstall, force-stop or the system). Tap Start."
                else -> "Status: stopped."
            },
            style = MaterialTheme.typography.bodySmall
        )

        Divider()
        Text("Local web UI", style = MaterialTheme.typography.titleMedium)
        Text(
            if (localIp != null) "http://$localIp:${ScannerService.WEB_PORT} (only reachable while the node is running)"
            else "Could not determine this device's local IP (connect to Wi-Fi first).",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = onOpenWebUi, modifier = Modifier.fillMaxWidth(), enabled = running && localIp != null) {
            Text("Open web UI in browser")
        }

        Divider()
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text(
            "Installed: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = beta,
                onCheckedChange = {
                    beta = it
                    prefs.updateChannel = if (it) UpdateChannel.PRERELEASE else UpdateChannel.STABLE
                    available = null
                    updateStatus = "Channel set to ${if (it) "pre-release" else "stable"}."
                }
            )
            Spacer(Modifier.width(8.dp))
            Text("Install pre-releases (beta)")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = autoCheck,
                onCheckedChange = { autoCheck = it; prefs.autoUpdateCheck = it }
            )
            Spacer(Modifier.width(8.dp))
            Text("Check for updates daily")
        }

        Button(
            onClick = { checkForUpdate(manual = true) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        ) { Text("Check for updates") }

        available?.let { release ->
            if (!canInstall) {
                Text(
                    "Android needs permission to let this app install updates.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onRequestInstallPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow installing updates")
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val apk = withContext(Dispatchers.IO) {
                                UpdateManager.download(context, release) { percent ->
                                    updateStatus = "Downloading… $percent%"
                                }
                            }
                            updateStatus = "Downloaded. Confirm the install prompt."
                            withContext(Dispatchers.IO) { UpdateManager.install(context, apk) }
                        } catch (e: Exception) {
                            updateStatus = "Update failed: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && canInstall
            ) { Text("Download & install ${release.version}") }
        }

        if (updateStatus.isNotBlank()) {
            Text(updateStatus, style = MaterialTheme.typography.bodySmall)
        }
    }
}
