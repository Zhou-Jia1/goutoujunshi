package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class FloatingActivityCancellationTest {
    @After fun cleanup() { FloatingRuntime.session.stop() }
    @Test fun olderActivityCannotCancelNewerPermissionRequest() {
        val a=Robolectric.buildActivity(FloatingCounselActivity::class.java).create().start().resume()
        val ticketA=FloatingRuntime.session.request()
        FloatingCounselActivity::class.java.getDeclaredField("ticket").apply { isAccessible=true }.set(a.get(),ticketA)
        val ticketB=FloatingRuntime.session.request()
        a.pause().stop().destroy()
        assertTrue(FloatingRuntime.session.valid(ticketB))
    }
    @Test fun cancellingOldCredentialTripCannotCancelAnotherActivityStart() {
        val a=Robolectric.buildActivity(FloatingCounselActivity::class.java).create().start().resume()
        FloatingCounselActivity::class.java.getDeclaredField("authEpoch").apply { isAccessible=true }.set(a.get(),FloatingRuntime.session.epoch())
        val ticketB=FloatingRuntime.session.request()
        a.pause().stop().destroy()
        assertTrue(FloatingRuntime.session.valid(ticketB))
    }
    @Test fun oldProjectionResultCannotGrantNewerActivityRequest() {
        val holder=Robolectric.buildActivity(FloatingCounselActivity::class.java).create().start().resume()
        val old=FloatingRuntime.session.request()
        FloatingCounselActivity::class.java.getDeclaredField("ticket").apply { isAccessible=true }.set(holder.get(),old)
        val replacement=FloatingRuntime.session.request()
        deliverProjectionResult(holder.get())
        assertNull(ProjectionGrantStore.take(replacement))
        assertTrue(FloatingRuntime.session.valid(replacement))
        holder.pause().stop().destroy()
        assertTrue(FloatingRuntime.session.valid(replacement))
    }

    @Test fun cleanupOnlyFinishesWindowsOwnedByItsSession() {
        val old=Robolectric.buildActivity(FloatingCounselActivity::class.java).create().start().resume()
        val replacement=Robolectric.buildActivity(FloatingCounselActivity::class.java).create().start().resume()
        FloatingRuntime.bindWindow(old.get(),10);FloatingRuntime.bindWindow(replacement.get(),11)
        FloatingRuntime.hideWindows(10)
        assertTrue(old.get().isFinishing);assertFalse(replacement.get().isFinishing)
        old.pause().stop().destroy();replacement.pause().stop().destroy()
    }

    private fun requestProjection(holder:org.robolectric.android.controller.ActivityController<FloatingCounselActivity>):Long {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val ticket=FloatingRuntime.session.request()
        FloatingCounselActivity::class.java.getDeclaredField("ticket").apply { isAccessible=true }.set(holder.get(),ticket)
        FloatingCounselActivity::class.java.getDeclaredMethod("startIfResumed").apply { isAccessible=true }.invoke(holder.get())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return ticket
    }
    @Test fun currentSingleAppResultStartsServiceWhileHostPausedWithoutRequestingNewConsent() {
        val app=RuntimeEnvironment.getApplication()
        val holder=Robolectric.buildActivity(FloatingCounselActivity::class.java).create().start().resume().visible()
        try {
            val ticket=requestProjection(holder)
            assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
            holder.pause()
            deliverProjectionResult(holder.get())
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val service=org.robolectric.Shadows.shadowOf(app).nextStartedService
            assertNotNull(service)
            assertEquals(ticket,service!!.getLongExtra(FloatingCounselService.TICKET,-1))
            assertEquals(setOf(FloatingCounselService.TICKET),service.extras!!.keySet())
            assertNotNull(ProjectionGrantStore.take(ticket))
        } finally { holder.stop().destroy();ProjectionGrantStore.clear() }
    }
    @Test fun lockThenUnlockCannotReauthorizeEarlierProjectionResult() {
        val app=RuntimeEnvironment.getApplication()
        val controller=com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.get(app)
        val holder=Robolectric.buildActivity(FloatingCounselActivity::class.java).create().start().resume().visible()
        try {
            val ticket=requestProjection(holder)
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=false))
            controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true,unlocked=true))
            deliverProjectionResult(holder.get())
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
            assertNull(ProjectionGrantStore.take(ticket));assertFalse(FloatingRuntime.session.valid(ticket))
        } finally { holder.pause().stop().destroy();controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState());ProjectionGrantStore.clear() }
    }

}
