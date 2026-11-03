package com.shengjidaguai.goutoujunshi.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.domain.Contact
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T55CleanupDeviceTest {
    @Test fun platformFailureRevokesSessionAndDurableRetryReplaysBothCleanupPhases():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().context
        var stops=0;var finishes=0
        val interrupted=EncryptedDataStore(context,{stops++;error("synthetic platform interruption")},{finishes++})
        val old=interrupted.open()
        ContactRepository(old).save(Contact("t55-synthetic","合成旧资料"))
        try {
            try {interrupted.clearAll(TemporarySessionStore());fail("must retain pending cleanup")} catch(_:IllegalStateException) {}
            assertFalse(old.active);assertTrue(interrupted.vault.hasPendingCleanup());assertEquals(0,finishes)
            try {ContactRepository(old).save(Contact("late","合成迟到"));fail("old session must reject writes")} catch(_:IllegalStateException) {}
            val resumed=EncryptedDataStore(context,{stops++},{finishes++})
            assertTrue(resumed.retryCleanup().isEmpty())
            assertEquals(2,stops);assertEquals(1,finishes);assertFalse(resumed.vault.hasPendingCleanup())
            assertNull(resumed.vault.read("database"));assertFalse(context.getDatabasePath("junshi.db").exists())
            val fresh=resumed.open();assertTrue(ContactRepository(fresh).list().isEmpty())
            resumed.clearAll(TemporarySessionStore())
        } finally {EncryptedDataStore(context).clearAll(TemporarySessionStore())}
    }
    @Test fun preferenceFailureAfterKeyDestructionRetainsMarkerAndReplaysOnOpen():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().context
        val interrupted=EncryptedDataStore(context,{}, {error("synthetic preference interruption")})
        val old=interrupted.open();ContactRepository(old).save(Contact("t55-synthetic","合成旧资料"))
        try {
            try {interrupted.clearAll(TemporarySessionStore());fail("must retain pending cleanup")} catch(_:IllegalStateException) {}
            assertTrue(interrupted.vault.hasPendingCleanup());assertNull(interrupted.vault.read("database"));assertFalse(context.getDatabasePath("junshi.db").exists())
            var replayed=false
            val resumed=EncryptedDataStore(context,{}, {replayed=true})
            val fresh=resumed.open()
            assertTrue(replayed);assertFalse(resumed.vault.hasPendingCleanup());assertTrue(ContactRepository(fresh).list().isEmpty())
            resumed.clearAll(TemporarySessionStore())
        } finally {EncryptedDataStore(context).clearAll(TemporarySessionStore())}
    }
}
