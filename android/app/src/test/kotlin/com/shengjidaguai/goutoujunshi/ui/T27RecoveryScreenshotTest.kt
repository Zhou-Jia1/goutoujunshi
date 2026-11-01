package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T27RecoveryScreenshotTest(private val scenario: String, private val expected: String) {
    @get:Rule val compose = createComposeRule()

    @Test fun stateIsReadableWithAnimationsRemoved() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val base = syntheticCounselState("empty").let { initial -> initial.copy(draft = "虚构问题",
            messages = listOf(Message("synthetic-user", initial.conversation!!.id, MessageRole.USER,
                "虚构问题", requestId = "synthetic-request", status = MessageStatus.COMPLETED))) }
        val formal = base.copy(currentContact = Contact("synthetic", "虚构甲"),
            conversation = Conversation("synthetic-formal", "synthetic", false), draftSaved = true,
            messages = base.messages.map { it.copy(conversationId = "synthetic-formal", contactId = "synthetic") })
        val state = when (scenario) {
            "listen" -> base.copy(streaming = true, waiting = WaitingPhase.at(0))
            "sort" -> base.copy(streaming = true, waiting = WaitingPhase.at(5_000))
            "slow" -> base.copy(streaming = true, waiting = WaitingPhase.at(20_001))
            "network_temporary" -> base.copy(error = CounselError("NETWORK", true))
            "network_saved" -> formal.copy(error = CounselError("NETWORK", true))
            "interrupted" -> formal.copy(error = CounselError("INTERRUPTED", true))
            "format" -> base.copy(answer = StructuredAnswer(freeText = "虚构全文：无标记仍然可读。", rawText = "虚构全文：无标记仍然可读。"))
            else -> base.copy(error = CounselError("CONTENT_FILTERED"))
        }
        compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) { CounselContent(state, CounselUiActions()) } } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(expected, substring = true))
        compose.onNodeWithText(expected, substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/t27_${scenario}.png")
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf(
            arrayOf("listen", "在听你说"), arrayOf("sort", "在理清事实"),
            arrayOf("slow", "有点慢"), arrayOf("network_temporary", "内容暂留本次打开期间"),
            arrayOf("network_saved", "网络断开了，已保存加密草稿"),
            arrayOf("interrupted", "上次没说完，要继续吗？"),
            arrayOf("format", "虚构全文：无标记仍然可读。"),
            arrayOf("filtered", "这个问题模型服务商不愿回答")
        )
    }
}
