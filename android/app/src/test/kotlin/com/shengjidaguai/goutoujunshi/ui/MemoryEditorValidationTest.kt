package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.LongTermMemoryControllerTest
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryEditorValidationTest(private val adding: Boolean) {
    @get:Rule val compose=createComposeRule()
    @Test fun invalidInputStaysEditableAndCorrectionSavesWithoutAnyModelCall() {
        val f=LongTermMemoryControllerTest.Fixture()
        try {
            f.persistence.consent=ConsentState(enabled=true,consentVersion=1)
            f.controller.initialize();f.controller.createContact("虚构输入校验",RelationType.FRIEND)
            val owner=f.controller.state.value.currentContact!!.id
            if(!adding) f.persistence.entries+=MemoryEntry("original",owner,subjectId=owner,field="pattern",value="原始虚构内容",confidence=Confidence.LOW)
            f.controller.openMemory(owner)
            compose.setContent {
                val state by f.controller.memoryPage.collectAsState()
                JunshiTheme { Surface {
                    MemoryContent(state,{},f.controller::setMemoryEnabled,f.controller::editMemory,f.controller::deleteMemory,
                        f.controller::markMemoryWrong,f.controller::undoMemory,f.controller::clearMemories,add=f.controller::addMemory)
                } }
            }
            if(adding) {
                compose.onNodeWithText("添加一条由你确认的记忆").performClick()
                compose.onNodeWithText("请主动选择分组：请主动选择分组").performClick()
                compose.onAllNodesWithText("关于你").onLast().performClick()
            } else {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText("编辑"))
                compose.onNodeWithText("编辑").performClick()
            }
            val confirm=if(adding) "由我确认并保存到本机" else "保存到本机"
            for(invalid in listOf("虚".repeat(201),"虚构\u0001内容")) {
                compose.onNode(hasSetTextAction()).performTextReplacement(invalid)
                compose.onNodeWithText(confirm).performClick()
                compose.onNodeWithText("请输入1–200个字，移除异常控制字符后再保存。").assertIsDisplayed()
                compose.onNode(hasSetTextAction()).assertExists()
                assertEquals(0,f.persistence.manualCommits);assertEquals(0,f.providerCalls)
            }
            val corrected="虚".repeat(189)+"13800138000"
            compose.onNode(hasSetTextAction()).performTextReplacement(corrected)
            compose.onNodeWithText(confirm).performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            assertEquals(corrected,f.persistence.entries.single().value)
            assertEquals(1,f.persistence.manualCommits);assertEquals(0,f.providerCalls)
        } finally { f.close() }
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="add={0}")
        fun cases()=listOf(arrayOf(false),arrayOf(true))
    }
}
