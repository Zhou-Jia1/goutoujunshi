package com.shengjidaguai.goutoujunshi.ui
import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h4000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T25AnswerScreenshotTest(private val scene: String, private val appearance: String) {
 @get:Rule val compose=createComposeRule()
 @Test fun productionAnswer() {
  val relation=when(scene) { "work"->RelationType.BOSS;"intimate"->RelationType.PARTNER;"family"->RelationType.PARENT;"mediation"->RelationType.MEDIATION;else->RelationType.FRIEND }
  val answer=StructuredAnswer(id="synthetic-answer",scene=Scene(relation,Goal.REPLY),verdict="先回应对方的关注，再把自己的安排说清楚。",reply=listOf("谢谢你告诉我你的想法。","我想先确认时间，明天再和你商量。"),why=listOf("先确认双方需求"),alternatives=listOf(Alternative("直接一点","我们明天再商量。")),cost="需要再确认一次",situation="双方安排尚未对齐",facts=listOf(Fact("对方提出了时间安排")),guesses=listOf(Guess("对方可能着急")),unknowns=listOf("具体期限"),risk="避免把推测当成事实",next="确认可行时间")
  val conversation=Conversation("synthetic-conversation")
  val state=CounselState(loading=false,conversation=conversation,draft="虚构问题",currentRequest=AnalysisRequest(requestId="synthetic-request",conversationId=conversation.id,userText="虚构问题"),relationType=relation,answer=answer,
   messages=listOf(Message("synthetic-user",conversation.id,MessageRole.USER,"虚构问题",requestId="synthetic-request",status=MessageStatus.COMPLETED),Message("synthetic-assistant",conversation.id,MessageRole.ASSISTANT,answer.reply.joinToString("\n"),answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId="synthetic-request",status=MessageStatus.COMPLETED)))
  compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(appearance=="font260")2f else 1f)) {
   JunshiTheme(darkTheme=appearance=="dark",fontScaleLevel=if(appearance=="font260")FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) { Surface(Modifier.fillMaxSize()) { CounselContent(state.copy(answerRequest=state.currentRequest,answerConfigRevision=""),CounselUiActions()) } }
  } }
  compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-answer"))
  compose.onNodeWithTag("counsel-answer").captureRoboImage("src/test/screenshots/t25_${scene}_${appearance}.png")
 }
 companion object {
  @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}") fun cases()=listOf("work","intimate","family","social","mediation").flatMap { scene->listOf("light","dark","font260").map { arrayOf(scene,it) } }
 }
}
