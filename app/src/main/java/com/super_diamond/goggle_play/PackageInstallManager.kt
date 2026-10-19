package com.super_diamond.goggle_play

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.PlayFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PackageInstallManager(private val context: Context) {
    suspend fun prepare(app: App, files: List<PlayFile>, onProgress: (DownloadProgress) -> Unit): Int = withContext(Dispatchers.IO) {
        val apkFiles = files.filter { it.type == PlayFile.Type.BASE || it.type == PlayFile.Type.SPLIT }
        require(apkFiles.isNotEmpty()) { "Google Play did not return installable APK files" }
        val totalBytes = apkFiles.sumOf { it.size.coerceAtLeast(0) }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apkFiles.sumOf { it.size })
        }
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        try {
            var completedBytes = 0L
            apkFiles.forEach { file ->
                val written = copyVerified(file, session) { fileBytes, fileTotal ->
                    onProgress(
                        DownloadProgress(
                            packageName = app.packageName,
                            appName = app.displayName.ifBlank { app.packageName },
                            stage = DownloadProgress.Stage.DOWNLOADING,
                            downloadedBytes = completedBytes + fileBytes,
                            totalBytes = totalBytes,
                            fileName = file.name,
                            fileDownloadedBytes = fileBytes,
                            fileTotalBytes = fileTotal
                        )
                    )
                }
                completedBytes += written
            }

            sessionId
        } catch (error: Throwable) {
            runCatching { session.abandon() }
            throw error
        } finally {
            session.close()
        }

    }

    private fun copyVerified(
        file: PlayFile,
        session: PackageInstaller.Session,
        onBytes: (downloaded: Long, total: Long) -> Unit
    ): Long {
        val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GogglePlay/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("Download failed with HTTP ${connection.responseCode}")
            val responseSize = connection.getHeaderField("Content-Length")?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val expectedSize = file.size.takeIf { it > 0 } ?: responseSize
            val expected = file.sha256.ifBlank { file.sha1 }
            val algorithm = if (file.sha256.isNotBlank()) "SHA-256" else "SHA-1"
            val digest = MessageDigest.getInstance(algorithm)
            var downloaded = 0L
            onBytes(0, expectedSize)
            connection.inputStream.use { input ->
                session.openWrite(file.name, 0, expectedSize.takeIf { it > 0 } ?: -1L).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        downloaded += read
                        onBytes(downloaded, expectedSize.coerceAtLeast(downloaded))
                    }
                    session.fsync(output)
                }
            }
            if (expected.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw IOException("Integrity check failed for ${file.name}")
            }
            return downloaded
        } finally {
            connection.disconnect()
        }
    }

    suspend fun commit(sessionId: Int, packageName: String, appName: String) = withContext(Dispatchers.IO) {
        require(sessionId >= 0) { "This download no longer has an install session. Download it again." }
        val callback = Intent(ACTION_INSTALL_RESULT).apply {
            component = ComponentName(context, InstallResultReceiver::class.java)
            putExtra(EXTRA_APP_NAME, appName)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            callback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        context.packageManager.packageInstaller.openSession(sessionId).use { session ->
            session.commit(pendingIntent.intentSender)
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.super_diamond.goggle_play.INSTALL_RESULT"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
