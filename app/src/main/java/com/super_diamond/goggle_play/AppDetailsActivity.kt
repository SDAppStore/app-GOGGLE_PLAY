package com.super_diamond.goggle_play

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.aurora.gplayapi.data.models.App
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class AppDetailsActivity : AppCompatActivity() {
    private lateinit var client: GogglePlayClient
    private lateinit var packageNameToLoad: String
    private lateinit var loading: LinearProgressIndicator
    private lateinit var action: MaterialButton
    private lateinit var downloadCard: MaterialCardView
    private lateinit var downloadTitle: TextView
    private lateinit var downloadSubtitle: TextView
    private lateinit var downloadBar: LinearProgressIndicator
    private var app: App? = null
    private var receiverRegistered = false
    private var pendingDownload = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingDownload) app?.let(::startDownload)
        pendingDownload = false
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.let(DownloadProgressContract::from) ?: return
            if (progress.packageName == packageNameToLoad) renderProgress(progress)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_details)
        packageNameToLoad = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (packageNameToLoad.isBlank()) {
            finish()
            return
        }
        client = GogglePlaySession.get(applicationContext)
        loading = findViewById(R.id.detailsLoading)
        action = findViewById(R.id.detailsAction)
        downloadCard = findViewById(R.id.detailsDownloadCard)
        downloadTitle = findViewById(R.id.detailsDownloadTitle)
        downloadSubtitle = findViewById(R.id.detailsDownloadSubtitle)
        downloadBar = findViewById(R.id.detailsDownloadProgress)
        findViewById<MaterialToolbar>(R.id.detailsToolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.detailsName).text = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { packageNameToLoad }
        action.isEnabled = false
        action.setOnClickListener { handleAction() }
        downloadCard.setOnClickListener {
            DownloadProgressStore(this).load(packageNameToLoad)
                ?.takeIf { it.stage == DownloadProgress.Stage.READY_TO_INSTALL }
                ?.let(::installPrepared)
        }
        loadDetails()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                progressReceiver,
                IntentFilter(DownloadProgressContract.ACTION_PROGRESS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        DownloadProgressStore(this).load(packageNameToLoad)?.let(::renderProgress) ?: refreshAction()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(progressReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun loadDetails() {
        lifecycleScope.launch {
            loading.isVisible = true
            runCatching { client.details(packageNameToLoad) }
                .onSuccess {
                    app = it
                    renderApp(it)
                }
                .onFailure {
                    Snackbar.make(action, it.message ?: "Could not load this app", Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.retry) { loadDetails() }
                        .show()
                }
            loading.isVisible = false
            refreshAction()
        }
    }

    private fun renderApp(app: App) {
        findViewById<ImageView>(R.id.detailsIcon).load(app.iconArtwork.url) {
            crossfade(true)
            placeholder(R.drawable.ic_goggle_triangle)
            error(R.drawable.ic_goggle_triangle)
        }
        findViewById<TextView>(R.id.detailsName).text = app.displayName
        findViewById<TextView>(R.id.detailsDeveloper).text = app.developerName
        findViewById<TextView>(R.id.detailsAds).text = listOfNotNull(
            "Contains ads".takeIf { app.containsAds },
            "In-app purchases".takeIf { app.offerDetails.isNotEmpty() }
        ).joinToString("  •  ").ifBlank { app.categoryName }
        findViewById<TextView>(R.id.detailsRating).text = if (app.rating.average > 0) {
            "${"%.1f".format(app.rating.average)} ★\nRating"
        } else "New\nRating"
        findViewById<TextView>(R.id.detailsInstalls).text = "${formatInstalls(app.installs)}+\nDownloads"
        findViewById<TextView>(R.id.detailsSize).text = "${DownloadProgress.formatBytes(app.size)}\nDownload"
        findViewById<TextView>(R.id.detailsDescription).apply {
            text = HtmlCompat.fromHtml(
                app.description.ifBlank { app.shortDescription },
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            movementMethod = LinkMovementMethod.getInstance()
        }
        findViewById<TextView>(R.id.detailsVersion).text =
            "Version ${app.versionName}  •  Updated ${app.updatedOn}\nRequires Android API ${app.targetSdk.coerceAtLeast(1)} or compatible device profile"
        renderScreenshots(app)
    }

    private fun renderScreenshots(app: App) {
        val container = findViewById<LinearLayout>(R.id.detailsScreenshots)
        container.removeAllViews()
        app.screenshots.take(10).forEach { artwork ->
            container.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(220), dp(390)).apply { marginEnd = dp(10) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ContextCompat.getDrawable(this@AppDetailsActivity, R.drawable.search_background)
                clipToOutline = true
                load(artwork.url) { crossfade(true) }
            })
        }
        findViewById<HorizontalScrollView>(R.id.detailsScreenshotsScroll).isVisible = container.childCount > 0
    }

    private fun handleAction() {
        val progress = DownloadProgressStore(this).load(packageNameToLoad)
        if (progress?.stage == DownloadProgress.Stage.READY_TO_INSTALL) {
            installPrepared(progress)
            return
        }
        val details = app ?: return
        if (isInstalled(packageNameToLoad) && !hasUpdate(details)) {
            packageManager.getLaunchIntentForPackage(packageNameToLoad)?.let(::startActivity)
            return
        }
        requestDownload(details)
    }

    private fun requestDownload(app: App) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Snackbar.make(action, "Allow installs from Goggle Play, then tap again.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startDownload(app)
    }

    private fun startDownload(app: App) {
        val preparing = DownloadProgress(app.packageName, app.displayName, DownloadProgress.Stage.PREPARING, message = "Contacting Google Play…")
        DownloadProgressStore(this).save(preparing)
        renderProgress(preparing)
        DownloadInstallService.start(this, app.packageName, app.displayName)
    }

    private fun installPrepared(progress: DownloadProgress) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        DownloadInstallService.installPrepared(this, progress.packageName, progress.appName)
    }

    private fun renderProgress(progress: DownloadProgress) {
        downloadCard.isVisible = progress.stage != DownloadProgress.Stage.FINISHED
        downloadTitle.text = progress.title
        downloadSubtitle.text = progress.subtitle.ifBlank { progress.appName }
        downloadBar.isIndeterminate = progress.stage == DownloadProgress.Stage.PREPARING || (progress.isActive && progress.totalBytes <= 0)
        if (!downloadBar.isIndeterminate) {
            downloadBar.setProgressCompat(if (progress.stage == DownloadProgress.Stage.READY_TO_INSTALL) 100 else progress.overallPercent, true)
        }
        refreshAction(progress)
    }

    private fun refreshAction(progress: DownloadProgress? = DownloadProgressStore(this).load(packageNameToLoad)) {
        val details = app
        action.isEnabled = details != null && progress?.isActive != true
        action.text = when (progress?.stage) {
            DownloadProgress.Stage.PREPARING -> "Preparing…"
            DownloadProgress.Stage.DOWNLOADING -> "${progress.overallPercent}%"
            DownloadProgress.Stage.READY_TO_INSTALL -> "Install"
            DownloadProgress.Stage.INSTALLING -> "Installing…"
            else -> when {
                details == null -> "Loading…"
                isInstalled(packageNameToLoad) && hasUpdate(details) -> "Update"
                isInstalled(packageNameToLoad) -> getString(R.string.open)
                else -> getString(R.string.install)
            }
        }
    }

    private fun isInstalled(packageName: String): Boolean = runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun hasUpdate(app: App): Boolean = runCatching {
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(app.packageName, 0)
        }
        val installedVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else {
            @Suppress("DEPRECATION")
            installed.versionCode.toLong()
        }
        app.versionCode > installedVersion
    }.getOrDefault(false)

    private fun formatInstalls(value: Long): String = when {
        value >= 1_000_000_000 -> "${value / 1_000_000_000}B"
        value >= 1_000_000 -> "${value / 1_000_000}M"
        value >= 1_000 -> "${value / 1_000}K"
        else -> value.toString()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, packageName: String, appName: String = packageName): Intent =
            Intent(context, AppDetailsActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_NAME, appName)
            }

        fun open(context: Context, packageName: String, appName: String = packageName) {
            context.startActivity(intent(context, packageName, appName))
        }
    }
}
