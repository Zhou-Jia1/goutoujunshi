package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class T41StyleControllerTest {
    private class Provider:AnalysisProvider {
        private val delegate=CounselControllerTest.ControlledProvider()
        override val kind=ProviderKind.BYOK
        val calls get()=delegate.calls
        val outputs get()=delegate.outputs
        override fun analyze(request:AnalysisRequest)=delegate.analyze(request)
    }
    internal class Persistence(val base:CounselControllerTest.MemoryPersistence=CounselControllerTest.MemoryPersistence()):CounselPersistence by base {
        var enabled=true
        var paused=false
        var consentVersion=1L
        override suspend fun memoryContextSnapshot(owner:String?)=MemorySnapshot(consent=ConsentState(enabled=enabled,paused=paused,consentVersion=consentVersion))
        var quizWrites=0
        override suspend fun styleQuiz(answers:List<String>,stamp:WriteStamp,current:()->Boolean) {check(current());quizWrites++}
        var sampleText="虚构本人表达"
        var count=15
        var revision=0L
        var description=""
        var commits=0
        override suspend fun styleSnapshot():StyleManagementSnapshot {
            val samples=(0 until count).map {StyleSample("sample$it",RelationGroup.SOCIAL,"$sampleText$it",StyleOrigin.IMPORT,"a","batch","line$it",consentVersion=1)}
            return StyleManagementSnapshot(ConsentState(enabled=enabled,paused=paused,consentVersion=consentVersion),WriteStamp(consentVersion,revision,1),mapOf(RelationGroup.SOCIAL to
                StyleGroupSnapshot(RelationGroup.SOCIAL,samples,StyleProfile(relationGroup=RelationGroup.SOCIAL,description=description))),sourceTerms=mapOf(RelationGroup.SOCIAL to listOf("虚构来源昵称")))
        }
        override suspend fun styleGenerated(group:RelationGroup,text:String,stamp:WriteStamp,current:()->Boolean) = styleEdit(group,text,stamp,current)
        override suspend fun styleEdit(group:RelationGroup,text:String,stamp:WriteStamp,current:()->Boolean) {
            check(current() && enabled && !paused && stamp.consentVersion==consentVersion && stamp.sourceRevision==revision)
            description=text;revision++;commits++
        }
    }
    private suspend fun await(predicate:()->Boolean)=withTimeout(5_000) {while(!predicate()) yield()}
    @Test fun thresholdPreviewRequiresExplicitSendAndOneSharedInFlight()=runBlocking {
        val persistence=Persistence()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val provider=Provider()
        val observed=mutableListOf<AnalysisRequest>()
        val controller=CounselController(persistence,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest):PreparedPrompt {observed+=request;return PreparedPrompt("合成知识","冻结内容",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")}
        }),provider,scope)
        try {
            controller.initialize();await {!controller.state.value.loading}
            for(count in listOf(14,15,16)) {
                persistence.count=count
                val priorReceipt=controller.stylePage.value.receipt
                controller.openStyle();await {controller.stylePage.value.receipt!=null && controller.stylePage.value.receipt!=priorReceipt}
                val receipt=controller.stylePage.value.receipt!!
                controller.previewStyle(RelationGroup.SOCIAL,receipt)
                if(count==14) {assertNull(controller.state.value.preview);assertTrue(provider.calls.isEmpty());continue}
                await {controller.state.value.preview!=null}
                assertEquals(count-15,provider.calls.size)
                assertEquals(OutboundPurpose.STYLE_DESCRIPTION,observed.last().purpose)
                assertEquals(listOf("虚构来源昵称"),observed.last().redactionTerms)
                controller.analyze();controller.analyze();await {provider.calls.size==count-14}
                assertTrue(controller.state.value.streaming)
                provider.outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(freeText="你喜欢自然的短句，常用语气词。")))
                await {!controller.state.value.streaming}
                assertEquals(count-14,persistence.commits)
                assertFalse(controller.state.value.messages.any {it.text.contains("请用不超过200字")})
                persistence.description=""
            }
        } finally {scope.cancel()}
    }
    @Test fun staleGroupRevisionRejectsLateResultWithoutCommit()=runBlocking {
        val persistence=Persistence()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val provider=Provider()
        val controller=CounselController(persistence,TemporarySessionStore(),object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("合成知识","冻结内容",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")
        },provider,scope)
        try {
            controller.initialize();await {!controller.state.value.loading};controller.openStyle();await {controller.stylePage.value.receipt!=null}
            controller.previewStyle(RelationGroup.SOCIAL,controller.stylePage.value.receipt!!);await {controller.state.value.preview!=null}
            controller.analyze();await {provider.calls.size==1};persistence.revision++
            provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(freeText="迟到的风格描述")))
            await {!controller.state.value.streaming}
            assertEquals(0,persistence.commits)
        } finally {scope.cancel()}
    }
    @Test fun deniedQuizFeedsOnlyThisRamSessionAndLockRevokesItWithoutDatabaseWrite()=runBlocking {
        val persistence=Persistence().apply {enabled=false}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val observed=mutableListOf<AnalysisRequest>()
        val provider=Provider()
        val controller=CounselController(persistence,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest):PreparedPrompt {observed+=request;return PreparedPrompt("synthetic","preview",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")}
        }),provider,scope)
        try {
            controller.initialize();await {!controller.state.value.loading};controller.openStyle();await {controller.stylePage.value.receipt!=null}
            val answers=List(5) {"合成自己的选择$it"}
            controller.saveStyleQuiz(answers,controller.stylePage.value.receipt!!)
            await {controller.stylePage.value.temporaryQuiz.size==5}
            assertEquals(0,persistence.quizWrites)
            controller.closeStyle();controller.editDraft("合成临时问题");controller.preparePreview();await {controller.state.value.preview!=null}
            assertEquals(answers,observed.last().styleSamples.map {it.text})
            assertTrue(observed.last().styleSamples.all {it.origin==StyleOrigin.QUIZ && it.relationGroup==RelationGroup.DEFAULT && it.sourceContactId==null})
            controller.updateLockState(LockState(true,false));controller.updateLockState(LockState())
            controller.preparePreview();await {controller.state.value.preview!=null}
            assertTrue(observed.last().styleSamples.isEmpty())
            assertTrue(provider.calls.isEmpty());assertEquals(0,persistence.quizWrites)
        } finally {scope.cancel()}
    }
    @Test fun pendingModelCannotCommitAcrossPauseAndResume()=runBlocking {
        val persistence=Persistence()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val provider=Provider()
        val controller=CounselController(persistence,TemporarySessionStore(),object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","preview",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")
        },provider,scope)
        try {
            controller.initialize();await {!controller.state.value.loading};controller.openStyle();await {controller.stylePage.value.receipt!=null}
            controller.previewStyle(RelationGroup.SOCIAL,controller.stylePage.value.receipt!!);await {controller.state.value.preview!=null}
            controller.analyze();await {provider.calls.size==1}
            persistence.paused=true;persistence.consentVersion++;persistence.revision++
            persistence.paused=false;persistence.consentVersion++;persistence.revision++
            provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(freeText="旧授权发出的模型描述")))
            await {!controller.state.value.streaming}
            assertEquals(0,persistence.commits);assertEquals("",persistence.description)
        } finally {scope.cancel()}
    }
    @Test fun demoDoesNotCallOrPersistStyleAndSameSourceRemainsAvailableToRealProvider()=runBlocking {
        val persistence=Persistence()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val demoCalls=mutableListOf<AnalysisRequest>()
        val demo=object:AnalysisProvider {
            override val kind=ProviderKind.DEMO
            override fun analyze(request:AnalysisRequest)=kotlinx.coroutines.flow.flow<AnalysisChunk> {demoCalls+=request;emit(AnalysisChunk.Completed(StructuredAnswer(freeText="不能持久保存的演示描述")))}
        }
        val assembler=object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","preview",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")
        }
        try {
            val controller=CounselController(persistence,TemporarySessionStore(),assembler,demo,scope)
            controller.initialize();await {!controller.state.value.loading};controller.openStyle();await {controller.stylePage.value.receipt!=null}
            controller.previewStyle(RelationGroup.SOCIAL,controller.stylePage.value.receipt!!);await {controller.stylePage.value.modelRequired}
            assertTrue(demoCalls.isEmpty());assertEquals(0,persistence.commits);assertEquals("",persistence.description)
            val provider=Provider()
            val real=CounselController(persistence,TemporarySessionStore(),assembler,provider,scope)
            real.initialize();await {!real.state.value.loading};real.openStyle();await {real.stylePage.value.receipt!=null}
            real.previewStyle(RelationGroup.SOCIAL,real.stylePage.value.receipt!!);await {real.state.value.preview!=null}
            real.analyze();await {provider.calls.size==1}
            provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(freeText="当前真实配置返回的风格描述")))
            await {!real.state.value.streaming};assertEquals(1,persistence.commits)
        } finally {scope.cancel()}
    }
    @Test fun globalConsentStillKeepsQuizOnlyInActualTemporarySessionAndFormalCanPersist()=runBlocking {
        val persistence=Persistence()
        persistence.base.people+=Contact("existing-formal","合成已有朋友",RelationType.FRIEND)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val provider=Provider();val observed=mutableListOf<AnalysisRequest>()
        val controller=CounselController(persistence,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest):PreparedPrompt {observed+=request;return PreparedPrompt("synthetic","preview",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")}
        }),provider,scope)
        suspend fun quiz() {
            val prior=controller.stylePage.value.receipt
            controller.openStyle();await {controller.stylePage.value.receipt!=null && controller.stylePage.value.receipt!=prior}
            controller.saveStyleQuiz(List(5) {"全局授权下的当前选择$it"},controller.stylePage.value.receipt!!)
            await {controller.stylePage.value.temporaryQuiz.size==5 || persistence.quizWrites>0}
        }
        try {
            controller.initialize();await {!controller.state.value.loading && controller.state.value.currentContact?.id=="existing-formal"}
            assertTrue(controller.state.value.conversation!!.canPersist)
            controller.newTemporary();await {controller.state.value.currentContact==null && controller.state.value.conversation?.isTemporary==true}
            quiz();assertEquals(0,persistence.quizWrites)
            controller.closeStyle();controller.editDraft("临时会话仍可使用自己的选择");controller.preparePreview();await {controller.state.value.preview!=null}
            assertEquals(5,observed.last().styleSamples.size);assertTrue(observed.last().styleSamples.all {it.origin==StyleOrigin.QUIZ && it.sourceContactId==null})
            controller.updateLockState(LockState(true,false));controller.updateLockState(LockState())
            controller.preparePreview();await {controller.state.value.preview!=null};assertTrue(observed.last().styleSamples.isEmpty())
            quiz();assertEquals(0,persistence.quizWrites)
            val previous=controller.state.value.conversation!!.id
            controller.newTemporary();await {controller.state.value.conversation?.id!=previous}
            controller.editDraft("切换后的新临时问题");controller.preparePreview();await {controller.state.value.preview!=null}
            assertTrue(observed.last().styleSamples.isEmpty());assertEquals(0,persistence.quizWrites);assertTrue(provider.calls.isEmpty())
            controller.selectContact("existing-formal");await {controller.state.value.currentContact?.id=="existing-formal" && !controller.state.value.loading}
            quiz();assertEquals(1,persistence.quizWrites)
        } finally {scope.cancel()}
    }
    @Test fun groupDescriptionRoutesSelectedGroupWithoutBorrowingCurrentConversationRelation()=runBlocking {
        val base=Persistence()
        val persistence=object:CounselPersistence by base {
            override suspend fun styleSnapshot():StyleManagementSnapshot {
                val source=base.styleSnapshot();val group=source.groups.getValue(RelationGroup.SOCIAL)
                return source.copy(groups=mapOf(RelationGroup.WORK to group.copy(group=RelationGroup.WORK,samples=group.samples.map {it.copy(relationGroup=RelationGroup.WORK)},profile=group.profile?.copy(relationGroup=RelationGroup.WORK))),sourceTerms=mapOf(RelationGroup.WORK to listOf("虚构工作来源")))
            }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val requests=mutableListOf<AnalysisRequest>()
        val controller=CounselController(persistence,TemporarySessionStore(),object:PromptAssembler {
            override fun prepare(request:AnalysisRequest):PreparedPrompt {requests+=request;return PreparedPrompt("synthetic","preview",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")}
        },Provider(),scope)
        try {
            controller.initialize();await {!controller.state.value.loading};controller.setRelation(RelationType.PARENT)
            controller.openStyle();await {controller.stylePage.value.receipt!=null}
            controller.previewStyle(RelationGroup.WORK,controller.stylePage.value.receipt!!);await {controller.state.value.preview!=null}
            assertEquals(RelationGroup.WORK,requests.last().relationType!!.group())
            assertEquals(OutboundPurpose.STYLE_DESCRIPTION,requests.last().purpose)
            assertTrue(requests.last().evidence.isEmpty());assertEquals(OutboundDataContext(),requests.last().actualDataContext)
        } finally {scope.cancel()}
    }
    @Test fun styleBiasPreferencesAreGroupScoped() {
        val preferences=MemoryStyleBiasPreferences()
        preferences.set(RelationGroup.WORK,0.2f);preferences.set(RelationGroup.FAMILY,0.8f)
        assertEquals(0.2f,preferences.get(RelationGroup.WORK));assertEquals(0.8f,preferences.get(RelationGroup.FAMILY));assertEquals(0.5f,preferences.get(RelationGroup.DEFAULT))
    }
}
