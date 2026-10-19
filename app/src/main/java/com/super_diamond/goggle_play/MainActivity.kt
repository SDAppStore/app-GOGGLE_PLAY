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
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.helpers.contracts.StreamContract
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var client: GogglePlayClient
    private lateinit var profileRepository: DeviceProfileRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var adapter: AppListAdapter
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var list: RecyclerView
    private lateinit var searchInput: AutoCompleteTextView
    private lateinit var suggestionAdapter: ArrayAdapter<String>
    private lateinit var tabs: TabLayout
    private lateinit var installedAdapter: InstalledAppAdapter
    private lateinit var downloadStatusAdapter: DownloadStatusAdapter
    private lateinit var downloadList: RecyclerView
    private var profileGeneration = -1L
    private var authGeneration = -1L
    private var currentQuery = ""
    private var currentCategory = StreamContract.Category.APPLICATION
    private var currentMode = BrowseMode.HOME
    private var lastCatalog = emptyList<App>()
    private var loadJob: Job? = null
    private var suggestionJob: Job? = null
    private var progressReceiverRegistered = false
    private var pendingNotificationInstall: App? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingNotificationInstall?.let(::startDownload)
        pendingNotificationInstall = null
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(DownloadProgressContract::from)?.let(::renderDownloadProgress)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        client = GogglePlaySession.get(applicationContext)
        profileRepository = DeviceProfileRepository(applicationContext)
        authRepository = AuthRepository(applicationContext)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.statusText)
        title = findViewById(R.id.screenTitle)
        list = findViewById(R.id.appList)
        searchInput = findViewById(R.id.searchInput)
        tabs = findViewById(R.id.topTabs)
        downloadList = findViewById(R.id.downloadList)
        adapter = AppListAdapter(
            isInstalled = ::isInstalled,
            onOpen = ::openApp,
            onInstall = ::installApp,
            onDetails = ::showDetails
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        installedAdapter = InstalledAppAdapter(
            onOpen = ::openInstalledApp,
            onUpdate = ::installApp,
            onDetails = { entry ->
                AppDetailsActivity.open(this, entry.packageName, entry.label)
            },
            onReadyToInstall = ::installPrepared
        )
        downloadStatusAdapter = DownloadStatusAdapter(::onDownloadStatusClick)
        downloadList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        downloadList.adapter = downloadStatusAdapter

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        setUpSearch()
        setUpTabs()
        setUpBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        if (profileGeneration != profileRepository.generation || authGeneration != authRepository.generation) {
            profileGeneration = profileRepository.generation
            authGeneration = authRepository.generation
            client.clearSession()
            loadCurrentContent()
        } else {
            renderStoredDownloads()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!progressReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                progressReceiver,
                IntentFilter(DownloadProgressContract.ACTION_PROGRESS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            progressReceiverRegistered = true
        }
        renderStoredDownloads()
    }

    override fun onStop() {
        if (progressReceiverRegistered) {
            unregisterReceiver(progressReceiver)
            progressReceiverRegistered = false
        }
        super.onStop()
    }

    private fun setUpSearch() {
        suggestionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        searchInput.setAdapter(suggestionAdapter)
        searchInput.threshold = 2
        searchInput.setOnItemClickListener { parent, _, position, _ ->
            performSearch(parent.getItemAtPosition(position).toString())
        }
        searchInput.setOnEditorActionListener { input, actionId, event ->
            val submitted = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submitted && input.text.isNotBlank()) {
                performSearch(input.text.toString())
                true
            } else false
        }
        searchInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN && searchInput.text.isNotBlank()) {
                performSearch(searchInput.text.toString())
                true
            } else false
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(text: Editable?) {
                suggestionJob?.cancel()
                val query = text?.toString().orEmpty().trim()
                if (query.length < 2) {
                    suggestionAdapter.clear()
                    return
                }
                suggestionJob = lifecycleScope.launch {
                    delay(SUGGESTION_DELAY_MS)
                    runCatching { client.suggestions(query) }.onSuccess { suggestions ->
                        if (searchInput.text.toString().trim() == query) {
                            suggestionAdapter.clear()
                            suggestionAdapter.addAll(suggestions)
                            suggestionAdapter.notifyDataSetChanged()
                            if (searchInput.hasFocus() && suggestions.isNotEmpty()) searchInput.showDropDown()
                        }
                    }
                }
            }
        })
    }

    private fun setUpTabs() {
        listOf(R.string.for_you, R.string.top_charts, R.string.kids, R.string.categories).forEach { label ->
            tabs.addTab(tabs.newTab().setText(label))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentMode = modeForTab(tab.position)
                loadBrowse()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                currentMode = modeForTab(tab.position)
                loadBrowse()
            }
        })
    }

    private fun modeForTab(position: Int): BrowseMode = when (position) {
        1 -> BrowseMode.TOP
        2 -> BrowseMode.KIDS
        3 -> BrowseMode.CATEGORIES
        else -> BrowseMode.HOME
    }

    private fun setUpBottomNavigation() {
        val navigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        navigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_games -> {
                    currentCategory = StreamContract.Category.GAME
                    tabs.isVisible = true
                    selectHomeTabOrReload()
                    true
                }
                R.id.navigation_apps -> {
                    currentCategory = StreamContract.Category.APPLICATION
                    tabs.isVisible = true
                    selectHomeTabOrReload()
                    true
                }
                R.id.navigation_manage -> {
                    tabs.isVisible = false
                    currentMode = BrowseMode.MANAGE
                    showInstalledFromCatalog()
                    true
                }
                else -> false
            }
        }
        navigation.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.navigation_games -> {
                    currentCategory = StreamContract.Category.GAME
                    tabs.isVisible = true
                    selectHomeTabOrReload()
                }
                R.id.navigation_apps -> {
                    currentCategory = StreamContract.Category.APPLICATION
                    tabs.isVisible = true
                    selectHomeTabOrReload()
                }
                R.id.navigation_manage -> showInstalledFromCatalog()
            }
        }
    }

    private fun selectHomeTabOrReload() {
        val homeTab = tabs.getTabAt(0)
        if (tabs.selectedTabPosition == 0) {
            currentMode = BrowseMode.HOME
            loadBrowse()
        } else {
            tabs.selectTab(homeTab)
        }
    }

    private fun loadCurrentContent() {
        when (currentMode) {
            BrowseMode.SEARCH -> loadApps(currentQuery)
            BrowseMode.MANAGE -> showInstalledFromCatalog()
            else -> loadBrowse()
        }
    }

    private fun loadBrowse() {
        clearSearchUi()
        currentQuery = ""
        val mode = currentMode
        title.text = when (mode) {
            BrowseMode.TOP -> if (currentCategory == StreamContract.Category.GAME) getString(R.string.popular_games) else getString(R.string.popular_apps)
            BrowseMode.KIDS -> getString(R.string.kids)
            BrowseMode.CATEGORIES -> getString(R.string.categories)
            else -> getString(R.string.recommended_for_you)
        }
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            setBusy(true, connectionMessage())
            val result = runCatching {
                when (mode) {
                    BrowseMode.TOP -> client.home(currentCategory, topCharts = true)
                    BrowseMode.KIDS -> client.search(if (currentCategory == StreamContract.Category.GAME) "kids games" else "apps for kids")
                    BrowseMode.CATEGORIES -> client.home(currentCategory)
                    else -> client.home(currentCategory)
                }
            }
            result.onSuccess(::submitApps).onFailure { if (it !is CancellationException) showError(it) }
            setBusy(false)
        }
    }

    private fun performSearch(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return
        searchInput.setText(cleanQuery)
        searchInput.setSelection(cleanQuery.length)
        searchInput.dismissDropDown()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        loadApps(cleanQuery)
    }

    private fun loadApps(query: String) {
        currentMode = BrowseMode.SEARCH
        currentQuery = query
        title.text = "Results for “$query”"
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            setBusy(true, connectionMessage())
            runCatching { client.search(query) }
                .onSuccess(::submitApps)
                .onFailure { if (it !is CancellationException) showError(it) }
            setBusy(false)
        }
    }

    private fun submitApps(apps: List<App>) {
        lastCatalog = apps
        if (list.adapter !== adapter) list.adapter = adapter
        adapter.submit(apps)
        list.scrollToPosition(0)
        status.text = if (apps.isEmpty()) "No apps found" else "${apps.size} apps compatible with this profile"
    }

    private fun showInstalledFromCatalog() {
        clearSearchUi()
        title.text = getString(R.string.installed_apps)
        list.adapter = installedAdapter
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            setBusy(true, "Reading apps installed on this device…")
            val installed = withContext(Dispatchers.IO) { installedApps() }
            installedAdapter.submit(installed)
            installedAdapter.setProgresses(DownloadProgressStore(this@MainActivity).loadAll())
            status.text = if (installed.isEmpty()) "No launchable apps found on this device" else
                "${installed.size} installed apps  •  Checking for updates…"
            setBusy(false)
            val semaphore = Semaphore(4)
            val checks = installed.map { entry ->
                async {
                    entry to semaphore.withPermit { runCatching { client.details(entry.packageName) }.getOrNull() }
                }
            }
            var checked = 0
            checks.forEach { check ->
                val (entry, remote) = check.await()
                installedAdapter.updateRemote(entry.packageName, remote)
                checked++
                val updates = installedAdapter.updateCount()
                status.text = if (checked == installed.size) {
                    "${installed.size} installed apps  •  $updates updates available"
                } else {
                    "Checking updates $checked/${installed.size}  •  $updates found"
                }
            }
        }
    }

    private fun connectionMessage(): String {
        val authentication = when (authRepository.mode) {
            AuthRepository.Mode.PERSONAL -> "personal Google account"
            AuthRepository.Mode.DISPENSER -> "configured dispenser"
        }
        return "Connecting with $authentication and ${if (profileRepository.editableProfile().custom) "custom" else "detected"} device profile…"
    }

    private fun clearSearchUi() {
        suggestionJob?.cancel()
        if (searchInput.text.isNotEmpty()) searchInput.setText("")
        searchInput.dismissDropDown()
        searchInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun showDetails(summary: App) {
        AppDetailsActivity.open(this, summary.packageName, summary.displayName)
    }

    private fun installApp(summary: App) {
        DownloadProgressStore(this).load(summary.packageName)
            ?.takeIf { it.stage == DownloadProgress.Stage.READY_TO_INSTALL }
            ?.let {
                installPrepared(it)
                return
            }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Snackbar.make(list, "Allow installs from Goggle Play, then tap Install again.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationInstall = summary
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startDownload(summary)
    }

    private fun startDownload(app: App) {
        val existing = DownloadProgressStore(this).load(app.packageName)
        if (existing?.isActive == true) {
            Snackbar.make(list, "${existing.appName} is already downloading", Snackbar.LENGTH_SHORT).show()
            return
        }
        val preparing = DownloadProgress(
            packageName = app.packageName,
            appName = app.displayName.ifBlank { app.packageName },
            stage = DownloadProgress.Stage.PREPARING,
            message = "Contacting Google Play…"
        )
        renderDownloadProgress(preparing)
        DownloadInstallService.start(this, app.packageName, preparing.appName)
        Snackbar.make(list, "Downloading in the background", Snackbar.LENGTH_SHORT).show()
    }

    private fun renderDownloadProgress(download: DownloadProgress) {
        adapter.updateProgress(download)
        installedAdapter.updateProgress(download)
        renderStoredDownloads()
        if (download.stage == DownloadProgress.Stage.FINISHED) {
            status.text = download.message.ifBlank { "${download.appName} installed" }
        } else if (download.stage == DownloadProgress.Stage.FAILED) {
            status.text = download.message.ifBlank { "${download.appName} failed" }
        }
    }

    private fun renderStoredDownloads() {
        val downloads = DownloadProgressStore(this).loadAll()
        adapter.setProgresses(downloads)
        installedAdapter.setProgresses(downloads)
        downloadStatusAdapter.submit(downloads)
        downloadList.isVisible = downloads.any { it.stage != DownloadProgress.Stage.FINISHED }
    }

    private fun onDownloadStatusClick(download: DownloadProgress) {
        if (download.stage == DownloadProgress.Stage.READY_TO_INSTALL) installPrepared(download)
        else AppDetailsActivity.open(this, download.packageName, download.appName)
    }

    private fun installPrepared(download: DownloadProgress) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Snackbar.make(list, "Allow installs from Goggle Play, then tap the download again.", Snackbar.LENGTH_LONG).show()
            return
        }
        DownloadInstallService.installPrepared(this, download.packageName, download.appName)
    }

    private fun installedApps(): List<InstalledAppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        return resolved
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .filter { installedFromPlay(it.packageName) }
            .mapNotNull { info ->
                runCatching {
                    val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
                        packageManager.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(info.packageName, 0)
                    }
                    InstalledAppEntry(
                        packageName = info.packageName,
                        label = info.loadLabel(packageManager).toString(),
                        versionName = packageInfo.versionName.orEmpty().ifBlank { packageInfo.compatVersionCode().toString() },
                        versionCode = packageInfo.compatVersionCode(),
                        icon = info.loadIcon(packageManager)
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun installedFromPlay(packageName: String): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()
        return installer in PLAY_INSTALLERS
    }

    private fun openInstalledApp(entry: InstalledAppEntry) {
        packageManager.getLaunchIntentForPackage(entry.packageName)?.let(::startActivity)
            ?: Snackbar.make(list, "${entry.label} has no launcher activity.", Snackbar.LENGTH_LONG).show()
    }

    private fun android.content.pm.PackageInfo.compatVersionCode(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    private fun isInstalled(packageName: String): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    private fun openApp(app: App) {
        packageManager.getLaunchIntentForPackage(app.packageName)?.let(::startActivity)
            ?: Snackbar.make(list, "${app.displayName} has no launcher activity.", Snackbar.LENGTH_LONG).show()
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        progress.isVisible = busy
        if (message != null) status.text = message
    }

    private fun showError(error: Throwable) {
        val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
        status.text = "Couldn’t load the store: $message"
        val snackbar = Snackbar.make(list, message, Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(R.id.bottomNavigation)
        if (error is DispenserAccessException && error.responseCode == 403) {
            snackbar.setAction(R.string.settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        } else {
            snackbar.setAction(R.string.retry) { loadCurrentContent() }
        }
        snackbar.show()
    }

    private enum class BrowseMode { HOME, TOP, KIDS, CATEGORIES, SEARCH, MANAGE }

    companion object {
        private const val SUGGESTION_DELAY_MS = 300L
        private val PLAY_INSTALLERS = setOf(
            "com.android.vending",
            BuildConfig.APPLICATION_ID,
            "com.aurora.store",
            "com.aurora.store.nightly"
        )
    }
}
