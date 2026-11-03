package com.shengjidaguai.goutoujunshi.data

import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.Contact
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class T55ClearPhasesTest {
    @Test fun platformStopRunsAfterRevocationAndReminderCancellationBeforeDatabaseCloses()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).allowMainThreadQueries().build()
        val session=DataSession(db,1);ContactRepository(session).save(Contact("synthetic","合成"))
        val order=mutableListOf<String>()
        session.reminderEffects=object:FollowupEffects {
            override fun clear() {assertFalse(session.active);order+="reminders"}
            override fun cancel(id:String) {};override fun retain(ids:Set<String>) {}
        }
        session.closeForClear {assertFalse(session.active);assertTrue(db.isOpen);order+="platform"}
        assertFalse(db.isOpen);assertEquals(listOf("reminders","platform"),order)
        assertTrue(runCatching {ContactRepository(session).save(Contact("late","合成迟到"))}.isFailure)
    }
    @Test fun reminderAndPlatformFailuresStillRunEveryStopAndCloseDatabase()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).allowMainThreadQueries().build()
        val session=DataSession(db,1);ContactRepository(session).save(Contact("synthetic","合成"))
        var stopped=false
        session.reminderEffects=object:FollowupEffects {
            override fun clear() {error("synthetic reminder failure")}
            override fun cancel(id:String) {};override fun retain(ids:Set<String>) {}
        }
        assertTrue(runCatching {session.closeForClear {assertFalse(session.active);stopped=true;error("synthetic platform failure")}}.isFailure)
        assertTrue(stopped);assertFalse(session.active);assertFalse(db.isOpen)
    }
}
