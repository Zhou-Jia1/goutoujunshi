package com.shengjidaguai.goutoujunshi.privacy

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.view.WindowManager
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest
import com.shengjidaguai.goutoujunshi.counsel.CounselRuntime
import com.shengjidaguai.goutoujunshi.domain.LockState
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[35])
class T55FailedPreferenceCommitTest {
    private fun preferences()=RuntimeEnvironment.getApplication().getSharedPreferences("t55-config-synthetic",0).also {it.edit().clear().commit()}
    @Test fun newInstallWithoutFileDefaultsToDisabled() {assertFalse(PrivacyRuntime.readEnabled(preferences()) {false})}
    @Test fun existingEmptyUnreadableConfigurationFailsClosed() {assertTrue(PrivacyRuntime.readEnabled(preferences()) {true})}
    @Test fun invalidEnabledValueFailsClosed() {val p=preferences();p.edit().putString("enabled","invalid").commit();assertTrue(PrivacyRuntime.readEnabled(p) {true})}
    @Test fun persistedEnabledAndExplicitDisabledValuesAreRespected() {
        val p=preferences();p.edit().putBoolean("enabled",true).commit();assertTrue(PrivacyRuntime.readEnabled(p) {true})
        p.edit().putBoolean("enabled",false).commit();assertFalse(PrivacyRuntime.readEnabled(p) {true})
    }
    @Test fun preferenceReadExceptionFailsClosed() {
        val p=object:SharedPreferences by preferences() {override fun getAll():Map<String,*> = error("synthetic storage failure")}
        assertTrue(PrivacyRuntime.readEnabled(p) {true})
    }
    @Test fun failedDisableCommitCannotRemoveSecureFlagFromStillEnabledAuthority() {
        val base=RuntimeEnvironment.getApplication().getSharedPreferences("t55-failed-pref-synthetic",0)
        base.edit().putBoolean("enabled",true).commit()
        val failed=object:SharedPreferences by base {
            override fun edit():SharedPreferences.Editor {
                val editor=base.edit()
                return object:SharedPreferences.Editor by editor {
                    override fun putBoolean(key:String?,value:Boolean):SharedPreferences.Editor {editor.putBoolean(key,value);return this}
                    override fun commit():Boolean {editor.apply();return false}
                }
            }
        }
        val app=object:Application() {override fun getSharedPreferences(name:String,mode:Int)=failed}
        val appField=PrivacyRuntime::class.java.getDeclaredField("app").apply {isAccessible=true}
        val controllerField=CounselRuntime::class.java.getDeclaredField("controller").apply {isAccessible=true}
        val oldApp=appField.get(PrivacyRuntime);val oldController=controllerField.get(CounselRuntime)
        val f=CounselControllerTest.Fixture()
        val activity=Robolectric.buildActivity(Activity::class.java).create().start().resume()
        try {
            appField.set(PrivacyRuntime,app);controllerField.set(CounselRuntime,f.controller)
            f.controller.updateLockState(LockState(true,true))
            activity.get().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            assertFalse(PrivacyRuntime.setEnabled(false))
            assertTrue(f.controller.state.value.lockState.enabled)
            PrivacyRuntime.secure(activity.get())
            assertTrue("a failed preference commit must not unprotect the existing locked window",activity.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        } finally {activity.pause().stop().destroy();appField.set(PrivacyRuntime,oldApp);controllerField.set(CounselRuntime,oldController);f.close()}
    }
}
