package com.shengjidaguai.goutoujunshi.privacy

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest
import com.shengjidaguai.goutoujunshi.JunshiApplication
import com.shengjidaguai.goutoujunshi.MainActivity
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.domain.LockState
import com.shengjidaguai.goutoujunshi.importing.ImportCoordinator
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Real Runtime callbacks and Controller authority; no separate timeout algorithm. */
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class T55SystemTripLifecycleTest {
    private val fixture=CounselControllerTest.Fixture()
    private val controller get()=fixture.controller
    private val appField=PrivacyRuntime::class.java.getDeclaredField("app").apply {isAccessible=true}
    private val controllerField=CounselRuntime::class.java.getDeclaredField("controller").apply {isAccessible=true}
    private val importerField=CounselRuntime::class.java.getDeclaredField("importer").apply {isAccessible=true}
    private val ownerField=PrivacyRuntime::class.java.getDeclaredField("temporaryOwner").apply {isAccessible=true}
    private val visibleField=PrivacyRuntime::class.java.getDeclaredField("visible").apply {isAccessible=true}
    private val windowsField=PrivacyRuntime::class.java.getDeclaredField("windows").apply {isAccessible=true}
    private val handler=PrivacyRuntime::class.java.getDeclaredField("handler").apply {isAccessible=true}.get(PrivacyRuntime) as Handler
    private val preferences=RuntimeEnvironment.getApplication().getSharedPreferences("t55-system-trip-synthetic",0)
    private var oldApp:Any?=null
    private var oldController:Any?=null
    private var oldOwner:Any?=null
    private var oldImporter:Any?=null
    private var oldVisible=emptySet<Activity>()
    private var oldWindows=emptySet<Activity>()
    private val activities=mutableListOf<org.robolectric.android.controller.ActivityController<out Activity>>()
    @Suppress("UNCHECKED_CAST") private fun visible()=visibleField.get(PrivacyRuntime) as MutableSet<Activity>
    @Suppress("UNCHECKED_CAST") private fun windows()=windowsField.get(PrivacyRuntime) as MutableSet<Activity>
    @Before fun bindActualRuntime() {
        oldApp=appField.get(PrivacyRuntime);oldController=controllerField.get(CounselRuntime);oldOwner=ownerField.get(PrivacyRuntime)
        oldImporter=importerField.get(CounselRuntime);importerField.set(CounselRuntime,null)
        oldVisible=visible().toSet();oldWindows=windows().toSet();visible().clear();windows().clear()
        handler.removeCallbacksAndMessages(null);ownerField.set(PrivacyRuntime,null)
        appField.set(PrivacyRuntime,object:Application() {override fun getSharedPreferences(name:String,mode:Int)=preferences})
        controllerField.set(CounselRuntime,controller)
        controller.updateLockState(LockState(true,true))
    }
    @After fun restore() {
        handler.removeCallbacksAndMessages(null)
        activities.reversed().forEach {PrivacyRuntime.onActivityDestroyed(it.get());it.destroy()}
        visible().clear();visible().addAll(oldVisible);windows().clear();windows().addAll(oldWindows)
        ownerField.set(PrivacyRuntime,oldOwner);appField.set(PrivacyRuntime,oldApp);controllerField.set(CounselRuntime,oldController)
        (importerField.get(CounselRuntime) as? ImportCoordinator)?.takeIf {it!==oldImporter}?.let {importer ->
            val scope=ImportCoordinator::class.java.getDeclaredField("scope").apply {isAccessible=true}.get(importer) as kotlinx.coroutines.CoroutineScope
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        importerField.set(CounselRuntime,oldImporter)
        fixture.close()
    }
    private fun privateActivity(delay:Long):Activity {
        preferences.edit().putLong("delay",delay).putBoolean("enabled",true).commit()
        val activity=Robolectric.buildActivity(Activity::class.java).create().also(activities::add)
        PrivacyRuntime.onActivityCreated(activity.get(),null)
        PrivacyRuntime.onActivityStarted(activity.get())
        activity.start().resume()
        return activity.get()
    }
    private fun leaveToSystem(activity:Activity,dispatch:Boolean=true) {
        PrivacyRuntime.systemTrip(activity)
        PrivacyRuntime.onActivityStopped(activity)
        if(dispatch) Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
    private fun elapse(ms:Long)=Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS)
    private fun frozenElapse(ms:Long)=ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
    private fun assertLockedBeforePrivateStart(activity:Activity) {
        PrivacyRuntime.onActivityStarted(activity)
        assertFalse("return must revoke unlocked state before private reentry",controller.state.value.lockState.unlocked)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
    }
    @Test fun immediateExplicitSystemTripHasAtMostThirtySecondsFromActualLaunch() {
        val a=privateActivity(0);leaveToSystem(a)
        elapse(29_999);assertTrue(controller.state.value.lockState.unlocked)
        elapse(1)
        assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun repeatedSystemTripsAndStopsCannotRenewImmediateHandoff() {
        val a=privateActivity(0);leaveToSystem(a);elapse(15_000)
        PrivacyRuntime.systemTrip(a);PrivacyRuntime.onActivityStopped(a)
        elapse(14_999);assertTrue(controller.state.value.lockState.unlocked)
        elapse(1);assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun expiredImmediateHandoffRejectsLatePickerAndAuthentication() {
        val a=privateActivity(0);controller.initialize()
        val imports=ImportCoordinator(controller,fixture.scope,{true},{})
        val selection=checkNotNull(imports.beginSelection());val attempt=controller.beginAuthentication()
        leaveToSystem(a,false);frozenElapse(30_000);PrivacyRuntime.systemReturn(a)
        assertFalse(imports.selectionIsCurrent(selection));assertFalse(controller.finishAuthentication(attempt,true))
        assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun cancelingImmediateSystemTripEndsItsBackgroundHandoff() {
        val a=privateActivity(0);leaveToSystem(a);elapse(1)
        PrivacyRuntime.finishHandoff(a.application,a)
        assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun returnedImmediatePickerCancelsLeaseOnceActualPrivateActivityStarts() {
        val a=privateActivity(0);leaveToSystem(a,false);frozenElapse(1)
        PrivacyRuntime.completeSystemTrip(a);PrivacyRuntime.onActivityStarted(a)
        assertTrue(controller.state.value.lockState.unlocked)
        PrivacyRuntime.onActivityStopped(a)
        assertFalse("ordinary next Home cannot reuse the completed selector lease",controller.state.value.lockState.unlocked)
    }
    @Test fun oldCancelCannotEndANewCurrentHandoff() {
        val a=privateActivity(0);leaveToSystem(a)
        controller.updateLockState(LockState(true))
        val b=privateActivity(0)
        assertTrue(controller.finishAuthentication(controller.beginAuthentication(),true))
        leaveToSystem(b);elapse(1)
        PrivacyRuntime.finishHandoff(a.application,a)
        assertTrue(controller.state.value.lockState.unlocked)
        elapse(29_999);assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun oldFinallyFromSameServiceCannotEndItsLaterCaptureHandoff() {
        val a=privateActivity(0)
        val holder=Robolectric.buildService(com.shengjidaguai.goutoujunshi.floating.FloatingCounselService::class.java).create()
        val begin=holder.get().javaClass.getDeclaredMethod("beginCapturePrivacyHandoff").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val oldFinally=begin.invoke(holder.get()) as ()->Unit
        try {
            PrivacyRuntime.onActivityStopped(a);controller.updateLockState(LockState(true))
            PrivacyRuntime.onActivityStarted(a)
            assertTrue(controller.finishAuthentication(controller.beginAuthentication(),true))
            @Suppress("UNCHECKED_CAST") val newFinally=begin.invoke(holder.get()) as ()->Unit
            PrivacyRuntime.onActivityStopped(a);elapse(1)
            oldFinally()
            assertTrue("an old coroutine finally in this same Service must not end the new capture's lease",controller.state.value.lockState.unlocked)
            newFinally();assertFalse(controller.state.value.lockState.unlocked)
        } finally {holder.destroy()}
    }
    @Test fun pendingOldReturnCannotEndNewHandoffOnSameActivityStart() {
        val a=privateActivity(0);val old=PrivacyRuntime.systemTrip(a)
        PrivacyRuntime.onActivityStopped(a);PrivacyRuntime.completeSystemTrip(a,old)
        controller.updateLockState(LockState(true))
        assertTrue(controller.finishAuthentication(controller.beginAuthentication(),true))
        val current=PrivacyRuntime.systemTrip(a)
        assertNotEquals(old,current)
        PrivacyRuntime.onActivityStarted(a)
        assertEquals(current,controller.currentPrivateUiHandoff())
        PrivacyRuntime.onActivityStopped(a);elapse(1)
        assertTrue(controller.state.value.lockState.unlocked)
        PrivacyRuntime.finishHandoff(a.application,a,current);assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun oldFullIntentCannotConsumeNewHandoffAndItsIdIsStripped() {
        val a=privateActivity(0);val old=checkNotNull(PrivacyRuntime.systemTrip(a))
        controller.updateLockState(LockState(true))
        assertTrue(controller.finishAuthentication(controller.beginAuthentication(),true))
        val current=PrivacyRuntime.systemTrip(a)
        val oldIntent=Intent().putExtra(PrivacyRuntime.HANDOFF_EXTRA,old.id)
        PrivacyRuntime.fullPrivateUiReturned(a,oldIntent)
        assertFalse(oldIntent.hasExtra(PrivacyRuntime.HANDOFF_EXTRA));assertEquals(current,controller.currentPrivateUiHandoff())
        PrivacyRuntime.fullPrivateUiReturned(a,Intent())
        assertEquals(current,controller.currentPrivateUiHandoff())
    }
    @Test @Config(shadows=[com.shengjidaguai.goutoujunshi.floating.ProjectionTestManager::class,com.shengjidaguai.goutoujunshi.floating.ProjectionTestProjection::class,com.shengjidaguai.goutoujunshi.floating.ProjectionTestDisplay::class])
    fun realServiceAndAttachedControlBallCannotExtendTheThirtySecondHandoff() {
        val a=privateActivity(0)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val started=android.os.SystemClock.elapsedRealtime();PrivacyRuntime.systemTrip(a)
        val floating=com.shengjidaguai.goutoujunshi.floating.FloatingRuntime
        val holder=Robolectric.buildService(com.shengjidaguai.goutoujunshi.floating.FloatingCounselService::class.java).create()
        val ticket=floating.session.request();com.shengjidaguai.goutoujunshi.floating.putCurrentProjectionGrant(ticket)
        try {
            holder.get().onStartCommand(Intent().putExtra(com.shengjidaguai.goutoujunshi.floating.FloatingCounselService.TICKET,ticket),0,1)
            elapse(32);assertTrue(floating.active.value)
            val ball=holder.get().javaClass.getDeclaredField("window").apply {isAccessible=true}.get(holder.get()) as android.view.View
            assertTrue(ball.isAttachedToWindow);assertTrue(ball.isShown)
            PrivacyRuntime.onActivityStopped(a)
            val remaining=30_000-(android.os.SystemClock.elapsedRealtime()-started)
            assertTrue(remaining>0);elapse(remaining-1);assertTrue(controller.state.value.lockState.unlocked)
            elapse(1);assertFalse("ball and service are not private UI",controller.state.value.lockState.unlocked)
        } finally {holder.destroy();floating.active.value=false;floating.session.stop()}
    }
    private fun scheduledBoundary(delay:Long) {
        val a=privateActivity(delay);leaveToSystem(a)
        elapse(delay-1);assertTrue(controller.state.value.lockState.unlocked)
        elapse(1);assertFalse("no second Main stop occurs after Home in the selector",controller.state.value.lockState.unlocked)
    }
    @Test fun oneMinuteSystemTripHomeLocksAtExactDeadline()=scheduledBoundary(60_000)
    @Test fun fiveMinuteSystemTripHomeLocksAtExactDeadline()=scheduledBoundary(300_000)
    private fun earlyReturn(delay:Long) {
        val a=privateActivity(delay);leaveToSystem(a);elapse(delay-1)
        PrivacyRuntime.systemReturn(a);PrivacyRuntime.onActivityStarted(a);PrivacyRuntime.onActivityResumed(a)
        assertTrue(controller.state.value.lockState.unlocked)
        elapse(delay+1);assertTrue("return cancels the previous deadline",controller.state.value.lockState.unlocked)
    }
    @Test fun oneMinuteShortSystemTripRemainsUsableAndCancelsDeadline()=earlyReturn(60_000)
    @Test fun fiveMinuteShortSystemTripRemainsUsableAndCancelsDeadline()=earlyReturn(300_000)
    private fun frozenReturn(delay:Long) {
        val a=privateActivity(delay);leaveToSystem(a,dispatch=false)
        frozenElapse(delay)
        assertLockedBeforePrivateStart(a)
    }
    @Test fun oneMinuteReturnChecksDeadlineEvenWhenStopRunnableNeverRan()=frozenReturn(60_000)
    @Test fun fiveMinuteReturnChecksDeadlineEvenWhenStopRunnableNeverRan()=frozenReturn(300_000)
    @Test fun rotationDoesNotStartImmediateBackgroundDeadline() {
        val a=privateActivity(0)
        ReflectionHelpers.setField(a,"mChangingConfigurations",true)
        assertTrue(a.isChangingConfigurations)
        PrivacyRuntime.onActivityStopped(a)
        privateActivity(0);elapse(1000)
        assertTrue(controller.state.value.lockState.unlocked)
    }
    @Test fun startedSameAppHandoffKeepsImmediateSessionUnlocked() {
        val a=privateActivity(0);privateActivity(0)
        PrivacyRuntime.onActivityStopped(a);elapse(1000)
        assertTrue(controller.state.value.lockState.unlocked)
    }
    @Test fun additionalStopCannotExtendTheOriginalMinuteDeadline() {
        val a=privateActivity(60_000);leaveToSystem(a)
        elapse(30_000);PrivacyRuntime.onActivityStopped(a);Shadows.shadowOf(Looper.getMainLooper()).idle()
        elapse(29_999);assertTrue(controller.state.value.lockState.unlocked)
        elapse(1);assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun expiredSystemReturnRevokesOldAuthenticationBeforeResultConsumption() {
        val a=privateActivity(60_000);val attempt=controller.beginAuthentication();leaveToSystem(a,false)
        frozenElapse(60_000);PrivacyRuntime.systemReturn(a)
        assertFalse(controller.finishAuthentication(attempt,true));assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun expiredSystemReturnRevokesOriginalPickerSelectionBeforeConsumption() {
        val a=privateActivity(60_000);controller.initialize()
        val imports=ImportCoordinator(controller,fixture.scope,{true},{})
        val selection=checkNotNull(imports.beginSelection())
        leaveToSystem(a,false);frozenElapse(60_000);PrivacyRuntime.systemReturn(a)
        assertFalse(imports.selectionIsCurrent(selection));assertNull(imports.state.value)
    }
    @Test fun clearRejectsLateTripAuthenticationAndTimerCannotRestoreState() {
        val a=privateActivity(60_000);val attempt=controller.beginAuthentication();leaveToSystem(a)
        controller.clearAll();elapse(300_000);PrivacyRuntime.systemReturn(a)
        assertFalse(controller.finishAuthentication(attempt,true));assertNull(controller.state.value.conversation)
        assertTrue(controller.state.value.contacts.isEmpty());assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun ordinaryHomeWithoutSystemTripStillLocks() {
        val a=privateActivity(0);PrivacyRuntime.onActivityStopped(a);Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun alreadyLockedRealAuthenticationIsNotRevokedAgainByElapsedTrip() {
        val a=privateActivity(60_000);controller.updateLockState(LockState(true))
        val revision=controller.lockRevision;val attempt=controller.beginAuthentication()
        leaveToSystem(a,false);frozenElapse(300_000);PrivacyRuntime.systemReturn(a)
        assertEquals(revision,controller.lockRevision)
        assertTrue(controller.finishAuthentication(attempt,true));assertTrue(controller.state.value.lockState.unlocked)
    }
    @Test fun shortTripAuthenticationRemainsCurrent() {
        val a=privateActivity(60_000);val attempt=controller.beginAuthentication()
        leaveToSystem(a,false);frozenElapse(59_999);PrivacyRuntime.systemReturn(a)
        assertTrue(controller.finishAuthentication(attempt,true));PrivacyRuntime.onActivityStarted(a)
        elapse(60_001);assertTrue(controller.state.value.lockState.unlocked)
    }
    private fun projectionReturnAfter(ms:Long,expired:Boolean,policy:Long=60_000) {
        val app=RuntimeEnvironment.getApplication()
        preferences.edit().putLong("delay",policy).commit()
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val type=com.shengjidaguai.goutoujunshi.floating.FloatingCounselActivity::class.java
        val owner=Robolectric.buildActivity(type).create().start().resume().also(activities::add)
        val activity=owner.get()
        PrivacyRuntime.onActivityCreated(activity,null);PrivacyRuntime.onActivityStarted(activity)
        val ticket=com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.session.request()
        type.getDeclaredField("ticket").apply {isAccessible=true}.set(activity,ticket)
        type.getDeclaredMethod("startIfResumed").apply {isAccessible=true}.invoke(activity)
        val code=type.getDeclaredField("expectedProjectionCode").apply {isAccessible=true}.get(activity) as Int
        owner.pause().stop();PrivacyRuntime.onActivityStopped(activity);frozenElapse(ms)
        type.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
            .apply {isAccessible=true}.invoke(activity,code,Activity.RESULT_OK,Intent().putExtra("synthetic-grant",true))
        assertEquals(!expired,controller.state.value.lockState.unlocked)
        val grant=com.shengjidaguai.goutoujunshi.floating.ProjectionGrantStore.take(ticket)
        if(expired) {
            assertNull(grant);assertNull(Shadows.shadowOf(app).nextStartedService)
        } else {assertNotNull(grant);assertNotNull(Shadows.shadowOf(app).nextStartedService)}
        com.shengjidaguai.goutoujunshi.floating.ProjectionGrantStore.clear()
        com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.stop(app)
    }
    @Test fun expiredProjectionResultCannotLaunchServiceBeforeStartedCallback()=projectionReturnAfter(60_000,true)
    @Test fun shortProjectionResultStillLaunchesServiceBeforeStartedCallback()=projectionReturnAfter(59_999,false)
    @Test fun immediateAuthorizedProjectionReturnRemainsUsableForItsBoundedTrip()=projectionReturnAfter(1,false,0)
    @Test fun immediateExplicitPickerSelectionRemainsCurrentDuringItsBoundedTrip() {
        val a=privateActivity(0);controller.initialize()
        val imports=ImportCoordinator(controller,fixture.scope,{true},{})
        val selection=checkNotNull(imports.beginSelection())
        leaveToSystem(a,false);frozenElapse(1);PrivacyRuntime.systemReturn(a)
        assertTrue("explicit short selector must remain usable, rather than an endless reselect/relock loop",imports.selectionIsCurrent(selection))
        assertTrue(controller.state.value.lockState.unlocked)
    }
    @Test fun attachedPrivateAnswerWindowKeepsImmediateSessionUntilLastPrivateUiCloses() {
        val a=privateActivity(0)
        val app=RuntimeEnvironment.getApplication()
        val holder=Robolectric.buildService(com.shengjidaguai.goutoujunshi.floating.FloatingCounselService::class.java).create()
        val ticket=com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.session.request()
        val oldTicket=com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.runtimeTicket
        com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.runtimeTicket=ticket
        val panel=com.shengjidaguai.goutoujunshi.floating.FloatingAnswerWindow(holder.get(),fixture.scope)
        try {
            elapse(32)
            val view=com.shengjidaguai.goutoujunshi.floating.FloatingAnswerWindow::class.java.getDeclaredField("view").apply {isAccessible=true}.get(panel) as android.view.View
            assertTrue(view.isAttachedToWindow);assertTrue(view.isShown)
            PrivacyRuntime.onActivityStopped(a)
            assertTrue("only a real attached visible private panel can continue the private UI",controller.state.value.lockState.unlocked)
            panel.close();assertFalse("closing the last private panel must apply immediate locking",controller.state.value.lockState.unlocked)
        } finally {panel.close();holder.destroy();com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.runtimeTicket=oldTicket;com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.stop(app)}
    }
    private fun withPrivatePanel(action:(com.shengjidaguai.goutoujunshi.floating.FloatingAnswerWindow)->Unit) {
        val a=privateActivity(0)
        val floating=com.shengjidaguai.goutoujunshi.floating.FloatingRuntime
        val holder=Robolectric.buildService(com.shengjidaguai.goutoujunshi.floating.FloatingCounselService::class.java).create()
        val previous=floating.runtimeTicket;val oldAction=floating.captureAction;val oldActive=floating.active.value
        val accepted=floating.session.request();assertTrue(floating.session.accept(accepted));assertFalse(floating.session.valid(accepted))
        floating.runtimeTicket=accepted;floating.active.value=true
        val panel=com.shengjidaguai.goutoujunshi.floating.FloatingAnswerWindow(holder.get(),fixture.scope)
        try {elapse(32);PrivacyRuntime.onActivityStopped(a);assertTrue(controller.state.value.lockState.unlocked);action(panel)}
        finally {panel.close();holder.destroy();floating.runtimeTicket=previous;floating.captureAction=oldAction;floating.active.value=oldActive}
    }
    @Test fun actualMoreActionStartsHandoffBeforeLastPrivatePanelDetaches()=withPrivatePanel {panel ->
        var called=false
        com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.captureAction={
            assertTrue("the production More entry must not lock before invoking capture",controller.state.value.lockState.unlocked);called=true
        }
        panel.javaClass.getDeclaredMethod("captureMore").apply {isAccessible=true}.invoke(panel)
        assertTrue(called);assertTrue(panel.isClosed);assertTrue(controller.state.value.lockState.unlocked)
        elapse(30_000);assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test @Config(application=JunshiApplication::class)
    fun actualFullActionReachesActualMainAndNextHomeImmediatelyLocks()=withPrivatePanel {panel ->
        panel.javaClass.getDeclaredMethod("openFull").apply {isAccessible=true}.invoke(panel)
        assertTrue(panel.isClosed);assertTrue(controller.state.value.lockState.unlocked)
        val intent=checkNotNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(MainActivity::class.java.name,intent.component?.className)
        val main=Robolectric.buildActivity(MainActivity::class.java,intent).create().start().resume().also(activities::add)
        PrivacyRuntime.onActivityCreated(main.get(),null);PrivacyRuntime.onActivityStarted(main.get())
        assertTrue(controller.state.value.lockState.unlocked)
        main.pause().stop();PrivacyRuntime.onActivityStopped(main.get());assertFalse(controller.state.value.lockState.unlocked)
    }
    @Test fun stalePrivatePanelCannotBeginMoreOrFullAfterLockVersionChanges()=withPrivatePanel {panel ->
        var called=false
        com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.captureAction={called=true}
        controller.updateLockState(LockState(true))
        for(method in listOf("captureMore","openFull")) panel.javaClass.getDeclaredMethod(method).apply {isAccessible=true}.invoke(panel)
        assertFalse(called);assertNull(controller.currentPrivateUiHandoff())
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }
    @Test @Config(application=JunshiApplication::class)
    fun hotMainIntentChecksDeadlineBeforeStartOrResumeConsumesIt() {
        preferences.edit().putLong("delay",60_000).commit()
        val owner=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().also(activities::add)
        // Each Robolectric test creates another Application while the singleton can retain
        // install's callback flag. Drive the exact registered callbacks explicitly, as in
        // this class's other lifecycle tests, and keep the real Main.onNewIntent entry.
        PrivacyRuntime.onActivityCreated(owner.get(),null);PrivacyRuntime.onActivityStarted(owner.get())
        PrivacyRuntime.systemTrip(owner.get());owner.pause().stop();PrivacyRuntime.onActivityStopped(owner.get())
        assertNotNull(controller.javaClass.getDeclaredField("backgroundAt").apply {isAccessible=true}.get(controller))
        frozenElapse(60_000)
        MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply {isAccessible=true}.invoke(owner.get(),Intent())
        assertFalse("hot Intent must check elapsed deadline before private followup/share routing",controller.state.value.lockState.unlocked)
        assertTrue(owner.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
    }
}
