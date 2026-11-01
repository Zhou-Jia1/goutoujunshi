package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T43MemoryScreenshotTest(private val page: String,private val mode: String) {
    @get:Rule val compose=createComposeRule()
    private lateinit var originalTimeZone:java.util.TimeZone
    @Before fun pinReferenceTimeZone() {
        originalTimeZone=java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
    }
    @After fun restoreTimeZone() {
        if(::originalTimeZone.isInitialized) java.util.TimeZone.setDefault(originalTimeZone)
    }
    @Test fun memoryPages() {
        val entry=MemoryEntry("fictional-memory","a",subjectId="a",field="节奏",value="虚构小北可能最近周末忙",createdAt=1793404800000,updatedAt=1793404800000)
        val initial=MemoryUiState("a",MemorySnapshot(ConsentState(enabled=true),WriteStamp(1,0,1,0,1),listOf(entry),"operation",verifiedInferenceIds=setOf(entry.id)),totalMemories=1)
        val state=initial.copy(receipt=MemoryUiReceipt("synthetic-page","page","a",initial.snapshot.stamp!!))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260") 2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",fontScaleLevel=if(mode=="font260") FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) {
                    when(page) {
                        "consent" -> Column(Modifier.verticalScroll(rememberScrollState())) { MemoryInline(state.copy(consentProposal=MemoryProposal(subjectId="a",field="节奏",value=entry.value)),{},{},{},{}) }
                        "notice" -> Column { MemoryInline(state.copy(notice=MemoryNotice(entry.value,"operation")),{},{},{},{}) }
                        else -> MemoryContent(state,{}, { _,_,_-> },{ _,_,_,_-> },{ _,_,_-> },{ _,_,_-> },{ _,_,_-> },{})
                    }
                }
            }
        } }
        compose.onRoot().captureRoboImage("src/test/screenshots/t43_${page}_${mode}.png")
        if(page=="consent") {
            compose.onNodeWithText("好，记住").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("先不用").performScrollTo().assertIsDisplayed()
        }
        if(page=="list") {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("编辑"))
            compose.onNodeWithText("编辑").performClick()
            compose.onNodeWithText("保存到本机").assertIsDisplayed()
        }
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}")
        fun cases()=listOf("consent","notice","list").flatMap { page -> listOf("light","dark","font260").map { arrayOf(page,it) } }
    }
}
