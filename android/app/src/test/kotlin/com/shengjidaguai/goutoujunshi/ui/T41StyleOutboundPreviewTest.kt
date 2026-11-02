package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.runtime.*
import org.junit.Assert.*
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun styleOutboundFixture():Pair<StyleUiState,CounselState> {
    val samples=(0..15).map {StyleSample(id="r04-$it",relationGroup=RelationGroup.SOCIAL,text="合成本人短句$it",origin=StyleOrigin.IMPORT,sourceContactId="a",sourceImportId="source",sourceMessageId="line$it")}
    val page=StyleUiState(StyleManagementSnapshot(ConsentState(enabled=true),WriteStamp(1,1,1),mapOf(RelationGroup.SOCIAL to StyleGroupSnapshot(RelationGroup.SOCIAL,samples,StyleProfile(relationGroup=RelationGroup.SOCIAL)))),"r04-receipt")
    val saved=SavedModelSettings(true,"https://styles.example/v1/","synthetic-model","合成模型服务商","r1")
    val prompt=PreparedPrompt("合成系统知识","合成风格样本：待屏蔽词",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic",redactionCount=1)
    val state=CounselState(loading=false,conversation=Conversation("r04-temporary"),demo=false,modelStatus=ProviderKind.BYOK,
        modelSettings=ModelSettingsSnapshot(saved,"masked"),currentRequest=AnalysisRequest(purpose=OutboundPurpose.STYLE_DESCRIPTION,conversationId="r04-temporary",relationType=RelationType.FRIEND),preview=prompt,outboundBody=CompletionBody.encode(saved.connection(),prompt))
    return page to state
}

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T41StyleOutboundPreviewTest {
    @get:Rule val compose=createComposeRule()
    @Test fun stylePreviewDisclosesRealDestinationAndOpensTemporaryRules() {
        val (initialPage,state)=styleOutboundFixture()
        val page=initialPage.copy(previewGroup=RelationGroup.SOCIAL)
        var current by mutableStateOf(state)
        var saved:List<String>?=null
        var requested:RelationGroup?=null
        compose.setContent {JunshiTheme {Surface {StyleContent(page,current,{}, {_,_,_->},{_,_->},{_,_->},{_,_->},{group,receipt->assertEquals(page.receipt,receipt);requested=group},{},saveRules={terms,aliases->
            saved=terms;current=current.copy(redactionProfile=RedactionProfile(1,terms,aliases),preview=null,outboundBody=null,currentRequest=null)
        })}}}
        val destination=state.modelSettings.saved.connection().safeDestination()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(destination))
        compose.onNodeWithText(destination).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("补充屏蔽词与昵称代号"))
        compose.onNodeWithText("补充屏蔽词与昵称代号").performClick()
        compose.onNodeWithText("仅用于这次临时问问，保存在内存，离开或进程结束即清除。").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("待屏蔽词")
        compose.onNode(hasText("保存规则（发送前需重新预览）")).performScrollTo().performClick()
        compose.runOnIdle {assertEquals(listOf("待屏蔽词"),saved);assertNull(current.preview);assertNull(current.outboundBody)}
        compose.onNode(hasText("关闭")).performScrollTo().performClick()
        compose.onNodeWithText("我的风格").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("重新预览风格描述请求"))
        compose.onNodeWithText("重新预览风格描述请求").performClick()
        compose.runOnIdle {assertEquals(RelationGroup.SOCIAL,requested)}
    }
}
