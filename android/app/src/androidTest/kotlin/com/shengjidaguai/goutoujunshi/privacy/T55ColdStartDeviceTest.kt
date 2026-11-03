package com.shengjidaguai.goutoujunshi.privacy

import android.app.Application
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shengjidaguai.goutoujunshi.MainActivity
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.domain.LockState
import org.junit.*
import org.junit.Assert.*

/** Run in a new instrumentation process after root terminates the real authenticated prep. */
class T55ColdStartDeviceTest {
    @Test fun persistedLockLoadsLockedAndSecureAtFirstDrawWithoutResetOrFakeUnlock() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as Application
        val controller=CounselRuntime.get(app)
        assertTrue("root must run real authentication prep in an earlier process",app.getSharedPreferences("privacy",0).getBoolean("enabled",false))
        assertEquals(LockState(true,false),controller.state.value.lockState)
        val first=java.util.concurrent.atomic.AtomicReference<Pair<Int,LockState>?>()
        val monitor=ActivityLifecycleMonitorRegistry.getInstance()
        val callback=ActivityLifecycleCallback {activity,stage ->
            if(activity is MainActivity && stage==Stage.CREATED) {
                activity.window.decorView.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw():Boolean {
                        first.compareAndSet(null,activity.window.attributes.flags to controller.state.value.lockState)
                        activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                        return true
                    }
                })
            }
        }
        instrumentation.runOnMainSync {monitor.addLifecycleCallback(callback)}
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val end=android.os.SystemClock.uptimeMillis()+15000
                while(first.get()==null && android.os.SystemClock.uptimeMillis()<end) android.os.SystemClock.sleep(50)
                val observed=checkNotNull(first.get())
                assertTrue(observed.first and WindowManager.LayoutParams.FLAG_SECURE!=0)
                assertEquals(LockState(true,false),observed.second)
                assertFalse(controller.state.value.lockState.canDisplaySensitiveContent)
                println("T55_COLD_PROBE pid=${android.os.Process.myPid()} firstDrawSecure=true firstDrawLocked=true noConfigurationReset=true")
            }
        } finally {
            instrumentation.runOnMainSync {monitor.removeLifecycleCallback(callback);PrivacyRuntime.setEnabled(false);LauncherAliases.select(app,0)}
        }
    }
}
