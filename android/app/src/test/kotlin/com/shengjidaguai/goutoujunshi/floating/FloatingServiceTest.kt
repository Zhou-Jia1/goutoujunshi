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
@Config(application=Application::class,sdk=[35])
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
    @Test fun staleStartAndNullRestartNeverCreateWindowOrNotification() {
        val service=Robolectric.buildService(FloatingCounselService::class.java).create()
        val ticket=FloatingRuntime.session.request()
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
    private fun realTap(longPress:Boolean) {
        ShadowSettings.setCanDrawOverlays(true)
        val holder=Robolectric.buildService(FloatingCounselService::class.java).create()
        val service=holder.get()
        try {
            val ticket=FloatingRuntime.session.request()
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
            assertEquals(FloatingCounselActivity::class.java.name,opened?.component?.className)
        } finally { holder.destroy() }
    }
    @Test fun measuredComposeChildDispatchesTapToTheServiceMenu() { realTap(false) }
    @Test fun measuredComposeChildDispatchesLongPressToTheServiceMenu() { realTap(true) }
}
