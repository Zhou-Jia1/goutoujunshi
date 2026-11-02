package com.shengjidaguai.goutoujunshi.floating

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class T34PermissionLifecycleTest {
    @After fun cleanup() { FloatingRuntime.stop(RuntimeEnvironment.getApplication());ProjectionGrantStore.clear() }
    private fun field(activity:FloatingCounselActivity,name:String,value:Any?) = FloatingCounselActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.set(activity,value)
    private fun invoke(activity:FloatingCounselActivity,name:String)=FloatingCounselActivity::class.java.getDeclaredMethod(name).apply {isAccessible=true}.invoke(activity)
    private fun request(activity:FloatingCounselActivity):Pair<Long,Int> {
        val ticket=FloatingRuntime.session.request();field(activity,"ticket",ticket)
        invoke(activity,"startIfResumed")
        val code=FloatingCounselActivity::class.java.getDeclaredField("expectedProjectionCode").apply {isAccessible=true}.get(activity) as Int
        return ticket to code
    }
    private fun deliver(activity:FloatingCounselActivity,code:Int) {
        FloatingCounselActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
            .apply {isAccessible=true}.invoke(activity,code,-1,Intent().putExtra("synthetic-grant","SYNTHETIC_T34_GRANT"))
    }
    @Test fun oldAResultNeverStartsOrCancelsNewBAndNeitherOldGrantNorTicketIsSaved() {
        ShadowSettings.setCanDrawOverlays(true)
        val app=RuntimeEnvironment.getApplication()
        val owner=Robolectric.buildActivity(FloatingCounselActivity::class.java).create().start().resume().visible()
        try {
            val a=request(owner.get());invoke(owner.get(),"cancelPending")
            val b=request(owner.get());assertNotEquals(a.second,b.second)
            deliver(owner.get(),a.second)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(Shadows.shadowOf(app).nextStartedService)
            assertTrue(FloatingRuntime.session.valid(b.first));assertNull(ProjectionGrantStore.take(a.first))
            val saved=Bundle();owner.saveInstanceState(saved)
            val parcel=android.os.Parcel.obtain()
            try {
                saved.writeToParcel(parcel,0)
                assertFalse(String(parcel.marshall(),Charsets.UTF_16LE).contains("SYNTHETIC_T34_GRANT"))
            } finally {parcel.recycle()}
            owner.pause().stop().destroy()
            val recreated=Robolectric.buildActivity(FloatingCounselActivity::class.java).create(saved).start().resume().visible()
            try {
                deliver(recreated.get(),b.second);Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertNull(Shadows.shadowOf(app).nextStartedService)
                assertNull(ProjectionGrantStore.take(b.first))
                assertNull(FloatingCounselActivity::class.java.getDeclaredField("ticket").apply {isAccessible=true}.get(recreated.get()))
            } finally {recreated.pause().stop().destroy()}
        } finally {if(!owner.get().isDestroyed) owner.pause().stop().destroy()}
    }
}
