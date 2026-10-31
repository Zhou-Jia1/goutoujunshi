package com.shengjidaguai.goutoujunshi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val vaultStores = java.util.concurrent.ConcurrentHashMap<String, DataStore<Preferences>>()
private fun vaultPreferences(context: Context): DataStore<Preferences> {
    val file = File(context.filesDir, "datastore/secret_wrappers.preferences_pb").canonicalFile
    return vaultStores.computeIfAbsent(file.path) { PreferenceDataStoreFactory.create(produceFile = { file }) }
}

/** Only authenticated ciphertext reaches DataStore. No JVM test should create this class. */
class SecretVault(context: Context) {
    private val preferences = vaultPreferences(context)
    private val mutex = Mutex()
    private val alias = "${context.packageName}.secret-wrapper.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    suspend fun read(name: String): ByteArray? = mutex.withLock { readLocked(name) }
    private suspend fun readLocked(name: String): ByteArray? {
        val wrapped = preferences.data.first()[stringPreferencesKey(name)] ?: return null
        val bytes = Base64.decode(wrapped, Base64.NO_WRAP)
        require(bytes.size >= 28) { "Invalid secret wrapper" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(name.toByteArray(Charsets.UTF_8))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
    }
    suspend fun write(name: String, secret: ByteArray) = mutex.withLock { writeLocked(name, secret) }
    private suspend fun writeLocked(name: String, secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray(Charsets.UTF_8))
        }
        val wrapped = Base64.encodeToString(cipher.iv + cipher.doFinal(secret), Base64.NO_WRAP)
        preferences.edit { it[stringPreferencesKey(name)] = wrapped }
    }
    suspend fun databasePassphrase(): ByteArray = mutex.withLock {
        readLocked("database") ?: ByteArray(32).also {
            java.security.SecureRandom().nextBytes(it); writeLocked("database", it)
        }
    }
    suspend fun remove(name: String) = mutex.withLock { preferences.edit { it.remove(stringPreferencesKey(name)) }; Unit }
    // Cleanup intent contains no user content and remains readable after all keys are destroyed.
    suspend fun markCleanupPending(pending: Boolean) = mutex.withLock {
        preferences.edit {
            if (pending) it[stringPreferencesKey("cleanup_pending")] = "true"
            else it.remove(stringPreferencesKey("cleanup_pending"))
        }; Unit
    }
    suspend fun hasPendingCleanup(): Boolean = preferences.data.first()[stringPreferencesKey("cleanup_pending")] == "true"
    suspend fun clear() = mutex.withLock {
        preferences.edit {
            val pending = it[stringPreferencesKey("cleanup_pending")] == "true"
            it.clear()
            if (pending) it[stringPreferencesKey("cleanup_pending")] = "true"
        }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
