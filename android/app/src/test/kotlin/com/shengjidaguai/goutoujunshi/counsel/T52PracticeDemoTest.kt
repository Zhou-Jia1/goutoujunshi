package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.Assert.*
class T52PracticeDemoTest {
 @Test fun allDemoStagesUseRealKnowledgeAndStrictPracticeJsonWithoutOrdinaryExample()=runBlocking {
  val root=File(System.getProperty("junshi.repositoryRoot")!!);val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
  val localReads=CopyOnWriteArrayList<String>()
  val builder=PromptBuilder(KnowledgeLibrary(KnowledgeSource {path ->
   localReads+=path
   File(if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes()
  }))
  val prepared=CopyOnWriteArrayList<PreparedPrompt>()
  val assembler=FrozenPromptAssembler(object:PromptAssembler {override fun prepare(request:AnalysisRequest)=builder.prepare(request).also {prepared+=it}})
  val ordinaryExamples=AtomicInteger()
  val provider=DemoAnalysisProvider(assembler,{ordinaryExamples.incrementAndGet();error("Practice must not read ordinary full-analysis examples")},0)
  assertEquals(ProviderKind.DEMO,provider.kind)
  for(stage in PracticeStage.entries) {
   val turns=if(stage==PracticeStage.OPENING) emptyList() else listOf(PracticeRound("合成对方问题","我明天先处理"))
   val request=AnalysisRequest(requestId="demo-${stage.name}",contactId="demo-owner",conversationId="demo-conversation",userText=if(stage==PracticeStage.ROUND) "我明天先处理" else "对练",purpose=OutboundPurpose.PRACTICE,
    practice=PracticeRequestData("demo-session",1,stage,PracticeDifficulty.NORMAL,turns),evidence=listOf(EvidenceBlock("demo-import","PASTE",listOf(ChatLine("demo-line","demo-import",1,Speaker.THEM,"合成对方消息",speakerConfirmed=true,contactId="demo-owner")))),speakersConfirmed=true)
   val chunks=provider.analyze(request).toList()
   assertEquals(1,chunks.size)
   val complete=chunks.single() as AnalysisChunk.Completed
   val result=PracticeProtocol.parse(complete.answer.freeText,stage)
   when(stage) {
    PracticeStage.OPENING->assertFalse(result.counterpartLine.isNullOrBlank())
    PracticeStage.ROUND->{assertFalse(result.feedback.isNullOrBlank());assertFalse(result.betterVersion.isNullOrBlank());assertFalse(result.feedback!!.contains('\n'))}
    PracticeStage.SUMMARY->assertFalse(result.summary?.name.isNullOrBlank())
   }
   assertTrue(prepared.last().references.isNotEmpty());assertNotNull(prepared.last().practiceSelection)
   assertTrue(prepared.last().user.contains("合成对方消息"))
  }
  assertEquals(3,prepared.size);assertEquals(0,ordinaryExamples.get())
  assertTrue(localReads.isNotEmpty());assertTrue(localReads.none {it.contains("://")})
 }
}
