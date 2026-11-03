package com.shengjidaguai.goutoujunshi.privacy

import com.shengjidaguai.goutoujunshi.counsel.*
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.domain.*
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.*

class T55ClearAuthorityTest {
    @Test fun clearImmediatelyRevokesAuthenticationRevisionBeforeActorRuns() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=CounselController(CounselControllerTest.MemoryPersistence(),TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request:AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"v1")
        }),object:AnalysisProvider {override val kind=ProviderKind.DEMO;override fun analyze(request:AnalysisRequest)=kotlinx.coroutines.flow.emptyFlow<AnalysisChunk>()},scope)
        controller.updateLockState(LockState(true,true))
        val before=controller.lockRevision
        controller.clearAll()
        assertTrue("wipe must revoke pending OS authentication",controller.lockRevision>before)
        scope.cancel()
    }
}
