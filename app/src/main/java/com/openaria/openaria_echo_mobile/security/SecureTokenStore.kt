package com.openaria.openaria_echo_mobile.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTokenStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "openaria_echo_mobile_secure_token_v1",
        Context.MODE_PRIVATE,
    )

    fun save(origin: String, token: String): StoreResult {
        val normalizedOrigin = normalizeOrigin(origin)
            ?: return StoreResult.Failed("invalid origin")
        val normalizedToken = token.trim()
        if (normalizedToken.isEmpty()) {
            return StoreResult.Failed("empty token")
        }

        return saveEncrypted(prefix = originTokenPrefix(normalizedOrigin), token = normalizedToken)
    }

    fun saveForVerifiedBody(origin: String, deviceId: String, token: String): StoreResult {
        val normalizedOrigin = normalizeOrigin(origin)
            ?: return StoreResult.Failed("invalid origin")
        val normalizedDeviceId = normalizeDeviceId(deviceId)
            ?: return StoreResult.Failed("invalid device identity")
        val normalizedToken = token.trim()
        if (normalizedToken.isEmpty()) {
            return StoreResult.Failed("empty token")
        }

        return when (val result = saveEncrypted(prefix = bodyTokenPrefix(normalizedDeviceId), token = normalizedToken)) {
            StoreResult.Saved -> {
                preferences.edit()
                    .putString(originIndexKey(normalizedOrigin), normalizedDeviceId)
                    .apply()
                StoreResult.Saved
            }
            is StoreResult.Failed -> result
        }
    }

    fun load(origin: String): String? {
        val normalizedOrigin = normalizeOrigin(origin) ?: return null
        val indexedDeviceId = preferences.getString(originIndexKey(normalizedOrigin), null)
        if (!indexedDeviceId.isNullOrBlank()) {
            loadEncrypted(bodyTokenPrefix(indexedDeviceId))?.let { return it }
        }
        loadEncrypted(originTokenPrefix(normalizedOrigin))?.let { return it }
        return loadLegacySingleOrigin(normalizedOrigin)
    }

    fun hasTokenFor(origin: String): Boolean {
        val normalizedOrigin = normalizeOrigin(origin) ?: return false
        val indexedDeviceId = preferences.getString(originIndexKey(normalizedOrigin), null)
        return (!indexedDeviceId.isNullOrBlank() && hasEncryptedToken(bodyTokenPrefix(indexedDeviceId))) ||
            hasEncryptedToken(originTokenPrefix(normalizedOrigin)) ||
            hasLegacySingleOriginToken(normalizedOrigin)
    }

    fun clear(origin: String) {
        val normalizedOrigin = normalizeOrigin(origin) ?: return
        val indexedDeviceId = preferences.getString(originIndexKey(normalizedOrigin), null)
        preferences.edit()
            .remove(originIndexKey(normalizedOrigin))
            .apply()
        if (!indexedDeviceId.isNullOrBlank()) {
            clearEncrypted(bodyTokenPrefix(indexedDeviceId))
        }
        clearEncrypted(originTokenPrefix(normalizedOrigin))
        if (preferences.getString(KEY_ORIGIN, null) == normalizedOrigin) {
            clearLegacySingleOrigin()
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun saveEncrypted(prefix: String, token: String): StoreResult {
        val normalizedToken = token.trim()
        if (normalizedToken.isEmpty()) {
            return StoreResult.Failed("empty token")
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(normalizedToken.toByteArray(Charsets.UTF_8))
            preferences.edit()
                .putString("$prefix.$KEY_IV", cipher.iv.encodeBase64())
                .putString("$prefix.$KEY_CIPHERTEXT", encrypted.encodeBase64())
                .apply()
            StoreResult.Saved
        } catch (exception: Exception) {
            StoreResult.Failed(exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun loadEncrypted(prefix: String): String? {
        val iv = preferences.getString("$prefix.$KEY_IV", null)?.decodeBase64() ?: return null
        val ciphertext = preferences.getString("$prefix.$KEY_CIPHERTEXT", null)?.decodeBase64() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            clearEncrypted(prefix)
            null
        }
    }

    private fun hasEncryptedToken(prefix: String): Boolean {
        return preferences.contains("$prefix.$KEY_IV") &&
            preferences.contains("$prefix.$KEY_CIPHERTEXT")
    }

    private fun clearEncrypted(prefix: String) {
        preferences.edit()
            .remove("$prefix.$KEY_IV")
            .remove("$prefix.$KEY_CIPHERTEXT")
            .apply()
    }

    private fun loadLegacySingleOrigin(normalizedOrigin: String): String? {
        if (preferences.getString(KEY_ORIGIN, null) != normalizedOrigin) {
            return null
        }
        val iv = preferences.getString(KEY_IV, null)?.decodeBase64() ?: return null
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)?.decodeBase64() ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            clearLegacySingleOrigin()
            null
        }
    }

    private fun hasLegacySingleOriginToken(normalizedOrigin: String): Boolean {
        return preferences.getString(KEY_ORIGIN, null) == normalizedOrigin &&
            preferences.contains(KEY_IV) &&
            preferences.contains(KEY_CIPHERTEXT)
    }

    private fun clearLegacySingleOrigin() {
        preferences.edit()
            .remove(KEY_ORIGIN)
            .remove(KEY_IV)
            .remove(KEY_CIPHERTEXT)
            .apply()
    }

    private fun normalizeOrigin(origin: String): String? {
        return when (val decision = EndpointPolicy.validate(origin)) {
            is EndpointPolicy.Decision.Allowed -> decision.target.origin.toString()
            is EndpointPolicy.Decision.Rejected -> null
        }
    }

    private fun normalizeDeviceId(deviceId: String): String? {
        val normalized = deviceId.trim()
        return normalized.takeIf {
            it.isNotEmpty() &&
                it.length <= 128 &&
                it.all { character -> character.code in 0x21..0x7e }
        }
    }

    private fun originIndexKey(normalizedOrigin: String): String = "$KEY_ORIGIN_INDEX_PREFIX$normalizedOrigin"

    private fun originTokenPrefix(normalizedOrigin: String): String = "$KEY_ORIGIN_TOKEN_PREFIX$normalizedOrigin"

    private fun bodyTokenPrefix(normalizedDeviceId: String): String = "$KEY_BODY_TOKEN_PREFIX$normalizedDeviceId"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    sealed interface StoreResult {
        data object Saved : StoreResult
        data class Failed(val message: String) : StoreResult
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "openaria_echo_mobile_body_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_ORIGIN = "origin"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_ORIGIN_INDEX_PREFIX = "origin_index:"
        private const val KEY_ORIGIN_TOKEN_PREFIX = "origin_token:"
        private const val KEY_BODY_TOKEN_PREFIX = "body_token:"
    }
}
