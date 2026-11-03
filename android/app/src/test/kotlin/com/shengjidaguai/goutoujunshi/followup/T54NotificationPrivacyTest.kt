package com.shengjidaguai.goutoujunshi.followup
import android.app.*
import androidx.work.Configuration
import androidx.work.testing.*
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import kotlinx.coroutines.*
import com.shengjidaguai.goutoujunshi.data.FollowupRecord
import com.shengjidaguai.goutoujunshi.domain.LockState
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(application=Application::class,sdk=[35])
class T54NotificationPrivacyTest {
 @Test fun aliasRequiresExplicitSettingAndUnlockedAuthorityAndLockRemovesShownAlias() {
  val context=RuntimeEnvironment.getApplication()
  WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)
  Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  val controller=CounselController(CounselControllerTest.MemoryPersistence(),TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
   override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"v1")
  }),CounselControllerTest.ControlledProvider(),scope,onPrivacyLock={FollowupScheduler.hidePrivate(context)},localSafety={SafetyLevel.NONE})
  val scheduler=FollowupScheduler(context,displayAuthority=controller::withNotificationDisplay);val manager=context.getSystemService(NotificationManager::class.java)
  context.getSharedPreferences("followup-settings",0).edit().putBoolean("alias",false).commit()
  controller.updateLockState(LockState(true,true))
  val r=FollowupRecord(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),"owner","plan",0,0)
  val alias="合成隐私代号"
  scheduler.notify(r,alias)
  assertFalse(manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(alias))
  context.getSharedPreferences("followup-settings",0).edit().putBoolean("alias",true).commit()
  controller.updateLockState(LockState(true,false));scheduler.notify(r,alias)
  assertFalse(manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(alias))
  controller.updateLockState(LockState(true,true));scheduler.notify(r,alias)
  assertTrue(manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(alias))
  controller.updateLockState(LockState(true,false));assertTrue(manager.activeNotifications.isEmpty())
  scheduler.notify(r,alias)
  assertFalse(manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(alias))
  scheduler.cancel(r.id);scope.cancel()
 }
}
