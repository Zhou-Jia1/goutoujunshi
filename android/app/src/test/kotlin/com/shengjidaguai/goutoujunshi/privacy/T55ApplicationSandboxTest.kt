package com.shengjidaguai.goutoujunshi.privacy

import android.app.Activity
import android.app.Application
import android.content.*
import android.os.Process
import android.view.WindowManager
import com.shengjidaguai.goutoujunshi.JunshiApplication
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.floating.FloatingRuntime
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Cached API28/33/35 runtime probes; the public UID API is available since API24. */
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[28,33,35])
class T55ApplicationSandboxTest {
    private val appField=PrivacyRuntime::class.java.getDeclaredField("app").apply {isAccessible=true}
    private val installed=PrivacyRuntime::class.java.getDeclaredField("initialized").apply {isAccessible=true}
    private val controllerField=CounselRuntime::class.java.getDeclaredField("controller").apply {isAccessible=true}
    private val watching=FloatingRuntime::class.java.getDeclaredField("watching").apply {isAccessible=true}
    private var oldApp:Any?=null
    private var oldController:Any?=null
    private var oldInstalled=false
    private var oldWatching=false
    private var oldUid=0
    private var fixture:CounselControllerTest.Fixture?=null
    private val probes=mutableListOf<PrivateContext>()
    @Before fun isolateFixture() {
        oldApp=appField.get(PrivacyRuntime);oldController=controllerField.get(CounselRuntime)
        oldInstalled=installed.getBoolean(PrivacyRuntime);oldWatching=watching.getBoolean(FloatingRuntime);oldUid=Process.myUid()
        installed.setBoolean(PrivacyRuntime,false);controllerField.set(CounselRuntime,null);watching.setBoolean(FloatingRuntime,false)
    }
    @After fun restoreFixture() {
        probes.forEach {p->p.receivers.distinct().forEach {runCatching {p.unregisterReceiver(it)}}}
        appField.set(PrivacyRuntime,oldApp);installed.setBoolean(PrivacyRuntime,oldInstalled)
        controllerField.set(CounselRuntime,oldController);watching.setBoolean(FloatingRuntime,oldWatching)
        ShadowProcess.setUid(oldUid);fixture?.close()
    }
    private class PrivateContext(base:Context,val denied:Boolean):ContextWrapper(base) {
        var preferences=0;var files=0
        val receivers=mutableListOf<BroadcastReceiver>()
        val failure=IllegalStateException("synthetic isolated UserManager cannot expose private runtime")
        override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
            preferences++;if(denied) throw failure
            return super.getSharedPreferences(name,mode)
        }
        override fun getFilesDir():File {files++;if(denied) throw failure;return super.getFilesDir()}
        override fun registerReceiver(receiver:BroadcastReceiver?,filter:IntentFilter):Intent? {
            if(denied) throw failure
            receiver?.let(receivers::add);return super.registerReceiver(receiver,filter)
        }
        override fun registerReceiver(receiver:BroadcastReceiver?,filter:IntentFilter,flags:Int):Intent? {
            if(denied) throw failure
            receiver?.let(receivers::add);return super.registerReceiver(receiver,filter,flags)
        }
    }
    private fun application(denied:Boolean):Pair<JunshiApplication,PrivateContext> {
        val probe=PrivateContext(RuntimeEnvironment.getApplication(),denied).also(probes::add)
        val app=JunshiApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app,"attachBaseContext",ReflectionHelpers.ClassParameter.from(Context::class.java,probe))
        return app to probe
    }
    @Test fun nonAppSandboxNeverTouchesPrivateRuntime() {
        // A non-application sandbox fixture, not a production UID-range implementation.
        ShadowProcess.setUid(Process.LAST_APPLICATION_UID+1)
        assertFalse(Process.isApplicationUid(Process.myUid()))
        val (app,probe)=application(true)
        val failure=runCatching {app.onCreate()}.exceptionOrNull()
        assertNull("real Application startup must not enter private runtime in a non-App sandbox",failure)
        assertEquals(0,probe.preferences);assertEquals(0,probe.files);assertTrue(probe.receivers.isEmpty())
        assertNull(controllerField.get(CounselRuntime));assertFalse(installed.getBoolean(PrivacyRuntime))
    }
    private fun installsBeforePrivateWindow(malformed:Boolean) {
        ShadowProcess.setUid(Process.FIRST_APPLICATION_UID)
        assertTrue(Process.isApplicationUid(Process.myUid()))
        val base=RuntimeEnvironment.getApplication()
        val preferences=base.getSharedPreferences("privacy",0)
        val edit=preferences.edit().clear()
        if(malformed) edit.putString("enabled","synthetic malformed") else edit.putBoolean("enabled",true)
        assertTrue(edit.commit())
        val f=CounselControllerTest.Fixture().also {fixture=it};controllerField.set(CounselRuntime,f.controller)
        val (app,probe)=application(false)
        app.onCreate()
        assertTrue(probe.preferences>0);assertTrue(installed.getBoolean(PrivacyRuntime))
        assertTrue(f.controller.state.value.lockState.enabled);assertFalse(f.controller.state.value.lockState.unlocked)
        val callbacks=Application::class.java.getDeclaredField("mActivityLifecycleCallbacks").apply {isAccessible=true}.get(app) as List<*>
        val callback=callbacks.filterIsInstance<Application.ActivityLifecycleCallbacks>().single {it===PrivacyRuntime}
        val activity=Robolectric.buildActivity(Activity::class.java).create()
        try {
            callback.onActivityCreated(activity.get(),null)
            assertTrue("the installed callback must secure the window before start/draw",activity.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        } finally {callback.onActivityDestroyed(activity.get());activity.destroy()}
    }
    @Test fun ordinaryAppSandboxInstallsPersistedLockBeforePrivateWindow()=installsBeforePrivateWindow(false)
    @Test fun ordinaryAppSandboxMalformedSettingsFailClosedBeforePrivateWindow()=installsBeforePrivateWindow(true)
    @Test fun ordinaryAppSandboxInitializationFailureIsNotSilentlySkipped() {
        ShadowProcess.setUid(Process.FIRST_APPLICATION_UID)
        assertTrue(Process.isApplicationUid(Process.myUid()))
        val (app,probe)=application(true)
        assertSame(probe.failure,runCatching {app.onCreate()}.exceptionOrNull())
        assertEquals(1,probe.preferences)
    }
}
