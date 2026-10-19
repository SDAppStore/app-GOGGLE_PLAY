package com.super_diamond.goggle_play

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.NotificationManager
import android.widget.Toast

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appName = intent.getStringExtra(PackageInstallManager.EXTRA_APP_NAME) ?: "App"
        val packageName = intent.getStringExtra(PackageInstallManager.EXTRA_PACKAGE_NAME).orEmpty()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                publishResult(context, packageName, appName, DownloadProgress.Stage.FINISHED, "$appName installed")
                Toast.makeText(context, "$appName installed", Toast.LENGTH_LONG).show()
            }
            else -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked by Android"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "This app conflicts with an installed package"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This app is not compatible with the current device"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install this app"
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed"
                }
                publishResult(context, packageName, appName, DownloadProgress.Stage.FAILED, message)
                Toast.makeText(context, "$appName: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun publishResult(
        context: Context,
        packageName: String,
        appName: String,
        stage: DownloadProgress.Stage,
        message: String
    ) {
        if (packageName.isBlank()) return
        val store = DownloadProgressStore(context)
        val previous = store.load(packageName)
        val progress = (previous ?: DownloadProgress(packageName, appName, stage)).copy(
            stage = stage,
            message = message,
            updatedAt = System.currentTimeMillis()
        )
        store.save(progress)
        context.sendBroadcast(DownloadProgressContract.intent(progress).setPackage(context.packageName))
        context.getSystemService(NotificationManager::class.java)
            .cancel(DownloadInstallService.notificationId(packageName))
    }
}
