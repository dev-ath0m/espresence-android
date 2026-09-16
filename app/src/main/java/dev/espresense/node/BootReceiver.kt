package dev.espresense.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Auto-starts the scanner service after device boot or app update, if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.MY_PACKAGE_REPLACED" -> {
                val prefs = Prefs(context)
                if (prefs.serviceEnabled && prefs.isConfigured()) {
                    ScannerService.start(context)
                }
            }
        }
    }
}
