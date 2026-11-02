package com.shengjidaguai.goutoujunshi.counsel
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import java.io.File
import org.junit.Test
import org.junit.Assert.*
class T52PracticePromptTest {
 @Test fun practiceRequiresStructuredOneLineFeedbackAndCounterpart() {
  val root=File(System.getProperty("junshi.repositoryRoot")!!)
  val bundled=File(System.getProperty("junshi.generatedKnowledge")!!)
  val builder=PromptBuilder(KnowledgeLibrary(KnowledgeSource { path -> File(if(path.startsWith("knowledge/")) bundled else File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes() }))
  val prompt=builder.prepare(OutboundRequests.practice(AnalysisRequest(userText="合成练习",contactId="a",evidence=listOf(EvidenceBlock("i","PASTE",listOf(ChatLine("l","i",0,Speaker.THEM,"合成对方当前消息",speakerConfirmed=true,contactId="a")))),speakersConfirmed=true)))
  assertTrue(prompt.user.contains("合成对方当前消息"))
  assertTrue("practice protocol must contain the counterpart field",prompt.system.contains("counterpartLine"))
  assertTrue("practice protocol must enforce one line",prompt.system.contains("单行"))
 }
}
