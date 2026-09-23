package dev.espresense.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the outcome of a [UpdateManager.install] session.
 *
 * The interesting case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the
 * system will not install anything until the user confirms, and it hands back
 * the intent that shows that prompt.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    // Launched from a receiver, so it needs its own task.
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    lastResult = "Update needs confirmation, but the system sent no prompt"
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // BootReceiver handles MY_PACKAGE_REPLACED, so the node restarts itself.
                lastResult = "Update installed"
                Log.i(TAG, "Update installed")
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                lastResult = "Update cancelled"
            }

            else -> {
                lastResult = "Install failed: ${message ?: "status $status"}"
                Log.w(TAG, "Install failed with status $status: $message")
            }
        }
    }

    companion object {
        private const val TAG = "InstallReceiver"
        const val ACTION_INSTALL_STATUS = "dev.espresense.node.INSTALL_STATUS"

        /**
         * Last install outcome, for the settings screen to display. Held in the
         * process rather than prefs because it is only meaningful while the app
         * that started the install is still around.
         */
        @Volatile
        var lastResult: String? = null
    }
}
