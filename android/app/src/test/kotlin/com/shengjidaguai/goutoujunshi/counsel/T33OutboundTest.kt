package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.importing.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class T33OutboundTest {
    private fun builder():PromptBuilder {
        val root=File(System.getProperty("junshi.repositoryRoot")!!)
        val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
        return PromptBuilder(KnowledgeLibrary(KnowledgeSource { path ->
            val base=if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets")
            File(base,path.removePrefix("knowledge/")).readBytes()
        }))
    }
    @Test fun unresolvedCandidatesNeverEnterRealPayloadAndRapidClicksMakeOneHttpCall()=runBlocking {
        for(scenario in listOf("同名","跨应用同名","改名","无标题","群聊")) MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBodyDelay(700,TimeUnit.MILLISECONDS)
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"[[REPLY]]合成回复\"}}]}\n\ndata: [DONE]\n\n"))
            val base=CounselControllerTest.MemoryPersistence()
            base.people+=Contact("candidate","虚构真昵称甲",RelationType.FRIEND)
            val reads=mutableListOf<String?>()
            val persistence=object:CounselPersistence by base {
                override suspend fun memoryContextSnapshot(owner:String?) = memorySnapshot(owner)
                override suspend fun memorySnapshot(owner:String?):com.shengjidaguai.goutoujunshi.data.MemorySnapshot {
                    reads+=owner;return base.memorySnapshot(owner)
                }
            }
            val settings=object:ModelSettingsRepository {
                override suspend fun load()=ModelSettingsSnapshot(SavedModelSettings(true,server.url("/v1/").toString(),"synthetic","Mock","r1"),"masked")
                override suspend fun save(settings:SavedModelSettings,newKey:String?,removeKey:Boolean)=load()
                override suspend fun clear() {}
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val frozen=FrozenPromptAssembler(builder())
            val provider=OpenAiCompatibleProvider(ModelConnection(server.url("/v1/").toString(),"synthetic","Mock"),frozen,CredentialSource { "sk-synthetic".toByteArray() },allowLocalhostHttpForTests=true)
            val controller=CounselController(persistence,TemporarySessionStore(),frozen,provider,scope,settings,{provider})
            try {
                val imports=ImportCoordinator(controller,scope,{true},{})
                val previewId=imports.beginCapture(33) { true }!!
                imports.captured(previewId,listOf(com.shengjidaguai.goutoujunshi.domain.ocr.OcrLine("虚构真昵称甲：请帮忙 $scenario",.99f,.1f,.3f,.4f,.4f)))
                imports.target(ImportTarget())
                imports.side(previewId,com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT)
                val row=imports.state.value!!.rows.first()
                imports.edit(0,row.copy(name="虚构真昵称甲"))
                imports.confirm()
                withTimeout(5000) { while(imports.state.value!=null) yield() }
                val origin=controller.state.value.conversation?.id
                if(scenario=="群聊") {
                    val capture=controller.state.value.floatingCapture!!
                    assertEquals(0,server.requestCount)
                    controller.switchFloatingSide(origin,capture.id)
                    withTimeout(5000) { while(controller.state.value.floatingCapture?.id==capture.id || controller.state.value.preview==null) yield() }
                    assertEquals(com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.LEFT,controller.state.value.floatingCapture!!.mySide)
                    assertEquals(0,server.requestCount)
                }
                if(scenario=="改名") {
                    val receipt=controller.state.value.floatingCapture!!
                    check(imports.prepareCaptureMore(origin,receipt.id,33))
                    val more=imports.beginCapture(33) {true}!!
                    imports.captured(more,listOf(com.shengjidaguai.goutoujunshi.domain.ocr.OcrLine("新的合成消息",.99f,.1f,.3f,.4f,.4f)))
                    imports.target(ImportTarget());imports.floatingSource(FloatingSourceContext("另一应用","另一账号"));imports.continueCaptureMore();imports.side(more,com.shengjidaguai.goutoujunshi.domain.ocr.OcrSide.RIGHT)
                    assertNull(imports.state.value!!.captureContinuationId)
                    imports.confirm();withTimeout(5000) { while(imports.state.value!=null) yield() }
                }
                val actualOrigin=controller.state.value.conversation?.id
                controller.preparePreview(actualOrigin,true)
                withTimeout(5000) { while(controller.state.value.outboundBody==null) yield() }
                val preview=controller.state.value.outboundBody
                val rendered=controller.state.value.floatingPreviewRequest
                repeat(10) { controller.analyzeFloating(actualOrigin,rendered) }
                val received=server.takeRequest(5,TimeUnit.SECONDS)!!
                val payload=received.body.readUtf8()
                assertEquals(preview,payload);if(scenario=="改名") { assertFalse(payload.contains("请帮忙"));assertTrue(payload.contains("新的合成消息")) };assertFalse(payload.contains("虚构真昵称甲"));assertFalse(payload.contains("candidate"))
                assertFalse(reads.contains("candidate"));assertTrue(controller.state.value.currentRequest!!.floating)
                assertEquals(1,server.requestCount)
                if(scenario=="群聊") assertTrue(payload.contains("我："))
            } finally { scope.cancel() }
        }
    }
}
