package com.shengjidaguai.goutoujunshi.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.Contact
import java.io.File
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Run on an emulator/device: JVM relational tests never stand in for this native encryption check. */
@RunWith(AndroidJUnit4::class)
class SqlCipherDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Use the test APK's sandbox, never the installed target application's databases or wrappers.
    private val context get() = instrumentation.context
    @Test fun testEncryptedFileAndWrongKeyAndReopen() = runBlocking {
        System.loadLibrary("sqlcipher")
        val name = "synthetic-cipher-test.db"
        context.deleteDatabase(name)
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        fun open(pass: ByteArray) = Room.databaseBuilder(context, JunshiDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(pass.copyOf())).build()
        try {
            val db = open(key)
            ContactRepository(DataSession(db, 1)).save(Contact("synthetic", "合成加密标记"))
            db.close()
            val header = File(context.getDatabasePath(name).absolutePath).inputStream().use { it.readBytes().take(16).toByteArray() }
            assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
            val reopened = open(key)
            assertEquals("合成加密标记", ContactRepository(DataSession(reopened, 2)).get("synthetic")?.alias)
            reopened.close()
            val wrong = open(ByteArray(32) { 7 })
            var failed = false
            try { wrong.openHelper.writableDatabase } catch (_: Exception) { failed = true } finally { wrong.close() }
            assertTrue("A wrong passphrase must fail", failed)
        } finally { key.fill(0); context.deleteDatabase(name) }
    }

    @Test fun testKeystoreWrappersAndFullClearRejectLateWrites() = runBlocking {
        val manager = EncryptedDataStore(context)
        val session = manager.open()
        ContactRepository(session).save(Contact("synthetic", "仅测试"))
        val temporary = TemporarySessionStore()
        val credential = temporary.begin("temp")
        assertTrue(temporary.put(credential, TemporarySession("temp")))
        manager.vault.write("api-key-test", "synthetic-secret".toByteArray())
        assertEquals("synthetic-secret", manager.vault.read("api-key-test")!!.toString(Charsets.UTF_8))
        manager.clearAll(temporary)
        assertNull(manager.vault.read("api-key-test"))
        assertNull(temporary.get(credential))
        assertFalse(temporary.put(credential, TemporarySession("temp", draft = "迟到敏感内容")))
        val nextCredential = temporary.begin("temp")
        assertTrue(temporary.put(nextCredential, TemporarySession("temp", draft = "用户主动新建")))
        assertEquals("用户主动新建", temporary.get(nextCredential)?.draft)
        assertFalse(context.getDatabasePath("junshi.db").exists())
        var rejected = false
        try { ContactRepository(session).save(Contact("late", "迟到结果")) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertFalse(context.getDatabasePath("junshi.db").exists())
    }

    @Test fun testInterruptedWipeRetryDestroysDatabaseKeysAndFilesBeforeReopen() = runBlocking {
        // Simulate process death after the durable intent but before database deletion.
        // The database and Android Keystore here remain the real native implementations.
        val interruptedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun deleteDatabase(name: String): Boolean = error("Synthetic wipe interruption")
        }
        val interrupted = EncryptedDataStore(interruptedContext)
        val session = interrupted.open()
        ContactRepository(session).save(Contact("before-wipe", "合成旧内容"))
        interrupted.vault.write("api-key-test", "synthetic-secret".toByteArray())
        val source = File(interrupted.permanentFileRoot(), "synthetic.enc").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        }
        FileCleanupRepository(session).register("before-wipe", null, source, interrupted.permanentFileRoot())
        val temporary = TemporarySessionStore()
        val credential = temporary.begin("before-wipe")
        assertTrue(temporary.put(credential, TemporarySession("before-wipe", draft = "合成临时内容")))
        val resumed = EncryptedDataStore(context)
        try {
            var failed = false
            try { interrupted.clearAll(temporary) } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            assertTrue(interrupted.vault.hasPendingCleanup())
            assertTrue(context.getDatabasePath("junshi.db").exists())
            assertNotNull(interrupted.vault.read("database"))
            assertNull(temporary.get(credential))
            assertTrue(resumed.retryCleanup().isEmpty())
            assertFalse(resumed.vault.hasPendingCleanup())
            assertFalse(context.getDatabasePath("junshi.db").exists())
            assertNull(resumed.vault.read("database"))
            assertNull(resumed.vault.read("api-key-test"))
            assertFalse(source.exists())
            val fresh = resumed.open()
            assertNull(ContactRepository(fresh).get("before-wipe"))
            var rejected = false
            try { ContactRepository(session).save(Contact("late", "合成迟到内容")) }
            catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertNull(ContactRepository(fresh).get("late"))
        } finally { resumed.clearAll(TemporarySessionStore()) }
    }

    @Test fun testPendingWipeInvalidatesActiveSessionForBothRetryAndOpen() = runBlocking {
        for (retryFirst in listOf(true, false)) {
            val manager = EncryptedDataStore(context)
            try {
                val old = manager.open()
                ContactRepository(old).save(Contact("before-wipe", "合成旧内容"))
                manager.vault.markCleanupPending(true)
                if (retryFirst) assertTrue(manager.retryCleanup().isEmpty())
                val fresh = manager.open()
                assertTrue(fresh.generation > old.generation)
                assertNull(ContactRepository(fresh).get("before-wipe"))
                var rejected = false
                try { ContactRepository(old).save(Contact("late", "合成迟到内容")) }
                catch (_: IllegalStateException) { rejected = true }
                assertTrue(rejected)
                assertNull(ContactRepository(fresh).get("late"))
                // With no pending wipe, retry must leave the new active session usable.
                assertTrue(manager.retryCleanup().isEmpty())
                ContactRepository(fresh).save(Contact("new", "合成新内容"))
            } finally { manager.clearAll(TemporarySessionStore()) }
        }
    }
}
