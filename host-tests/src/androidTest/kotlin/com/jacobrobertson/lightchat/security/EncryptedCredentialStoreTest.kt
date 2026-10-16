package com.jacobrobertson.lightchat.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jacobrobertson.lightchat.data.StoredCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Runs on a real device/emulator because it needs the genuine Android Keystore. */
@RunWith(AndroidJUnit4::class)
class EncryptedCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saved = StoredCredentials(
        subject = "subject-1",
        accessToken = null,
        clientId = "client-id-value",
        clientSecret = "client-secret-value",
        refreshToken = "super-secret-refresh-token",
    )

    @Before
    fun setUp() {
        file = File(context.filesDir, "keystore-test-${System.nanoTime()}.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun openStore(): Pair<DataStore<Preferences>, EncryptedCredentialStore> {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return dataStore to EncryptedCredentialStore(dataStore)
    }

    @Test
    fun savedCredentialsSurviveARestartAndAreNotStoredAsPlaintext() = runBlocking<Unit> {
        val (_, first) = openStore()
        assertNull(first.load())
        first.save(saved)
        assertEquals(saved, first.load())

        val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("super-secret-refresh-token", "client-secret-value", "client-id-value", "subject-1")) {
            assertFalse(secret in onDisk, "plaintext '$secret' found in the preferences file")
        }

        // Simulate the app being closed and reopened: drop the first DataStore, open a new one.
        scope.cancel()
        val (_, reopened) = openStore()
        assertEquals(saved, reopened.load())
    }

    @Test
    fun clearRemovesTheSavedSignIn() = runBlocking<Unit> {
        val (_, store) = openStore()
        store.save(saved)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun accessTokenOnlyCredentialsRoundTrip() = runBlocking<Unit> {
        val (_, store) = openStore()
        val accessOnly = StoredCredentials(subject = "s", accessToken = "ya29.short-lived")
        store.save(accessOnly)
        assertEquals(accessOnly, store.load())
    }

    @Test
    fun tamperedOrForeignCiphertextFailsInsteadOfReturningCredentials() = runBlocking<Unit> {
        val (dataStore, store) = openStore()
        store.save(saved)
        val key = stringPreferencesKey("google_sign_in_v1")
        val original = dataStore.data.first()[key]!!
        val bytes = java.util.Base64.getDecoder().decode(original)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        dataStore.edit { it[key] = java.util.Base64.getEncoder().encodeToString(bytes) }
        assertFailsWith<Exception> { store.load() }
    }
}
