package com.shengjidaguai.goutoujunshi.data

import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class LongTermMemoryRepositoryTest {
    private lateinit var db: JunshiDatabase
    private lateinit var session: DataSession
    @Before fun setup() = runBlocking {
        db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),JunshiDatabase::class.java).build()
        session=DataSession(db,1)
        ContactRepository(session).save(Contact("a","虚构甲")); ContactRepository(session).save(Contact("b","虚构乙"))
        ConsentRepository(session).update(true)
        Unit
    }
    @After fun close() { db.close() }
    /** Real verified source fixture, never a flag disabling the production guard. */
    private suspend fun MemoryRepository.applyFixtureProposal(owner: String,proposal: MemoryProposal,stamp: WriteStamp): MemoryOperation {
        val conversation="fixture-$owner"
        ConversationRepository(session).save(Conversation(conversation,owner,false))
        val requestId=proposal.sourceRef.ifBlank { java.util.UUID.randomUUID().toString() }
        val snapshot=snapshot(owner)
        val request=AnalysisRequest(requestId=requestId,contactId=owner,conversationId=conversation,
            sourceRevision=ContactRepository(session).get(owner)!!.sourceRevision,userText="合成来源问题",
            actualDataContext=MemoryContextPolicy.context(owner,snapshot.consent,snapshot.stamp!!.memoryRevision ?: 0,snapshot.outboundEntries()))
        val messages=ConversationRepository(session)
        messages.saveMessage(Message("user-$requestId",conversation,MessageRole.USER,request.userText,requestId=requestId,contactId=owner,status=MessageStatus.COMPLETED))
        messages.saveMessage(Message("assistant-$requestId",conversation,MessageRole.ASSISTANT,"合成来源答案",requestId=requestId,contactId=owner,status=MessageStatus.COMPLETED,providerKind=ProviderKind.BYOK))
        TimelineRepository(session).save(AnalysisRun(requestId=requestId,contactId=owner,conversationId=conversation,status=RunStatus.COMPLETED,requestSnapshot=request))
        return applyProposal(owner,proposal.copy(sourceRef=requestId),stamp)
    }
    private fun proposal(value: String="虚构假设") = MemoryProposal(subjectId="ta",field="pattern",value=value)
    @Test fun approvalIsOneTransactionAndFinalAuthorityGuardRollsBackConsentAndProposalTogether()=runBlocking {
        val repo=MemoryRepository(session)
        ConsentRepository(session).update(false)
        val proposal=proposal("原子同意虚构猜测").copy(sourceRef="atomic-approval-request")
        // Register a real completed source while disabled; the ordinary apply must remain denied.
        assertTrue(runCatching { repo.applyFixtureProposal("a",proposal,session.stamp("a")) }.isFailure)
        val before=ConsentRepository(session).get()
        val stamp=session.stamp("a")
        var checks=0
        assertTrue(runCatching { repo.approveProposal("a",proposal,stamp,"atomic-approval-operation") {
            checks++;if(checks==2) error("synthetic owner/lock authority revoked before commit")
        } }.isFailure)
        assertEquals(2,checks);assertEquals(before,ConsentRepository(session).get())
        assertTrue(repo.list("a").isEmpty());assertTrue(repo.operations("a").isEmpty());assertEquals(stamp,session.stamp("a"))
        assertTrue(session.transaction { records("a",OwnedRecordKind.MEMORY_SOURCE.name).isEmpty() })
        var successChecks=0
        val committed=repo.approveProposal("a",proposal,stamp,"atomic-approval-operation") { successChecks++ }
        assertEquals(2,successChecks)
        assertNotNull(committed.approval)
        val after=ConsentRepository(session).get();val afterStamp=session.stamp("a")
        var replayChecks=0
        val replay=repo.approveProposal("a",proposal,stamp,"atomic-approval-operation") { replayChecks++ }
        assertEquals(1,replayChecks)
        assertEquals(committed,replay);assertEquals(after,ConsentRepository(session).get());assertEquals(afterStamp,session.stamp("a"))
        assertEquals(1,repo.list("a").size);assertEquals(1,repo.operations("a").size)
        assertTrue(runCatching { repo.approveProposal("a",proposal.copy(value="改变后的另一个提案"),stamp,"atomic-approval-operation") {} }.isFailure)
    }
    @Test fun proposalsRequireVerifiedSourceByConstructionAndLegacyInferenceNeverBecomesOutboundOrAnchor()=runBlocking {
        val repo=MemoryRepository(session)
        assertTrue(runCatching { repo.applyProposal("a",proposal(),session.stamp("a")) }.isFailure)
        val legacy=MemoryEntry("legacy-inference","a",subjectId="a",field="pattern",value="旧版未核验推测",confidence=Confidence.LOW)
        val explicit=MemoryEntry("explicit-independent","a",scope=MemoryScope.USER,subjectId="a",field="fact",value="用户独立确认",sourceType=MemorySource.USER_EXPLICIT)
        session.transaction { put(MemoryRow(legacy.id,"a",encode(legacy)));put(MemoryRow(explicit.id,"a",encode(explicit))) }
        val snapshot=repo.snapshot("a")
        assertEquals(2,snapshot.entries.size)
        assertTrue(snapshot.verifiedInferenceIds.isEmpty())
        assertEquals(listOf(explicit),snapshot.outboundEntries())
        assertTrue(snapshot.anchors.isEmpty())
        assertTrue(session.transaction { records("a",OwnedRecordKind.MEMORY_SOURCE.name).isEmpty() })
        val context=MemoryContextPolicy.context("a",snapshot.consent,snapshot.stamp!!.memoryRevision ?: 0,snapshot.outboundEntries())
        assertFalse(context.text.contains(legacy.value));assertTrue(context.text.contains(explicit.value))
    }
    @Test fun storageRetryAcknowledgesOnlyTheSameLatestCommittedEditorOperation()=runBlocking {
        val repo=MemoryRepository(session)
        val stamp=session.stamp("a")
        val entry=MemoryEntry("stable-editor-entry","a",scope=MemoryScope.USER,subjectId="a",field="profile",value="唯一虚构编辑稿",sourceType=MemorySource.USER_EXPLICIT,createdAt=100,updatedAt=100)
        val first=repo.apply("a",entry,stamp,operationId="editor-operation")
        val after=session.stamp("a")
        val retry=repo.apply("a",entry.copy(createdAt=200,updatedAt=200),stamp,operationId="editor-operation")
        assertEquals(first,retry);assertEquals(after,session.stamp("a"));assertEquals(1,repo.operations("a").size)
        assertTrue(runCatching { repo.apply("a",entry.copy(value="改了操作载荷"),stamp,operationId="editor-operation") }.isFailure)
        assertTrue(runCatching { repo.apply("b",entry.copy(id="b-editor-entry",contactId="b"),session.stamp("b"),operationId="editor-operation") }.isFailure)
        assertEquals("唯一虚构编辑稿",repo.list("a").single().value)
        ConsentRepository(session).update(true,true)
        assertTrue(runCatching { repo.apply("a",entry,stamp,operationId="editor-operation") }.isFailure)
    }
    @Test fun staleGlobalClearAndConsentCallbacksHaveNoDataOrAuthorizationSideEffects()=runBlocking {
        val repo=MemoryRepository(session)
        val clearDisplay=repo.snapshot(null).clearAuthority!!
        repo.applyFixtureProposal("a",proposal("之后新增"),session.stamp("a"))
        var called=false
        assertTrue(runCatching { repo.clear(clearDisplay) { called=true } }.isFailure)
        assertFalse(called);assertEquals(1,repo.list("a").size)
        assertTrue(runCatching { ConsentRepository(session).update(false,expected=session.stamp("a"),expectedClear=clearDisplay,onAuthorized={ called=true }) }.isFailure)
        assertFalse(called);assertTrue(ConsentRepository(session).get().enabled);assertEquals(1,repo.list("a").size)
        val beforeWithdraw=session.stamp("a")
        ConsentRepository(session).update(false,expected=beforeWithdraw)
        val withdrawn=ConsentRepository(session).get()
        assertTrue(runCatching { ConsentRepository(session).update(true,true,expected=beforeWithdraw,onAuthorized={ called=true }) }.isFailure)
        assertFalse(called);assertEquals(withdrawn,ConsentRepository(session).get())
    }
    @Test fun oldDeclineIsNonDestructiveAndCannotEraseLaterSamplesOrMemory()=runBlocking {
        val consent=ConsentRepository(session)
        consent.update(false)
        val oldCard=session.stamp("a")
        consent.update(true,expected=oldCard)
        val stamp=session.stamp()
        val style=StyleRepository(session)
        style.saveSample(StyleSample("protected-style",text="虚构样本",consentVersion=stamp.consentVersion,sourceRevision=stamp.sourceRevision),stamp)
        MemoryRepository(session).applyFixtureProposal("a",proposal(),session.stamp("a"))
        val before=consent.get()
        assertTrue(runCatching { consent.recordDecline("a",oldCard,Long.MAX_VALUE) }.isFailure)
        assertEquals(before,consent.get());assertEquals(1,style.samples(RelationGroup.DEFAULT).size)
        assertEquals(1,MemoryRepository(session).list("a").size)
        consent.update(false)
        val declined=consent.recordDecline("a",session.stamp("a"),12345)
        assertFalse(declined.enabled);assertEquals(12345L,declined.declinedUntil)
    }
    @Test fun wrongContentSurvivesRepositoryRestartAndNewRandomIdsButIsOwnerScoped() = runBlocking {
        val repo=MemoryRepository(session)
        repo.applyFixtureProposal("a",proposal("喜欢 Ａ"),session.stamp("a"))
        repo.markWrong("a",repo.list("a").single().id,session.stamp("a"))
        assertTrue(runCatching { MemoryRepository(session).applyFixtureProposal("a",proposal(" 喜欢 a "),session.stamp("a")) }.isFailure)
        repo.applyFixtureProposal("b",proposal("喜欢 a"),session.stamp("b"))
        assertEquals(1,repo.list("b").size)
    }
    @Test fun ownerEditsFenceOnlyOwnerWhileGlobalPauseFencesEveryoneAndAllowsDeletion() = runBlocking {
        val repo=MemoryRepository(session)
        val a=session.stamp("a"); val b=session.stamp("b"); val consent=ConsentRepository(session).get()
        repo.applyFixtureProposal("a",proposal(),a)
        assertEquals(consent,ConsentRepository(session).get())
        assertEquals(b,session.stamp("b"))
        assertTrue(runCatching { repo.applyFixtureProposal("a",proposal("迟到"),a) }.isFailure)
        repo.applyFixtureProposal("b",proposal(),b)
        ConsentRepository(session).update(true,true)
        assertTrue(runCatching { repo.applyFixtureProposal("b",proposal("迟到"),b) }.isFailure)
        repo.delete("b",repo.list("b").single().id,session.stamp("b"))
        assertTrue(repo.list("b").isEmpty())
    }
    @Test fun deletingEvidenceRemovesDerivedMemoryAndUndoButPreservesIndependentOtherOwner() = runBlocking {
        val repo=MemoryRepository(session)
        val conversations=ConversationRepository(session)
        conversations.save(Conversation("conv","a",false))
        val line=ChatLine("line","import",contactId="a",speaker=Speaker.ME,text="唯一虚构来源",speakerConfirmed=true)
        ImportRepository(session).save(ChatImport("import","a",ImportSource.PASTE,"hash"),listOf(line))
        val request=AnalysisRequest(requestId="r",contactId="a",conversationId="conv",sourceRevision=ContactRepository(session).get("a")!!.sourceRevision,userText="虚构问题",evidence=listOf(EvidenceBlock("import","PASTE",listOf(line))))
        conversations.saveMessage(Message("u","conv",MessageRole.USER,"虚构问题",requestId="r",contactId="a"))
        conversations.saveMessage(Message("assistant","conv",MessageRole.ASSISTANT,"虚构答案",requestId="r",contactId="a",providerKind=ProviderKind.BYOK,status=MessageStatus.COMPLETED))
        TimelineRepository(session).save(AnalysisRun(requestId="r",conversationId="conv",contactId="a",status=RunStatus.COMPLETED,requestSnapshot=request))
        repo.applyProposal("a",proposal("唯一虚构来源派生猜测").copy(sourceRef="r"),session.stamp("a"))
        repo.apply("b",MemoryEntry("manual","b",scope=MemoryScope.USER,subjectId="me",field="独立",value="独立虚构资料",sourceType=MemorySource.USER_EXPLICIT),session.stamp("b"))
        ImportRepository(session).delete("a","import")
        assertTrue(repo.list("a").isEmpty());assertTrue(repo.operations("a").isEmpty())
        assertEquals(1,repo.list("b").size)
        assertFalse(session.transaction { records("a",OwnedRecordKind.MEMORY_SOURCE.name).any { it.payload.contains("唯一虚构来源") } })
        assertTrue(runCatching { repo.applyProposal("a",proposal("旧说话人猜测").copy(sourceRef="r"),session.stamp("a")) }.isFailure)
    }
    @Test fun modelMasksAreNamespacedAcrossRequestsAndWrongContentStillMatches() = runBlocking {
        val repo=MemoryRepository(session)
        repo.applyFixtureProposal("a",proposal("[昵称1]可能周末忙").copy(sourceRef="request-a"),session.stamp("a"))
        val first=repo.list("a").single()
        assertFalse(first.value.contains("[昵称1]"))
        repo.applyFixtureProposal("a",proposal("[昵称1]可能周一忙").copy(sourceRef="request-b"),session.stamp("a"))
        val second=repo.list("a").last()
        assertNotEquals(first.value,second.value)
        assertNotEquals(first.value.substringBefore("·"),second.value.substringBefore("·"))
        assertEquals(LongTermMemoryPolicy.contentKey("[昵称1]可能周末忙"),LongTermMemoryPolicy.contentKey(first.value))
        assertEquals(2,repo.list("a").size)
        val secondSource=session.transaction { decode<MemorySourceReceipt>(records("a",OwnedRecordKind.MEMORY_SOURCE.name).first { it.id=="MEMORY_SOURCE:${second.id}" }.payload) }
        assertEquals(MemoryContextPolicy.entryFingerprint(first),secondSource.memoryDependencies[first.id])
        repo.markWrong("a",first.id,session.stamp("a"))
        // The verified second request selected the first memory: wrong-root removal must cascade.
        assertTrue(repo.list("a").isEmpty())
        assertTrue(runCatching { repo.applyFixtureProposal("a",proposal("[昵称1]可能周末忙").copy(sourceRef="request-c"),session.stamp("a")) }.isFailure)
    }
    @Test fun duplicateDoesNotRebindSourceOrAdvanceRevisionAndDependenciesPruneToFixedPoint() = runBlocking {
        val repo=MemoryRepository(session)
        ConversationRepository(session).save(Conversation("dependency-conv","a",false))
        ConversationRepository(session).saveMessage(Message("dependency-user","dependency-conv",MessageRole.USER,"",requestId="dependency-request",contactId="a"))
        repo.applyFixtureProposal("a",proposal("M1虚构"),session.stamp("a"))
        val first=repo.list("a").single()
        val stamp=session.stamp("a")
        assertTrue(runCatching { repo.applyFixtureProposal("a",proposal("M1虚构").copy(sourceRef="new-request"),stamp) }.isFailure)
        assertEquals(stamp,session.stamp("a"))
        repo.applyFixtureProposal("a",proposal("M2虚构"),session.stamp("a"))
        val second=repo.list("a").last()
        repo.applyFixtureProposal("a",proposal("M3虚构"),session.stamp("a"))
        val third=repo.list("a").last()
        val emptyHash=java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf()).joinToString("") { "%02x".format(it) }
        val dependencyAssistant=Message("dependency-assistant","dependency-conv",MessageRole.ASSISTANT,"虚构依赖答案",requestId="dependency-request",contactId="a",status=MessageStatus.COMPLETED,providerKind=ProviderKind.BYOK)
        ConversationRepository(session).saveMessage(dependencyAssistant)
        val dependencyAssistantHash=java.security.MessageDigest.getInstance("SHA-256").digest(encode(dependencyAssistant).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val dependencyRequest=AnalysisRequest(requestId="dependency-request",contactId="a",conversationId="dependency-conv")
        TimelineRepository(session).save(AnalysisRun(requestId="dependency-request",contactId="a",conversationId="dependency-conv",status=RunStatus.COMPLETED,requestSnapshot=dependencyRequest))
        val runHash=java.security.MessageDigest.getInstance("SHA-256").digest(encode(dependencyRequest).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        session.transaction {
            listOf(second to first,third to second).forEach { (child,parent) ->
                val receipt=MemorySourceReceipt("dependency-request","dependency-conv",emptyHash,emptyMap(),mapOf(parent.id to MemoryContextPolicy.entryFingerprint(parent)),runHash,dependencyAssistantHash)
                put(OwnedRecordRow("MEMORY_SOURCE:${child.id}","a",OwnedRecordKind.MEMORY_SOURCE,encode(receipt)))
            }
        }
        repo.apply("b",MemoryEntry("independent","b",scope=MemoryScope.USER,subjectId="me",field="资料",value="独立虚构",sourceType=MemorySource.USER_EXPLICIT),session.stamp("b"))
        repo.delete("a",first.id,session.stamp("a"))
        assertTrue(repo.list("a").isEmpty());assertEquals(1,repo.list("b").size)
        repo.undoLatest("a",session.stamp("a"))
        assertEquals(3,repo.list("a").size)
        repo.markWrong("a",first.id,session.stamp("a"))
        assertTrue(repo.list("a").isEmpty());assertTrue(repo.operations("a").isEmpty())
    }
    private suspend fun sixVerifiedRounds(): Pair<MemoryRepository,MemoryEntry> {
        val repo=MemoryRepository(session)
        val conversations=ConversationRepository(session)
        conversations.save(Conversation("quota-conv","a",false))
        var first: MemoryEntry?=null
        repeat(6) { index ->
            val id="quota-$index"
            val snapshot=repo.snapshot("a")
            val request=AnalysisRequest(requestId=id,contactId="a",conversationId="quota-conv",sourceRevision=ContactRepository(session).get("a")!!.sourceRevision,userText="虚构问题$index",
                actualDataContext=MemoryContextPolicy.context("a",snapshot.consent,snapshot.stamp!!.memoryRevision ?: 0,snapshot.entries))
            conversations.saveMessage(Message("user-$id","quota-conv",MessageRole.USER,request.userText,requestId=id,contactId="a",status=MessageStatus.COMPLETED))
            conversations.saveMessage(Message("assistant-$id","quota-conv",MessageRole.ASSISTANT,"虚构答案",requestId=id,contactId="a",status=MessageStatus.COMPLETED,providerKind=ProviderKind.BYOK))
            TimelineRepository(session).save(AnalysisRun(requestId=id,contactId="a",conversationId="quota-conv",status=RunStatus.COMPLETED,requestSnapshot=request))
            repo.applyProposal("a",proposal("容量虚构猜测$index").copy(sourceRef=id),session.stamp("a"))
            if(index==0) first=repo.list("a").single()
        }
        return repo to first!!
    }
    @Test fun realFiveToSixReplacementRetainsDescendantsButExplicitRootDeleteOrWrongPrunesThem()=runBlocking {
        val (repo,root)=sixVerifiedRounds()
        assertEquals(5,repo.list("a").size)
        val snapshot=repo.snapshot("a")
        assertEquals(MemoryContextPolicy.entryFingerprint(root),snapshot.anchors[root.id])
        assertFalse(MemoryContextPolicy.context("a",snapshot.consent,snapshot.stamp!!.memoryRevision ?: 0,snapshot.entries).text.contains(root.value))
        assertFalse(session.transaction { records("a",OwnedRecordKind.MEMORY_ANCHOR.name).any { it.payload.contains(root.value) } })
        repo.apply("b",MemoryEntry("independent-quota","b",scope=MemoryScope.USER,subjectId="me",field="资料",value="独立虚构",sourceType=MemorySource.USER_EXPLICIT),session.stamp("b"))
        repo.delete("a",root.id,session.stamp("a"))
        assertTrue(repo.list("a").isEmpty());assertTrue(repo.snapshot("a").anchors.isEmpty())
        repo.undoLatest("a",session.stamp("a"))
        assertEquals(5,repo.list("a").size);assertEquals(1,repo.snapshot("a").anchors.size)
        repo.markWrong("a",root.id,session.stamp("a"))
        assertTrue(repo.list("a").isEmpty());assertTrue(repo.snapshot("a").anchors.isEmpty());assertEquals(1,repo.list("b").size)
    }
    @Test fun quotaProofsDisappearOnWithdrawalOrMemoryClearAndGcDropsOrphans()=runBlocking {
        val (repo,_)=sixVerifiedRounds()
        assertTrue(repo.snapshot("a").anchors.isNotEmpty())
        repo.clear(repo.snapshot(null).clearAuthority!!);assertTrue(repo.snapshot("a").anchors.isEmpty())
        assertTrue(session.transaction { records("a",OwnedRecordKind.MEMORY_SOURCE.name).isEmpty() })
        sixVerifiedRounds()
        ConsentRepository(session).update(false)
        assertTrue(repo.snapshot("a").anchors.isEmpty())
        assertTrue(session.transaction { records("a",OwnedRecordKind.MEMORY_SOURCE.name).isEmpty() })
        session.transaction {
            putMemoryAnchor("a",MemoryAnchor("orphan","a".repeat(64),"b".repeat(64)))
            collectMemoryAuthority("a")
            assertTrue(memoryAnchors("a").isEmpty())
        }
    }
    @Test fun anchorHardLimitRejectsAtomicallyWithoutDroppingPrivacyProofs()=runBlocking {
        val anchors=(0..MAX_MEMORY_ANCHORS).map { MemoryAnchor("bounded-$it","a".repeat(64),"b".repeat(64)) }
        assertTrue(runCatching { session.transaction {
            anchors.forEach { putMemoryAnchor("a",it) }
            anchors.chunked(200).forEachIndexed { index,chunk ->
                val op=MemoryOperation("bound-operation-$index","a",before=chunk.map { MemoryEntry(it.id,"a",subjectId="a",field="合成",value="虚构") },afterAnchors=chunk)
                put(MemoryOperationRow(op.id,"a",index.toLong(),encode(op)))
            }
            collectMemoryAuthority("a")
        } }.isFailure)
        assertTrue(MemoryRepository(session).snapshot("a").anchors.isEmpty())
        assertTrue(MemoryRepository(session).operations("a").isEmpty())
    }
    @Test fun randomModelSubjectsCannotBypassFiveHypothesisQuota() = runBlocking {
        val repo=MemoryRepository(session)
        repeat(8) { index -> repo.applyFixtureProposal("a",proposal("虚构假设$index").copy(subjectId="random-$index"),session.stamp("a")) }
        assertEquals(5,repo.list("a").size)
        assertTrue(repo.list("a").all { it.subjectId=="a" && it.confidence==Confidence.LOW })
    }
    @Test fun globalMemoryClearPreservesConsentAndRejectsLateWritesAndUndo() = runBlocking {
        val repo=MemoryRepository(session)
        repo.applyFixtureProposal("a",proposal(),session.stamp("a"))
        val old=session.stamp("a"); val consent=ConsentRepository(session).get()
        repo.clear(repo.snapshot(null).clearAuthority!!)
        assertEquals(consent,ConsentRepository(session).get())
        assertTrue(runCatching { repo.applyFixtureProposal("a",proposal(),old) }.isFailure)
        assertTrue(repo.operations("a").isEmpty())
    }
}
