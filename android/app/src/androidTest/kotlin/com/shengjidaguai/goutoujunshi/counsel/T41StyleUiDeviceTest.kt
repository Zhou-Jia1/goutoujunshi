package com.shengjidaguai.goutoujunshi.counsel

import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.ui.StyleContent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T41StyleUiDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun allFiveQuizGroupsOfferThreeChoicesAndSubmitOnlySelectedTexts() {
        val quiz=Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().targetContext.assets.open("style-quiz.json").bufferedReader().use {it.readText()}).jsonObject.getValue("groups").jsonArray
        assertEquals(5,quiz.size)
        val selected=mutableListOf<String>()
        var submitted:List<String>?=null
        val snapshot=StyleManagementSnapshot(ConsentState(),WriteStamp(0,0,1),emptyMap())
        compose.setContent {JunshiTheme {Surface {StyleContent(StyleUiState(snapshot,"native-receipt"),CounselState(loading=false),{},{_,_,_->},{_,_->},{_,_->},{texts,receipt->assertEquals("native-receipt",receipt);submitted=texts},{_,_->},{})}}}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("哪句更像你"));compose.onNodeWithText("哪句更像你").performClick()
        quiz.forEach {element ->
            val options=element.jsonObject.getValue("options").jsonArray
            assertEquals(3,options.size)
            options.forEach {val text=it.jsonPrimitive.content;compose.onNode(hasScrollAction()).performScrollToNode(hasText(text));compose.onNodeWithText(text).assertIsDisplayed()}
            val text=options.first().jsonPrimitive.content
            selected+=text
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text));compose.onNodeWithText(text).performClick()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("采用这五句"));compose.onNodeWithText("采用这五句").performClick()
        compose.runOnIdle {assertEquals(selected,submitted)}
    }
    @Test fun actualSampleEditorSubmitsExplicitOverrideAndLabelsOriginalSource() {
        val original=StyleSample("native-sample",RelationGroup.SOCIAL,"原确认本人消息",StyleOrigin.IMPORT,"a","source","line")
        var page by mutableStateOf(StyleUiState(StyleManagementSnapshot(ConsentState(enabled=true),WriteStamp(1,1,1),
            mapOf(RelationGroup.SOCIAL to StyleGroupSnapshot(RelationGroup.SOCIAL,listOf(original),StyleProfile(relationGroup=RelationGroup.SOCIAL))),mapOf("a" to "合成朋友")),"native-edit"))
        var submitted:Triple<String,String,String>?=null
        compose.setContent {JunshiTheme {Surface {StyleContent(page,CounselState(loading=false),{},{_,_,_->},{_,_->},{_,_->},{_,_->},{_,_->},{},
            editSample={id,text,receipt ->
                submitted=Triple(id,text,receipt)
                val edited=original.copy(userEdit=UserStyleSampleEdit(text,"ui-fixture-only",1,1,1))
                page=page.copy(receipt="native-edit-saved",snapshot=page.snapshot!!.copy(groups=mapOf(RelationGroup.SOCIAL to StyleGroupSnapshot(RelationGroup.SOCIAL,listOf(edited),StyleProfile(relationGroup=RelationGroup.SOCIAL)))))
            })}}}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("编辑这条示例"));compose.onNodeWithText("编辑这条示例").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("合成用户明确改写")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("保存风格示例"));compose.onNodeWithText("保存风格示例").performClick()
        compose.runOnIdle {assertEquals(Triple(original.id,"合成用户明确改写","native-edit"),submitted);assertEquals("原确认本人消息",page.snapshot!!.groups.getValue(RelationGroup.SOCIAL).samples.single().text)}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("你编辑的风格示例（以下保留原来源）"))
        compose.onNodeWithText("你编辑的风格示例（以下保留原来源）").assertIsDisplayed()
    }

    @Test fun stylePreviewDestinationAndTemporaryRulesUseActualEditorAndRepreviewCallback() {
        val samples=(0..15).map {StyleSample("native-r04-$it",RelationGroup.SOCIAL,"合成本人样本$it",StyleOrigin.IMPORT,"a","source","line$it")}
        val page=StyleUiState(StyleManagementSnapshot(ConsentState(enabled=true),WriteStamp(1,1,1),mapOf(RelationGroup.SOCIAL to StyleGroupSnapshot(RelationGroup.SOCIAL,samples,StyleProfile(relationGroup=RelationGroup.SOCIAL)))),"native-r04",previewGroup=RelationGroup.SOCIAL)
        val saved=SavedModelSettings(true,"https://styles.example/v1/","synthetic-model","合成服务商","r1")
        val prompt=PreparedPrompt("合成系统知识","合成风格内容",listOf(KnowledgeReference("synthetic.md","a".repeat(64),1)),"synthetic")
        var state by mutableStateOf(CounselState(loading=false,conversation=Conversation("native-r04-temporary"),demo=false,modelStatus=ProviderKind.BYOK,modelSettings=ModelSettingsSnapshot(saved,"masked"),currentRequest=AnalysisRequest(purpose=OutboundPurpose.STYLE_DESCRIPTION),preview=prompt,outboundBody=CompletionBody.encode(saved.connection(),prompt)))
        var edited:List<String>?=null;var previewed:RelationGroup?=null
        compose.setContent {JunshiTheme {Surface {StyleContent(page,state,{}, {_,_,_->},{_,_->},{_,_->},{_,_->},{group,receipt->assertEquals("native-r04",receipt);previewed=group},{},saveRules={terms,aliases->
            edited=terms;state=state.copy(redactionProfile=RedactionProfile(1,terms,aliases),preview=null,outboundBody=null,currentRequest=null)
        })}}}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(saved.connection().safeDestination()));compose.onNodeWithText(saved.connection().safeDestination()).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("补充屏蔽词与昵称代号"));compose.onNodeWithText("补充屏蔽词与昵称代号").performClick()
        compose.onNodeWithText("仅用于这次临时问问，保存在内存，离开或进程结束即清除。").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("原生新增屏蔽词")
        compose.onNode(hasText("保存规则（发送前需重新预览）")).performScrollTo().performClick()
        compose.runOnIdle {assertEquals(listOf("原生新增屏蔽词"),edited);assertNull(state.outboundBody)}
        compose.onNode(hasText("关闭")).performScrollTo().performClick()
        compose.onNodeWithText("我的风格").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("重新预览风格描述请求"));compose.onNodeWithText("重新预览风格描述请求").performClick()
        compose.runOnIdle {assertEquals(RelationGroup.SOCIAL,previewed)}
    }

}
