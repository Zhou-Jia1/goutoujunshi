package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.CounselState
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** All examples are synthetic. These tests render the actual application UI without a VM/DB. */
internal fun syntheticCounselState(scene: String): CounselState {
    val conversation = Conversation("synthetic-temporary")
    val base = CounselState(loading = false, conversation = conversation, demo = true, relationType = RelationType.BOSS)
    val user = Message("synthetic-user", conversation.id, MessageRole.USER, "虚构测试：怎样说明任务需要多一点时间？", requestId = "synthetic-request")
    return when (scene) {
        "streaming" -> base.copy(streaming = true, messages = listOf(user), partial = mapOf(
            SectionId.VERDICT to "虚构示例：先说明进度，再给出时间。",
            SectionId.REPLY to "虚构测试：目前已经完成第一部分，剩余内容我会在明天下午前确认。",
        ))
        "completed" -> base.copy(messages = listOf(user), answer = StructuredAnswer(
            id = "synthetic-answer", verdict = "虚构示例：先说明进度，再给出时间。",
            reply = listOf("虚构测试：目前已完成第一部分，剩余内容我会在明天下午前确认。"),
            empathy = "虚构测试：任务紧张时，可以把当前进度说清楚。",
            why = listOf("虚构测试：让对方知道进度和下一步。"), next = "虚构测试：检查时间是否可行。",
        ))
        else -> base
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppCounselScreenshotTest(private val scene: String, private val mode: String) {
    @get:Rule val compose = createComposeRule()

    @Test fun captureCounsel() {
        compose.setContent {
            JunshiTheme(darkTheme = mode == "dark") {
                Surface(Modifier.fillMaxSize()) { CounselContent(syntheticCounselState(scene), CounselUiActions()) }
            }
        }
        // Capture the state-specific content, rather than six identical off-screen answers.
        val focus = if (scene == "empty") "counsel-empty" else "counsel-answer"
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag(focus))
        compose.onNodeWithTag(focus).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/counsel_${scene}_${mode}.png")
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_{1}")
        fun cases(): List<Array<String>> = listOf("empty", "streaming", "completed")
            .flatMap { scene -> listOf("light", "dark").map { mode -> arrayOf(scene, mode) } }
    }
}
