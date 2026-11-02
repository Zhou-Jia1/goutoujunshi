package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.JunshiNavigation
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.importing.ImportCoordinator
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.LockState
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN")
class FloatingNavigationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun homeAndMeExposeEntryButLockedRootHidesPendingTextImport() {
        val fixture=CounselControllerTest.Fixture()
        try {
            val app=RuntimeEnvironment.getApplication()
            val imports=ImportCoordinator(fixture.controller,fixture.scope,{true},{})
            val vm=CounselViewModel(app,fixture.controller,imports)
            compose.setContent { JunshiTheme { JunshiNavigation(vm) } }
            compose.onNodeWithText("悬浮军师").assertIsDisplayed()
            compose.onNodeWithText("我的").performClick()
            compose.onNodeWithText("悬浮军师").assertIsDisplayed()
            compose.runOnIdle { imports.text("虚构甲：虚构导入内容"); fixture.controller.updateLockState(LockState(enabled=true)) }
            compose.onNodeWithText("应用已锁定").assertIsDisplayed()
            compose.onNodeWithText("解锁应用").assertIsDisplayed()
            compose.onNodeWithText("悬浮军师").assertDoesNotExist()
            compose.onNodeWithText("虚构导入内容",substring=true).assertDoesNotExist()
            compose.runOnIdle {
                val ticket=FloatingRuntime.session.request()
                app.getSharedPreferences(FloatingCounselService.POSITIONS,0).edit().putBoolean("1-right",true).commit()
                vm.clearAll()
                Assert.assertFalse(FloatingRuntime.session.valid(ticket))
                Assert.assertTrue(app.getSharedPreferences(FloatingCounselService.POSITIONS,0).all.isEmpty())
            }
        } finally { fixture.close() }
    }
    @Test fun captureConfirmationDoesNotChangeLaterControlEntryRoute()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val fixture=CounselControllerTest.Fixture()
        val imports=ImportCoordinator(fixture.controller,fixture.scope,{true},{})
        val importer=CounselRuntime::class.java.getDeclaredField("importer").apply {isAccessible=true}
        val previous=importer.get(null);val previousTicket=FloatingRuntime.runtimeTicket
        try {
            importer.set(null,imports)
            FloatingRuntime.runtimeTicket=33
            FloatingRuntime.showCapture(app)
            Assert.assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
            val preview=checkNotNull(imports.beginCapture(33) {true})
            FloatingRuntime.runtimeTicket=34
            FloatingRuntime.showCapture(app)
            Assert.assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
            FloatingRuntime.runtimeTicket=33
            FloatingRuntime.showCapture(app)
            val confirmation=checkNotNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
            Assert.assertEquals(FloatingRuntime.CAPTURE_CONFIRM,confirmation.action)
            Assert.assertEquals(preview,confirmation.getStringExtra(FloatingRuntime.CAPTURE_PREVIEW))
            Assert.assertEquals(33L,confirmation.getLongExtra(FloatingRuntime.CAPTURE_TICKET,-1))
            Assert.assertFalse(app.packageManager.getActivityInfo(ComponentName(app,FloatingCounselActivity::class.java),0).exported)
            FloatingRuntime.open(app)
            Assert.assertNull(checkNotNull(org.robolectric.Shadows.shadowOf(app).nextStartedActivity).action)
        } finally {importer.set(null,previous);FloatingRuntime.runtimeTicket=previousTicket;fixture.close()}
    }

}
