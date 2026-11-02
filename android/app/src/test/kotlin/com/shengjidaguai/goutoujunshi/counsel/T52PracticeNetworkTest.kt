package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.TimeUnit
class T52PracticeNetworkTest {
 @Test fun realKnowledgeAndFrozenPreviewEqualEveryHttpBodyWithoutOtherOwner()=runBlocking {
  MockWebServer().use {server ->
   val root=File(System.getProperty("junshi.repositoryRoot")!!);val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
   val assembler=FrozenPromptAssembler(PromptBuilder(KnowledgeLibrary(KnowledgeSource {path -> File(if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes()})))
   val connection=ModelConnection(server.url("/v1/").toString(),"synthetic","Mock")
   val provider=OpenAiCompatibleProvider(connection,assembler,CredentialSource {"sk-synthetic".toByteArray()},allowLocalhostHttpForTests=true)
   val settings=object:ModelSettingsRepository {
    override suspend fun load()=ModelSettingsSnapshot(SavedModelSettings(true,server.url("/v1/").toString(),"synthetic","Mock","r1"),"masked")
    override suspend fun save(settings:SavedModelSettings,newKey:String?,removeKey:Boolean)=load()
    override suspend fun clear() {}
   }
   val base=CounselControllerTest.MemoryPersistence()
   for(owner in listOf("a","b")) {
    base.people+=Contact(owner,if(owner=="a") "合成昵称甲" else "合成昵称乙")
    val text=if(owner=="a") "合成昵称甲：当前甲唯一消息" else "其他乙唯一消息"
    base.saved[owner]=StoredCounsel(Conversation("conversation:$owner",owner,false),"主草稿不进入对练",emptyList(),listOf(EvidenceBlock("import:$owner","PASTE",listOf(ChatLine("line:$owner","import:$owner",1,Speaker.THEM,text,speakerConfirmed=true,contactId=owner)))))
   }
   val safety=LocalSafetyClassifier(File(root,"android/core/knowledge/src/main/assets/crisis-keywords.txt").readText())
   val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
   val readOnly=object:CounselPersistence by base {override suspend fun practiceSourceSnapshot(contact:Contact,conversationId:String)=base.saved[contact.id]?.takeIf {it.conversation.id==conversationId}}
   val controller=CounselController(readOnly,TemporarySessionStore(),assembler,provider,scope,settings,{provider},localSafety=safety::classify)
   suspend fun await(test:()->Boolean)=withTimeout(10000) {while(!test()) yield()}
   fun response(text:String) {
    val event=buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {put("delta",buildJsonObject {put("content","[[FREE_TEXT]]"+text)})})})}
    server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: $event\n\ndata: [DONE]\n\n"))
   }
   try {
    controller.initialize();await {controller.state.value.currentContact?.id=="a"};val origin=controller.state.value.conversation!!.id
    controller.enterPractice(origin);await {controller.state.value.practice!=null};val id=controller.state.value.practice!!.id
    suspend fun send(stage:PracticeStage,text:String) {
     response(text);controller.previewPractice(origin,id,stage==PracticeStage.SUMMARY);await {controller.state.value.outboundBody!=null}
     val before=controller.state.value;val preview=before.outboundBody!!
     assertTrue(before.preview!!.references.isNotEmpty());assertTrue(before.preview!!.system.contains("<reference path="))
     assertTrue(preview.contains("当前甲唯一消息"));assertFalse(preview.contains("其他乙唯一消息"));assertFalse(preview.contains("主草稿不进入对练"));assertFalse(preview.contains("合成昵称甲"))
     controller.sendPractice(origin,id,checkNotNull(controller.state.value.practicePreviewReceipt));val received=server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8();assertEquals(preview,received)
     File("build/t52-payloads").mkdirs();File("build/t52-payloads/${stage.name}.json").writeText(received)
     await {!controller.state.value.streaming && controller.state.value.preview==null}
     assertNull(controller.state.value.error)
    }
    send(PracticeStage.OPENING,"{\"counterpartLine\":\"你打算怎么安排？\"}")
    controller.editPracticeInput(origin,id,"合成昵称甲，我明天发方案");await {controller.state.value.practice?.draft?.isNotEmpty()==true}
    send(PracticeStage.ROUND,"{\"counterpartLine\":\"几点发？\",\"feedback\":\"你说明了安排，可以补充确认请求。\",\"betterVersion\":\"明天下午请你确认时间。\"}")
    send(PracticeStage.SUMMARY,"{\"name\":\"确认下一步\",\"explanation\":\"安排后附确认请求。\",\"example\":\"明天下午请你确认时间。\"}")
    assertEquals(3,server.requestCount);assertEquals(0,base.messageWrites);assertEquals(0,base.draftWrites);assertTrue(base.saved.getValue("a").runs.isEmpty())
   } finally {scope.cancel()}
  }
 }
 @Test fun extremeRoundAndSummarySendExactlyTheBudgetedSelectionWithCompleteCurrentInput()=runBlocking {
  MockWebServer().use {server ->
   val root=File(System.getProperty("junshi.repositoryRoot")!!);val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
   val assembler=FrozenPromptAssembler(PromptBuilder(KnowledgeLibrary(KnowledgeSource {path -> File(if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes()})))
   val connection=ModelConnection(server.url("/v1/").toString(),"synthetic","Mock")
   val provider=OpenAiCompatibleProvider(connection,assembler,CredentialSource {"sk-synthetic".toByteArray()},allowLocalhostHttpForTests=true)
   for(stage in listOf(PracticeStage.ROUND,PracticeStage.SUMMARY)) {
    val input=("CURRENTINPUT"+"敏".repeat(2000)).take(2000)
    val turns=(0 until 20).map {i->PracticeRound("合成对方问题${i}",if(i==19) input else ((if(i==0) "OMITTED_OLD_SECRET" else "历史${i}")+"敏".repeat(2000)).take(2000),"你说明了时间，可以再提出确认请求。","明天下午请你确认时间。")}
    val request=AnalysisRequest(requestId="budget-${stage.name}",contactId="a",conversationId="conversation",userText=if(stage==PracticeStage.ROUND)input else "对练",purpose=OutboundPurpose.PRACTICE,practice=PracticeRequestData("ram",20,stage,PracticeDifficulty.NORMAL,turns),evidence=listOf(EvidenceBlock("i","PASTE",listOf(ChatLine("current-opponent","i",1,Speaker.THEM,"当前对方唯一消息",speakerConfirmed=true,contactId="a")))),speakersConfirmed=true,redactionTerms=listOf("敏"))
    val preview=assembler.prepare(request);val selection=checkNotNull(preview.practiceSelection)
    assertTrue(selection.omittedTurns>0);assertTrue(selection.selectedTurns>0);assertEquals(listOf("current-opponent"),selection.opponentMessageIds)
    val expected=CompletionBody.encode(connection,preview)
    server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: [DONE]\n\n"))
    provider.analyze(request).toList()
    val actual=server.takeRequest(5,TimeUnit.SECONDS)!!.body.readUtf8();assertEquals(expected,actual)
    val messages=Json.parseToJsonElement(actual).jsonObject.getValue("messages").jsonArray
    val user=messages.last().jsonObject.getValue("content").jsonPrimitive.content
    val system=messages.first().jsonObject.getValue("content").jsonPrimitive.content
    assertTrue(system.codePointCount(0,system.length)+user.codePointCount(0,user.length)<=48000)
    assertTrue(user.contains("选中${selection.selectedTurns}轮／省略${selection.omittedTurns}轮"));assertTrue(user.contains("当前对方唯一消息"));assertFalse(actual.contains("OMITTED_OLD_SECRET"))
    val completeRedacted=Redactor(listOf("敏")).redact(input)
    if(stage==PracticeStage.ROUND) {assertTrue(user.contains("<user_request>$completeRedacted</user_request>"));assertTrue(user.contains("userLineField"))} else assertTrue(user.contains(completeRedacted))
    assertEquals(20,request.practice!!.turns.size);assertEquals(input,request.practice!!.turns.last().userLine)
    File("build/t52-payloads").mkdirs();File("build/t52-payloads/BUDGET_${stage.name}.json").writeText(actual)
   }
   assertEquals(2,server.requestCount)
  }
 }

}
