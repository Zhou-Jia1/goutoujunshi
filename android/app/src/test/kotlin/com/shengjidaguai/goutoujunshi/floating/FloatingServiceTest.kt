package com.shengjidaguai.goutoujunshi.floating

import android.app.*
import android.content.Intent
import androidx.lifecycle.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],shadows=[ProjectionTestManager::class,ProjectionTestProjection::class,ProjectionTestDisplay::class])
class FloatingServiceTest {
    private fun attachMeasuredWindow(view:android.view.View) {
        // addView schedules attach/traversal; exercise the real Compose child only afterwards.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
        assertTrue(view.isAttachedToWindow)
        val params=view.layoutParams
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(params.width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(params.height,android.view.View.MeasureSpec.EXACTLY))
        view.layout(0,0,params.width,params.height)
        assertTrue((view as android.view.ViewGroup).getChildAt(0).width>0)
    }
    @After fun stop() { FloatingRuntime.session.stop(); FloatingRuntime.active.value=false }
    private fun assertStartupOff(service:FloatingCounselService) {
        assertFalse(FloatingRuntime.active.value)
        assertEquals(CaptureState.Phase.OFF,FloatingRuntime.session.capture.phase)
        assertNull(FloatingCounselService::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(service))
        assertNull(FloatingCounselService::class.java.getDeclaredField("window").apply { isAccessible=true }.get(service))
        assertNull(FloatingRuntime.captureAction);assertNull(FloatingRuntime.stopCapture)
        assertEquals(0,service.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertTrue(org.robolectric.Shadows.shadowOf(service).isForegroundStopped)
    }
    private fun failedDisplayStartup(returnNull:Boolean) {
        ShadowSettings.setCanDrawOverlays(true)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get();val ticket=FloatingRuntime.session.request();putCurrentProjectionGrant(ticket)
        var input:android.media.ImageReader?=null
        ProjectionTestProjection.nullDisplay=returnNull
        ProjectionTestProjection.beforeDisplayReturn={
            val callback=checkNotNull(ProjectionTestProjection.registered).callback!!
            val owner=callback.javaClass.declaredFields.first { it.type==SingleFrameProjection::class.java }
                .apply { isAccessible=true }.get(callback) as SingleFrameProjection
            input=SingleFrameProjection::class.java.getDeclaredField("reader").apply { isAccessible=true }.get(owner) as android.media.ImageReader
            if(!returnNull) throw SecurityException("fixture display creation")
        }
        try {
            service.onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            val projection=checkNotNull(ProjectionTestProjection.registered)
            assertNull(projection.callback);assertEquals(1,projection.stops)
            assertThrows(IllegalStateException::class.java) { checkNotNull(input).acquireLatestImage()?.close() }
            assertStartupOff(service)
            holder.destroy();assertEquals(1,projection.stops)
        } finally {
            ProjectionTestProjection.beforeDisplayReturn=null;ProjectionTestProjection.nullDisplay=false
            holder.destroy()
        }
    }
    @Test fun displayCreationFailureReleasesStartupResourcesAndForeground() { failedDisplayStartup(false) }
    @Test fun nullDisplayCannotPublishReadyAndReleasesStartupResources() { failedDisplayStartup(true) }
    @Test fun inlineStopBeforeDisplayReturnCannotInstallWindowOrRuntimeCallbacks() {
        ShadowSettings.setCanDrawOverlays(true)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get();val ticket=FloatingRuntime.session.request();putCurrentProjectionGrant(ticket)
        ProjectionTestProjection.beforeDisplayReturn={ checkNotNull(ProjectionTestProjection.registered).callback!!.onStop() }
        try {
            service.onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            val projection=checkNotNull(ProjectionTestProjection.registered)
            assertEquals(1,org.robolectric.shadow.api.Shadow.extract<ProjectionTestDisplay>(checkNotNull(projection.display)).released)
            assertNull(projection.callback);assertEquals(1,projection.stops)
            assertStartupOff(service)
            holder.destroy();assertEquals(1,projection.stops)
        } finally { ProjectionTestProjection.beforeDisplayReturn=null;holder.destroy() }
    }
    @Test fun staleStartAndNullRestartNeverCreateWindowOrNotification() {
        val service=Robolectric.buildService(FloatingCounselService::class.java).create()
        val ticket=FloatingRuntime.session.request()
        putCurrentProjectionGrant(ticket)
        FloatingRuntime.session.stop()
        val newer=FloatingRuntime.session.request()
        assertEquals(Service.START_NOT_STICKY,service.get().onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1))
        assertFalse(FloatingRuntime.active.value)
        assertEquals(0,service.get().getSystemService(NotificationManager::class.java).activeNotifications.size)
        service.get().onStartCommand(null,0,2)
        assertFalse(FloatingRuntime.active.value)
        service.destroy()
        assertTrue(FloatingRuntime.session.valid(newer))
    }
    @Test fun missingOverlayPermissionCleansValidStart() {
        ShadowSettings.setCanDrawOverlays(false)
        val service=Robolectric.buildService(FloatingCounselService::class.java).create()
        val ticket=FloatingRuntime.session.request()
        putCurrentProjectionGrant(ticket)
        service.get().onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
        assertFalse(FloatingRuntime.active.value)
        assertFalse(FloatingRuntime.session.valid(ticket))
        assertEquals(0,service.get().getSystemService(NotificationManager::class.java).activeNotifications.size)
        service.destroy()
    }
    @Test fun ownersDestroyStoreExactlyOnce() {
        val owner=OverlayOwners()
        var cleared=0
        owner.viewModelStore.put("synthetic",object: ViewModel() { override fun onCleared() { cleared++ } })
        assertEquals(Lifecycle.State.RESUMED,owner.lifecycle.currentState)
        owner.destroy(); owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED,owner.lifecycle.currentState)
        assertEquals(1,cleared)
    }
    @Test fun dragDocksAndRemembersEachOrientationThenStopRemovesWindow() {
        ShadowSettings.setCanDrawOverlays(true)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get()
        val ticket=FloatingRuntime.session.request()
        putCurrentProjectionGrant(ticket)
        service.onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
        assertTrue(FloatingRuntime.active.value)
        val notification=service.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE,Notification::class.java.getDeclaredField("mFgsDeferBehavior").apply { isAccessible=true }.getInt(notification))
        assertEquals("悬浮军师已开启 · 点击关闭",notification.extras.getString(Notification.EXTRA_TITLE))
        val field=FloatingCounselService::class.java.getDeclaredField("window").apply { isAccessible=true }
        val view=field.get(service) as android.view.View
        val before=view.layoutParams as android.view.WindowManager.LayoutParams
        assertTrue(before.flags and android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertEquals(0,before.fitInsetsTypes)
        // Complete the WM traversal before dispatching into the actual Compose child.
        attachMeasuredWindow(view)
        val start=android.os.SystemClock.uptimeMillis()
        fun event(action:Int,x:Float,y:Float,time:Long) {
            val e=android.view.MotionEvent.obtain(start,time,action,x,y,0)
            view.dispatchTouchEvent(e); e.recycle()
        }
        event(android.view.MotionEvent.ACTION_DOWN,before.width/2f,before.height/2f,start)
        event(android.view.MotionEvent.ACTION_MOVE,-1000f,300f,start+100)
        event(android.view.MotionEvent.ACTION_UP,-1000f,300f,start+150)
        assertTrue((view.layoutParams as android.view.WindowManager.LayoutParams).x<0)
        val portraitY=before.y
        val config=android.content.res.Configuration(service.resources.configuration).apply { orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        service.resources.updateConfiguration(config,service.resources.displayMetrics)
        service.onConfigurationChanged(config)
        assertTrue((view.layoutParams as android.view.WindowManager.LayoutParams).x>=0)
        config.orientation=android.content.res.Configuration.ORIENTATION_PORTRAIT
        service.resources.updateConfiguration(config,service.resources.displayMetrics)
        service.onConfigurationChanged(config)
        assertEquals(portraitY,(view.layoutParams as android.view.WindowManager.LayoutParams).y)
        assertTrue((view.layoutParams as android.view.WindowManager.LayoutParams).x<0)
        service.onStartCommand(Intent().setAction(FloatingCounselService.STOP),0,2)
        assertNull(field.get(service))
        assertFalse(FloatingRuntime.active.value)
        assertEquals(0,service.getSystemService(NotificationManager::class.java).activeNotifications.size)
        val positions=service.getSharedPreferences(FloatingCounselService.POSITIONS,0)
        positions.edit().clear().commit()
        event(android.view.MotionEvent.ACTION_DOWN,before.width/2f,before.height/2f,start+200)
        event(android.view.MotionEvent.ACTION_MOVE,-1000f,300f,start+300)
        event(android.view.MotionEvent.ACTION_UP,-1000f,300f,start+350)
        assertTrue(positions.all.isEmpty())
        holder.destroy()
    }
    @Test fun inactivityTimeoutReleasesWindowForegroundAndNotification() {
        ShadowSettings.setCanDrawOverlays(true)
        FloatingRuntime.timeoutMinutes=10
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get();val ticket=FloatingRuntime.session.request()
        putCurrentProjectionGrant(ticket)
        try {
            service.onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            assertTrue(FloatingRuntime.active.value)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(9))
            assertTrue(FloatingRuntime.active.value)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
            assertFalse(FloatingRuntime.active.value)
            assertNull(FloatingCounselService::class.java.getDeclaredField("window").apply { isAccessible=true }.get(service))
            assertEquals(0,service.getSystemService(NotificationManager::class.java).activeNotifications.size)
        } finally { holder.destroy() }
    }
    @Test fun applicationLockWhileScreenIsOnStopsProjectionAndAllResources() {
        ShadowSettings.setCanDrawOverlays(true)
        val app=RuntimeEnvironment.getApplication()
        val controller=com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.get(app)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get();val ticket=FloatingRuntime.session.request()
        putCurrentProjectionGrant(ticket)
        try {
            service.onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            assertTrue(FloatingRuntime.active.value)
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=false))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(FloatingRuntime.active.value)
            assertNull(FloatingCounselService::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(service))
            assertNull(FloatingCounselService::class.java.getDeclaredField("window").apply { isAccessible=true }.get(service))
            assertEquals(0,service.getSystemService(NotificationManager::class.java).activeNotifications.size)
        } finally { holder.destroy();controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState()) }
    }
    @Test fun mainVisibleClickIsHandledWithHintAndNeverAttachesCaptureSurface() {
        ShadowSettings.setCanDrawOverlays(true)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get();val ticket=FloatingRuntime.session.request();val owner=Any()
        putCurrentProjectionGrant(ticket)
        try {
            service.onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            CaptureOwnUi.started(owner)
            FloatingRuntime.captureAction!!.invoke()
            assertTrue(CaptureOwnUi.notice.value)
            assertEquals(CaptureState.Phase.READY,FloatingRuntime.session.capture.phase)
            val capture=FloatingCounselService::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(service) as SingleFrameProjection
            val projection=SingleFrameProjection::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(capture) as android.media.projection.MediaProjection
            assertNull(org.robolectric.shadow.api.Shadow.extract<ProjectionTestProjection>(projection).display?.surface)
        } finally { CaptureOwnUi.hidden(owner);holder.destroy() }
    }
    @Test fun mainRevealedAfterHidingFloatingWindowCancelsBeforeFrameAndLeavesBallReady() {
        ShadowSettings.setCanDrawOverlays(true)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get();val ticket=FloatingRuntime.session.request();val owner=Any()
        putCurrentProjectionGrant(ticket)
        try {
            service.onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            FloatingRuntime.captureAction!!.invoke()
            assertEquals(CaptureState.Phase.CAPTURING,FloatingRuntime.session.capture.phase)
            CaptureOwnUi.started(owner)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(48))
            assertTrue(CaptureOwnUi.notice.value)
            assertEquals(CaptureState.Phase.READY,FloatingRuntime.session.capture.phase)
            assertTrue(FloatingRuntime.active.value)
        } finally { CaptureOwnUi.hidden(owner);holder.destroy() }
    }
    @Test fun lockRevisionChangedBeforeServiceDeliveryRejectsItsRamGrant() {
        ShadowSettings.setCanDrawOverlays(true)
        val app=RuntimeEnvironment.getApplication()
        val controller=com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.get(app)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val ticket=FloatingRuntime.session.request();putCurrentProjectionGrant(ticket)
        try {
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=false))
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=true))
            holder.get().onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            assertFalse(FloatingRuntime.active.value)
            assertNull(ProjectionGrantStore.take(ticket))
            assertEquals(0,holder.get().getSystemService(NotificationManager::class.java).activeNotifications.size)
        } finally { holder.destroy();controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState()) }
    }
    @Test fun rapidLockAndUnlockStillRevokesProjectionWhenFlowConflatesStates() {
        ShadowSettings.setCanDrawOverlays(true)
        val app=RuntimeEnvironment.getApplication()
        val controller=com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.get(app)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val ticket=FloatingRuntime.session.request();putCurrentProjectionGrant(ticket)
        try {
            holder.get().onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            assertTrue(FloatingRuntime.active.value)
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=false))
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=true))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(FloatingRuntime.active.value)
        } finally { holder.destroy();controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState()) }
    }
    @Test fun lockChangeDuringDisplayCreationCannotPublishActiveAfterCleanup() {
        ShadowSettings.setCanDrawOverlays(true)
        val app=RuntimeEnvironment.getApplication()
        val controller=com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.get(app)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val ticket=FloatingRuntime.session.request();putCurrentProjectionGrant(ticket)
        var input:android.media.ImageReader?=null
        ProjectionTestProjection.beforeDisplayReturn={
            val candidate=FloatingCounselService::class.java.getDeclaredField("projection").apply { isAccessible=true }.get(holder.get()) as SingleFrameProjection
            input=SingleFrameProjection::class.java.getDeclaredField("reader").apply { isAccessible=true }.get(candidate) as android.media.ImageReader
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=false))
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=true))
        }
        try {
            holder.get().onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            assertStartupOff(holder.get())
            val projection=checkNotNull(ProjectionTestProjection.registered)
            assertNull(projection.callback);assertEquals(1,projection.stops)
            assertEquals(1,org.robolectric.shadow.api.Shadow.extract<ProjectionTestDisplay>(checkNotNull(projection.display)).released)
            assertThrows(IllegalStateException::class.java) { checkNotNull(input).acquireLatestImage()?.close() }
        } finally { ProjectionTestProjection.beforeDisplayReturn=null;holder.destroy();controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState()) }
    }
    @Test fun oldServiceCleanupCannotStopTheNewPermissionState() {
        ShadowSettings.setCanDrawOverlays(true)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val old=FloatingRuntime.session.request();putCurrentProjectionGrant(old)
        holder.get().onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,old),0,1)
        val current=FloatingRuntime.session.request()
        holder.destroy()
        assertTrue(FloatingRuntime.session.valid(current))
        assertEquals(CaptureState.Phase.REQUESTING_PERMISSION,FloatingRuntime.session.capture.phase)
    }
    private fun realTap(longPress:Boolean) {
        ShadowSettings.setCanDrawOverlays(true)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get()
        try {
            val ticket=FloatingRuntime.session.request()
        putCurrentProjectionGrant(ticket)
            service.onStartCommand(Intent().putExtra(FloatingCounselService.TICKET,ticket),0,1)
            val field=FloatingCounselService::class.java.getDeclaredField("window").apply { isAccessible=true }
            val view=field.get(service) as android.view.View
            attachMeasuredWindow(view)
            val now=android.os.SystemClock.uptimeMillis()
            val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,view.width/2f,view.height/2f,0)
            assertTrue(view.dispatchTouchEvent(down)); down.recycle()
            if(longPress) org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout().toLong()+50))
            else {
                val up=android.view.MotionEvent.obtain(now,now+50,android.view.MotionEvent.ACTION_UP,view.width/2f,view.height/2f,0)
                assertTrue(view.dispatchTouchEvent(up)); up.recycle()
            }
            val opened=org.robolectric.Shadows.shadowOf(service.application).nextStartedActivity
            if(longPress) assertEquals(FloatingCounselActivity::class.java.name,opened?.component?.className)
            else { assertNull(opened);assertEquals(CaptureState.Phase.CAPTURING,FloatingRuntime.session.capture.phase) }
        } finally { holder.destroy() }
    }
    @Test fun measuredComposeChildDispatchesTapToCapture() { realTap(false) }
    @Test fun measuredComposeChildDispatchesLongPressToTheServiceMenu() { realTap(true) }
}
