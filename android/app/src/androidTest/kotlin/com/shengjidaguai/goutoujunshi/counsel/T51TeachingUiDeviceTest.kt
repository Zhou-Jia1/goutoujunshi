package com.shengjidaguai.goutoujunshi.counsel

import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T51TeachingUiDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun actualSettingsListOffersReachableTipsEntry() {
        var opened=false
        compose.setContent {JunshiTheme {Surface {ModelSettingsContent(CounselState(loading=false),{_,_,_->},{},{},{},openTips={opened=true})}}}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("技巧本"))
        compose.onNodeWithText("技巧本").performClick()
        compose.runOnIdle {assertTrue(opened)}
    }
    @Test fun favoriteAndHabitControlsAreExplicit() {
        val answer=StructuredAnswer(id="synthetic-answer",reply=listOf("我理解，明天再聊。"),tips=listOf(Tip(name="先接后转",explanation="先理解再说明",example="我理解")))
        val request=AnalysisRequest(requestId="native-teaching-request",conversationId="native-teaching-conversation")
        var view by mutableStateOf(TeachingView(answer.id,answer.tips.single(),false,"先说事情。","native-teaching-receipt"))
        val base=CounselState(loading=false,conversation=Conversation(request.conversationId),answer=answer,answerRequest=request,answerConfigRevision="",answerDemo=false,
            messages=listOf(Message("native-teaching-message",request.conversationId,MessageRole.ASSISTANT,
                answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId=request.requestId,status=MessageStatus.COMPLETED)))
        var never=false
        compose.setContent {JunshiTheme {Surface {CounselContent(base.copy(teaching=view),CounselUiActions(
            toggleTip={origin,receipt->assertEquals(request.conversationId,origin);assertEquals(view.receipt,receipt);view=view.copy(saved=!view.saved)},
            dismissHabit={origin,receipt,disabled->assertEquals(request.conversationId,origin);assertEquals(view.receipt,receipt);never=disabled;view=view.copy(habit=null)}))}}}
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("收藏技巧"))
        compose.onNodeWithText("收藏技巧").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("取消收藏"))
        compose.onNodeWithText("取消收藏").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("不再提醒这一条"))
        compose.onNodeWithText("不再提醒这一条").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {assertFalse(view.saved);assertTrue(never);assertNull(view.habit)}
    }
    @Test fun bookGroupsSameTechniqueByUsedScenesAndCancelsExplicitly() {
        var tips by mutableStateOf(listOf(Tip("synthetic-tip","先接后转","先理解再说明",scenes=setOf(TipScene(RelationGroup.WORK),TipScene(RelationGroup.SOCIAL)))))
        compose.setContent {JunshiTheme {Surface {TipsContent(tips,{},remove={id->tips=tips.filterNot {it.id==id}})}}}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("工作沟通"))
        compose.onNodeWithText("工作沟通").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("取消收藏"))
        compose.onAllNodesWithText("取消收藏")[0].performClick()
        compose.runOnIdle {assertTrue(tips.isEmpty())}
    }
}
