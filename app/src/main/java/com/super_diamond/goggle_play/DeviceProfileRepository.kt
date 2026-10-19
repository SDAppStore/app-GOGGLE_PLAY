package com.super_diamond.goggle_play

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

class DeviceProfileRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class EditableProfile(
        val custom: Boolean,
        val manufacturer: String,
        val model: String,
        val device: String,
        val product: String,
        val abis: String,
        val sdk: String,
        val width: String,
        val height: String,
        val density: String,
        val localeTag: String
    )

    fun editableProfile(): EditableProfile {
        val native = nativeProperties()
        return EditableProfile(
            custom = prefs.getBoolean(KEY_CUSTOM, false),
            manufacturer = prefs.getString(KEY_MANUFACTURER, null) ?: native.value("Build.MANUFACTURER"),
            model = prefs.getString(KEY_MODEL, null) ?: native.value("Build.MODEL"),
            device = prefs.getString(KEY_DEVICE, null) ?: native.value("Build.DEVICE"),
            product = prefs.getString(KEY_PRODUCT, null) ?: native.value("Build.PRODUCT"),
            abis = prefs.getString(KEY_ABIS, null) ?: native.value("Platforms"),
            sdk = prefs.getString(KEY_SDK, null) ?: native.value("Build.VERSION.SDK_INT"),
            width = prefs.getString(KEY_WIDTH, null) ?: native.value("Screen.Width"),
            height = prefs.getString(KEY_HEIGHT, null) ?: native.value("Screen.Height"),
            density = prefs.getString(KEY_DENSITY, null) ?: native.value("Screen.Density"),
            localeTag = prefs.getString(KEY_LOCALE, null) ?: Locale.getDefault().toLanguageTag()
        )
    }

    fun save(profile: EditableProfile) {
        prefs.edit()
            .putBoolean(KEY_CUSTOM, profile.custom)
            .putString(KEY_MANUFACTURER, profile.manufacturer.trim())
            .putString(KEY_MODEL, profile.model.trim())
            .putString(KEY_DEVICE, profile.device.trim())
            .putString(KEY_PRODUCT, profile.product.trim())
            .putString(KEY_ABIS, profile.abis.split(',').map(String::trim).filter(String::isNotBlank).joinToString(","))
            .putString(KEY_SDK, profile.sdk.toIntOrNull()?.coerceIn(21, 99)?.toString() ?: Build.VERSION.SDK_INT.toString())
            .putString(KEY_WIDTH, profile.width.toIntOrNull()?.coerceAtLeast(240)?.toString())
            .putString(KEY_HEIGHT, profile.height.toIntOrNull()?.coerceAtLeast(240)?.toString())
            .putString(KEY_DENSITY, profile.density.toIntOrNull()?.coerceIn(72, 1000)?.toString())
            .putString(KEY_LOCALE, normalizedLocale(profile.localeTag).toLanguageTag())
            .putLong(KEY_GENERATION, generation + 1)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().putLong(KEY_GENERATION, generation + 1).apply()
    }

    val generation: Long get() = prefs.getLong(KEY_GENERATION, 0)
    val locale: Locale get() = normalizedLocale(editableProfile().localeTag)

    fun activeProperties(): Properties {
        val properties = nativeProperties()
        val profile = editableProfile()
        if (!profile.custom) return properties

        properties.setProperty("UserReadableName", "${profile.manufacturer} ${profile.model}".trim())
        properties.setProperty("Build.MANUFACTURER", profile.manufacturer)
        properties.setProperty("Build.BRAND", profile.manufacturer.lowercase(Locale.US).replace(" ", ""))
        properties.setProperty("Build.MODEL", profile.model)
        properties.setProperty("Build.DEVICE", profile.device)
        properties.setProperty("Build.HARDWARE", profile.device)
        properties.setProperty("Build.PRODUCT", profile.product)
        properties.setProperty("Build.VERSION.SDK_INT", profile.sdk)
        properties.setProperty("Platforms", profile.abis)
        properties.setProperty("Screen.Width", profile.width)
        properties.setProperty("Screen.Height", profile.height)
        properties.setProperty("Screen.Density", profile.density)
        properties.setProperty(
            "Build.FINGERPRINT",
            "${properties.value("Build.BRAND")}/${profile.product}/${profile.device}:${properties.value("Build.VERSION.RELEASE")}/${properties.value("Build.ID")}/goggle:user/release-keys"
        )
        return properties
    }

    fun nativeSummary(): String {
        val p = nativeProperties()
        return "Detected: ${p.value("Build.MANUFACTURER")} ${p.value("Build.MODEL")} • ${p.value("Platforms")} • API ${p.value("Build.VERSION.SDK_INT")} • ${p.value("Screen.Width")}×${p.value("Screen.Height")} @ ${p.value("Screen.Density")} dpi"
    }

    private fun nativeProperties(): Properties {
        val config = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val features = context.packageManager.systemAvailableFeatures.mapNotNull { it.name }
        val libraries = context.packageManager.systemSharedLibraryNames?.toList().orEmpty()
        val locales = context.assets.locales.map { it.replace('-', '_') }.distinct()

        return Properties().apply {
            setProperty("UserReadableName", "${Build.MANUFACTURER} ${Build.MODEL}")
            setProperty("Build.HARDWARE", Build.HARDWARE.orUnknown())
            setProperty("Build.RADIO", Build.getRadioVersion().orUnknown())
            setProperty("Build.BOOTLOADER", Build.BOOTLOADER.orUnknown())
            setProperty("Build.FINGERPRINT", Build.FINGERPRINT.orUnknown())
            setProperty("Build.BRAND", Build.BRAND.orUnknown())
            setProperty("Build.DEVICE", Build.DEVICE.orUnknown())
            setProperty("Build.VERSION.SDK_INT", Build.VERSION.SDK_INT.toString())
            setProperty("Build.VERSION.RELEASE", Build.VERSION.RELEASE.orUnknown())
            setProperty("Build.MODEL", Build.MODEL.orUnknown())
            setProperty("Build.MANUFACTURER", Build.MANUFACTURER.orUnknown())
            setProperty("Build.PRODUCT", Build.PRODUCT.orUnknown())
            setProperty("Build.ID", Build.ID.orUnknown())
            setProperty("TouchScreen", config.touchscreen.toString())
            setProperty("Keyboard", config.keyboard.toString())
            setProperty("Navigation", config.navigation.toString())
            setProperty("ScreenLayout", (config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK).toString())
            setProperty("HasHardKeyboard", (config.keyboard == Configuration.KEYBOARD_QWERTY).toString())
            setProperty("HasFiveWayNavigation", (config.navigation == Configuration.NAVIGATION_DPAD).toString())
            setProperty("LowRamDevice", if (activityManager.isLowRamDevice) "1" else "0")
            setProperty("MaxNumOfCPUCores", Runtime.getRuntime().availableProcessors().toString())
            setProperty("TotalMemoryBytes", memoryInfo.totalMem.toString())
            setProperty("Screen.Density", metrics.densityDpi.toString())
            setProperty("Screen.Width", metrics.widthPixels.toString())
            setProperty("Screen.Height", metrics.heightPixels.toString())
            setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(","))
            setProperty("Features", features.joinToString(","))
            setProperty("Locales", locales.ifEmpty { listOf("en_US") }.joinToString(","))
            setProperty("SharedLibraries", libraries.joinToString(","))
            setProperty("GL.Version", activityManager.deviceConfigurationInfo.reqGlEsVersion.toString())
            setProperty("GL.Extensions", "")
            setProperty("Client", "android-google")
            setProperty("GSF.version", packageVersion("com.google.android.gsf", 203615037L).toString())
            setProperty("Vending.version", packageVersion("com.android.vending", 82201710L).toString())
            setProperty("Vending.versionString", packageVersionName("com.android.vending", "22.0.17"))
            setProperty("Roaming", "mobile-notroaming")
            setProperty("TimeZone", TimeZone.getDefault().id)
            setProperty("CellOperator", "310260")
            setProperty("SimOperator", "310260")
        }
    }

    @Suppress("DEPRECATION")
    private fun packageVersion(packageName: String, fallback: Long): Long = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(fallback)

    private fun packageVersionName(packageName: String, fallback: String): String = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: fallback
    }.getOrDefault(fallback)

    private fun normalizedLocale(tag: String): Locale {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        return if (locale.language.isNullOrBlank()) Locale.getDefault() else locale
    }

    private fun String?.orUnknown(): String = if (this.isNullOrBlank()) "unknown" else this
    private fun Properties.value(key: String): String = getProperty(key, "")

    companion object {
        const val DEFAULT_DISPENSER = "https://auroraoss.com/api/auth"
        private const val PREFS = "goggle_play_profile"
        private const val KEY_CUSTOM = "custom"
        private const val KEY_MANUFACTURER = "manufacturer"
        private const val KEY_MODEL = "model"
        private const val KEY_DEVICE = "device"
        private const val KEY_PRODUCT = "product"
        private const val KEY_ABIS = "abis"
        private const val KEY_SDK = "sdk"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_DENSITY = "density"
        private const val KEY_LOCALE = "locale"
        private const val KEY_GENERATION = "generation"
    }
}
