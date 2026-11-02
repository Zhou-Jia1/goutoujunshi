package com.shengjidaguai.goutoujunshi.data

import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class T33MemoryContextQueryTest {
    @Test fun formalContextReadsOnlyCurrentOwnerWhileExplicitManagementRetainsGlobalAuthority()=runBlocking {
        val trace=CopyOnWriteArrayList<Pair<String,List<Any?>>>()
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java)
            .setQueryCallback({sql,args -> trace+=(sql.lowercase() to args.toList())},Executor {it.run()}).build()
        val session=DataSession(db,1)
        try {
            for(owner in listOf("a","b")) {
                ContactRepository(session).save(Contact(owner,"合成代号$owner"))
                ImportRepository(session).save(ChatImport("$owner-import",owner,ImportSource.PASTE,"$owner-hash"),listOf(ChatLine("$owner-line","$owner-import",text="合成原文",contactId=owner)))
                session.transaction { put(MemoryRow("$owner-memory",owner,encode(MemoryEntry("$owner-memory",owner,value="合成事实$owner",sourceType=MemorySource.USER_EXPLICIT)))) }
            }
            trace.clear()
            val context=MemoryRepository(session).contextSnapshot("a")
            val privateTables=Regex("\\b(imports|chat_lines|memories|memory_operations|owned_records)\\b")
            val reads=trace.filter { it.first.trimStart().startsWith("select") && privateTables.containsMatchIn(it.first) }
            assertTrue("Formal context read another owner's private sources",reads.none { it.second.contains("b") })
            assertTrue(reads.any { it.second.contains("a") })
            assertEquals(listOf("合成事实a"),context.entries.map { it.value });assertNull(context.clearAuthority)
            trace.clear()
            assertNotNull(MemoryRepository(session).snapshot("a").clearAuthority)
            assertTrue(trace.any { privateTables.containsMatchIn(it.first) && it.second.contains("b") })
        } finally { db.close() }
    }
    @Test fun temporaryContextReadsOnlyConsentAndVersionsWhileManagementStillReadsClearAuthority()=runBlocking {
        val trace=CopyOnWriteArrayList<String>()
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java)
            .setQueryCallback({sql,_ -> trace+=sql.lowercase()},Executor {it.run()}).build()
        val session=DataSession(db,1)
        try {
            ContactRepository(session).save(Contact("candidate","合成候选"))
            ImportRepository(session).save(ChatImport("candidate-import","candidate",ImportSource.PASTE,"candidate-hash"),listOf(ChatLine("candidate-line","candidate-import",text="合成候选原文",contactId="candidate")))
            session.transaction { put(MemoryRow("candidate-memory","candidate",encode(MemoryEntry("candidate-memory","candidate",value="合成候选事实",sourceType=MemorySource.USER_EXPLICIT)))) }
            trace.clear()
            val context=MemoryRepository(session).contextSnapshot(null)
            val forbidden=Regex("\\b(contacts|imports|chat_lines|memories|memory_operations|owned_records|style_samples|style_profiles)\\b")
            assertTrue("Context query touched a candidate source table",trace.none { forbidden.containsMatchIn(it) })
            assertTrue("Query spy must observe the actual metadata read",trace.any { it.contains("consent") })
            assertTrue(context.entries.isEmpty());assertNull(context.clearAuthority)
            trace.clear()
            val management=MemoryRepository(session).snapshot(null)
            assertNotNull(management.clearAuthority)
            assertTrue(trace.any { it.contains("memories") });assertTrue(trace.any { it.contains("imports") })
        } finally { db.close() }
    }
}
