package com.shengjidaguai.goutoujunshi.privacy

import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.*

class T55LockProtocolTest {
    private fun fixture(block:(CounselController)->Unit) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {block(CounselController(CounselControllerTest.MemoryPersistence(),TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"v1")
        }),object:AnalysisProvider {override val kind=ProviderKind.DEMO;override fun analyze(request:AnalysisRequest)=kotlinx.coroutines.flow.emptyFlow<AnalysisChunk>()},scope))} finally {scope.cancel()}
    }
    @Test fun canceledAuthenticationDoesNotUnlock()=fixture {c ->
        c.updateLockState(LockState(true));assertFalse(c.finishAuthentication(c.beginAuthentication(),false));assertFalse(c.state.value.lockState.unlocked)
    }
    @Test fun successfulAuthenticationIsOneShot()=fixture {c ->
        c.updateLockState(LockState(true));val a=c.beginAuthentication();assertTrue(c.finishAuthentication(a,true));assertFalse(c.finishAuthentication(a,true));assertTrue(c.state.value.lockState.unlocked)
    }
    @Test fun newAttemptRejectsOldSuccess()=fixture {c ->
        c.updateLockState(LockState(true));val a=c.beginAuthentication();val b=c.beginAuthentication();assertFalse(c.finishAuthentication(a,true));assertTrue(c.finishAuthentication(b,true))
    }
    @Test fun newLockRejectsOldSuccess()=fixture {c ->
        c.updateLockState(LockState(true));val a=c.beginAuthentication();c.updateLockState(LockState(true));assertFalse(c.finishAuthentication(a,true));assertFalse(c.state.value.lockState.unlocked)
    }
    @Test fun clearRejectsOldSuccess()=fixture {c ->
        c.updateLockState(LockState(true));val a=c.beginAuthentication();c.clearAll();assertFalse(c.finishAuthentication(a,true));assertFalse(c.state.value.lockState.unlocked)
    }
    @Test fun immediateBackgroundLocks()=fixture {c ->
        c.updateLockState(LockState(true,true));c.leftPrivateUi(100,0);assertFalse(c.state.value.lockState.unlocked)
    }
    @Test fun oneMinuteBoundaryUsesElapsedTime()=fixture {c ->
        c.updateLockState(LockState(true,true));c.leftPrivateUi(100,60_000);c.checkBackgroundLock(60_099,60_000);assertTrue(c.state.value.lockState.unlocked);c.checkBackgroundLock(60_100,60_000);assertFalse(c.state.value.lockState.unlocked)
    }
    @Test fun fiveMinuteBoundaryUsesElapsedTime()=fixture {c ->
        c.updateLockState(LockState(true,true));c.leftPrivateUi(100,300_000);c.checkBackgroundLock(300_099,300_000);assertTrue(c.state.value.lockState.unlocked);c.enteredPrivateUi(300_100,300_000);assertFalse(c.state.value.lockState.unlocked)
    }
    @Test fun earlyReturnCancelsBackgroundDeadline()=fixture {c ->
        c.updateLockState(LockState(true,true));c.leftPrivateUi(100,60_000);c.enteredPrivateUi(200,60_000);c.checkBackgroundLock(900_000,60_000);assertTrue(c.state.value.lockState.unlocked)
    }
    @Test fun elapsedClockResetFailsClosed()=fixture {c ->
        c.updateLockState(LockState(true,true));c.leftPrivateUi(100,60_000);c.checkBackgroundLock(99,60_000);assertFalse(c.state.value.lockState.unlocked)
    }
    @Test fun clearSuppressesNotificationDeliveryWhileCancellationMonitorStillRuns()=fixture {c ->
        c.clearAll()
        var deliveries=0;var cancellations=0
        c.withNotificationDelivery {deliveries++};c.withNotificationDisplay {cancellations++}
        assertEquals(0,deliveries);assertEquals(1,cancellations)
    }
    @Test fun failedImmediateStopStillReachesDurablePersistenceClear()=fixture {c ->
        c.clearAll {error("synthetic immediate stop failure")}
        assertFalse(c.state.value.loading);assertNull(c.state.value.conversation)
    }
    @Test fun staleSuccessCannotWriteEnabledPreferences()=fixture {c ->
        val a=c.beginAuthentication();c.clearAll();var writes=0
        assertFalse(c.finishAuthentication(a,true,true) {writes++;true});assertEquals(0,writes)
    }
    @Test fun failedPreferenceCommitDoesNotUnlock()=fixture {c ->
        c.updateLockState(LockState(true));val a=c.beginAuthentication()
        assertFalse(c.finishAuthentication(a,true,true) {false});assertFalse(c.state.value.lockState.unlocked)
    }
    @Test fun activityReentryAfterClearDoesNotAutomaticallyInitialize()=fixture {c ->
        c.clearAll();c.initializeOnEntry();assertNull(c.state.value.conversation)
        c.initialize();assertNotNull(c.state.value.conversation)
    }
}
