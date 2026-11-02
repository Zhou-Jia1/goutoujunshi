package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.*
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class T41StyleWireTest {
    private suspend fun await(predicate:()->Boolean)=withTimeout(10_000) {while(!predicate()) yield()}
    @Test fun actualKnowledgeUnifiedSourceRedactionAndFrozenBodyEqualWire()=runBlocking {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"[[FREE_TEXT]]喜欢自然短句与语气词\"}}]}\n\ndata: [DONE]\n\n"))
            val root=File(System.getProperty("junshi.repositoryRoot")!!)
            val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
            val assembler=FrozenPromptAssembler(PromptBuilder(KnowledgeLibrary(KnowledgeSource {path ->
                val base=if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets")
                File(base,path.removePrefix("knowledge/")).readBytes()
            })))
            val provider=OpenAiCompatibleProvider(ModelConnection(server.url("/v1/").toString(),"synthetic","Mock"),assembler,CredentialSource {"sk-synthetic".toByteArray()},allowLocalhostHttpForTests=true)
            val settings=object:ModelSettingsRepository {
                override suspend fun load()=ModelSettingsSnapshot(SavedModelSettings(true,server.url("/v1/").toString(),"synthetic","Mock","r1"),"masked")
                override suspend fun save(settings:SavedModelSettings,newKey:String?,removeKey:Boolean)=load()
                override suspend fun clear() {}
            }
            val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).build()
            val session=DataSession::class.java.declaredConstructors.single {it.parameterCount==3}.apply {isAccessible=true}.newInstance(db,1L,null) as DataSession
            val styles=StyleRepository(session)
            for((owner,name) in listOf("a" to "虚构来源昵称","b" to "另一来源昵称")) {
                ContactRepository(session).save(Contact(owner,name,RelationType.FRIEND))
            }
            ConsentRepository(session).update(true)
            for((owner,name) in listOf("a" to "虚构来源昵称","b" to "另一来源昵称")) {
                val source=ChatImport("batch-$owner",owner,ImportSource.PASTE,"synthetic-$owner")
                ImportRepository(session).save(source,(0..7).map {ChatLine("$owner:$it",source.id,seq=it,speaker=Speaker.ME,text="$name 13800138000 ${if(owner=="b") "BONLY" else "AONLY"}合成表达$it",speakerConfirmed=true,contactId=owner)})
            }
            val base=CounselControllerTest.MemoryPersistence()
            val owner=ContactRepository(session).get("a")!!
            base.people+=owner
            val evidence=EvidenceBlock("batch-a","PASTE",ImportRepository(session).lines("a","batch-a"))
            base.saved["a"]=StoredCounsel(Conversation("style-wire-conversation","a",false),"合成当前问题",emptyList(),listOf(evidence))
            val persistence=object:CounselPersistence by base {
                override suspend fun memoryContextSnapshot(owner:String?)=MemoryRepository(session).contextSnapshot(owner)
                override suspend fun styleContext(owner:String,group:RelationGroup)=styles.context(owner,group)
                override suspend fun styleSnapshot()=styles.snapshot()
                override suspend fun styleGenerated(group:RelationGroup,text:String,stamp:WriteStamp,current:()->Boolean) {styles.editDescription(group,text,stamp,generated=true,current=current)}
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val controller=CounselController(persistence,TemporarySessionStore(),assembler,provider,scope,settings,{provider})
            try {
                controller.initialize();await {!controller.state.value.loading}
                controller.newTemporary();await {controller.state.value.currentContact==null && controller.state.value.conversation?.isTemporary==true}
                controller.openStyle();await {controller.stylePage.value.receipt!=null}
                controller.previewStyle(RelationGroup.SOCIAL,controller.stylePage.value.receipt!!);await {controller.state.value.outboundBody!=null}
                val originalPreview=controller.state.value.outboundBody!!
                val originalRequest=controller.state.value.currentRequest!!.requestId
                assertTrue(originalPreview.contains("合成表达0"))
                val sourcesBeforeRules=styles.snapshot()
                controller.saveRedactionProfile(controller.state.value.conversation!!.id,listOf("合成表达0"),emptyList())
                await {controller.state.value.redactionProfile.revision==1L && controller.state.value.preview==null}
                assertNull(controller.state.value.outboundBody);assertNull(controller.state.value.currentRequest)
                assertEquals(sourcesBeforeRules,styles.snapshot())
                controller.analyze();await {controller.state.value.error!=null};assertEquals(0,server.requestCount)
                controller.previewStyle(RelationGroup.SOCIAL,controller.stylePage.value.receipt!!);await {controller.state.value.outboundBody!=null}
                assertNotEquals(originalRequest,controller.state.value.currentRequest!!.requestId)
                assertFalse(controller.state.value.outboundBody!!.contains("合成表达0"))
                assertNotEquals(originalPreview,controller.state.value.outboundBody)
                assertEquals(0,server.requestCount)
                val preview=controller.state.value.outboundBody!!
                assertFalse(preview.contains("虚构来源昵称"));assertFalse(preview.contains("另一来源昵称"));assertFalse(preview.contains("13800138000"))
                assertTrue(controller.state.value.preview!!.references.isNotEmpty())
                assertTrue(preview.contains("共享核心规则"));assertTrue(preview.contains("本轮仅描述样本"))
                controller.analyze()
                val captured=server.takeRequest(5,TimeUnit.SECONDS)!!
                assertEquals(preview,captured.body.readUtf8())
                assertEquals(1,server.requestCount)
                await {controller.state.value.currentRequest==null || controller.state.value.error!=null}
                assertNull(controller.state.value.error?.code,controller.state.value.error)
                assertEquals("喜欢自然短句与语气词",styles.profile(RelationGroup.SOCIAL)!!.description)
                controller.selectContact("a");await {controller.state.value.currentContact?.id=="a" && !controller.state.value.loading}
                assertTrue(controller.state.value.redactionProfile.terms.isEmpty())
                val original=styles.samples(RelationGroup.SOCIAL).single {it.sourceMessageId=="a:0"}
                styles.editSample(original.id,"AEDIT_ONLY 本人明确修改的风格表达～ ".repeat(4),styles.snapshot().stamp)
                assertEquals(original.text,styles.samples(RelationGroup.SOCIAL).single {it.id==original.id}.text)
                // The regular analysis projects 3–5 real current-owner examples, never B's raw messages.
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"[[REPLY]]合成正式回复\"}}]}\n\ndata: [DONE]\n\n"))
                controller.closeStyle();controller.editDraft("合成下一轮问题");controller.preparePreview();await {controller.state.value.outboundBody!=null}
                val analysisPreview=controller.state.value.outboundBody!!
                val user=controller.state.value.preview!!.user
                assertEquals(5,Regex("<style_sample>").findAll(user).count())
                assertTrue(user.contains("AONLY"));assertFalse(user.contains("BONLY"))
                val styleText=Regex("<style_sample>.*?</style_sample>",RegexOption.DOT_MATCHES_ALL).findAll(user).joinToString("\n") {it.value}
                assertTrue(styleText.contains("AEDIT_ONLY"));assertFalse(styleText.contains("AONLY合成表达0"))
                assertFalse(analysisPreview.contains("虚构来源昵称"));assertFalse(analysisPreview.contains("另一来源昵称"));assertFalse(analysisPreview.contains("13800138000"))
                assertEquals(1,server.requestCount)
                controller.analyze()
                assertEquals(analysisPreview,server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8())
                await {controller.state.value.answer!=null || controller.state.value.error!=null}
                assertNull(controller.state.value.error?.code,controller.state.value.error)
                assertEquals(2,server.requestCount)
                // Group sources remain plentiful, but only two belong to A: use real DEFAULT examples.
                styles.samples(RelationGroup.SOCIAL).filter {it.sourceContactId=="a"}.drop(2).forEach {styles.removeSample(it.id,styles.snapshot().stamp)}
                styles.saveQuiz(List(5) {"DEFAULTONLY 合成默认表达$it"},styles.snapshot().stamp)
                val quiz=styles.samples(RelationGroup.DEFAULT).first()
                styles.editSample(quiz.id,"DEFAULTEDIT_ONLY 用户修改的默认风格示例",styles.snapshot().stamp)
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"[[REPLY]]合成默认风格回复\"}}]}\n\ndata: [DONE]\n\n"))
                controller.editDraft("合成少样本后下一轮");controller.preparePreview();await {controller.state.value.outboundBody!=null}
                val defaultPreview=controller.state.value.outboundBody!!
                val defaultUser=controller.state.value.preview!!.user
                val defaultStyle=Regex("<style_sample>.*?</style_sample>",RegexOption.DOT_MATCHES_ALL).findAll(defaultUser).map {it.value}.toList()
                assertEquals(5,defaultStyle.size)
                assertTrue(defaultStyle.all {it.contains("DEFAULTONLY") || it.contains("DEFAULTEDIT_ONLY")})
                assertTrue(defaultStyle.any {it.contains("DEFAULTEDIT_ONLY")})
                assertFalse(defaultStyle.any {it.contains("AEDIT_ONLY") || it.contains("BONLY")})
                assertFalse(defaultPreview.contains("虚构来源昵称"));assertFalse(defaultPreview.contains("另一来源昵称"));assertFalse(defaultPreview.contains("13800138000"))
                assertEquals(2,server.requestCount);controller.analyze()
                assertEquals(defaultPreview,server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8())
                await {controller.state.value.answer!=null || controller.state.value.error!=null}
                assertNull(controller.state.value.error?.code,controller.state.value.error)
                assertEquals(3,server.requestCount)
            } finally {scope.cancel();db.close()}
        }
    }
}
