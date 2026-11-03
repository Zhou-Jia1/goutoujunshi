package com.shengjidaguai.goutoujunshi.followup

import android.app.*
import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowNotificationManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Models the documented posted-list boundary, not an assertion about API35 queue internals. */
@Implements(NotificationManager::class)
class QueuedFollowupNotificationManager:ShadowNotificationManager() {
    val pending=mutableListOf<Triple<String?,Int,Notification>>()
    var entered:CountDownLatch?=null
    var release:CountDownLatch?=null
    var atNotify:Notification?=null
    @Implementation override fun notify(tag:String?,id:Int,notification:Notification) {
        atNotify=notification
        entered?.countDown()
        check(release?.await(10,TimeUnit.SECONDS)!=false)
        synchronized(pending) {pending.add(Triple(tag,id,notification))}
    }
    @Implementation override fun cancel(tag:String?,id:Int) {
        synchronized(pending) {pending.removeAll {it.first==tag && it.second==id}}
        super.cancel(tag,id)
    }
    @Implementation override fun cancelAll() {
        synchronized(pending) {pending.clear()}
        super.cancelAll()
    }
    fun flushAccepted() {
        val accepted=synchronized(pending) {pending.toList().also {pending.clear()}}
        accepted.forEach {(tag,id,notification)->super.notify(tag,id,notification)}
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],shadows=[QueuedFollowupNotificationManager::class])
class T54NotificationQueueTest {
    private val context=RuntimeEnvironment.getApplication()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    private lateinit var controller:CounselController
    private lateinit var scheduler:FollowupScheduler
    private val runtimeField=CounselRuntime::class.java.getDeclaredField("controller").apply {isAccessible=true}
    private var originalController:Any?=null
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    private val platform get()=org.robolectric.shadow.api.Shadow.extract<QueuedFollowupNotificationManager>(manager)
    private fun record()=FollowupRecord(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"synthetic-owner","synthetic-plan",0,0)

    @Before fun setUp() {
        Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        controller=CounselController(CounselControllerTest.MemoryPersistence(),TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"v1")
        }),CounselControllerTest.ControlledProvider(),scope,onPrivacyLock={FollowupScheduler.hidePrivate(context)},localSafety={SafetyLevel.NONE})
        originalController=runtimeField.get(CounselRuntime)
        runtimeField.set(CounselRuntime,controller)
        controller.updateLockState(LockState(true,true))
        context.getSharedPreferences("followup-settings",0).edit().putBoolean("alias",true).commit()
        scheduler=FollowupScheduler(context)
    }
    @After fun tearDown() {runtimeField.set(CounselRuntime,originalController);scope.cancel()}

    @Test fun coldHideCancelsAcceptedNotificationAbsentFromPostedList() {
        scheduler.notify(record(),"合成代号")
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(1,platform.pending.size)
        assertTrue(platform.atNotify!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("合成代号"))
        FollowupScheduler.hidePrivate(context) // process-independent OS cancellation, no known-ID set
        platform.flushAccepted()
        assertTrue("A cancellation must cover accepted notifications before they become posted",manager.activeNotifications.isEmpty())
    }
    @Test fun lockCancelsAcceptedAliasBeforeItBecomesPosted() {
        scheduler.notify(record(),"合成代号")
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(1,platform.pending.size)
        assertTrue(platform.atNotify!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("合成代号"))
        controller.updateLockState(LockState(true,false))
        platform.flushAccepted()
        assertTrue("Lock must cover accepted alias notifications",manager.activeNotifications.isEmpty())
        assertFalse(controller.state.value.lockState.canDisplaySensitiveContent)
    }
    @Test fun aliasOffLinearizesWithAlreadyReadAliasAndSystemNotify() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        platform.entered=entered;platform.release=release
        val failure=AtomicReference<Throwable?>()
        val notifying=Thread {try {scheduler.notify(record(),"合成代号")} catch(t:Throwable) {failure.set(t)}}
        val changing=Thread {try {FollowupScheduler.setAliasVisibility(context,false)} catch(t:Throwable) {failure.set(t)}}
        try {
            notifying.start();assertTrue(entered.await(10,TimeUnit.SECONDS))
            assertTrue(platform.atNotify!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("合成代号"))
            changing.start()
            // Await either old unguarded completion or the real Controller monitor contention.
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(changing.isAlive && changing.state!=Thread.State.BLOCKED && System.nanoTime()<deadline) Thread.yield()
            assertTrue(!changing.isAlive || changing.state==Thread.State.BLOCKED)
        } finally {
            release.countDown();notifying.join(10000);changing.join(10000)
        }
        assertFalse(notifying.isAlive);assertFalse(changing.isAlive);failure.get()?.let {throw it}
        assertFalse(context.getSharedPreferences("followup-settings",0).getBoolean("alias",true))
        platform.flushAccepted()
        assertTrue("Alias-off must not finish before an old alias notify can escape cancellation",manager.activeNotifications.isEmpty())
    }
}
