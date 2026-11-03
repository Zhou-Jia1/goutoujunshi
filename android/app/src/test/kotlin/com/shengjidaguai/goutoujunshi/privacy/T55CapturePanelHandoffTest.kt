package com.shengjidaguai.goutoujunshi.privacy

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.domain.LockState
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.floating.*
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Real typed save acknowledgement and Activity effect. Only WM creation is deferred,
 * modelling addView/attach occurring after confirmation stop; no OS consent is claimed. */
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[35])
class T55CapturePanelHandoffTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var f:CounselControllerTest.Fixture
    private lateinit var imports:ImportCoordinator
    private var previousController:Any?=null;private var previousImporter:Any?=null;private var previousApp:Any?=null
    private var ticket=0L;private lateinit var preview:String;private var opened=0
    private var holder:org.robolectric.android.controller.ActivityController<FloatingCounselActivity>?=null
    private var mainHolder:org.robolectric.android.controller.ActivityController<com.shengjidaguai.goutoujunshi.MainActivity>?=null
    private var service:org.robolectric.android.controller.ServiceController<FloatingCounselService>?=null
    private var panel:FloatingAnswerWindow?=null
    private val app get()=RuntimeEnvironment.getApplication()
    private val controller get()=f.controller
    private fun runtimeField(name:String)=CounselRuntime::class.java.getDeclaredField(name).apply {isAccessible=true}
    private val appField=PrivacyRuntime::class.java.getDeclaredField("app").apply {isAccessible=true}
    private val visibleField=PrivacyRuntime::class.java.getDeclaredField("visible").apply {isAccessible=true}
    private val windowsField=PrivacyRuntime::class.java.getDeclaredField("windows").apply {isAccessible=true}
    private val handler=PrivacyRuntime::class.java.getDeclaredField("handler").apply {isAccessible=true}.get(PrivacyRuntime) as Handler
    private var oldVisible=emptySet<android.app.Activity>();private var oldWindows=emptySet<android.app.Activity>()
    @Suppress("UNCHECKED_CAST") private fun visible()=visibleField.get(PrivacyRuntime) as MutableSet<android.app.Activity>
    @Suppress("UNCHECKED_CAST") private fun windows()=windowsField.get(PrivacyRuntime) as MutableSet<android.app.Activity>
    @Before fun setup()=runBlocking {
        f=CounselControllerTest.Fixture();imports=ImportCoordinator(controller,f.scope,{true},{})
        previousController=runtimeField("controller").get(null);previousImporter=runtimeField("importer").get(null);previousApp=appField.get(PrivacyRuntime)
        runtimeField("controller").set(null,controller);runtimeField("importer").set(null,imports);appField.set(PrivacyRuntime,app)
        oldVisible=visible().toSet();oldWindows=windows().toSet();visible().clear();windows().clear();handler.removeCallbacksAndMessages(null)
        app.getSharedPreferences("privacy",0).edit().putBoolean("enabled",true).putLong("delay",0).commit()
        controller.updateLockState(LockState(true,true))
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        ticket=FloatingRuntime.session.request();assertTrue(FloatingRuntime.session.accept(ticket));assertFalse(FloatingRuntime.session.valid(ticket))
        FloatingRuntime.runtimeTicket=ticket;FloatingRuntime.active.value=true
        FloatingRuntime.showAnswer={opened++} // Actual window is deliberately attached later.
        preview=checkNotNull(imports.beginCapture(ticket) {true})
        imports.captured(preview,listOf(OcrLine("synthetic captured words",.99f,.1f,.2f,.4f,.3f)))
        imports.target(ImportTarget());imports.side(preview,OcrSide.RIGHT)
        service=Robolectric.buildService(FloatingCounselService::class.java).create()
    }
    @After fun cleanup() {
        panel?.close();service?.destroy()
        holder?.let {runCatching {it.get().finish();it.pause().stop();PrivacyRuntime.onActivityStopped(it.get());it.destroy();PrivacyRuntime.onActivityDestroyed(it.get())}}
        mainHolder?.let {main ->
            runCatching {main.pause()};runCatching {main.stop()};runCatching {main.destroy()};PrivacyRuntime.onActivityDestroyed(main.get())
        }
        handler.removeCallbacksAndMessages(null);visible().clear();visible().addAll(oldVisible);windows().clear();windows().addAll(oldWindows)
        FloatingRuntime.active.value=false;FloatingRuntime.runtimeTicket=null;FloatingRuntime.showAnswer=null;FloatingRuntime.session.stop()
        runtimeField("controller").set(null,previousController);runtimeField("importer").set(null,previousImporter);appField.set(PrivacyRuntime,previousApp);f.close()
    }
    private fun idle() {repeat(3) {compose.mainClock.advanceTimeByFrame();Shadows.shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS)}}
    private fun backgroundMain() {
        val main=Robolectric.buildActivity(com.shengjidaguai.goutoujunshi.MainActivity::class.java).create().start().resume().visible()
        mainHolder=main;PrivacyRuntime.onActivityCreated(main.get(),null);PrivacyRuntime.onActivityStarted(main.get())
        assertFalse(CaptureOwnUi.snapshot().clear)
        PrivacyRuntime.systemTrip(main.get());main.pause().stop();PrivacyRuntime.onActivityStopped(main.get())
        assertTrue(CaptureOwnUi.snapshot().clear);assertTrue(controller.state.value.lockState.unlocked)
    }
    @Test @Config(application=com.shengjidaguai.goutoujunshi.JunshiApplication::class)
    fun productionCaptureReviewIsIndependentOfBackgroundMainAndExcludedFromRecents() {
        backgroundMain()
        FloatingRuntime.showCapture(app)
        val launched=checkNotNull(Shadows.shadowOf(app).nextStartedActivity)
        assertEquals(FloatingCounselActivity::class.java.name,launched.component?.className)
        assertEquals(FloatingRuntime.CAPTURE_CONFIRM,launched.action)
        assertEquals(preview,launched.getStringExtra(FloatingRuntime.CAPTURE_PREVIEW))
        assertTrue("capture review must not reuse the existing private Main task",launched.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT!=0)
        assertTrue("a later capture must not reuse another preview's document task",launched.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK!=0)
        assertTrue("the transient private review must not leave a Recents entry",launched.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS!=0)
        assertNull(launched.data);assertFalse(launched.hasExtra(Intent.EXTRA_TEXT))
    }
    @Test @Config(application=com.shengjidaguai.goutoujunshi.JunshiApplication::class)
    fun backgroundMainCannotStealAckAndVisibleMainStillClosesPanel() {
        backgroundMain();saveAndStopBeforePanelExists()
        assertEquals("the Confirm Activity consumes the typed completion despite Main's shared imports observer",1,opened)
        panel=FloatingAnswerWindow(checkNotNull(service).get(),f.scope)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS)
        assertFalse(panel!!.isClosed);assertTrue(CaptureOwnUi.snapshot().clear)
        val main=checkNotNull(mainHolder);main.restart().start().resume().visible();PrivacyRuntime.onActivityStarted(main.get());idle()
        assertFalse(CaptureOwnUi.snapshot().clear)
        assertTrue("actual Main visibility must retain the original self-capture/panel close protection",panel!!.isClosed)
        assertEquals("Main's restart must not replay the consumed completion",1,opened)
    }
    private fun saveAndStopBeforePanelExists():Long {
        val intent=Intent(app,FloatingCounselActivity::class.java).setAction(FloatingRuntime.CAPTURE_CONFIRM)
            .putExtra(FloatingRuntime.CAPTURE_PREVIEW,preview).putExtra(FloatingRuntime.CAPTURE_TICKET,ticket)
        val activity=Robolectric.buildActivity(FloatingCounselActivity::class.java,intent).create().start().resume().visible().windowFocusChanged(true)
        holder=activity;PrivacyRuntime.onActivityCreated(activity.get(),null);PrivacyRuntime.onActivityStarted(activity.get())
        idle();imports.confirm()
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(opened==0 && System.nanoTime()<end) idle()
        assertEquals("the production typed acknowledgement must publish once",1,opened)
        assertTrue(activity.get().isFinishing)
        val lease=checkNotNull(controller.currentPrivateUiHandoff())
        activity.windowFocusChanged(false).pause().stop();PrivacyRuntime.onActivityStopped(activity.get())
        assertTrue(controller.state.value.lockState.unlocked)
        return lease.started
    }
    @Test fun productionSaveBridgesStoppedConfirmationUntilActualPrivatePanelAttaches() {
        val started=saveAndStopBeforePanelExists()
        val remaining=29_000-(SystemClock.elapsedRealtime()-started);assertTrue(remaining>0)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(remaining,TimeUnit.MILLISECONDS)
        assertTrue(controller.state.value.lockState.unlocked)
        panel=FloatingAnswerWindow(checkNotNull(service).get(),f.scope)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS)
        val view=FloatingAnswerWindow::class.java.getDeclaredField("view").apply {isAccessible=true}.get(panel) as android.view.View
        assertTrue(view.isAttachedToWindow);assertTrue(view.isShown);assertTrue(controller.state.value.lockState.unlocked)
        assertTrue(SystemClock.elapsedRealtime()-started<30_000);assertNull(controller.currentPrivateUiHandoff())
        panel!!.close();assertFalse("ordinary close has no handoff left",controller.state.value.lockState.unlocked)
    }
    @Test fun delayedPanelCannotExtendOrReviveExpiredProductionSaveHandoff() {
        val started=saveAndStopBeforePanelExists()
        val remaining=30_000-(SystemClock.elapsedRealtime()-started);assertTrue(remaining>0)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(remaining,TimeUnit.MILLISECONDS)
        assertFalse(controller.state.value.lockState.unlocked)
        panel=FloatingAnswerWindow(checkNotNull(service).get(),f.scope)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS)
        assertFalse("late actual attach cannot unlock a new lock state",controller.state.value.lockState.unlocked)
        assertTrue(panel!!.isClosed)
    }
}
