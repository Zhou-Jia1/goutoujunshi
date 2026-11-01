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
}
