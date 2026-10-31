package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allCopyEntrancesRejectStreamingAndEmptyReplies() {
        val state = mutableStateOf(AnswerUiState(isStreaming = true, reply = listOf("未完成")))
        var copies = 0
        compose.setContent { JunshiTheme { CounselAnswerCard(state.value, AnswerActions(onCopyAll = { copies++ })) } }
        fun assertCopiesDisabled() {
            val copies = compose.onAllNodesWithText("全部复制")
            copies.assertCountEquals(2)
            copies[0].assertIsNotEnabled()
            copies[1].assertIsNotEnabled()
        }
        assertCopiesDisabled()
        compose.runOnIdle { state.value = AnswerUiState() }
        assertCopiesDisabled()
        compose.runOnIdle { assertEquals(0, copies); state.value = AnswerUiState(reply = listOf("完成")) }
        compose.onAllNodesWithText("全部复制").onLast().performClick()
        compose.runOnIdle { assertEquals(1, copies) }
    }

    @Test fun dialogContentKeepsEffectiveSystemAndAppFontScale() {
        var observed = 0f
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                JunshiTheme(fontScaleLevel = FontScaleLevel.ExtraLarge) {
                    ContentDialog("合成标题", "关闭", {}) {
                        observed = LocalDensity.current.fontScale
                        BodyText("合成内容")
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(2.6f, observed, .001f) }
    }

    @Test fun dialogActionsDoNotOverlapAtMaximumFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                JunshiTheme(fontScaleLevel = FontScaleLevel.ExtraLarge) {
                    DangerConfirmDialog("删除合成资料", "合成后果说明", "确认删除", {}, {})
                }
            }
        }
        val confirm = compose.onNodeWithText("确认删除").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val dismiss = compose.onNodeWithText("取消").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Dialog actions must not overlap", confirm.bottom <= dismiss.top)
    }

    @Test fun calmHidesStrategiesUntilExplicitlyExpanded() {
        compose.setContent { JunshiTheme(calm = true) { CounselAnswerCard(
            AnswerUiState(calm = true, empathy = "先接住感受", next = "先喝一口水", verdict = "普通策略", reply = listOf("测试回复")), AnswerActions()) } }
        compose.onNodeWithText("先接住感受").assertIsDisplayed()
        compose.onNodeWithText("先喝一口水").assertIsDisplayed()
        compose.onNodeWithText("普通策略").assertDoesNotExist()
        compose.onNodeWithText("可以这样回").assertDoesNotExist()
        compose.onNodeWithText("等你平静一点再看").performClick()
        compose.onNodeWithText("普通策略").assertExists()
    }

    @Test fun crisisHidesOrdinaryAdviceAndDispatchesSafetyActions() {
        var dialed = ""
        var trusted = 0
        compose.setContent { JunshiTheme { FloatingAnswerPanel(
            AnswerUiState(crisis = true, verdict = "普通策略", reply = listOf("测试回复")), "联系人甲",
            {}, {}, {}, {}, {}, safetyActions = AnswerActions(onDial = { dialed = it }, onTrustedContact = { trusted++ })) } }
        compose.onNodeWithText("普通策略").assertDoesNotExist()
        compose.onNodeWithText("测试回复").assertDoesNotExist()
        compose.onNodeWithText("中国大陆：110").performScrollTo().performClick()
        assertEquals("110", dialed)
        compose.onNodeWithText("联系一个你信任的人").performScrollTo().performClick()
        assertEquals(1, trusted)
    }

    @Test fun consentRequiresAnExplicitChoiceAndBothChoicesAreAvailable() {
        var accepted = 0
        var declined = 0
        compose.setContent { JunshiTheme { ConsentCard("记忆同意", "仅保存经你同意的信息", "同意保存", "暂不保存", { accepted++ }, { declined++ }) } }
        assertEquals(0, accepted)
        assertEquals(0, declined)
        compose.onNodeWithText("暂不保存").performClick()
        assertEquals(0, accepted)
        assertEquals(1, declined)
        compose.onNodeWithText("同意保存").performClick()
        assertEquals(1, accepted)
    }

    @Test fun composerMoodIsOptionalAndCanBeCleared() {
        var mood: Int? = null
        var sent = 0
        compose.setContent { JunshiTheme { Composer("有个问题", {}, emptyList(), mood, { mood = it }, false, {}, { sent++ }, {}) } }
        compose.onNodeWithText("发送").performClick()
        assertNull(mood)
        assertEquals(1, sent)
        compose.onNodeWithText("此刻心情（可选）").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(8f) }
        assertEquals(8, mood)
        compose.onNodeWithText("不打分").performClick()
        assertNull(mood)
    }

    @Test fun backButtonDescribesNavigationInsteadOfPageTitle() {
        var backs = 0
        compose.setContent { JunshiTheme { JunshiTopBar("联系人", { backs++ }) } }
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backs)
    }

    @Test fun effective260PercentKeepsBottomActionsReachable() {
        var copied = 0
        var effectiveScale = 0f
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                JunshiTheme(fontScaleLevel = FontScaleLevel.ExtraLarge) {
                    effectiveScale = LocalDensity.current.fontScale
                    Surface(Modifier.width(360.dp).height(650.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CounselAnswerCard(AnswerUiState(reply = listOf("收到，我先核对时间，再给你明确答复。")), AnswerActions(onCopyAll = { copied++ }))
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithText("全部复制").onLast().performScrollTo().performClick()
        assertEquals(1, copied)
        assertEquals(2.6f, effectiveScale, .001f)
    }
}
