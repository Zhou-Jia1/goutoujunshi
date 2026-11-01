package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class LongTermMemoryControllerTest {
    internal class Persistence(val base: CounselControllerTest.MemoryPersistence=CounselControllerTest.MemoryPersistence()): CounselPersistence by base {
        var consent=ConsentState()
        var writes=0
        var revision=0L
        var writeFailure: Exception?=null
        var failAfterCommit=false
        var manualCommits=0
        var beforeApprove: CompletableDeferred<Unit>?=null
        var duringApprove: CompletableDeferred<Unit>?=null
        var approvalStarted=false
        var approvalCalls=0
        var committedSnapshotFailures=0
        var committedDetailsFailures=0
        override suspend fun contactDetails(contact: Contact): com.shengjidaguai.goutoujunshi.data.ContactDetails {
            if(consent.enabled && committedDetailsFailures>0) { committedDetailsFailures--;error("synthetic post-commit details read failure") }
            return base.contactDetails(contact)
        }
        var sourceLoadGate: CompletableDeferred<Unit>?=null
        var sourceLoadStarted=false
        override suspend fun loadOrCreate(contact: Contact): StoredCounsel {
            sourceLoadStarted=true;sourceLoadGate?.await();return base.loadOrCreate(contact)
        }
        var approvalFailure=false
        var approvalLostAck=false
        private val approvals=mutableMapOf<String,Pair<MemoryProposal,MemoryOperation>>()
        var lastOperation: MemoryOperation?=null
        val entries=mutableListOf<MemoryEntry>()
        override suspend fun memorySnapshot(owner: String?): MemorySnapshot {
            if(consent.enabled && committedSnapshotFailures>0) { committedSnapshotFailures--;error("synthetic post-commit memory snapshot failure") }
            return MemorySnapshot(consent,WriteStamp(consent.consentVersion,0,1,0,revision),entries.filter { it.contactId==owner },verifiedInferenceIds=entries.map { it.id }.toSet(),operationId=lastOperation?.id,clearAuthority=MemoryClearAuthority(1,consent.consentVersion,"$revision:${entries.map { it.id }}",entries.size,base.people.size,0))
        }
        override suspend fun memoryConsent(enabled: Boolean,paused: Boolean,stamp: WriteStamp,clearAuthority: MemoryClearAuthority?,onAuthorized: () -> Unit) { require(stamp.generation==1L && stamp.consentVersion==consent.consentVersion);if(!enabled) require(clearAuthority==memorySnapshot(null).clearAuthority);onAuthorized();consent=consent.copy(enabled=enabled,paused=paused,consentVersion=consent.consentVersion+1);if(!enabled) entries.clear() }
        override suspend fun memoryDecline(owner: String,stamp: WriteStamp,until: Long,onAuthorized: () -> Unit) { require(stamp.generation==1L && stamp.consentVersion==consent.consentVersion && !consent.enabled);onAuthorized();consent=consent.copy(declinedUntil=until,consentVersion=consent.consentVersion+1) }
        override suspend fun clearMemories(authority: MemoryClearAuthority,onAuthorized: () -> Unit) { require(authority==memorySnapshot(null).clearAuthority);onAuthorized();entries.clear();revision++ }
        override suspend fun memoryDelete(owner: String,id: String,stamp: WriteStamp,wrong: Boolean) { error("synthetic A storage failure") }
        override suspend fun memoryApprove(owner: String,proposal: MemoryProposal,stamp: WriteStamp,operationId: String,onAuthorized: () -> Unit): MemoryOperation {
            approvalStarted=true;approvalCalls++
            beforeApprove?.await()
            approvals[operationId]?.let { (original,committed) ->
                require(original==proposal && consent.enabled && consent.consentVersion==stamp.consentVersion+1)
                onAuthorized();return committed
            }
            require(!consent.enabled && consent.consentVersion==stamp.consentVersion)
            onAuthorized()
            if(approvalFailure) { approvalFailure=false;error("synthetic approval storage failure") }
            val next=consent.copy(enabled=true,consentVersion=consent.consentVersion+1)
            val entry=MemoryEntry("approval:$operationId",owner,subjectId=owner,field=proposal.field,value=proposal.value,confidence=Confidence.LOW)
            val after=MemoryPolicy.planWrite(entry,next,next.consentVersion,owner,entries)
            duringApprove?.await()
            onAuthorized()
            val op=MemoryOperation(operationId,owner,entries.toList(),after)
            consent=next;entries.clear();entries.addAll(after);revision++;writes++
            approvals[operationId]=proposal to op;lastOperation=op
            if(approvalLostAck) { approvalLostAck=false;error("synthetic lost approval acknowledgement") }
            return op
        }
        override suspend fun memoryEdit(owner: String,entry: MemoryEntry,stamp: WriteStamp,operationId: String): MemoryOperation {
            writeFailure?.let { writeFailure=null;throw it }
            lastOperation?.takeIf { it.id==operationId }?.let { previous ->
                require(previous.after.first { it.id==entry.id }.copy(createdAt=0,updatedAt=0)==entry.copy(createdAt=0,updatedAt=0))
                return previous
            }
            val before=entries.toList()
            val after=MemoryPolicy.planWrite(entry,consent,stamp.consentVersion,owner,before)
            entries.clear();entries.addAll(after);revision++;manualCommits++
            val operation=MemoryOperation(operationId,owner,before,after)
            lastOperation=operation
            if(failAfterCommit) { failAfterCommit=false;error("synthetic lost storage acknowledgement") }
            return operation
        }
        override suspend fun memoryProposal(owner: String,proposal: MemoryProposal,stamp: WriteStamp): MemoryOperation {
            MemoryPolicy.requireConsent(consent,stamp.consentVersion)
            writes++;revision++
            entries+=MemoryEntry("saved-$writes",owner,subjectId=owner,field=proposal.field,value=proposal.value)
            return MemoryOperation("operation-$writes",owner,after=entries.toList())
        }
    }
    internal class Fixture(kind: ProviderKind=ProviderKind.BYOK) {
        val persistence=Persistence()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val noticeGate=CompletableDeferred<Unit>()
        var noticeDelayMillis=0L
        val outputs=Channel<AnalysisChunk>(Channel.UNLIMITED)
        var providerCalls=0
        val provider=object: AnalysisProvider { override val kind=kind; override fun analyze(request: AnalysisRequest)=flow { providerCalls++;for(item in outputs) emit(item) } }
        val controller=CounselController(persistence,TemporarySessionStore(),FrozenPromptAssembler(object:PromptAssembler {
            override fun prepare(request: AnalysisRequest)=PreparedPrompt("synthetic","synthetic",emptyList(),"test")
        }),provider,scope,wallClockMillis={1000},memoryNoticeDelay={ noticeDelayMillis=it;noticeGate.await() })
        suspend fun complete(value: String="虚构猜测") {
            outputs.send(AnalysisChunk.Completed(StructuredAnswer(id="synthetic",reply=listOf("虚构回复"),memoryProposals=listOf(MemoryProposal(subjectId="ta",field="pattern",value=value)))))
            yield()
        }
        fun close()=scope.cancel()
    }
    @Test fun consentAppearsOnlyAfterValidFormalLiveCompletionAndDeclineSuppressesThirtyDays()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize(); assertNull(f.controller.memory.value.consentProposal)
            f.controller.createContact("虚构甲",RelationType.FRIEND);f.controller.editDraft("虚构输入");f.controller.analyze();f.complete()
            assertNotNull(f.controller.memory.value.consentProposal);assertEquals(0,f.persistence.writes)
            f.controller.declineMemory(f.controller.memory.value.receipt!!)
            assertTrue(f.persistence.consent.declinedUntil>1000)
            f.controller.editDraft("虚构输入二");f.controller.analyze();f.complete()
            assertNull(f.controller.memory.value.consentProposal);assertEquals(0,f.persistence.writes)
        } finally { f.close() }
    }
    @Test fun demoAndTemporaryNeverOfferPermanentConsentOrWrite()=runBlocking {
        for(kind in listOf(ProviderKind.DEMO,ProviderKind.BYOK)) {
            val f=Fixture(kind)
            try {
                f.controller.initialize()
                if(kind==ProviderKind.DEMO) f.controller.createContact("虚构甲",RelationType.FRIEND)
                f.controller.editDraft("虚构输入");f.controller.analyze();f.complete()
                assertNull(f.controller.memory.value.consentProposal);assertEquals(0,f.persistence.writes)
            } finally { f.close() }
        }
    }
    @Test fun threeAutomaticAppendsKeepCompletedAnswerCopyAndContinuousHistory()=runBlocking {
        val f=Fixture()
        try {
            f.persistence.consent=ConsentState(enabled=true,consentVersion=1)
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            repeat(3) { index ->
                f.controller.editDraft("虚构问题$index");f.controller.analyze();f.complete("虚构猜测$index")
                assertNotNull(f.controller.state.value.answer)
                assertTrue(f.controller.state.value.hasCurrentAnswerSource())
                assertNotNull(f.controller.memory.value.notice)
                if(index>0) assertTrue(f.controller.state.value.answerRequest!!.recentSummary.contains("历史建议"))
            }
            assertEquals(3,f.persistence.writes)
            f.controller.setMemoryEnabled(true,true,f.controller.memory.value.receipt!!)
            assertNull(f.controller.state.value.answer)
        } finally { f.close() }
    }
    @Test fun explicitAddCanPopulateEveryGroupAndOrdinaryEditNeverUpgradesModelProvenance()=runBlocking {
        val f=Fixture()
        try {
            f.persistence.consent=ConsentState(enabled=true,consentVersion=1)
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            val owner=f.controller.state.value.currentContact!!.id
            f.controller.openMemory(owner)
            MemoryScope.entries.forEach { group ->
                f.controller.addMemory(owner,group,"用户主动确认${group.name}",f.controller.memoryPage.value.receipt!!,"synthetic-editor-${group.name}")
            }
            assertEquals(MemoryScope.entries.toSet(),f.persistence.entries.map { it.scope }.toSet())
            assertTrue(f.persistence.entries.all { it.sourceType==MemorySource.USER_EXPLICIT })
            assertTrue(f.controller.state.value.messages.isEmpty())
            val hypothesis=MemoryEntry("model-hypothesis",owner,subjectId=owner,field="pattern",value="虚构猜测",confidence=Confidence.LOW)
            f.persistence.entries+=hypothesis
            f.controller.openMemory(owner)
            f.controller.editMemory(owner,hypothesis.copy(value="普通编辑",scope=MemoryScope.USER,sourceType=MemorySource.USER_EXPLICIT),f.controller.memoryPage.value.receipt!!,"edit-preserve-source")
            val edited=f.persistence.entries.first { it.id==hypothesis.id }
            assertEquals(MemoryScope.HYPOTHESIS,edited.scope);assertEquals(MemorySource.ASSISTANT_INFERENCE,edited.sourceType);assertEquals(Confidence.LOW,edited.confidence)
            f.persistence.consent=f.persistence.consent.copy(paused=true)
            f.controller.openMemory(owner)
            val before=f.persistence.entries.toList()
            f.controller.addMemory(owner,MemoryScope.USER,"暂停不能新增",f.controller.memoryPage.value.receipt!!,"paused-add")
            assertEquals(before,f.persistence.entries)
        } finally { f.close() }
    }
    @Test fun explicitAcceptanceWritesOnlyWithReceiptAndNoticeCollapsesAfterEightSeconds()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            f.controller.editDraft("虚构输入");f.controller.analyze();f.complete()
            f.controller.acceptMemory(f.controller.memory.value.receipt!!)
            assertEquals(1,f.persistence.writes);assertNotNull(f.controller.memory.value.notice)
            assertEquals(8000L,f.noticeDelayMillis)
            f.noticeGate.complete(Unit);yield()
            assertNull(f.controller.memory.value.notice)
            assertEquals(1,f.persistence.entries.size)
        } finally { f.close() }
    }
    @Test fun committedApprovalReadFailureHasIndependentReadOnlyFinalizationRetry()=runBlocking {
        for(detailsFailure in listOf(false,true)) {
            val f=Fixture()
            try {
                f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
                val owner=f.controller.state.value.currentContact!!.id
                f.controller.editDraft("虚构问题");f.controller.analyze();f.complete()
                val receipt=f.controller.memory.value.receipt!!
                // Browse the same owner so refresh also exercises contactDetails, not only snapshot.
                f.controller.openMemory(owner)
                if(detailsFailure) f.persistence.committedDetailsFailures=1 else f.persistence.committedSnapshotFailures=1
                f.controller.acceptMemory(receipt)
                assertTrue(f.persistence.consent.enabled);assertEquals(1,f.persistence.writes)
                assertTrue(f.controller.memory.value.approvalFinishing);assertTrue(f.controller.memory.value.failed)
                assertFalse(f.controller.memory.value.pendingOperation);assertNull(f.controller.memory.value.consentProposal)
                assertEquals(receipt,f.controller.memory.value.receipt);assertNull(f.controller.memory.value.notice)
                val version=f.persistence.consent.consentVersion
                val calls=f.providerCalls
                f.controller.acceptMemory(receipt) // same callback now means read-only finish, not approve
                assertEquals(1,f.persistence.approvalCalls);assertEquals(1,f.persistence.writes)
                assertEquals(version,f.persistence.consent.consentVersion);assertEquals(calls,f.providerCalls)
                assertFalse(f.controller.memory.value.approvalFinishing);assertFalse(f.controller.memory.value.pendingOperation)
                assertFalse(f.controller.memory.value.failed);assertNotNull(f.controller.memory.value.notice)
                assertEquals(1,f.controller.memory.value.entries.size)
            } finally { f.close() }
        }
    }
    @Test fun staleCommittedReadRetryCannotPublishToAnotherOwner()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            f.controller.editDraft("虚构问题");f.controller.analyze();f.complete()
            val receipt=f.controller.memory.value.receipt!!
            f.persistence.committedSnapshotFailures=1
            f.controller.acceptMemory(receipt)
            assertTrue(f.controller.memory.value.approvalFinishing)
            f.controller.createContact("虚构乙",RelationType.FRIEND)
            val before=f.controller.memory.value
            f.controller.acceptMemory(receipt)
            assertEquals(before,f.controller.memory.value);assertEquals(1,f.persistence.approvalCalls)
            assertEquals(1,f.persistence.writes)
        } finally { f.close() }
    }
    @Test fun approvalFailureKeepsOriginalCardRetryableAndUnknownCommitReplaysSameOperation()=runBlocking {
        for(lostAck in listOf(false,true)) {
            val f=Fixture()
            try {
                f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
                f.controller.editDraft("虚构问题");f.controller.analyze();f.complete()
                val receipt=f.controller.memory.value.receipt!!
                if(lostAck) f.persistence.approvalLostAck=true else f.persistence.approvalFailure=true
                f.controller.acceptMemory(receipt)
                assertTrue(f.controller.memory.value.failed);assertFalse(f.controller.memory.value.pendingOperation)
                assertEquals(receipt,f.controller.memory.value.receipt);assertNotNull(f.controller.memory.value.consentProposal)
                val calls=f.providerCalls
                f.controller.acceptMemory(receipt)
                assertTrue(f.persistence.consent.enabled);assertEquals(1,f.persistence.writes);assertEquals(1,f.persistence.entries.size)
                assertEquals(1L,f.persistence.consent.consentVersion);assertEquals(calls,f.providerCalls)
                assertNull(f.controller.memory.value.consentProposal);assertNotNull(f.controller.memory.value.notice)
            } finally { f.close() }
        }
    }
    @Test fun switchingWhileSourceLookupSuspendsNeverCallsApproval()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            f.controller.editDraft("虚构问题");f.controller.analyze();f.complete()
            val receipt=f.controller.memory.value.receipt!!
            f.persistence.sourceLoadStarted=false;f.persistence.sourceLoadGate=CompletableDeferred()
            f.controller.acceptMemory(receipt)
            assertTrue(f.persistence.sourceLoadStarted)
            f.controller.newTemporary()
            f.persistence.sourceLoadGate!!.complete(Unit);yield()
            assertFalse(f.persistence.approvalStarted);assertFalse(f.persistence.consent.enabled);assertEquals(0,f.persistence.writes)
        } finally { f.close() }
    }
    @Test fun authorityChangesDuringApprovalBeforeOrAfterInitialGuardCommitNothing()=runBlocking {
        for(during in listOf(false,true)) for(action in listOf("switch","lock","decline","clear")) {
            val f=Fixture()
            try {
                f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
                f.controller.editDraft("虚构问题");f.controller.analyze();f.complete()
                val receipt=f.controller.memory.value.receipt!!
                val gate=CompletableDeferred<Unit>()
                if(during) f.persistence.duringApprove=gate else f.persistence.beforeApprove=gate
                f.controller.acceptMemory(receipt)
                assertTrue(f.persistence.approvalStarted)
                when(action) {
                    "switch"->f.controller.newTemporary()
                    "lock"->{
                        val boundary=CounselController::class.java.getDeclaredMethod("set",CounselState::class.java).apply { isAccessible=true }
                        boundary.invoke(f.controller,f.controller.state.value.copy(lockState=LockState(enabled=true,unlocked=false)))
                    }
                    "decline"->f.controller.declineMemory(receipt)
                    "clear"->f.controller.clearAll()
                }
                gate.complete(Unit);yield()
                assertFalse("$during/$action",f.persistence.consent.enabled)
                assertEquals("$during/$action",0,f.persistence.writes);assertTrue(f.persistence.entries.isEmpty())
            } finally { f.close() }
        }
    }
    @Test fun oldCardCallbacksCannotApproveNewOwnerOrRevokeLaterConsent()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            f.controller.editDraft("虚构甲问题");f.controller.analyze();f.complete("甲猜测")
            val cardA=f.controller.memory.value.receipt!!
            val acceptA={ f.controller.acceptMemory(cardA) };val declineA={ f.controller.declineMemory(cardA) }
            f.controller.createContact("虚构乙",RelationType.FRIEND)
            f.controller.editDraft("虚构乙问题");f.controller.analyze();f.complete("乙猜测")
            val beforeB=f.controller.memory.value
            acceptA();declineA()
            assertEquals(beforeB,f.controller.memory.value);assertFalse(f.persistence.consent.enabled);assertEquals(0,f.persistence.writes)
            val cardB=f.controller.memory.value.receipt!!
            f.controller.acceptMemory(cardB)
            assertTrue(f.persistence.consent.enabled);assertEquals(1,f.persistence.entries.size)
            val enabled=f.persistence.consent
            f.controller.declineMemory(cardB);declineA()
            assertEquals(enabled,f.persistence.consent);assertEquals(1,f.persistence.entries.size)
            val enabledReceipt=f.controller.memory.value.receipt!!
            val oldPause={ f.controller.setMemoryEnabled(true,true,enabledReceipt) }
            f.controller.setMemoryEnabled(false,false,enabledReceipt)
            val withdrawn=f.persistence.consent
            oldPause()
            assertEquals(withdrawn,f.persistence.consent);assertFalse(f.persistence.consent.enabled)
        } finally { f.close() }
    }
    @Test fun declineKeepsCompletedAnswerCopyAndSavedInputWithoutWritingMemory()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            f.controller.editDraft("需要保留的虚构输入");f.controller.analyze();f.complete()
            val answer=f.controller.state.value.answer
            val original=f.controller.state.value.answerRequest
            f.controller.declineMemory(f.controller.memory.value.receipt!!)
            assertEquals(answer,f.controller.state.value.answer);assertEquals(original,f.controller.state.value.answerRequest)
            assertTrue(f.controller.state.value.hasCurrentAnswerSource());assertTrue(f.controller.state.value.draftSaved)
            assertEquals("需要保留的虚构输入",f.controller.state.value.draft)
            assertEquals(0,f.persistence.writes);assertTrue(f.persistence.entries.isEmpty());assertNull(f.controller.memory.value.consentProposal)
        } finally { f.close() }
    }
    @Test fun staleClearConfirmationCannotDeleteNewDataEvenWhenItsOwnerStampDidNotRefresh()=runBlocking {
        val f=Fixture()
        try {
            f.persistence.consent=ConsentState(enabled=true,consentVersion=1)
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            val owner=f.controller.state.value.currentContact!!.id
            f.controller.openMemory(owner)
            val confirmation=f.controller.memoryPage.value.receipt!!
            val clear={ f.controller.clearMemories(confirmation) }
            // Simulate a separate source transaction without refreshing this rendered page.
            f.persistence.entries+=MemoryEntry("later",owner,subjectId=owner,field="fact",value="后来新增")
            f.persistence.revision++
            val ui=f.controller.memoryPage.value;val state=f.controller.state.value
            clear()
            f.controller.setMemoryEnabled(false,false,confirmation)
            assertTrue(f.persistence.consent.enabled)
            assertEquals(ui,f.controller.memoryPage.value);assertEquals(state,f.controller.state.value)
            assertEquals(1,f.persistence.entries.size)
            f.controller.closeMemory();f.controller.openMemory(owner)
            f.controller.addMemory(owner,MemoryScope.USER,"过期表单",confirmation,"stale-add")
            assertEquals(1,f.persistence.entries.size)
        } finally { f.close() }
    }
    @Test fun ownerAFailureCannotClearOrReplaceCurrentBProposalAndReceipt()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            val a=f.controller.state.value.currentContact!!.id
            f.controller.createContact("虚构乙",RelationType.FRIEND)
            f.controller.editDraft("虚构乙问题");f.controller.analyze();f.complete("乙猜测")
            val before=f.controller.memory.value
            f.controller.openMemory(a)
            val aReceipt=f.controller.memoryPage.value.receipt!!
            f.controller.deleteMemory(a,"unavailable-A-record",aReceipt)
            assertTrue(f.controller.memoryPage.value.failed)
            assertEquals(before,f.controller.memory.value)
            f.controller.acceptMemory(before.receipt!!)
            assertEquals(1,f.persistence.writes)
        } finally { f.close() }
    }
    @Test fun switchingBeforeConsentAcceptDiscardsCandidate()=runBlocking {
        val f=Fixture()
        try {
            f.controller.initialize();f.controller.createContact("虚构甲",RelationType.FRIEND)
            f.controller.editDraft("虚构输入");f.controller.analyze();f.complete()
            assertNotNull(f.controller.memory.value.consentProposal)
            val displayed=f.controller.memory.value.receipt!!
            f.controller.newTemporary();f.controller.acceptMemory(displayed)
            assertEquals(0,f.persistence.writes);assertFalse(f.persistence.consent.enabled)
        } finally { f.close() }
    }
}
