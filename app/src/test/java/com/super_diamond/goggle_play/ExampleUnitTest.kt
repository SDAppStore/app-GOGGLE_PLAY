package com.super_diamond.goggle_play

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun downloadProgress_formatsNotificationText() {
        val gibibyte = 1_073_741_824L
        val progress = DownloadProgress(
            packageName = "example.app",
            appName = "Example",
            stage = DownloadProgress.Stage.DOWNLOADING,
            downloadedBytes = (gibibyte * 1.18).toLong(),
            totalBytes = gibibyte * 2,
            fileName = "install_pack.apk",
            fileDownloadedBytes = 102L * 1_048_576L,
            fileTotalBytes = gibibyte
        )

        assertEquals(59, progress.overallPercent)
        assertEquals("59% (1.2GB/2GB)", progress.title)
        assertEquals("10% install_pack.apk (102MB/1GB)", progress.subtitle)
    }

    @Test
    fun downloadProgress_clampsPercent() {
        assertEquals(0, DownloadProgress.percent(1, 0))
        assertEquals(100, DownloadProgress.percent(120, 100))
        assertEquals(0, DownloadProgress.percent(-1, 100))
    }
}
