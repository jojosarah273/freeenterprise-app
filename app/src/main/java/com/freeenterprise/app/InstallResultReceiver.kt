package com.freeenterprise.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Receives the package-installer result. The important case is
 * STATUS_PENDING_USER_ACTION: the system hands back an Intent that *shows the
 * Update dialog*, and nothing appears unless we start it.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        report("could not show the installer: ${e.message}")
                    }
                } else {
                    report("the installer returned no confirmation screen")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> report(null)

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                report(message ?: "install failed (status $status)")
            }
        }
    }

    private fun report(message: String?) {
        onResult?.invoke(message)
        if (message != null) {
            Toast.makeText(appContext, "Update failed: $message", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "install_session_id"

        /** Set by the activity; null means the install was accepted. */
        var onResult: ((String?) -> Unit)? = null

        private var appContext: Context? = null

        /** Remembered by the activity so the receiver can show a toast. */
        fun remember(context: Context) {
            appContext = context.applicationContext
        }
    }
}
