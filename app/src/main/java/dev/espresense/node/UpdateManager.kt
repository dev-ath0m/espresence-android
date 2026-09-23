package dev.espresense.node

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

/** Which GitHub releases this node is willing to install. */
enum class UpdateChannel {
    /** Only releases GitHub marks as "latest": published, not a draft, not a pre-release. */
    STABLE,

    /** The newest release of any kind, pre-releases included. */
    PRERELEASE;

    companion object {
        fun fromKey(key: String?): UpdateChannel =
            if (key == PRERELEASE.name) PRERELEASE else STABLE
    }
}

/** A release that can be installed over the running build. */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val isPrerelease: Boolean,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val notes: String
)

/**
 * Self-update from the project's GitHub releases.
 *
 * The node runs on a wall-mounted tablet, so reaching it over adb means walking
 * over and re-enabling wireless debugging every time - the pairing port rotates
 * and the toggle switches itself off. Pulling signed APKs from the release feed
 * instead keeps the tablet updatable without any of that.
 *
 * Android still shows its own install confirmation, which cannot be bypassed
 * without device-owner provisioning, so an update ends in one tap on the tablet.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val REPO = "dev-ath0m/espresence-android"
    private const val API = "https://api.github.com/repos/$REPO"

    /**
     * GitHub allows 60 unauthenticated API calls per hour *per IP*, shared with
     * everything else on the network, so checks stay manual or daily - never a poll.
     */
    const val MIN_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Queries the release feed. Returns null if the channel has no usable release. */
    @Throws(IOException::class)
    fun fetchLatest(channel: UpdateChannel): ReleaseInfo? {
        val json = when (channel) {
            // "latest" is GitHub's own definition of stable: it skips drafts and pre-releases.
            UpdateChannel.STABLE -> JSONObject(httpGet("$API/releases/latest"))
            UpdateChannel.PRERELEASE -> {
                val all = JSONArray(httpGet("$API/releases?per_page=10"))
                (0 until all.length())
                    .map { all.getJSONObject(it) }
                    .firstOrNull { !it.optBoolean("draft", false) }
                    ?: return null
            }
        }
        return parseRelease(json)
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name").ifBlank { return null }
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return null
        return ReleaseInfo(
            tag = tag,
            version = tag.removePrefix("v"),
            isPrerelease = json.optBoolean("prerelease", false),
            apkUrl = apk.optString("browser_download_url"),
            apkName = apk.optString("name"),
            sizeBytes = apk.optLong("size"),
            notes = json.optString("body").take(2000)
        )
    }

    /** True when [candidate] is a higher version than what is installed right now. */
    fun isNewer(candidate: String, installed: String = BuildConfig.VERSION_NAME): Boolean =
        compareVersions(candidate, installed) > 0

    /**
     * Semver-ish comparison: numeric parts left to right, and a build carrying a
     * pre-release suffix ranks below the same version without one, so 0.2.0 wins
     * over 0.2.0-dev.7.
     */
    fun compareVersions(a: String, b: String): Int {
        fun core(v: String) = v.substringBefore('-')
        fun suffix(v: String) = v.substringAfter('-', "")

        val pa = core(a).split('.')
        val pb = core(b).split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val na = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val nb = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (na != nb) return na.compareTo(nb)
        }
        val sa = suffix(a)
        val sb = suffix(b)
        return when {
            sa == sb -> 0
            sa.isEmpty() -> 1
            sb.isEmpty() -> -1
            else -> sa.compareTo(sb)
        }
    }

    /**
     * Downloads [release] into the cache directory, reporting 0..100 progress.
     * The file is only returned once it is complete and correctly signed.
     */
    @Throws(IOException::class)
    fun download(context: Context, release: ReleaseInfo, onProgress: (Int) -> Unit = {}): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Never leave a half-written APK from an earlier attempt lying around.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.apkName)

        val conn = open(URL(release.apkUrl))
        conn.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("Download failed: HTTP ${conn.responseCode}")
            }
            val total = if (release.sizeBytes > 0) release.sizeBytes else conn.contentLength.toLong()
            var written = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (total > 0 && written != total) {
                target.delete()
                throw IOException("Download truncated: $written of $total bytes")
            }
        } finally {
            conn.disconnect()
        }

        val signer = signerDigest(context, target)
        val installed = installedSignerDigest(context)
        // Android would reject a mismatched signature at install time anyway, but
        // checking first means a tampered or debug-signed APK never reaches the
        // installer and the failure is reported in terms of the signing key.
        if (signer == null || installed == null || !signer.equals(installed, ignoreCase = true)) {
            target.delete()
            throw IOException(
                "Signature check failed - the download is not signed with this app's release key"
            )
        }
        Log.i(TAG, "Downloaded ${target.name} (${target.length()} bytes), signer verified")
        return target
    }

    /**
     * Hands [apk] to the system installer. Android prompts the user on the tablet;
     * [InstallReceiver] surfaces that prompt and the final result.
     */
    @Throws(IOException::class)
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, InstallReceiver::class.java)
                .setAction(InstallReceiver.ACTION_INSTALL_STATUS)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The installer fills in EXTRA_STATUS and friends, so the intent
                // has to stay mutable.
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /** True once the user has allowed this app to install packages. */
    fun canRequestInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    // ---- signature helpers ----

    private fun signerDigest(context: Context, apk: File): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
        return digestOf(info)
    }

    private fun installedSignerDigest(context: Context): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return digestOf(info)
    }

    private fun digestOf(info: android.content.pm.PackageInfo): String? {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return null
        val first = signatures.firstOrNull() ?: return null
        val sha = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        return sha.joinToString("") { "%02x".format(it) }
    }

    // ---- http ----

    private fun httpGet(url: String): String {
        val conn = open(URL(url))
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) throw IOException("No release found for this channel")
            if (conn.responseCode == 403) {
                throw IOException("GitHub rate limit reached - try again later")
            }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        if (conn !is HttpsURLConnection) {
            // Release assets are served over https; anything else means the URL was
            // rewritten in transit and the APK must not be trusted.
            conn.disconnect()
            throw IOException("Refusing a non-HTTPS update URL: $url")
        }
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "espresense-node/${BuildConfig.VERSION_NAME}")
        return conn
    }
}
