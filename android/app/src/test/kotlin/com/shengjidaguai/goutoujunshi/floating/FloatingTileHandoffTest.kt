package com.shengjidaguai.goutoujunshi.floating

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class FloatingTileHandoffTest {
    @After fun cleanup() { FloatingRuntime.session.stop() }
    private fun intent(ticket:Long) = Intent(RuntimeEnvironment.getApplication(),FloatingCounselActivity::class.java)
        .setAction("com.shengjidaguai.goutoujunshi.FLOATING_TILE_START").putExtra(FloatingCounselService.TICKET,ticket)
    @Test fun currentTileTicketStartsFromResumedActivityWithoutSecondTap() {
        val app=RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val ticket=FloatingRuntime.session.request()
        val holder=Robolectric.buildActivity(FloatingCounselActivity::class.java,intent(ticket)).create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedService)
        val gate=FloatingCounselActivity::class.java.getDeclaredField("guideGate").apply {isAccessible=true}.get(holder.get()) as PermissionGuideGate
        val advance=FloatingCounselActivity::class.java.getDeclaredMethod("continueGuide",PermissionAuthority::class.java).apply {isAccessible=true}
        advance.invoke(holder.get(),gate.authority);advance.invoke(holder.get(),gate.authority)
        shadowOf(Looper.getMainLooper()).idle()
        deliverProjectionResult(holder.get())
        shadowOf(Looper.getMainLooper()).idle()
        val started=shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(ticket,started.getLongExtra(FloatingCounselService.TICKET,-1))
        holder.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedService)
        holder.pause().stop().destroy()
    }
    @Test fun staleOrMissingTileTicketDoesNotStartService() {
        val app=RuntimeEnvironment.getApplication()
        val ticket=FloatingRuntime.session.request(); FloatingRuntime.session.stop()
        val holder=Robolectric.buildActivity(FloatingCounselActivity::class.java,intent(ticket)).create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedService)
        holder.pause().stop().destroy()
        val missing=Robolectric.buildActivity(FloatingCounselActivity::class.java,intent(-1)).create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedService)
        missing.pause().stop().destroy()
    }
    @Test fun lockedTileTicketRequiresTheSharedLockBeforeAnyStart() {
        val app=RuntimeEnvironment.getApplication()
        val controller=com.shengjidaguai.goutoujunshi.counsel.CounselRuntime.get(app)
        controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState(enabled=true))
        try {
            val ticket=FloatingRuntime.session.request()
            val holder=Robolectric.buildActivity(FloatingCounselActivity::class.java,intent(ticket)).create().start().resume().visible()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(FloatingRuntime.session.valid(ticket))
            holder.pause().stop().destroy()
        } finally { controller.updateLockState(com.shengjidaguai.goutoujunshi.domain.LockState()) }
    }
}
