package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.data.MemorySnapshot
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The actor runs on a different real thread; gates expose suspend/resume races deterministically. */
class T31LockAuthorityTest {
    private class Gate {
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        suspend fun hold() { entered.complete(Unit); release.await() }
    }
    private class Persistence(val base:CounselControllerTest.MemoryPersistence=CounselControllerTest.MemoryPersistence()):CounselPersistence by base {
        @Volatile var initializeGate:Gate?=null
        @Volatile var loadGate:Gate?=null
        @Volatile var completionGate:Gate?=null
        val reads=AtomicInteger()
        val gateAt=AtomicInteger(Int.MAX_VALUE)
        val memoryGate=Gate()
        override suspend fun initialize() { initializeGate?.hold(); base.initialize() }
        override suspend fun loadOrCreate(contact:Contact):StoredCounsel { loadGate?.hold(); return base.loadOrCreate(contact) }
        override suspend fun memorySnapshot(owner:String?):MemorySnapshot {
            if(reads.incrementAndGet()==gateAt.get()) memoryGate.hold()
            return base.memorySnapshot(owner)
        }
        override suspend fun saveRun(contact:Contact,run:AnalysisRun) {
            if(run.status==RunStatus.COMPLETED) completionGate?.hold()
            base.saveRun(contact,run)
        }
    }
    private class Fixture(live:Boolean=false):AutoCloseable {
        val p=Persistence()
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val calls=CopyOnWriteArrayList<AnalysisRequest>()
        val responses=CopyOnWriteArrayList<Channel<AnalysisChunk>>()
        private val provider=object:AnalysisProvider {
            override val kind=if(live) ProviderKind.BYOK else ProviderKind.DEMO
            override fun analyze(request:AnalysisRequest)=flow {
                val channel=Channel<AnalysisChunk>(Channel.UNLIMITED)
                responses+=channel; calls+=request
                for(chunk in channel) emit(chunk)
            }
        }
        private val settings=object:ModelSettingsRepository {
            override suspend fun load()=ModelSettingsSnapshot(SavedModelSettings(live,"https://example.org/v1/","synthetic","Mock","r1"),"masked")
            override suspend fun save(settings:SavedModelSettings,newKey:String?,removeKey:Boolean)=load()
            override suspend fun clear() {}
        }
        val controller=CounselController(p,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic system","synthetic ${request.purpose}",listOf(KnowledgeReference("synthetic","a".repeat(64),1)),"v1")
        }),provider,scope,settings,{provider})
        suspend fun setup(summary:Boolean=false) {
            if(summary) {
                val contact=Contact("a","虚构甲",RelationType.FRIEND)
                p.base.people+=contact
                p.base.saved[contact.id]=StoredCounsel(Conversation("conversation:a","a",false),"虚构问题",emptyList(),evidence=listOf(
                    EvidenceBlock("synthetic","PASTE",(1..1000).map { ChatLine("synthetic:$it","synthetic",it,Speaker.ME,"虚构记录".repeat(20),speakerConfirmed=true,contactId="a") })))
            }
            controller.initialize(); until { controller.state.value.conversation!=null && !controller.state.value.loading }
            controller.editDraft("虚构问题"); until { controller.state.value.draft=="虚构问题" }
        }
        suspend fun drain() {
            val barrier=CompletableDeferred<Unit>()
            controller.retryStartup { barrier.complete(Unit) }
            withTimeout(5000) { barrier.await() }
            withContext(dispatcher) { Unit }
        }
        override fun close() { scope.cancel(); dispatcher.close() }
    }
    private companion object {
        suspend fun until(predicate:()->Boolean) = withTimeout(5000) { while(!predicate()) delay(1) }
    }
    @Test fun suspendedInitializeCannotDisableAnEnabledLock()=runBlocking {
        Fixture().use { f ->
            val gate=Gate(); f.p.initializeGate=gate
            f.controller.initialize(); withTimeout(5000) { gate.entered.await() }
            f.controller.updateLockState(LockState(enabled=true))
            gate.release.complete(Unit); f.drain()
            assertEquals(LockState(enabled=true),f.controller.state.value.lockState)
        }
    }
    @Test fun suspendedInitialContactLoadCannotDisableAnEnabledLock()=runBlocking {
        Fixture().use { f ->
            f.p.base.people+=Contact("a","虚构甲",RelationType.FRIEND)
            val gate=Gate(); f.p.loadGate=gate
            f.controller.initialize(); withTimeout(5000) { gate.entered.await() }
            f.controller.updateLockState(LockState(enabled=true))
            gate.release.complete(Unit); f.drain()
            assertEquals(LockState(enabled=true),f.controller.state.value.lockState)
        }
    }
    @Test fun unlockedLockRemainsEnabledAcrossSessionChanges()=runBlocking {
        Fixture().use { f ->
            f.setup(); f.controller.updateLockState(LockState(enabled=true,unlocked=true))
            f.controller.newTemporary(); f.drain()
            assertEquals(LockState(enabled=true,unlocked=true),f.controller.state.value.lockState)
            f.controller.createContact("虚构乙",RelationType.FRIEND); f.drain()
            assertEquals(LockState(enabled=true,unlocked=true),f.controller.state.value.lockState)
        }
    }
    @Test fun stalePublicationFromAnotherThreadCannotReplaceTheAuthorityLock()=runBlocking {
        Fixture().use { f ->
            f.setup(); val stale=f.controller.state.value.copy(draft="虚构旧状态")
            f.controller.updateLockState(LockState(enabled=true))
            withContext(Dispatchers.Default) {
                CounselController::class.java.getDeclaredMethod("set",CounselState::class.java).apply { isAccessible=true }.invoke(f.controller,stale)
            }
            assertEquals(LockState(enabled=true),f.controller.state.value.lockState)
        }
    }
    private suspend fun revokedStart(mode:String,unlockAgain:Boolean,requestRead:Boolean=false) {
        Fixture(live=mode=="summary").use { f ->
            f.setup(summary=mode=="summary")
            if(mode=="rewrite") {
                f.controller.analyze(); until { f.calls.size==1 }
                f.responses.single().send(AnalysisChunk.Completed(StructuredAnswer(id="synthetic-answer",reply=listOf("虚构回复"))))
                until { f.controller.state.value.answer!=null && !f.controller.state.value.streaming }
                f.calls.clear()
                f.p.gateAt.set(f.p.reads.get()+2)
                f.controller.rewrite(f.controller.state.value.conversation!!.id,"synthetic-answer",Tone.WARM,.5f)
            } else {
                if(!requestRead) {
                    f.controller.preparePreview(); until { f.controller.state.value.preview!=null }
                    if(mode=="summary") assertEquals(OutboundPurpose.LONG_RECORD_SUMMARY,f.controller.state.value.currentRequest?.purpose)
                }
                f.p.gateAt.set(f.p.reads.get()+1)
                f.controller.analyze()
            }
            withTimeout(5000) { f.p.memoryGate.entered.await() }
            f.controller.updateLockState(LockState(enabled=true))
            if(unlockAgain) f.controller.updateLockState(LockState(enabled=true,unlocked=true))
            f.p.memoryGate.release.complete(Unit); f.drain()
            assertTrue("revoked $mode may not invoke a provider",f.calls.isEmpty())
            assertFalse(f.controller.state.value.streaming)
            assertNull(f.controller.state.value.preview)
            assertNull(f.controller.state.value.currentRequest)
            for(field in listOf("frozen","previewRequest","recovery")) {
                assertNull(field,CounselController::class.java.getDeclaredField(field).apply { isAccessible=true }.get(f.controller))
            }
        }
    }
    @Test fun analysisSnapshotCannotResignAfterLock()=runBlocking { revokedStart("analysis",false) }
    @Test fun analysisSnapshotCannotResignAfterLockThenUnlock()=runBlocking { revokedStart("analysis",true) }
    @Test fun summarySnapshotCannotResignAfterLock()=runBlocking { revokedStart("summary",false) }
    @Test fun summarySnapshotCannotResignAfterLockThenUnlock()=runBlocking { revokedStart("summary",true) }
    @Test fun rewriteSnapshotCannotResignAfterLock()=runBlocking { revokedStart("rewrite",false) }
    @Test fun rewriteSnapshotCannotResignAfterLockThenUnlock()=runBlocking { revokedStart("rewrite",true) }
    @Test fun requestReadBeforeStartCannotAcquireNewPermissionAfterUnlock()=runBlocking { revokedStart("analysis",true,requestRead=true) }
    @Test fun completionCheckpointCannotPublishAnAnswerAfterLockThenUnlock()=runBlocking {
        Fixture().use { f ->
            f.setup(); f.controller.createContact("虚构甲",RelationType.FRIEND); f.drain()
            f.controller.editDraft("虚构问题"); f.drain()
            f.controller.analyze(); until { f.calls.size==1 }
            val gate=Gate(); f.p.completionGate=gate
            f.responses.single().send(AnalysisChunk.Completed(StructuredAnswer(id="synthetic-answer",reply=listOf("虚构迟到回复"))))
            withTimeout(5000) { gate.entered.await() }
            f.controller.updateLockState(LockState(enabled=true))
            f.controller.updateLockState(LockState(enabled=true,unlocked=true))
            gate.release.complete(Unit); f.drain()
            assertNull(f.controller.state.value.answer)
            assertNull(f.controller.state.value.currentRequest)
            assertFalse(f.controller.state.value.streaming)
        }
    }
}
