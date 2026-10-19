package com.super_diamond.goggle_play

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AuthRepository(private val context: Context) {
    enum class Mode { PERSONAL, DISPENSER }

    data class PersonalCredentials(val email: String, val aasToken: String)
    data class DispenserCredentials(val email: String, val authToken: String)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val mode: Mode
        get() = runCatching {
            Mode.valueOf(prefs.getString(KEY_MODE, Mode.DISPENSER.name) ?: Mode.DISPENSER.name)
        }.getOrDefault(Mode.DISPENSER)

    val dispenserUrl: String
        get() = prefs.getString(KEY_DISPENSER, null)
            ?: context.getSharedPreferences(LEGACY_PROFILE_PREFS, Context.MODE_PRIVATE)
                .getString(LEGACY_DISPENSER_KEY, DeviceProfileRepository.DEFAULT_DISPENSER)
            ?: DeviceProfileRepository.DEFAULT_DISPENSER

    val defaultUserAgent: String
        get() = "${BuildConfig.APPLICATION_ID}-${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}"

    val dispenserUserAgent: String
        get() = prefs.getString(KEY_USER_AGENT, null)?.takeIf(String::isNotBlank) ?: defaultUserAgent

    val generation: Long get() = prefs.getLong(KEY_GENERATION, 0)

    fun personalCredentials(): PersonalCredentials? {
        val email = decrypt(prefs.getString(KEY_EMAIL, null)).orEmpty()
        val token = decrypt(prefs.getString(KEY_AAS_TOKEN, null)).orEmpty()
        return if (email.isBlank() || token.isBlank()) null else PersonalCredentials(email, token)
    }

    fun savePersonal(email: String, aasToken: String) {
        prefs.edit()
            .putString(KEY_MODE, Mode.PERSONAL.name)
            .putString(KEY_EMAIL, encrypt(email.trim()))
            .putString(KEY_AAS_TOKEN, encrypt(aasToken.trim()))
            .remove(KEY_CACHED_DISPENSER_EMAIL)
            .remove(KEY_CACHED_DISPENSER_TOKEN)
            .remove(KEY_CACHED_DISPENSER_GENERATION)
            .putLong(KEY_GENERATION, generation + 1)
            .apply()
    }

    fun saveDispenser(url: String, userAgent: String) {
        prefs.edit()
            .putString(KEY_MODE, Mode.DISPENSER.name)
            .putString(KEY_DISPENSER, url.trim().trimEnd('/'))
            .putString(KEY_USER_AGENT, userAgent.trim().ifBlank { defaultUserAgent })
            .remove(KEY_CACHED_DISPENSER_EMAIL)
            .remove(KEY_CACHED_DISPENSER_TOKEN)
            .remove(KEY_CACHED_DISPENSER_GENERATION)
            .putLong(KEY_GENERATION, generation + 1)
            .apply()
    }

    fun forgetPersonalCredentials() {
        prefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_AAS_TOKEN)
            .remove(KEY_CACHED_DISPENSER_EMAIL)
            .remove(KEY_CACHED_DISPENSER_TOKEN)
            .remove(KEY_CACHED_DISPENSER_GENERATION)
            .putLong(KEY_GENERATION, generation + 1)
            .apply()
    }

    fun cachedDispenserCredentials(): DispenserCredentials? {
        if (prefs.getLong(KEY_CACHED_DISPENSER_GENERATION, -1) != generation) return null
        val email = decrypt(prefs.getString(KEY_CACHED_DISPENSER_EMAIL, null)).orEmpty()
        val token = decrypt(prefs.getString(KEY_CACHED_DISPENSER_TOKEN, null)).orEmpty()
        return if (email.isBlank() || token.isBlank()) null else DispenserCredentials(email, token)
    }

    fun cacheDispenserCredentials(email: String, authToken: String) {
        prefs.edit()
            .putString(KEY_CACHED_DISPENSER_EMAIL, encrypt(email))
            .putString(KEY_CACHED_DISPENSER_TOKEN, encrypt(authToken))
            .putLong(KEY_CACHED_DISPENSER_GENERATION, generation)
            .apply()
    }

    fun clearCachedDispenserCredentials() {
        prefs.edit()
            .remove(KEY_CACHED_DISPENSER_EMAIL)
            .remove(KEY_CACHED_DISPENSER_TOKEN)
            .remove(KEY_CACHED_DISPENSER_GENERATION)
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(BuildConfig.APPLICATION_ID.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted)
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            cipher.updateAAD(BuildConfig.APPLICATION_ID.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS = "goggle_play_auth"
        private const val KEY_MODE = "mode"
        private const val KEY_EMAIL = "email_encrypted"
        private const val KEY_AAS_TOKEN = "aas_token_encrypted"
        private const val KEY_DISPENSER = "dispenser"
        private const val KEY_USER_AGENT = "dispenser_user_agent"
        private const val KEY_GENERATION = "generation"
        private const val KEY_CACHED_DISPENSER_EMAIL = "cached_dispenser_email"
        private const val KEY_CACHED_DISPENSER_TOKEN = "cached_dispenser_token"
        private const val KEY_CACHED_DISPENSER_GENERATION = "cached_dispenser_generation"
        private const val LEGACY_PROFILE_PREFS = "goggle_play_profile"
        private const val LEGACY_DISPENSER_KEY = "dispenser"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "goggle_play_personal_account"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
