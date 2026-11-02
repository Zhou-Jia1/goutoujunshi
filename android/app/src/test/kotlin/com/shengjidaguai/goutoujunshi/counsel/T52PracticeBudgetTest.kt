package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class T52PracticeBudgetTest {
 private fun extreme(stage:PracticeStage) {
  val root=File(System.getProperty("junshi.repositoryRoot")!!);val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
  val builder=PromptBuilder(KnowledgeLibrary(KnowledgeSource {path -> File(if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes()}))
  val turns=(0 until 20).map {i->PracticeRound("合成对方第${i}轮问题",((if(i==0) "旧轮秘密零" else "第${i}轮")+"敏".repeat(2000)).take(2000),"你说明了时间，可以再明确确认请求。","明天下午请你确认时间。")}
  assertTrue(turns.all {it.userLine!!.codePointCount(0,it.userLine!!.length)==2000})
  val request=AnalysisRequest(requestId="budget-${stage.name}",contactId="a",conversationId="conversation",userText=if(stage==PracticeStage.ROUND) turns.last().userLine!! else "对练",
   purpose=OutboundPurpose.PRACTICE,practice=PracticeRequestData("session",20,stage,PracticeDifficulty.NORMAL,turns),
   evidence=listOf(EvidenceBlock("import","PASTE",listOf(ChatLine("current-opponent","import",1,Speaker.THEM,"当前对方唯一消息",speakerConfirmed=true,contactId="a")))),speakersConfirmed=true,redactionTerms=listOf("敏"))
  val prompt=builder.prepare(request)
  assertTrue(prompt.system.codePointCount(0,prompt.system.length)+prompt.user.codePointCount(0,prompt.user.length)<=48000)
  assertTrue(prompt.references.isNotEmpty());assertTrue(prompt.user.contains("当前对方唯一消息"));assertTrue(prompt.user.contains("屏蔽词1"))
  assertFalse(prompt.user.contains("旧轮秘密零"));assertTrue(prompt.user.contains("选中"));assertTrue(prompt.user.contains("省略"))
  val selection=checkNotNull(prompt.practiceSelection)
  assertTrue(selection.omittedTurns>0);assertTrue(selection.selectedTurns>0);assertEquals(listOf("current-opponent"),selection.opponentMessageIds)
  assertTrue(prompt.user.contains("选中${selection.selectedTurns}轮／省略${selection.omittedTurns}轮"))
  assertTrue(prompt.user.contains("第19轮"))
  if(stage==PracticeStage.ROUND) assertTrue(prompt.user.contains("userLineField"))
  assertEquals(20,request.practice!!.turns.size)
 }
 @Test fun roundAtMaximumInputAndTurnCountFitsAfterRealRedactionExpansion()=extreme(PracticeStage.ROUND)
 @Test fun summaryAtMaximumInputAndTurnCountFitsAfterRealRedactionExpansion()=extreme(PracticeStage.SUMMARY)
 @Test fun irreducibleExpandedOpponentSampleFailsWithTypedRangeError() {
  val root=File(System.getProperty("junshi.repositoryRoot")!!);val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
  val builder=PromptBuilder(KnowledgeLibrary(KnowledgeSource {path -> File(if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes()}))
  val input="敏".repeat(2000)
  val request=AnalysisRequest(contactId="a",userText=input,purpose=OutboundPurpose.PRACTICE,practice=PracticeRequestData("ram",1,PracticeStage.ROUND,PracticeDifficulty.NORMAL,listOf(PracticeRound("当前对方句",input))),evidence=listOf(EvidenceBlock("i","PASTE",listOf(ChatLine("l","i",1,Speaker.THEM,input,speakerConfirmed=true,contactId="a")))),speakersConfirmed=true,redactionTerms=listOf("敏"))
  assertTrue(runCatching {builder.prepare(request)}.exceptionOrNull() is PracticeInputRangeException)
  assertEquals(input,request.userText);assertEquals(input,request.practice!!.turns.single().userLine)
 }

}
