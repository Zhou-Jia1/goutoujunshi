package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T53DraftCalmTest(private val floating:Boolean,private val signal:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun savedBodySharesCalmFoldingAndRetainsCompleteTextOnBothSurfaces() {
        val text="T53_COMPLETE_SAVED_BODY"
        val state=CounselState(loading=false,conversation=Conversation("synthetic",coolingDraft=CoolingDraft(text,1000)),
            emotion=if(signal=="emotion")8 else null,safety=if(signal=="caution")SafetyLevel.CAUTION else SafetyLevel.NONE)
        compose.setContent {JunshiTheme(calm=state.safetyPresentation().calm) {Surface {
            if(floating) Box(Modifier.height(650.dp)) {com.shengjidaguai.goutoujunshi.floating.FloatingPanelContent(state)}
            else CounselContent(state,CounselUiActions())
        }}}
        if(!floating) compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
        val body=hasText(text) and hasAnyAncestor(hasTestTag("cooling-draft"))
        if(signal!="none") {
            compose.onNode(body).assertDoesNotExist()
            compose.onNode(hasText("等你平静一点再看") and hasAnyAncestor(hasTestTag("cooling-draft"))).performScrollTo().performClick()
        }
        compose.onNode(body).performScrollTo().assertIsDisplayed()
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="floating={0}_{1}")
        fun cases()=listOf(false,true).flatMap {floating->listOf("emotion","caution","none").map {arrayOf<Any>(floating,it)}}
    }
}
