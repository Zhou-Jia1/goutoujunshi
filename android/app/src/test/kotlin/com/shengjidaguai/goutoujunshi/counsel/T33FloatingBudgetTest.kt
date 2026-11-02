package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import com.shengjidaguai.goutoujunshi.importing.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class T33FloatingBudgetTest {
    private suspend fun await(check:()->Boolean)=withTimeout(5000) { while(!check()) yield() }
    private class Fixture(lines:List<ChatLine>) : AutoCloseable {
        val server=MockWebServer()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val base=CounselControllerTest.MemoryPersistence()
        val controller:CounselController
        init {
            repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: [DONE]\n\n")) }
            val root=File(System.getProperty("junshi.repositoryRoot")!!)
            val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
            val frozen=FrozenPromptAssembler(PromptBuilder(KnowledgeLibrary(KnowledgeSource { path ->
                val dir=if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets")
                File(dir,path.removePrefix("knowledge/")).readBytes()
            })))
            val settings=object:ModelSettingsRepository {
                override suspend fun load()=ModelSettingsSnapshot(SavedModelSettings(true,server.url("/v1/").toString(),"synthetic","Mock","r1"),"masked")
                override suspend fun save(settings:SavedModelSettings,newKey:String?,removeKey:Boolean)=load()
                override suspend fun clear() {}
            }
            val provider=OpenAiCompatibleProvider(ModelConnection(server.url("/v1/").toString(),"synthetic","Mock"),frozen,CredentialSource { "sk-synthetic".toByteArray() },allowLocalhostHttpForTests=true)
            base.people+=Contact("a","合成甲",RelationType.FRIEND)
            base.saved["a"]=StoredCounsel(Conversation("budget-conversation","a",false),"合成问题",emptyList(),if(lines.isEmpty()) emptyList() else listOf(EvidenceBlock("budget-evidence","synthetic",lines)))
            controller=CounselController(base,TemporarySessionStore(),frozen,provider,scope,settings,{provider})
            controller.initialize()
        }
        suspend fun ready()=withTimeout(5000) { while(controller.state.value.loading || controller.state.value.conversation==null || controller.state.value.currentContact?.id!="a") yield() }
        override fun close() { scope.cancel();server.close() }
    }
    private fun lines(count:Int=100,text:String="合成文字".repeat(30))=List(count) { ChatLine("line-$it","budget-evidence",it,speaker=Speaker.ME,text=text,speakerConfirmed=true,contactId="a") }
    private suspend fun fallback(f:Fixture) {
        val c=f.controller
        c.preparePreview(c.state.value.conversation?.id,true)
        await { c.state.value.preview!=null || c.state.value.error!=null }
        // Exercise the actual send entry as well: a rejected preview never authorizes HTTP.
        if(c.state.value.preview!=null) { c.analyze();await { f.server.requestCount>0 || c.state.value.error!=null } }
        assertEquals("FLOATING_OPEN_FULL",c.state.value.error?.code)
        assertNull(c.state.value.preview);assertNull(c.state.value.outboundBody);assertNull(c.state.value.currentRequest)
        assertEquals(0,f.server.requestCount)
    }
    @Test fun floatingLongRecordOffersFullAnalysisWithoutDroppingEarlierLinesOrSending()=runBlocking {
        Fixture(lines()).use { f -> f.ready();fallback(f) }
    }
    @Test fun floatingOversizeLatestLineOffersSameZeroHttpFallback()=runBlocking {
        Fixture(lines(1,"合".repeat(13000))).use { f -> f.ready();fallback(f) }
    }
    @Test fun mainSummaryPreviewCannotBeRetaggedAsFloatingAndMainStillKeepsSummaryFlow()=runBlocking {
        Fixture(lines()).use { f ->
            f.ready();val c=f.controller
            c.preparePreview();await { c.state.value.preview!=null }
            assertEquals(OutboundPurpose.LONG_RECORD_SUMMARY,c.state.value.currentRequest?.purpose)
            fallback(f)
            c.preparePreview();await { c.state.value.preview!=null }
            assertEquals(OutboundPurpose.LONG_RECORD_SUMMARY,c.state.value.currentRequest?.purpose)
            assertEquals(0,f.server.requestCount)
        }
    }
    @Test fun shortFloatingRewriteKeepsItsPurposeAndParent()=runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            val c=f.controller;c.initialize();c.editDraft("合成问题");c.analyze()
            await { f.provider.calls.size==1 }
            f.provider.outputs[0].send(AnalysisChunk.Completed(StructuredAnswer(id="answer-a",reply=listOf("合成回复"))))
            await { c.state.value.answer!=null }
            var rewrite:AnalysisRequest?=null
            c.rewrite(c.state.value.conversation?.id,"answer-a",Tone.WARM,.5f,true) { rewrite=it }
            await { rewrite!=null }
            c.preparePreview(c.state.value.conversation?.id,true) { rewrite=it }
            await { c.state.value.floatingPreviewReady }
            assertEquals(OutboundPurpose.REPLY_REWRITE,rewrite!!.purpose)
            assertEquals(RequestMode.REWRITE_REPLY,rewrite!!.mode);assertEquals("answer-a",rewrite!!.parentAnswerId)
            c.analyze();await { f.provider.calls.size==2 }
            assertEquals(OutboundPurpose.REPLY_REWRITE,f.provider.calls[1].purpose)
        } finally { f.close() }
    }

    @Test fun formalMoreSourceConflictSendsNoOldRawAndChangingToTemporarySendsOnlyNewFrame()=runBlocking {
        Fixture(emptyList()).use { f ->
            f.ready();val c=f.controller;val imports=ImportCoordinator(c,f.scope,{true},{})
            val first=imports.beginCapture(33) {true}!!
            imports.captured(first,listOf(OcrLine("旧联系人私有原话",.99f,.1f,.2f,.4f,.3f)))
            imports.target(ImportTarget(c.state.value.contacts.first {it.id=="a"}));imports.side(first,OcrSide.RIGHT);imports.confirm()
            await {imports.state.value==null && c.state.value.floatingCapture!=null}
            val before=c.state.value
            assertTrue(imports.prepareCaptureMore(before.conversation?.id,before.floatingCapture!!.id,33))
            val next=imports.beginCapture(33) {true}!!
            imports.captured(next,listOf(OcrLine("另一个来源的新消息",.99f,.1f,.2f,.4f,.3f)))
            imports.target(ImportTarget(c.state.value.contacts.first {it.id=="a"}));imports.floatingSource(FloatingSourceContext("另一个应用","另一个账号"));imports.continueCaptureMore();imports.side(next,OcrSide.RIGHT)
            assertFalse(imports.state.value!!.canConfirm);imports.confirm();assertNotNull(imports.state.value)
            assertEquals(0,f.server.requestCount)
            imports.target(ImportTarget());imports.side(next,OcrSide.RIGHT);imports.confirm();await {imports.state.value==null}
            c.preparePreview(c.state.value.conversation?.id,true);await {c.state.value.floatingPreviewReady}
            val body=checkNotNull(c.state.value.outboundBody)
            assertFalse(body.contains("旧联系人私有原话"));assertTrue(body.contains("另一个来源的新消息"));assertEquals("",c.state.value.draft)
            c.analyze();await {f.server.requestCount==1}
            assertEquals(body,f.server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(1,f.base.imports.size)
        }
    }

}
