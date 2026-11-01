package com.shengjidaguai.goutoujunshi.counsel

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.security.KeyStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Controller -> EncryptedCounselPersistence -> SQLCipher repository wiring.
 * Requires an explicitly selected test emulator; no production adapter or repository is faked. */
@RunWith(AndroidJUnit4::class)
class T43EncryptedMemoryIntegrationTest {
    private class Sandbox(base: Context): ContextWrapper(base) {
        private val nonce=UUID.randomUUID().toString().replace("-", "")
        private val root=File(base.filesDir,"t43-memory-$nonce").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "${baseContext.packageName}.t43.$nonce"
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = File(root,"cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root,"no-backup").apply { mkdirs() }
        fun preferenceFile(name: String): File = File(baseContext.applicationInfo.dataDir,"shared_prefs/t43-$nonce-$name.xml")
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences = baseContext.getSharedPreferences("t43-$nonce-$name",mode)
        override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences("t43-$nonce-$name")
        override fun getDatabasePath(name: String): File = File(root,"databases/$name").also { it.parentFile!!.mkdirs() }
        override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }
    private class Provider: AnalysisProvider {
        override val kind=ProviderKind.BYOK
        val calls=Channel<Pair<AnalysisRequest,Channel<AnalysisChunk>>>(Channel.UNLIMITED)
        override fun analyze(request: AnalysisRequest)=flow {
            val output=Channel<AnalysisChunk>(Channel.UNLIMITED)
            calls.send(request to output)
            for(chunk in output) emit(chunk)
        }
    }
    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(120_000) {
        while(!predicate()) delay(10)
    }

    @Test fun alreadyConsentedAutomaticWritesHaveVerifiedReceiptsAndSourceDeletionPrunesQuotaAnchors()=runBlocking {
        // Use only the test APK sandbox plus a unique database/files directory and Keystore alias.
        val context=Sandbox(InstrumentationRegistry.getInstrumentation().context)
        val store=EncryptedDataStore(context)
        val temporary=TemporarySessionStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            assertSame(context,context.applicationContext)
            val target=InstrumentationRegistry.getInstrumentation().targetContext
            assertNotEquals(target.packageName,context.packageName)
            assertNotEquals(target.filesDir.canonicalPath,context.filesDir.canonicalPath)
            assertNotEquals(target.getDatabasePath("junshi.db").canonicalPath,context.getDatabasePath("junshi.db").canonicalPath)
            assertTrue(context.getDatabasePath("junshi.db").canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator))
            val probe="t43-isolation-probe"
            val previousBaseProbe=context.baseContext.getSharedPreferences(probe,0).contains("synthetic-only")
            assertTrue(context.getSharedPreferences(probe,0).edit().putBoolean("synthetic-only",true).commit())
            assertEquals(previousBaseProbe,context.baseContext.getSharedPreferences(probe,0).contains("synthetic-only"))
            assertTrue(context.preferenceFile(probe).isFile)
            assertNotEquals(File(target.applicationInfo.dataDir,"shared_prefs/$probe.xml").canonicalPath,context.preferenceFile(probe).canonicalPath)
            val session=store.open()
            assertTrue(context.getDatabasePath("junshi.db").isFile)
            assertTrue(File(context.filesDir,"datastore/secret_wrappers.preferences_pb").isFile)
            val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias="${context.packageName}.secret-wrapper.v1"
            assertNotEquals("${target.packageName}.secret-wrapper.v1",alias)
            assertTrue(keys.containsAlias(alias))
            val owner="synthetic-t43-owner"
            ContactRepository(session).save(Contact(owner,"虚构来源测试"))
            val imported=ChatImport("synthetic-source",owner,ImportSource.PASTE,"synthetic-source-hash")
            val line=ChatLine("synthetic-line",imported.id,contactId=owner,speaker=Speaker.ME,text="唯一虚构来源标记",speakerConfirmed=true)
            ImportRepository(session).save(imported,listOf(line))
            ConsentRepository(session).update(true)
            val provider=Provider()
            val persistence=EncryptedCounselPersistence(store,temporary)
            val assembler=FrozenPromptAssembler(object: PromptAssembler {
                override fun prepare(request: AnalysisRequest)=PreparedPrompt("synthetic system","synthetic outbound",emptyList(),"synthetic")
            })
            val controller=CounselController(persistence,temporary,assembler,provider,scope)
            controller.initialize()
            await { !controller.state.value.loading && controller.state.value.currentContact?.id==owner }
            var root: MemoryEntry?=null
            repeat(6) { index ->
                val previousOperation=controller.memory.value.notice?.operationId
                controller.editDraft("虚构问题$index")
                await { controller.state.value.draft=="虚构问题$index" && controller.state.value.draftSaved }
                controller.analyze()
                val (request,output)=withTimeout(120_000) { provider.calls.receive() }
                assertEquals(owner,request.contactId)
                output.send(AnalysisChunk.Completed(StructuredAnswer(id="synthetic-answer-$index",reply=listOf("虚构回复$index"),memoryProposals=listOf(
                    MemoryProposal(subjectId="untrusted-model-subject",field="pattern",value="唯一虚构来源标记派生猜测$index",sourceRef="untrusted-source")
                ))))
                await { controller.memory.value.notice?.operationId?.let { it!=previousOperation }==true && controller.memory.value.entries.size==minOf(index+1,5) && controller.state.value.answer?.id=="synthetic-answer-$index" }
                if(index==0) root=controller.memory.value.entries.single()
                assertNull(controller.memory.value.consentProposal)
                assertTrue(controller.state.value.hasCurrentAnswerSource())
            }
            // This anchor can only exist if the ordinary already-consented path wrote MEMORY_SOURCE.
            // The first-approval memoryApprove branch was never called in this test.
            val repository=MemoryRepository(session)
            val before=repository.snapshot(owner)
            assertEquals(5,before.entries.size)
            assertEquals(MemoryContextPolicy.entryFingerprint(root!!),before.anchors[root!!.id])
            assertTrue(before.entries.all { it.sourceType==MemorySource.ASSISTANT_INFERENCE && it.confidence==Confidence.LOW })
            ImportRepository(session).delete(owner,imported.id)
            val after=repository.snapshot(owner)
            assertTrue(after.entries.isEmpty())
            assertTrue(after.anchors.isEmpty())
            assertTrue(repository.operations(owner).isEmpty())
        } finally {
            scope.cancel()
            store.clearAll(temporary)
            context.deleteSharedPreferences("t43-isolation-probe")
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("${context.packageName}.secret-wrapper.v1"))
        }
    }
}
