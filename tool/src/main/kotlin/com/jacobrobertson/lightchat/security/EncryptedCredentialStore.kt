package com.jacobrobertson.lightchat.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jacobrobertson.lightchat.data.CredentialStore
import com.jacobrobertson.lightchat.data.StoredCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps sign-in credentials in the app's private preferences, encrypted with a non-exportable
 * AES-GCM key held in the Android Keystore. No Play Services are involved.
 */
class EncryptedCredentialStore(private val dataStore: DataStore<Preferences>) : CredentialStore {
    private val entry = stringPreferencesKey("google_sign_in_v1")

    override suspend fun load(): StoredCredentials? = withContext(Dispatchers.IO) {
        val blob = dataStore.data.first()[entry] ?: return@withContext null
        Json.decodeFromString(StoredCredentials.serializer(), decrypt(Base64.getDecoder().decode(blob)))
    }

    override suspend fun save(credentials: StoredCredentials) = withContext(Dispatchers.IO) {
        val json = Json.encodeToString(StoredCredentials.serializer(), credentials)
        val encoded = Base64.getEncoder().encodeToString(encrypt(json))
        dataStore.edit { it[entry] = encoded }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dataStore.edit { it.remove(entry) }
        Unit
    }

    private fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(cipher.iv.size + ciphertext.size).put(cipher.iv).put(ciphertext).array()
    }

    private fun decrypt(blob: ByteArray): String {
        val buffer = ByteBuffer.wrap(blob)
        val iv = ByteArray(IV_BYTES).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lightchat_sign_in_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
