package com.shengjidaguai.goutoujunshi.privacy

import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*

class T55ClearSettingsRaceTest {
    @Test fun clearedTipsPageAcknowledgesEmptyWithoutReadingOrReopeningPersistence() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val base=CounselControllerTest.MemoryPersistence();var reads=0
        val persistence=object:CounselPersistence by base {override suspend fun tips():List<Tip> {reads++;error("cleared storage must not be read")}}
        val provider=object:AnalysisProvider {override val kind=ProviderKind.DEMO;override fun analyze(request:AnalysisRequest)=emptyFlow<AnalysisChunk>()}
        val c=CounselController(persistence,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"v1")}),provider,scope)
        try {
            c.clearAll();c.openTips()
            assertEquals(0,reads);assertNotNull(c.state.value.tipsReceipt);assertTrue(c.state.value.savedTips.isEmpty());assertNull(c.state.value.conversation);assertTrue(c.state.value.contacts.isEmpty());assertFalse(c.state.value.loading);assertNull(c.state.value.error)
        } finally {scope.cancel()}
    }
    @Test fun lateOldTipsLoadCannotPublishAfterClearAndNewEmptyReadCanAcknowledge()=lateTips(false)
    @Test fun lateOldTipsFailureCannotPolluteClearStateAndNewEmptyReadCanAcknowledge()=lateTips(true)
    private fun lateTips(failure:Boolean)=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val base=CounselControllerTest.MemoryPersistence();val gate=CompletableDeferred<Unit>();var reads=0
        val persistence=object:CounselPersistence by base {override suspend fun tips():List<Tip> {reads++;gate.await();if(failure) error("synthetic late tips failure");return listOf(Tip("synthetic-old-tip","合成旧技巧"))}}
        val provider=object:AnalysisProvider {override val kind=ProviderKind.DEMO;override fun analyze(request:AnalysisRequest)=emptyFlow<AnalysisChunk>()}
        val c=CounselController(persistence,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"v1")}),provider,scope)
        var clearing=false;var publications=0
        val observer=scope.launch(object:CoroutineDispatcher() {override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable)=block.run()},start=CoroutineStart.UNDISPATCHED) {c.state.collect {if(clearing && (it.savedTips.isNotEmpty() || it.error!=null)) publications++}}
        try {
            c.openTips();assertEquals(1,reads);clearing=true;c.clearAll();gate.complete(Unit);yield()
            assertEquals(0,publications);assertNull(c.state.value.tipsReceipt)
            c.openTips();assertNotNull(c.state.value.tipsReceipt);assertEquals(1,reads)
            assertTrue(c.state.value.savedTips.isEmpty());assertNull(c.state.value.conversation);assertTrue(c.state.value.contacts.isEmpty());assertFalse(c.state.value.loading);assertNull(c.state.value.error)
        } finally {observer.cancel();scope.cancel()}
    }
    @Test fun suspendedModelSettingsSaveCannotRepublishPrivateConfigurationDuringClear()=exercise("success")
    @Test fun suspendedModelSettingsFailureCannotRepublishPrivateConfigurationDuringClear()=exercise("failure")
    @Test fun canceledSettingsSaveCannotKillTheWipeActor()=exercise("cancel")
    private fun exercise(outcome:String)=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val gate=CompletableDeferred<Unit>()
        val repository=object:ModelSettingsRepository {
            override suspend fun load()=ModelSettingsSnapshot()
            override suspend fun save(settings:SavedModelSettings,newKey:String?,removeKey:Boolean):ModelSettingsSnapshot {
                gate.await()
                if(outcome=="failure") error("synthetic save failure")
                if(outcome=="cancel") throw CancellationException("synthetic canceled old write")
                return ModelSettingsSnapshot(settings.copy(revision="synthetic-old"),"synthetic-mask")
            }
            override suspend fun clear() {}
        }
        val provider=object:AnalysisProvider {override val kind=ProviderKind.DEMO;override fun analyze(request:AnalysisRequest)=emptyFlow<AnalysisChunk>()}
        val c=CounselController(CounselControllerTest.MemoryPersistence(),TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"v1")
        }),provider,scope,repository,{provider})
        var clearing=false;var privatePublications=0
        val observer=scope.launch(object:CoroutineDispatcher() {override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable)=block.run()},start=CoroutineStart.UNDISPATCHED) {
            c.state.collect {if(clearing && (it.modelSettings.maskedKey!=null || it.modelSettings.saved.label=="synthetic-private-provider")) privatePublications++}
        }
        try {
            c.initialize()
            c.saveModelSettings(SavedModelSettings(false,"https://example.org/v1/","synthetic","synthetic-private-provider"),"synthetic-secret")
            clearing=true;c.clearAll();gate.complete(Unit);yield()
            assertEquals("clear must block every late private settings publication",0,privatePublications)
            assertFalse("old cancellation must leave the wipe actor alive",c.state.value.loading)
            assertNull(c.state.value.error)
        } finally {observer.cancel();scope.cancel()}
    }
    @Test fun genuineCleanupFailureRemainsVisibleAndExplicitRetryStillWorks() {
        cleanupFailure(false)
    }
    @Test fun genuineCleanupCancellationRemainsVisibleAndExplicitRetryStillWorks() {
        cleanupFailure(true)
    }
    private fun cleanupFailure(canceled:Boolean) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val base=CounselControllerTest.MemoryPersistence();var fail=true
        val persistence=object:CounselPersistence by base {override suspend fun clearAll():Boolean {if(fail) {fail=false;if(canceled) throw CancellationException("synthetic cleanup cancellation") else error("synthetic cleanup failure")};return base.clearAll()}}
        val provider=object:AnalysisProvider {override val kind=ProviderKind.DEMO;override fun analyze(request:AnalysisRequest)=emptyFlow<AnalysisChunk>()}
        val c=CounselController(persistence,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"v1")}),provider,scope)
        try {
            c.clearAll();assertFalse(c.state.value.loading);assertEquals(if(canceled) "CLEANUP_INCOMPLETE" else "STORAGE_UNAVAILABLE",c.state.value.error?.code);assertTrue(c.state.value.error?.retryable==true)
            c.retry();assertFalse(c.state.value.loading);assertNull(c.state.value.error);assertNull(c.state.value.conversation)
        } finally {scope.cancel()}
    }
}
