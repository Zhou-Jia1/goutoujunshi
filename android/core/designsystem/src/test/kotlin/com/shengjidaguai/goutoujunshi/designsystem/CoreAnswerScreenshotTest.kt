package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreAnswerScreenshotTest(private val scene: String, private val mode: String) {
    @get:Rule val compose = createComposeRule()

    @Test fun answerAndEvidence() {
        val crisis = scene == "CRISIS"
        val calm = mode == "calm"
        val reply = "我先确认一下时间，今晚再给你答复。"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (mode == "font260") 2f else 1f)) {
                JunshiTheme(darkTheme = mode == "dark", calm = calm, fontScaleLevel = if (mode == "font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                    Surface(Modifier.width(360.dp).height(640.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            CounselAnswerCard(AnswerUiState(
                                group = if (crisis) RelationGroup.DEFAULT else RelationGroup.valueOf(scene),
                                relationLabel = "联系人甲", goalLabel = "澄清", verdict = "先确认，再说明安排",
                                reply = listOf(reply), empathy = "一下子遇到这些事，确实很难受。", next = "先把手机放下，喝一口水。",
                                calm = calm, crisis = crisis, demo = true,
                            ), AnswerActions())
                            if (!crisis && !calm) {
                                EvidenceChip(EvidenceKind.FACT, "对方说今晚再聊", "虚构测试消息", null)
                                EvidenceChip(EvidenceKind.GUESS, "对方可能正忙，需要核实", null, null)
                                EvidenceChip(EvidenceKind.UNKNOWN, "还不知道具体安排", null, null)
                            }
                        }
                    }
                }
            }
        }
        if (crisis) {
            compose.onNodeWithText("你现在的安全最重要").assertExists()
            compose.onNodeWithText(reply).assertDoesNotExist()
        } else if (calm) {
            compose.onNodeWithText("先把手机放下，喝一口水。").assertExists()
            compose.onNodeWithText(reply).assertDoesNotExist()
        } else {
            compose.onNodeWithText(reply).assertExists()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/answer_${scene}_${mode}.png")
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_{1}")
        fun cases(): List<Array<String>> = listOf("WORK", "INTIMATE", "FAMILY", "SOCIAL", "MEDIATION", "CRISIS")
            .flatMap { scene -> listOf("light", "dark", "calm", "font260").map { arrayOf(scene, it) } }
    }
}
