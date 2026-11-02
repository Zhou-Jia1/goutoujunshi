package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.designsystem.*
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w411dp-h2200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class T52PracticeScopeScreenshotTest(private val page:String,private val mode:String) {
    @get:Rule val compose=createComposeRule()
    @Test fun actualScopePreview() {
        val settings=com.shengjidaguai.goutoujunshi.data.network.SavedModelSettings(true,"https://practice.synthetic.example/v1/","synthetic-model","合成模型服务商","r1")
        val root=java.io.File(System.getProperty("junshi.repositoryRoot")!!);val bundled=java.io.File(System.getProperty("junshi.generatedKnowledge")!!)
        val builder=com.shengjidaguai.goutoujunshi.knowledge.PromptBuilder(com.shengjidaguai.goutoujunshi.knowledge.KnowledgeLibrary(com.shengjidaguai.goutoujunshi.knowledge.KnowledgeSource {path -> java.io.File(if(path.startsWith("knowledge/")) bundled else java.io.File(root,"android/core/knowledge/src/main/assets"),path.removePrefix("knowledge/")).readBytes()}))
        val request=AnalysisRequest(contactId="a",userText="对练",purpose=OutboundPurpose.PRACTICE,practice=PracticeRequestData("synthetic-practice",20,PracticeStage.SUMMARY,PracticeDifficulty.NORMAL,(0 until 20).map {PracticeRound("合成对方问题${it}","敏".repeat(2000),"你说明了时间，可以再说清确认请求。","明天下午请你确认时间。")}),evidence=listOf(EvidenceBlock("i","PASTE",listOf(ChatLine("current-opponent","i",1,Speaker.THEM,"合成当前对方消息",speakerConfirmed=true,contactId="a")))),speakersConfirmed=true,redactionTerms=listOf("敏"))
        val prepared=builder.prepare(request)
        val view=PracticeView("synthetic-practice",20,turns=(0 until 20).map {PracticeRound("合成对方问题${it}",if(it==19)null else "合成回应${it}")},stage=PracticeStage.SUMMARY)
        val state=CounselState(loading=false,demo=false,modelStatus=ProviderKind.BYOK,conversation=Conversation("synthetic-conversation"),practice=view,practicePreviewReceipt="native-preview-first",preview=prepared,outboundBody=com.shengjidaguai.goutoujunshi.data.network.CompletionBody.encode(settings.connection(),prepared),modelSettings=com.shengjidaguai.goutoujunshi.data.network.ModelSettingsSnapshot(settings,"masked"))
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,if(mode=="font260")2f else 1f)) {
            JunshiTheme(darkTheme=mode=="dark",calm=mode=="calm",fontScaleLevel=if(mode=="font260")FontScaleLevel.ExtraLarge else FontScaleLevel.Standard) {
                Surface(Modifier.fillMaxSize()) {PracticeContent(state,CounselUiActions())}
            }
        }}
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/t52_scope_${mode}.png")
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}_{1}")
        fun cases()=listOf("scope").flatMap {p->listOf("light","dark","calm","font260").map {arrayOf(p,it)}}
    }
}
