package com.super_diamond.goggle_play

import android.content.Context
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.SearchHelper
import com.aurora.gplayapi.helpers.StreamHelper
import com.aurora.gplayapi.helpers.contracts.StreamContract
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DispenserAccessException(
    val responseCode: Int,
    message: String
) : IOException(message)

class GogglePlayClient(context: Context) {
    private val profileRepository = DeviceProfileRepository(context.applicationContext)
    private val authRepository = AuthRepository(context.applicationContext)

    @Volatile
    private var authData: AuthData? = null
    private val authMutex = Mutex()

    fun clearSession() {
        authData = null
    }

    fun forceReconnect() {
        authData = null
        authRepository.clearCachedDispenserCredentials()
    }

    suspend fun connect(): AuthData = authMutex.withLock {
        authData ?: withContext(Dispatchers.IO) {
            when (authRepository.mode) {
                AuthRepository.Mode.PERSONAL -> connectPersonal()
                AuthRepository.Mode.DISPENSER -> connectDispenserWithRetry()
            }
        }
    }

    private suspend fun connectDispenserWithRetry(): AuthData {
        authRepository.cachedDispenserCredentials()?.let { cached ->
            runCatching { buildDispenserAuth(cached.email, cached.authToken) }
                .onSuccess { return it }
            authRepository.clearCachedDispenserCredentials()
        }
        var lastError: DispenserAccessException? = null
        repeat(DISPENSER_ATTEMPTS) { attempt ->
            try {
                return connectDispenser()
            } catch (error: DispenserAccessException) {
                lastError = error
                if (error.responseCode !in 500..599 || attempt == DISPENSER_ATTEMPTS - 1) throw error
                delay(DISPENSER_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Dispenser authentication failed")
    }

    private fun connectPersonal(): AuthData {
        val credentials = authRepository.personalCredentials()
            ?: throw IOException("Personal Google account is not configured. Add an email and AAS token in Settings.")
        return AuthHelper.build(
            email = credentials.email,
            token = credentials.aasToken,
            tokenType = AuthHelper.Token.AAS,
            isAnonymous = false,
            properties = profileRepository.activeProperties(),
            locale = profileRepository.locale
        ).also { authData = it }
    }

    private fun connectDispenser(): AuthData {
        val endpoint = authRepository.dispenserUrl
        require(endpoint.startsWith("https://")) { "The dispenser URL must use HTTPS" }

        val properties = profileRepository.activeProperties()
        val locale = profileRepository.locale
        val body = properties.toJson().toString().toByteArray(Charsets.UTF_8)
        val separator = if (endpoint.contains('?')) '&' else '?'
        val url = URL("$endpoint${separator}locale=${URLEncoder.encode(locale.toLanguageTag(), "UTF-8")}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 45_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", authRepository.dispenserUserAgent)
        }

        try {
            connection.outputStream.use { it.write(body) }
            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                val detail = runCatching { JSONObject(responseText).optString("error") }.getOrNull()
                val serverDetail = detail?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                val message = if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    "The Aurora public dispenser declined this independent client (HTTP 403). " +
                        "Choose a compatible self-hosted dispenser in Settings."
                } else {
                    "Dispenser returned HTTP $responseCode$serverDetail"
                }
                throw DispenserAccessException(responseCode, message)
            }

            val json = JSONObject(responseText)
            val email = json.optString("email")
            val token = json.optString("authToken").ifBlank { json.optString("auth") }
            if (email.isBlank() || token.isBlank()) throw IOException("Dispenser response did not contain an anonymous account token")

            authRepository.cacheDispenserCredentials(email, token)
            return buildDispenserAuth(email, token)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildDispenserAuth(email: String, token: String): AuthData = AuthHelper.build(
        email = email,
        token = token,
        tokenType = AuthHelper.Token.AUTH,
        isAnonymous = true,
        properties = profileRepository.activeProperties(),
        locale = profileRepository.locale
    ).also { authData = it }

    suspend fun search(query: String): List<App> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().trim('@').ifBlank { query.trim() }
        require(cleanQuery.isNotBlank()) { "Enter something to search for" }

        val webPackages = runCatching { webSearchPackages(cleanQuery) }.getOrDefault(emptyList())
        if (webPackages.isNotEmpty()) {
            val resolved = mutableListOf<App>()
            webPackages.take(MAX_WEB_RESULTS).chunked(WEB_DETAIL_CONCURRENCY).forEach { chunk ->
                resolved += coroutineScope {
                    chunk.map { packageName ->
                        async { runCatching { details(packageName) }.getOrNull() }
                    }.awaitAll().filterNotNull()
                }
            }
            val relevant = resolved
                .distinctBy { it.packageName }
                .filter { relevanceScore(it, cleanQuery) >= DIRECT_MATCH_SCORE }
            if (relevant.isNotEmpty()) return@withContext relevant
        }

        withAuthRetry { auth ->
            val search = SearchHelper(auth)
            val merged = linkedMapOf<String, App>()

            // Modern Play responses often put the exact app in autocomplete while the
            // legacy search stream contains charts or "similar apps". Resolve the package
            // IDs from autocomplete first so those clusters cannot replace the real result.
            val exactSuggestions = runCatching { search.searchSuggestions(cleanQuery) }.getOrDefault(emptyList())
            exactSuggestions
                .asSequence()
                .filter { it.hasPackageNameContainer() }
                .map { it.packageNameContainer.packageName.trim() }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_EXACT_SUGGESTIONS)
                .mapNotNull { packageName ->
                    runCatching { AppDetailsHelper(auth).getAppByPackageName(packageName) }.getOrNull()
                }
                .filter { relevanceScore(it, cleanQuery) >= DIRECT_MATCH_SCORE }
                .forEach { merged[it.packageName] = it }

            collectSearchResults(search, cleanQuery, strict = true).forEach { app ->
                if (!merged.containsKey(app.packageName)) merged[app.packageName] = app
            }
            merged.values.take(MAX_RESULTS)
        }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().trim('@')
        if (cleanQuery.length < 2) return@withContext emptyList()
        withAuthRetry { auth -> SearchHelper(auth).searchSuggestions(cleanQuery) }
            .asSequence()
            .map { it.suggestedQuery.ifBlank { it.title }.trim() }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(8)
            .toList()
    }

    suspend fun home(
        category: StreamContract.Category,
        topCharts: Boolean = false
    ): List<App> = withContext(Dispatchers.IO) {
        val type = if (topCharts) StreamContract.Type.TOP_CHART else StreamContract.Type.HOME
        val (helper, firstBundle) = withAuthRetry { auth ->
            val stream = StreamHelper(auth)
            stream to stream.fetch(type, category)
        }
        val apps = linkedMapOf<String, App>()
        var bundle = firstBundle
        val seenBundles = mutableSetOf<String>()

        var page = 0
        while (page++ < MAX_BUNDLE_PAGES) {
            collectHomeBundle(helper, bundle, apps)
            if (apps.size >= MAX_RESULTS || !bundle.hasNext()) break
            val nextUrl = bundle.streamNextPageUrl
            if (nextUrl.isBlank() || !seenBundles.add(nextUrl)) break
            bundle = runCatching { helper.nextStreamBundle(bundle.id, category, nextUrl) }.getOrNull()
                ?: break
        }

        if (apps.isEmpty()) {
            val choices = if (category == StreamContract.Category.GAME) HOME_GAME_QUERIES else HOME_APP_QUERIES
            val queries = if (topCharts) choices.take(HOME_SEED_COUNT) else choices.shuffled().take(HOME_SEED_COUNT)
            val packageNames = queries.flatMap { query ->
                runCatching { webSearchPackages(query).take(HOME_RESULTS_PER_SEED) }.getOrDefault(emptyList())
            }.distinct().take(MAX_RESULTS)
            val resolved = packageNames.chunked(WEB_DETAIL_CONCURRENCY).flatMap { chunk ->
                coroutineScope {
                    chunk.map { packageName ->
                        async { runCatching { details(packageName) }.getOrNull() }
                    }.awaitAll().filterNotNull()
                }
            }
            resolved.forEach { app ->
                if (!apps.containsKey(app.packageName)) apps[app.packageName] = app
            }

            if (apps.isEmpty()) {
                val auth = authData ?: connect()
                queries.flatMap { query ->
                    runCatching { collectSearchResults(SearchHelper(auth), query, strict = true) }.getOrDefault(emptyList())
                }.forEach { app ->
                    if (!apps.containsKey(app.packageName)) apps[app.packageName] = app
                }
            }
        }

        apps.values.take(MAX_RESULTS).let { if (topCharts) it else it.shuffled() }
    }

    suspend fun details(packageName: String): App = withContext(Dispatchers.IO) {
        withAuthRetry { auth -> AppDetailsHelper(auth).getAppByPackageName(packageName) }
    }

    suspend fun purchase(app: App): List<PlayFile> = withContext(Dispatchers.IO) {
        withAuthRetry { auth ->
            PurchaseHelper(auth).purchase(
                packageName = app.packageName,
                versionCode = app.versionCode,
                offerType = app.offerType
            )
        }
    }

    private fun collectSearchResults(helper: SearchHelper, query: String, strict: Boolean): List<App> {
        val apps = linkedMapOf<String, App>()
        var bundle = helper.searchResults(query, "")
        val seenBundles = mutableSetOf<String>()

        var bundlePage = 0
        while (bundlePage++ < MAX_BUNDLE_PAGES) {
            for (cluster in bundle.streamClusters.values) {
                addApps(apps, cluster.clusterAppList)
                var nextUrl = cluster.clusterNextPageUrl
                val seenClusters = mutableSetOf<String>()
                var clusterPage = 0
                while (clusterPage++ < MAX_CLUSTER_PAGES && apps.size < MAX_RESULTS && nextUrl.isNotBlank() && seenClusters.add(nextUrl)) {
                    val next = runCatching { helper.nextStreamCluster(query, nextUrl) }.getOrNull() ?: break
                    addApps(apps, next.clusterAppList)
                    nextUrl = next.clusterNextPageUrl
                }
                if (apps.size >= MAX_RESULTS) break
            }
            if (apps.size >= MAX_RESULTS || !bundle.hasNext()) break
            val nextUrl = bundle.streamNextPageUrl
            if (nextUrl.isBlank() || !seenBundles.add(nextUrl)) break
            bundle = runCatching { helper.nextStreamBundle(query, nextUrl) }.getOrNull() ?: break
        }
        if (!strict) return apps.values.take(MAX_RESULTS)

        val ranked = apps.values
            .map { it to relevanceScore(it, query) }
            .filter { it.second >= DIRECT_MATCH_SCORE }
            .sortedWith(compareByDescending<Pair<App, Int>> { it.second }.thenBy { it.first.displayName.lowercase() })
            .map(Pair<App, Int>::first)
        return ranked.take(MAX_RESULTS)
    }

    private fun relevanceScore(app: App, query: String): Int {
        val needle = normalize(query)
        val compact = needle.replace(" ", "")
        val name = normalize(app.displayName)
        val packageName = normalize(app.packageName)
        val developer = normalize(app.developerName)
        val tokens = needle.split(' ').filter(String::isNotBlank)
        return when {
            name == needle -> 1_000
            name.startsWith("$needle ") -> 900
            name.contains(needle) -> 800
            compact.length >= 3 && packageName.replace(".", "").contains(compact) -> 750
            tokens.isNotEmpty() && tokens.all(name::contains) -> 700
            developer == needle || developer.contains(needle) -> 600
            else -> 0
        }
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun webSearchPackages(query: String): List<String> {
        val locale = profileRepository.locale
        val language = locale.language.ifBlank { "en" }
        val country = locale.country.ifBlank { "US" }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val connection = (URL("https://play.google.com/store/search?q=$encoded&c=apps&hl=$language&gl=$country")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", locale.toLanguageTag())
            setRequestProperty("User-Agent", WEB_USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("Play search returned HTTP ${connection.responseCode}")
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            return PLAY_DETAILS_PATTERN.findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .take(MAX_WEB_RESULTS)
                .toList()
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun <T> withAuthRetry(block: (AuthData) -> T): T {
        val auth = connect()
        return try {
            block(auth)
        } catch (error: Throwable) {
            if (!isAuthenticationFailure(error)) throw error
            forceReconnect()
            block(connect())
        }
    }

    private fun isAuthenticationFailure(error: Throwable): Boolean {
        val text = generateSequence(error) { it.cause }
            .joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }
            .lowercase()
        return listOf("401", "unauthorized", "invalid auth", "authentication required", "auth token").any(text::contains)
    }

    private fun collectHomeBundle(
        helper: StreamHelper,
        bundle: com.aurora.gplayapi.data.models.StreamBundle,
        apps: LinkedHashMap<String, App>
    ) {
        for (cluster in bundle.streamClusters.values.shuffled()) {
            addApps(apps, cluster.clusterAppList)
            var nextUrl = cluster.clusterNextPageUrl
            val seen = mutableSetOf<String>()
            var page = 0
            while (page++ < MAX_HOME_CLUSTER_PAGES && apps.size < MAX_RESULTS && nextUrl.isNotBlank() && seen.add(nextUrl)) {
                val next = runCatching { helper.nextStreamCluster(cluster.id, nextUrl) }.getOrNull() ?: break
                addApps(apps, next.clusterAppList)
                nextUrl = next.clusterNextPageUrl
            }
            if (apps.size >= MAX_RESULTS) break
        }
    }

    private fun Properties.toJson(): JSONObject = JSONObject().also { json ->
        stringPropertyNames().forEach { key -> json.put(key, getProperty(key)) }
    }

    private fun addApps(target: LinkedHashMap<String, App>, source: List<App>) {
        source.forEach { app ->
            if (!target.containsKey(app.packageName)) target[app.packageName] = app
        }
    }

    private companion object {
        const val MAX_RESULTS = 80
        const val MAX_BUNDLE_PAGES = 3
        const val MAX_CLUSTER_PAGES = 4
        const val MAX_HOME_CLUSTER_PAGES = 2
        const val DIRECT_MATCH_SCORE = 600
        const val MAX_EXACT_SUGGESTIONS = 12
        const val MAX_WEB_RESULTS = 24
        const val WEB_DETAIL_CONCURRENCY = 6
        const val HOME_SEED_COUNT = 5
        const val HOME_RESULTS_PER_SEED = 2
        const val DISPENSER_ATTEMPTS = 3
        const val DISPENSER_RETRY_DELAY_MS = 750L
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/129 Mobile Safari/537.36"
        val PLAY_DETAILS_PATTERN = Regex("""/store/apps/details\?id=([A-Za-z0-9._]+)""")
        val HOME_APP_QUERIES = listOf("YouTube", "WhatsApp", "Spotify", "Steam", "Netflix", "Reddit", "TikTok")
        val HOME_GAME_QUERIES = listOf("Minecraft", "Roblox", "Clash of Clans", "PUBG Mobile", "Among Us", "Call of Duty Mobile")
    }
}

object GogglePlaySession {
    @Volatile
    private var instance: GogglePlayClient? = null

    fun get(context: Context): GogglePlayClient = instance ?: synchronized(this) {
        instance ?: GogglePlayClient(context.applicationContext).also { instance = it }
    }
}
