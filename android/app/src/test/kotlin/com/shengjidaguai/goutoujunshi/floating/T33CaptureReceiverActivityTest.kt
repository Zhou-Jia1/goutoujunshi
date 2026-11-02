package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import com.shengjidaguai.goutoujunshi.importing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** JVM Activity lifecycle / synthetic RAM-source test. This does not exercise or claim projection consent. */
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[35])
class T33CaptureReceiverActivityTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var f:CounselControllerTest.Fixture
    private lateinit var imports:ImportCoordinator
    private val holders=mutableListOf<org.robolectric.android.controller.ActivityController<FloatingCounselActivity>>()
    private var previousController:Any?=null;private var previousImporter:Any?=null
    private var opened=0;private var ticket=0L;private lateinit var preview:String
    private fun runtimeField(name:String)=CounselRuntime::class.java.getDeclaredField(name).apply {isAccessible=true}
    @Before fun setup()=runBlocking {
        f=CounselControllerTest.Fixture();imports=ImportCoordinator(f.controller,f.scope,{true},{})
        previousController=runtimeField("controller").get(null);previousImporter=runtimeField("importer").get(null)
        runtimeField("controller").set(null,f.controller);runtimeField("importer").set(null,imports)
        ticket=FloatingRuntime.session.request();assertTrue(FloatingRuntime.session.accept(ticket));assertFalse(FloatingRuntime.session.valid(ticket));FloatingRuntime.runtimeTicket=ticket;FloatingRuntime.active.value=true
        FloatingRuntime.showAnswer={opened++}
        preview=imports.beginCapture(ticket) {true}!!
        imports.captured(preview,listOf(OcrLine("合成原话",.99f,.1f,.2f,.4f,.3f)))
        imports.target(ImportTarget());imports.side(preview,OcrSide.RIGHT)
    }
    @After fun cleanup() {
        holders.forEach {runCatching {it.get().finish();it.pause().stop().destroy()}}
        FloatingRuntime.active.value=false;FloatingRuntime.runtimeTicket=null;FloatingRuntime.showAnswer=null;FloatingRuntime.session.stop()
        runtimeField("controller").set(null,previousController);runtimeField("importer").set(null,previousImporter);f.close()
    }
    private fun activity():org.robolectric.android.controller.ActivityController<FloatingCounselActivity> {
        val intent=Intent(RuntimeEnvironment.getApplication(),FloatingCounselActivity::class.java).setAction(FloatingRuntime.CAPTURE_CONFIRM)
            .putExtra(FloatingRuntime.CAPTURE_PREVIEW,preview).putExtra(FloatingRuntime.CAPTURE_TICKET,ticket)
        return Robolectric.buildActivity(FloatingCounselActivity::class.java,intent).create().start().resume().visible().windowFocusChanged(true).also {holders+=it;idle()}
    }
    private fun diagnose(activity:FloatingCounselActivity) {
        val receiver=CounselController::class.java.getDeclaredField("captureReceiver").apply {isAccessible=true}.get(f.controller) as? CaptureReceiver
        val ack=CounselController::class.java.getDeclaredField("captureConfirmation").apply {isAccessible=true}.get(f.controller) as? CaptureConfirmation
        val own=FloatingCounselActivity::class.java.getDeclaredField("captureReceiver").apply {isAccessible=true}.get(activity) as? String
        println("T33receiver hasOwn=${own!=null} controllerMatches=${own!=null && receiver?.token==own} epochMatches=${receiver?.epoch==f.controller.importAuthority} ackPresent=${ack!=null} ackMatches=${own!=null && ack?.receiver==own} lifecycle=${activity.lifecycle.currentState} finishing=${activity.isFinishing} decorAttached=${activity.window.decorView.isAttachedToWindow} windowFocus=${activity.window.decorView.hasWindowFocus()} opened=$opened")
    }
    private fun idle() {repeat(20) {compose.mainClock.advanceTimeByFrame();shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS);Thread.sleep(2)}}
    private fun publication(activity:FloatingCounselActivity) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(opened==0 && System.nanoTime()<end) idle()
        if(opened==0) diagnose(activity)
    }
    @Test fun actualActivityConsumesItsCurrentTypedCompletionOnce() {
        val a=activity();imports.confirm();publication(a.get())
        assertEquals(1,opened);assertTrue(a.get().isFinishing)
    }
    @Test fun oldActivityDestroyCannotDiscardReplacementActivityCompletion() {
        val old=activity();old.windowFocusChanged(false).pause().stop()
        val replacement=activity()
        imports.confirm() // Publish the typed completion, before the next composition frame.
        old.get().finish();old.destroy();holders.remove(old)
        publication(replacement.get())
        assertEquals("Old onDestroy must not revoke the replacement receiver",1,opened)
        assertTrue(replacement.get().isFinishing)
    }
    @Test fun closingActivityCannotPublishByReplayingItsOldIntentIntoAnotherActivity() {
        val a=activity();imports.confirm()
        a.get().finish();a.pause().stop().destroy();holders.remove(a)
        idle();assertEquals(0,opened)
        val replay=activity();idle()
        assertEquals(0,opened);assertTrue(replay.get().isFinishing)
        assertNotNull(f.controller.state.value.floatingCapture)
    }
}
