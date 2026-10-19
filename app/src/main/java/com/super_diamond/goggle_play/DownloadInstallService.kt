package com.super_diamond.goggle_play

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

class DownloadInstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifications: NotificationManager
    private lateinit var progressStore: DownloadProgressStore
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val lastNotificationAt = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        progressStore = DownloadProgressStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf(String::isNotBlank) ?: return START_NOT_STICKY
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { packageName }
        if (activeJobs[packageName]?.isActive == true) return START_NOT_STICKY

        when (action) {
            ACTION_DOWNLOAD -> startDownload(packageName, appName)
            ACTION_INSTALL -> installPrepared(packageName, appName)
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun startDownload(packageName: String, appName: String) {
        val preparing = DownloadProgress(packageName, appName, DownloadProgress.Stage.PREPARING, message = "Contacting Google Play…")
        ensureForeground(preparing)
        publish(preparing, force = true)
        activeJobs[packageName] = scope.launch {
            try {
                val client = GogglePlaySession.get(applicationContext)
                val app = client.details(packageName)
                val files = client.purchase(app)
                val sessionId = PackageInstallManager(applicationContext).prepare(app, files, ::publish)
                publish(
                    preparing.copy(
                        stage = DownloadProgress.Stage.READY_TO_INSTALL,
                        downloadedBytes = files.sumOf { it.size.coerceAtLeast(0) },
                        totalBytes = files.sumOf { it.size.coerceAtLeast(0) },
                        sessionId = sessionId,
                        message = "Tap to install"
                    ),
                    force = true
                )
            } catch (error: Throwable) {
                publish(preparing.copy(stage = DownloadProgress.Stage.FAILED, message = error.readableMessage()), force = true)
            } finally {
                activeJobs.remove(packageName)
                finishForegroundIfIdle()
            }
        }
    }

    private fun installPrepared(packageName: String, appName: String) {
        val saved = progressStore.load(packageName) ?: return
        if (saved.stage != DownloadProgress.Stage.READY_TO_INSTALL) return
        ensureForeground(saved)
        val installing = saved.copy(stage = DownloadProgress.Stage.INSTALLING, message = "Waiting for Android installation confirmation")
        publish(installing, force = true)
        activeJobs[packageName] = scope.launch {
            try {
                PackageInstallManager(applicationContext).commit(saved.sessionId, packageName, appName)
            } catch (error: Throwable) {
                publish(saved.copy(stage = DownloadProgress.Stage.FAILED, message = error.readableMessage()), force = true)
            } finally {
                activeJobs.remove(packageName)
                finishForegroundIfIdle()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun publish(progress: DownloadProgress, force: Boolean = false) {
        val current = progress.copy(updatedAt = System.currentTimeMillis())
        progressStore.save(current)
        sendBroadcast(DownloadProgressContract.intent(current).setPackage(packageName))

        val now = System.currentTimeMillis()
        val terminal = current.stage != DownloadProgress.Stage.DOWNLOADING
        val previous = lastNotificationAt[current.packageName] ?: 0L
        if (force || terminal || now - previous >= NOTIFICATION_THROTTLE_MS) {
            notifications.notify(notificationId(current.packageName), buildNotification(current))
            lastNotificationAt[current.packageName] = now
        }
    }

    private fun ensureForeground(progress: DownloadProgress) {
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            buildSummaryNotification(progress),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    private fun finishForegroundIfIdle() {
        if (activeJobs.isNotEmpty()) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(progress: DownloadProgress) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_goggle_triangle)
        .setContentTitle(progress.title)
        .setContentText(progress.subtitle)
        .setSubText(progress.appName)
        .setContentIntent(contentIntent(progress))
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(progress.isActive)
        .setAutoCancel(progress.stage == DownloadProgress.Stage.FAILED || progress.stage == DownloadProgress.Stage.FINISHED)
        .setProgress(
            100,
            progress.overallPercent,
            progress.stage == DownloadProgress.Stage.PREPARING || progress.totalBytes <= 0
        )
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()

    private fun contentIntent(progress: DownloadProgress): PendingIntent {
        if (progress.stage == DownloadProgress.Stage.READY_TO_INSTALL) {
            val install = Intent(this, DownloadInstallService::class.java).apply {
                action = ACTION_INSTALL
                putExtra(EXTRA_PACKAGE, progress.packageName)
                putExtra(EXTRA_APP_NAME, progress.appName)
            }
            return PendingIntent.getService(
                this,
                notificationId(progress.packageName),
                install,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return PendingIntent.getActivity(
            this,
            notificationId(progress.packageName),
            AppDetailsActivity.intent(this, progress.packageName, progress.appName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildSummaryNotification(progress: DownloadProgress) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_goggle_triangle)
        .setContentTitle("Goggle Play downloads")
        .setContentText(progress.title)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(0, 0, true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                FOREGROUND_NOTIFICATION_ID,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun Throwable.readableMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.download_channel_description)
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        private const val ACTION_DOWNLOAD = "com.super_diamond.goggle_play.DOWNLOAD"
        private const val ACTION_INSTALL = "com.super_diamond.goggle_play.INSTALL_PREPARED"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_APP_NAME = "app_name"
        const val CHANNEL_ID = "app_downloads"
        const val FOREGROUND_NOTIFICATION_ID = 7301
        private const val NOTIFICATION_THROTTLE_MS = 300L

        fun start(context: Context, packageName: String, appName: String) {
            val intent = Intent(context, DownloadInstallService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_APP_NAME, appName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun installPrepared(context: Context, packageName: String, appName: String) {
            val intent = Intent(context, DownloadInstallService::class.java).apply {
                action = ACTION_INSTALL
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_APP_NAME, appName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun notificationId(packageName: String): Int = 8_000 + (packageName.hashCode().absoluteValue % 10_000)
    }
}
