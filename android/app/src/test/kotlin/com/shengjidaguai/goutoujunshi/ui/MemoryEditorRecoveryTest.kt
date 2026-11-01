package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.counsel.LongTermMemoryControllerTest
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fault injection at persistence boundary; does not claim native disk fault coverage. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryEditorRecoveryTest(private val adding: Boolean,private val lostAcknowledgement: Boolean) {
    @get:Rule val compose=createComposeRule()
    @Test fun privateBufferSurvivesStorageFailureAndRetryHasNoModelCallOrDuplicate() {
        val f=LongTermMemoryControllerTest.Fixture()
        try {
            f.persistence.consent=ConsentState(enabled=true,consentVersion=1)
            f.controller.initialize();f.controller.createContact("虚构编辑测试",RelationType.FRIEND)
            val owner=f.controller.state.value.currentContact!!.id
            if(!adding) f.persistence.entries+=MemoryEntry("original",owner,subjectId=owner,field="pattern",value="原始虚构值",confidence=Confidence.LOW)
            f.controller.openMemory(owner)
            if(lostAcknowledgement) f.persistence.failAfterCommit=true
            else f.persistence.writeFailure=if(adding) IOException("synthetic DataStore unavailable") else SQLiteException("synthetic SQLite unavailable")
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
                compose.onNodeWithText("请主动选择分组：关于你").performClick()
                compose.onAllNodesWithText("关于你").onLast().performClick()
                compose.onAllNodesWithText("关闭").onLast().performClick()
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.onNodeWithText("添加一条由你确认的记忆").performClick()
                compose.onNodeWithText("请主动选择分组：请主动选择分组").performClick()
                compose.onAllNodesWithText("关于你").onLast().performClick()
                compose.onNodeWithText("请主动选择分组：关于你").performClick()
                compose.onAllNodesWithText("关于你").onLast().performClick()
            } else {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText("编辑"))
                compose.onNodeWithText("编辑").performClick()
            }
            val value="失败后仍应保留的唯一虚构编辑稿"
            compose.onNode(hasSetTextAction()).performTextReplacement(value)
            val confirm=if(adding) "由我确认并保存到本机" else "保存到本机"
            compose.onNodeWithText(confirm).performClick()
            compose.onNodeWithText(value).assertTextContains(value).assertIsDisplayed()
            compose.onNodeWithText("这次没有确认保存成功，输入仍保留在本次打开期间。可以重试保存或取消，不会重新调用模型。").assertIsDisplayed()
            compose.onNodeWithText(confirm).performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            assertEquals(1,f.persistence.manualCommits)
            assertEquals(1,f.persistence.entries.size)
            assertEquals(value,f.persistence.entries.single().value)
            assertEquals(0,f.providerCalls)
        } finally { f.close() }
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="add={0}_lostAck={1}")
        fun cases()=listOf(false,true).flatMap { add -> listOf(false,true).map { arrayOf(add,it) } }
    }
}
