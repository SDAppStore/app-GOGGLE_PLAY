package com.super_diamond.goggle_play

import android.content.Context
import android.content.Intent
import kotlin.math.roundToInt
import org.json.JSONObject

data class DownloadProgress(
    val packageName: String,
    val appName: String,
    val stage: Stage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val fileName: String = "",
    val fileDownloadedBytes: Long = 0,
    val fileTotalBytes: Long = 0,
    val message: String = "",
    val sessionId: Int = -1,
    val updatedAt: Long = System.currentTimeMillis()
) {
    enum class Stage { PREPARING, DOWNLOADING, READY_TO_INSTALL, INSTALLING, FINISHED, FAILED }

    val overallPercent: Int
        get() = percent(downloadedBytes, totalBytes)

    val filePercent: Int
        get() = percent(fileDownloadedBytes, fileTotalBytes)

    val title: String
        get() = when (stage) {
            Stage.DOWNLOADING -> "$overallPercent% (${formatBytes(downloadedBytes)}/${formatBytes(totalBytes)})"
            Stage.PREPARING -> "Preparing $appName"
            Stage.READY_TO_INSTALL -> "$appName is ready"
            Stage.INSTALLING -> "Installing $appName"
            Stage.FINISHED -> "$appName installed"
            Stage.FAILED -> "$appName failed"
        }

    val subtitle: String
        get() = when (stage) {
            Stage.DOWNLOADING -> "$filePercent% $fileName (${formatBytes(fileDownloadedBytes)}/${formatBytes(fileTotalBytes)})"
            Stage.READY_TO_INSTALL -> message.ifBlank { "Tap to install" }
            else -> message
        }

    val isActive: Boolean
        get() = stage == Stage.PREPARING || stage == Stage.DOWNLOADING || stage == Stage.INSTALLING

    companion object {
        fun percent(value: Long, total: Long): Int = if (total <= 0) 0 else {
            ((value.coerceIn(0, total) * 100.0) / total).roundToInt().coerceIn(0, 100)
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824L -> formatUnit(bytes / 1_073_741_824.0, "GB")
            bytes >= 1_048_576L -> formatUnit(bytes / 1_048_576.0, "MB")
            bytes >= 1_024L -> formatUnit(bytes / 1_024.0, "KB")
            else -> "${bytes}B"
        }

        private fun formatUnit(value: Double, unit: String): String {
            val formatted = if (value >= 10 || value % 1.0 < 0.05) "%.0f".format(value) else "%.1f".format(value)
            return "$formatted$unit"
        }
    }
}

object DownloadProgressContract {
    const val ACTION_PROGRESS = "com.super_diamond.goggle_play.DOWNLOAD_PROGRESS"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_APP_NAME = "app_name"
    const val EXTRA_STAGE = "stage"
    const val EXTRA_DOWNLOADED = "downloaded"
    const val EXTRA_TOTAL = "total"
    const val EXTRA_FILE_NAME = "file_name"
    const val EXTRA_FILE_DOWNLOADED = "file_downloaded"
    const val EXTRA_FILE_TOTAL = "file_total"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_UPDATED_AT = "updated_at"

    fun intent(progress: DownloadProgress): Intent = Intent(ACTION_PROGRESS).apply {
        putExtra(EXTRA_PACKAGE, progress.packageName)
        putExtra(EXTRA_APP_NAME, progress.appName)
        putExtra(EXTRA_STAGE, progress.stage.name)
        putExtra(EXTRA_DOWNLOADED, progress.downloadedBytes)
        putExtra(EXTRA_TOTAL, progress.totalBytes)
        putExtra(EXTRA_FILE_NAME, progress.fileName)
        putExtra(EXTRA_FILE_DOWNLOADED, progress.fileDownloadedBytes)
        putExtra(EXTRA_FILE_TOTAL, progress.fileTotalBytes)
        putExtra(EXTRA_MESSAGE, progress.message)
        putExtra(EXTRA_SESSION_ID, progress.sessionId)
        putExtra(EXTRA_UPDATED_AT, progress.updatedAt)
    }

    fun from(intent: Intent): DownloadProgress? {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf(String::isNotBlank) ?: return null
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { packageName }
        val stage = runCatching {
            DownloadProgress.Stage.valueOf(intent.getStringExtra(EXTRA_STAGE).orEmpty())
        }.getOrNull() ?: return null
        return DownloadProgress(
            packageName = packageName,
            appName = appName,
            stage = stage,
            downloadedBytes = intent.getLongExtra(EXTRA_DOWNLOADED, 0),
            totalBytes = intent.getLongExtra(EXTRA_TOTAL, 0),
            fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
            fileDownloadedBytes = intent.getLongExtra(EXTRA_FILE_DOWNLOADED, 0),
            fileTotalBytes = intent.getLongExtra(EXTRA_FILE_TOTAL, 0),
            message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
            sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1),
            updatedAt = intent.getLongExtra(EXTRA_UPDATED_AT, System.currentTimeMillis())
        )
    }
}

class DownloadProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(progress: DownloadProgress) {
        val json = JSONObject()
            .put("package", progress.packageName)
            .put("name", progress.appName)
            .put("stage", progress.stage.name)
            .put("downloaded", progress.downloadedBytes)
            .put("total", progress.totalBytes)
            .put("file", progress.fileName)
            .put("fileDownloaded", progress.fileDownloadedBytes)
            .put("fileTotal", progress.fileTotalBytes)
            .put("message", progress.message)
            .put("session", progress.sessionId)
            .put("updated", progress.updatedAt)
        prefs.edit().putString(KEY_PREFIX + progress.packageName, json.toString()).apply()
    }

    fun load(packageName: String): DownloadProgress? = parse(prefs.getString(KEY_PREFIX + packageName, null))

    fun loadAll(): List<DownloadProgress> = prefs.all
        .asSequence()
        .filter { it.key.startsWith(KEY_PREFIX) }
        .mapNotNull { parse(it.value as? String) }
        .sortedByDescending(DownloadProgress::updatedAt)
        .toList()

    fun remove(packageName: String) {
        prefs.edit().remove(KEY_PREFIX + packageName).apply()
    }

    private fun parse(raw: String?): DownloadProgress? = runCatching {
        val json = JSONObject(raw ?: return null)
        DownloadProgress(
            packageName = json.getString("package"),
            appName = json.optString("name").ifBlank { json.getString("package") },
            stage = DownloadProgress.Stage.valueOf(json.getString("stage")),
            downloadedBytes = json.optLong("downloaded"),
            totalBytes = json.optLong("total"),
            fileName = json.optString("file"),
            fileDownloadedBytes = json.optLong("fileDownloaded"),
            fileTotalBytes = json.optLong("fileTotal"),
            message = friendlyMessage(json.optString("message")),
            sessionId = json.optInt("session", -1),
            updatedAt = json.optLong("updated", System.currentTimeMillis())
        )
    }.getOrNull()

    private fun friendlyMessage(message: String): String = when {
        message.contains("INSTALL_FAILED_ABORTED", ignoreCase = true) -> "Installation cancelled"
        else -> message
    }

    private companion object {
        const val PREFS = "goggle_play_download_progress_v2"
        const val KEY_PREFIX = "download:"
    }
}
