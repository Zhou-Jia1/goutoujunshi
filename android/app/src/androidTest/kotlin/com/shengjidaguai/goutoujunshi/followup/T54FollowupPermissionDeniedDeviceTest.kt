package com.shengjidaguai.goutoujunshi.followup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.*
import com.shengjidaguai.goutoujunshi.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class T54FollowupPermissionDeniedDeviceTest {
 @Test fun actualDeniedPermissionCreatesZeroRecordsAndZeroWork()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  assertFalse("Root fixture must explicitly revoke POST_NOTIFICATIONS for this negative case",NotificationManagerCompat.from(context).areNotificationsEnabled())
  val db=Room.inMemoryDatabaseBuilder(context,JunshiDatabase::class.java).allowMainThreadQueries().build()
  try {
   val constructor=DataSession::class.java.getDeclaredConstructor(JunshiDatabase::class.java,java.lang.Long.TYPE,java.io.File::class.java).apply {isAccessible=true}
   val session=constructor.newInstance(db,1L,null)
   WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).build())
   val work=WorkManager.getInstance(context);val scheduler=FollowupScheduler(context,work);val repo=FollowupRepository(session);repo.bind(scheduler)
   assertNull(repo.schedule("synthetic-owner","synthetic-plan",2000,1000,true,scheduler.permitted()))
   assertTrue(db.dao().followups().isEmpty())
   assertTrue(work.getWorkInfos(WorkQuery.Builder.fromStates(listOf(WorkInfo.State.ENQUEUED,WorkInfo.State.RUNNING,WorkInfo.State.BLOCKED)).build()).get().isEmpty())
  } finally {db.close()}
 }
}
