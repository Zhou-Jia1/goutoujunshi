package com.shengjidaguai.goutoujunshi.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.shengjidaguai.goutoujunshi.domain.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

internal val dataJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
internal inline fun <reified T> encode(value: T): String = dataJson.encodeToString(value)
internal inline fun <reified T> decode(value: String): T = dataJson.decodeFromString(value)
data class WriteStamp(val consentVersion: Long, val sourceRevision: Long, val generation: Long, val contactRevision: Long? = null)

class DataSession internal constructor(internal val database: JunshiDatabase, val generation: Long, internal val ownedFileRoot: File? = null) {
    internal val gate = Mutex()
    internal var active = true
    internal val dao get() = database.dao()
    internal suspend fun <T> transaction(block: suspend JunshiDao.() -> T): T = gate.withLock {
        check(active) { "Database session was cleared; explicit initialization required" }
        database.withTransaction { dao.block() }
    }
    suspend fun stamp(contactId: String? = null): WriteStamp = transaction {
        val contact = contactId?.let { requireContact(it) }
        WriteStamp(currentConsent().consentVersion, metadata()?.styleRevision ?: 0, generation, contact?.revision)
    }
    internal fun checkGeneration(stamp: WriteStamp) {
        check(active && stamp.generation == generation) { "Stale database generation" }
    }
}

internal suspend fun JunshiDao.currentConsent(): ConsentState = consent()?.let { decode(it.payload) } ?: ConsentState()
internal suspend fun JunshiDao.requireContact(contactId: String): ContactRow {
    require(contactId.isNotBlank()) { "Temporary data must remain in memory" }
    return requireNotNull(contact(contactId)) { "Unknown contact" }
}
internal suspend fun JunshiDao.checkStamp(session: DataSession, stamp: WriteStamp, contactId: String? = null) {
    session.checkGeneration(stamp)
    MemoryPolicy.requireConsent(currentConsent(), stamp.consentVersion)
    if (contactId != null && requireContact(contactId).revision != stamp.contactRevision)
        throw MemoryWriteRejected("STALE_SOURCE", "联系人来源已变更")
    if ((metadata()?.styleRevision ?: 0) != stamp.sourceRevision)
        throw MemoryWriteRejected("STALE_SOURCE", "来源已变更")
}

/** Own one instance per application. open() is explicit; repositories never recreate a cleared DB. */
class EncryptedDataStore(context: Context) {
    private val context = context.applicationContext
    val vault = SecretVault(this.context)
    private val mutex = Mutex()
    private var generation = 0L
    private var current: DataSession? = null
    private val failedCleanup = mutableSetOf<String>()
    private val ownedFileRoot = File(this.context.filesDir, "permanent-sources").canonicalFile
    /** Permanent files registered with repositories must live below this app-private root. */
    fun permanentFileRoot(): File = ownedFileRoot
    suspend fun open(): DataSession = mutex.withLock {
        if (vault.hasPendingCleanup()) {
            // Resume a previously requested wipe after process death before initializing anew.
            check(wipeLocked().isEmpty()) { "Permanent file cleanup is incomplete" }
        }
        current?.takeIf { it.active }?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            System.loadLibrary("sqlcipher")
            val passphrase = vault.databasePassphrase()
            try {
                val db = Room.databaseBuilder(context, JunshiDatabase::class.java, "junshi.db")
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(*JunshiDatabase.MIGRATIONS).build()
                // Force native open here, so wrong keys fail before a session is handed to the UI.
                db.openHelper.writableDatabase
                DataSession(db, generation, ownedFileRoot).also { current = it }
            } finally { passphrase.fill(0) }
        }
    }
    /** Returns file paths still requiring retry; a failure is never reported as successful deletion. */
    suspend fun clearAll(temporary: TemporarySessionStore): List<String> = mutex.withLock {
        // Once the durable request is written, UI cancellation must not leave an active session
        // able to accept late results before the wipe resumes.
        withContext(NonCancellable) {
            temporary.clear()
            // Non-sensitive retry intent survives key destruction. Remaining paths are rediscovered
            // from the dedicated app-private source directory, never serialized in plaintext.
            vault.markCleanupPending(true)
            wipeLocked()
        }
    }
    suspend fun retryCleanup(): List<String> = mutex.withLock {
        if (vault.hasPendingCleanup()) wipeLocked() else emptyList()
    }

    /** Called only with the store mutex held and a durable wipe intent already recorded.
     * Every entry point retries the full wipe, including DB/key destruction, before acknowledging
     * the marker. A process can die between any two steps without making old data reopenable.
     */
    private suspend fun wipeLocked(): List<String> = withContext(NonCancellable) {
        generation++
        val session = current
        if (session != null) session.gate.withLock {
            session.active = false
            withContext(Dispatchers.IO) {
                // All registered permanent files must be below ownedFileRoot. Rediscover them
                // there after closing; cleanup must not depend on a still-readable database.
                session.database.close()
            }
        }
        current = null
        withContext(Dispatchers.IO) {
            context.deleteDatabase("junshi.db")
            val database = context.getDatabasePath("junshi.db")
            check(listOf("", "-wal", "-shm", "-journal").none { File(database.path + it).exists() }) {
                "Database cleanup is incomplete"
            }
        }
        vault.clear()
        withContext(Dispatchers.IO) {
            if (ownedFileRoot.exists()) ownedFileRoot.walkBottomUp().onFail { _, error -> throw error }.filter { it.isFile }.forEach {
                if (it.canonicalFile.toPath().startsWith(ownedFileRoot.toPath())) failedCleanup += it.canonicalPath
            }
        }
        val failures = withContext(Dispatchers.IO) {
            failedCleanup.removeAll { path -> runCatching { !File(path).exists() || File(path).delete() }.getOrDefault(false) }
            failedCleanup.toList()
        }
        if (failures.isEmpty()) vault.markCleanupPending(false)
        failures
    }
}
