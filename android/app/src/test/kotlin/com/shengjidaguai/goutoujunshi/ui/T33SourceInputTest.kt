package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T33SourceInputTest {
    @get:Rule val compose=createComposeRule()
    private fun text(id:Int)=RuntimeEnvironment.getApplication().getString(id)
    private fun scroll(id:Int) {compose.onNodeWithTag("import-preview").performScrollToNode(hasText(text(id)))}
    private suspend fun exercise(clearApp:Boolean,recreate:Boolean=false) {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people+=Contact("a","合成甲")
            val c=f.controller;val imports=ImportCoordinator(c,f.scope,{true},{})
            val first=imports.beginCapture(33) {true}!!
            imports.captured(first,listOf(OcrLine("原始帧",.99f,.1f,.2f,.4f,.3f)))
            imports.target(ImportTarget(c.state.value.contacts.first {it.id=="a"}));imports.side(first,OcrSide.RIGHT)
            imports.floatingSource(FloatingSourceContext("合成应用","合成账号"));imports.confirm()
            withTimeout(5000) {while(imports.state.value!=null) yield()}
            val old=c.state.value;check(imports.prepareCaptureMore(old.conversation?.id,old.floatingCapture!!.id,33))
            val next=imports.beginCapture(33) {true}!!;imports.captured(next,listOf(OcrLine("新帧",.99f,.1f,.2f,.4f,.3f)))
            imports.target(ImportTarget(c.state.value.contacts.first {it.id=="a"}))
            var composition by mutableIntStateOf(0)
            compose.setContent {val p by imports.state.collectAsState();val counsel by c.state.collectAsState();JunshiTheme {Surface {key(composition) {p?.let {
                ImportContent(it,counsel.contacts,target=imports::target,side={v->imports.side(next,v)},onFloatingSource=imports::floatingSource,onContinueCapture=imports::continueCaptureMore)
            }}}}}
            scroll(R.string.t33_source_app);compose.onNodeWithText(text(R.string.t33_source_app)).performTextInput("合成应用")
            scroll(R.string.t33_source_account);compose.onNodeWithText(text(R.string.t33_source_account)).performTextInput("合成账号")
            scroll(R.string.t33_confirm_source);compose.onNodeWithText(text(R.string.t33_confirm_source)).performClick()
            scroll(R.string.t33_continue_capture);compose.onNodeWithText(text(R.string.t33_continue_capture)).performClick()
            compose.waitUntil {imports.state.value!!.captureContinuationId!=null}
            if(recreate) {
                val before=imports.state.value
                compose.runOnIdle {composition++}
                compose.waitForIdle()
                assertEquals(before,imports.state.value)
                for((label,value) in listOf(R.string.t33_source_app to "合成应用",R.string.t33_source_account to "合成账号")) {
                    scroll(label)
                    compose.onNodeWithText(text(label)).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString(value)))
                }
            }
            val field=if(clearApp) R.string.t33_source_app else R.string.t33_source_account
            scroll(field)
            val node=compose.onNodeWithText(text(field))
            node.performClick();node.performTextInputSelection(TextRange(0,4));node.performKeyInput {pressKey(Key.Backspace)}
            compose.waitForIdle()
            node.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
            assertNull("selectAll-delete must revoke the confirmed source",imports.state.value!!.floatingSource)
            assertNull(imports.state.value!!.captureContinuationId)
            assertFalse(imports.state.value!!.captureMoreEligible);assertFalse(imports.state.value!!.canConfirm)
            scroll(R.string.t33_confirm_source);compose.onNodeWithText(text(R.string.t33_confirm_source)).performClick()
            assertNull(imports.state.value!!.floatingSource)
            scroll(field);node.performTextInput("新的值")
            scroll(R.string.t33_confirm_source);compose.onNodeWithText(text(R.string.t33_confirm_source)).performClick()
            val expected=if(clearApp) FloatingSourceContext("新的值","合成账号") else FloatingSourceContext("合成应用","新的值")
            assertEquals(expected,imports.state.value!!.floatingSource)
            assertNull(imports.state.value!!.captureContinuationId)
        } finally {f.close()}
    }
    @Test fun selectAllClearApplicationRevokesSourceAndMore()=runBlocking {exercise(true)}
    @Test fun selectAllClearAccountRevokesSourceAndMore()=runBlocking {exercise(false)}
    @Test fun recreatedCompositionDisplaysSourceAndClearingApplicationRevokesMore()=runBlocking {exercise(true,true)}
    @Test fun recreatedCompositionDisplaysSourceAndClearingAccountRevokesMore()=runBlocking {exercise(false,true)}
}
