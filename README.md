# ESPresense Node (Android)

An [ESPresense](https://espresense.com/)-compatible BLE presence-detection node that
runs as a normal Android app, using your tablet's own Bluetooth radio instead of
ESP32 firmware — the Android equivalent of [espresense-rpi](https://github.com/dev-ath0m/espresence-rpi).

It scans BLE advertisements (prioritizing iBeacons), estimates distance from RSSI,
and publishes to your existing MQTT broker using the same MQTT topic shape as a
real ESPresense node, so it can be used as a drop-in additional "room" alongside
ESP32 nodes with Home Assistant's `mqtt_room` integration, [ESPresense-companion](https://github.com/ESPresense/ESPresense-companion), etc.

This is a community re-implementation guided by the public espresense.com docs and
the `espresense-rpi` project. It is not affiliated with the ESPresense project.

A GitHub Actions workflow ([.github/workflows/build.yml](.github/workflows/build.yml))
builds a debug APK on every push to `main` and uploads it as a workflow artifact —
see **CI/CD** below.

## Features

- BLE scanning via Android's native `BluetoothLeScanner`, running in a persistent
  foreground service (survives Doze/app-standby once battery-optimization is disabled).
- iBeacon parsing (UUID/major/minor + the beacon's own calibrated "measured power"),
  identified the same way ESPresense identifies iBeacons (`ibeacon:<uuid>-<major>-<minor>`).
- Optional publishing of non-iBeacon BLE devices, identified by MAC (`mac:<address>`).
- RSSI → distance using the same log-distance path-loss model ESPresense/ESPresense-Pi use.
- MQTT publishing compatible with ESPresense's topic shape (see below).
- Basic Home Assistant MQTT discovery (node online/offline `binary_sensor`).
- Auto-starts on boot (if you started the node at least once) and auto-restarts if
  Android kills the service.
- Small built-in local web configuration UI (see below) for editing settings and
  viewing currently-detected BLE devices from a browser.
- Telemetry includes `ip`/`ver`/`firm` fields so the node's IP address and app
  version show up in ESPresense-companion's Nodes overview.

## Requirements

- An Android tablet/phone with Bluetooth LE (Android 8.0 / API 26+).
- An existing MQTT broker reachable from the tablet's network.
- A JDK (17+) on your PATH. No Android Studio required — everything below is CLI-only.

## Building & sideloading (CLI only, no Android Studio)

The repo ships a real Gradle wrapper (`gradlew`/`gradlew.bat`), so once a local
Android SDK is available, building is a single command.

1. **One-time SDK setup.** Run the bootstrap script, which downloads the Android
   command-line tools into `.cli-tools/android-sdk` (gitignored), accepts the SDK
   licenses, installs `platform-tools`, `platforms;android-34`, and
   `build-tools;34.0.0`, and writes `local.properties`:
   ```powershell
   .\scripts\setup-android-sdk.ps1
   ```
2. **Build the APK:**
   ```powershell
   .\gradlew.bat assembleDebug
   ```
   The Gradle wrapper downloads Gradle itself on first run. Output APK:
   `app\build\outputs\apk\debug\app-debug.apk`.
3. **Sideload via ADB** (enable Developer Options ▸ USB debugging on the tablet, connect via USB):
   ```powershell
   .cli-tools\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
   ```
   Alternatively, enable **Install unknown apps** for whatever app you use to
   transfer the APK (Settings ▸ Apps ▸ Special access ▸ Install unknown apps) and
   copy/open the APK directly on the tablet.

   > If `assembleDebug` fails with `jlink executable ... does not exist`, your
   > system Java install is a JRE without `jlink` (a full JDK is required). Either
   > install a full JDK 17 and put it on `PATH`/`JAVA_HOME`, or point Gradle at one
   > via `org.gradle.java.home=<path>` in your **user-level**
   > `%USERPROFILE%\.gradle\gradle.properties` (not the project's `gradle.properties`,
   > which stays machine-independent so CI and other developers aren't affected).
4. Launch **ESPresense Node**, fill in:
   - MQTT host/port/username/password
   - Room name (this becomes the ESPresense "room")
   - Calibration values (defaults match ESPresense's own defaults)
5. Tap **Grant Bluetooth / notification permissions**, then **Exempt from battery
   optimization** (important for reliable 24/7 operation), then **Start node**.

The app keeps running via a foreground service (persistent notification) and will
automatically restart after a reboot.

## MQTT topic compatibility

```
espresense/rooms/<room>/status              online / offline (retained, LWT)
espresense/rooms/<room>/name                room display name (retained)
espresense/rooms/<room>/telemetry           JSON uptime/battery (non-retained)
espresense/rooms/<room>/<setting>           current value, e.g. max_distance (retained)
espresense/rooms/<room>/<setting>/set       write a setting live
espresense/rooms/*/<setting>/set            fleet-wide write (also honored)
espresense/devices/<id>/<room>              per-device JSON: id, distance, rssi, mac, name
homeassistant/binary_sensor/espresense_<room>/config   HA discovery (retained)
```

Example:

```
mosquitto_pub -h 192.168.1.10 -t "espresense/rooms/tablet/max_distance/set" -m "10.0"
mosquitto_sub -h 192.168.1.10 -v -t "espresense/devices/#"
```

Supported settings via `/set`: `max_distance`, `ref_rssi`, `absorption`, `skip_ms`, `skip_distance`.

Telemetry (`espresense/rooms/<room>/telemetry`) includes `uptime`, `battery`, `ip`,
`ver` (app version), and `firm` (`"android"`) — the same field names espresense-rpi
and ESP32 firmware use, so companion's Nodes overview picks up the IP/version columns.

## Local web configuration UI

While the node is running, it also serves a small configuration page on the tablet's
own IP address:

```
http://<device-ip>:8080
```

The device IP is shown in the app under **Local web UI**, with an **Open web UI**
button. The page lets you edit MQTT/room/calibration settings (saved immediately,
reconnecting MQTT if the broker/room changed) and shows a live table of currently
detected BLE devices with their distance/RSSI, useful for calibration. A `/json`
endpoint is also available for scripting.

> **Note:** Android does not allow regular apps to bind privileged port 80 without
> root, so this runs on port **8080** instead. ESPresense-companion's Nodes overview
> will show the node's IP (via the `ip` telemetry field), but its "visit" link may
> assume port 80 and therefore might not reach this server directly — browse to
> `http://<device-ip>:8080` manually instead.

## Configuration reference

| Setting | Default | Meaning |
|---|---|---|
| `ref_rssi` | -65 | Fallback RSSI @ 1m for non-iBeacon devices (iBeacons use their own broadcast measured-power byte) |
| `absorption` | 2.7 | Path-loss / environmental exponent |
| `max_distance` | 16.0 | Meters; farther reports are dropped |
| `skip_ms` / `skip_distance` | 5000 / 0.5 | Report-rate limiting: republish only if this much time passed or the distance moved this much |

See [espresense.com/configuration](https://espresense.com/configuration/settings/) for the semantics of each field (this project reuses the same names).

## CI/CD

[.github/workflows/build.yml](.github/workflows/build.yml) runs on every push to
`main` (and can be triggered manually via **Actions ▸ Build APK ▸ Run workflow**):
it checks out the repo, sets up JDK 17 and the Android SDK (`platform-tools`,
`platforms;android-34`, `build-tools;34.0.0`), runs `./gradlew assembleDebug`, and
uploads `app-debug.apk` as a workflow artifact you can download from the run's
summary page. It only builds a debug (unsigned) APK — there's no release signing
config set up yet.

## Differences from a real ESP32 node / espresense-rpi

- No active BLE/GATT querying (`query`/`requery_ms`, Mi Flora, etc.) — only advertisement data is used.
- No captive Wi-Fi portal; the local web UI (port 8080, see above) is simpler than
  ESP32 firmware's/espresense-rpi's Network/Settings/Devices pages and can't be
  reached via ESPresense-companion's "visit" link (port 80 assumption).
- Home Assistant discovery is minimal (node status only), not the full entity set the ESP32 firmware publishes.
- Non-iBeacon devices are identified only by MAC address (no `apple:` continuity-type fingerprinting).

If you need full protocol parity (especially for iOS device tracking or precise indoor
positioning), a real ESP32 node will track more precisely. This project is best suited
as an extra, no-extra-hardware presence sensor for one room using spare Android hardware.

## Project layout

```
app/src/main/java/dev/espresense/node/
  Prefs.kt                # SharedPreferences-backed settings store
  BeaconParser.kt          # BLE advertisement -> ESPresense-style id (iBeacon-aware)
  DistanceCalculator.kt    # RSSI -> distance (log-distance path-loss model)
  MqttPublisher.kt         # MQTT publish/subscribe, ESPresense topic shape
  ScannerService.kt        # Foreground service: BLE scan loop + telemetry + web server lifecycle
  ConfigWebServer.kt       # Embedded local web configuration UI (port 8080)
  NetUtils.kt              # Local IPv4 address detection
  BootReceiver.kt          # Auto-start after boot / app update
  MainActivity.kt          # Compose settings UI + start/stop controls + web UI launcher
scripts/setup-android-sdk.ps1  # One-time CLI Android SDK bootstrap (no Android Studio)
```
