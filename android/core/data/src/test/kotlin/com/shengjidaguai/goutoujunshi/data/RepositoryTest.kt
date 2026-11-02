package com.shengjidaguai.goutoujunshi.data

import androidx.room.Room
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Deliberately unencrypted relational tests; native SQLCipher is tested on a device separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RepositoryTest {
    @Test fun floatingIdentityIsTypedOwnedVersionedAndCascades() = runBlocking {
        val repo=FloatingIdentityRepository(session)
        val binding=FloatingIdentityBinding("binding-a","a",FloatingSourceContext("合成应用","合成账号"),"同名",true,0)
        repo.save(binding);repo.save(binding)
        assertEquals(binding,repo.list("a").single())
        assertTrue(repo.list("b").isEmpty())
        assertTrue(runCatching { repo.save(binding.copy(revision=2),expected=0) }.isFailure)
        ContactRepository(session).save(Contact("a","改名"))
        assertTrue(repo.list("a").isEmpty())
        ContactRepository(session).delete("a")
        assertTrue(runCatching { repo.save(binding) }.isFailure)
        assertTrue(session.transaction { records("a",OwnedRecordKind.FLOATING_IDENTITY.name) }.isEmpty())
    }

    @Test fun timelineCacheIsOwnedSourceCasAndRemovedWithRulesOrOwner() = runBlocking {
        val conversations = ConversationRepository(session)
        val c = Conversation("summary-conv", "a", false)
        conversations.save(c); conversations.saveDraft("a", c.id, "虚构问题")
        val line = ChatLine("line","batch",speaker=Speaker.ME,text="虚构原文",speakerConfirmed=true,contactId="a")
        ImportRepository(session).save(ChatImport("batch","a",ImportSource.PASTE,"synthetic-summary-hash"),listOf(line))
        val request = AnalysisRequest(requestId="summary-request", contactId="a", sourceRevision=1, conversationId=c.id, userText="虚构问题", networkSelection=listOf("line"),
            evidence=listOf(EvidenceBlock("batch","PASTE",listOf(ChatLine("line","batch",speaker=Speaker.ME,text="虚构原文",speakerConfirmed=true,contactId="a")))))
        val cache = TimelineSummary("key", request, "[line] 虚构事件")
        val repo = SummaryRepository(session)
        repo.save(cache); assertEquals(1,repo.list("a").size); assertTrue(repo.list("b").isEmpty())
        assertTrue(runCatching { repo.save(cache.copy(request=request.copy(sourceRevision=99))) }.isFailure)
        assertTrue(runCatching { repo.save(cache.copy(text="[missing] 无来源")) }.isFailure)
        val newer = cache.copy(request=request.copy(requestId="new-summary-request"))
        repo.save(newer); repo.discard(cache); assertEquals("new-summary-request",repo.list("a").single().request.requestId)
        repo.discard(newer); assertTrue(repo.list("a").isEmpty());repo.save(cache)
        RedactionRepository(session).save("a",0,RedactionProfile(revision=1))
        assertTrue(repo.list("a").isEmpty())
        assertTrue(runCatching { repo.save(cache) }.isFailure)
        ContactRepository(session).delete("a")
        assertTrue(runCatching { repo.save(cache) }.isFailure)
    }

    @Test fun messageTimestampIsImmutableAcrossStorageRetriesAndLegacyReads() = runBlocking {
        val repo = ConversationRepository(session)
        repo.save(Conversation("timestamp-conv", "a", false))
        val message = Message("timestamp-message", "timestamp-conv", MessageRole.ASSISTANT,
            "虚构建议", contactId="a", createdAt=123L)
        repo.saveMessage(message)
        repo.saveMessage(message.copy(createdAt=999L))
        assertEquals(123L, repo.messages("a", "timestamp-conv").single().createdAt)
        val legacy = message.copy(id="legacy-message", createdAt=0)
        repo.saveMessage(legacy); repo.saveMessage(legacy.copy(createdAt=999L))
        assertEquals(0L, ContactDetailsRepository(session).load("a", ContactRepository(session).get("a")!!.sourceRevision)
            .messages.single { it.id == legacy.id }.createdAt)
    }

    @Test fun feedbackKeepsSourceAndDoesNotCreateLearningSamples() = runBlocking {
        val conversations = ConversationRepository(session)
        val c = Conversation("feedback-conv", "a", false)
        conversations.save(c)
        val message = Message(id="feedback-assistant", conversationId=c.id, contactId="a", role=MessageRole.ASSISTANT, requestId="feedback-request", answerJson="{}")
        conversations.saveMessage(message)
        val plan = ActionPlan(id="feedback", contactId="a", chosenReply="虚构用户修改", wasEdited=true, status=PlanStatus.SENT, sourceAnswerId=message.id, sourceRequestId=message.requestId, conversationId=c.id)
        val repo=PlanRepository(session)
        repo.saveFeedback(plan, 0); repo.saveFeedback(plan, 0)
        val stored=repo.list("a").single()
        assertEquals(message.id,stored.sourceAnswerId); assertFalse(stored.learningAuthorized)
        assertTrue(StyleRepository(session).samples(RelationGroup.SOCIAL).isEmpty()); assertTrue(repo.list("b").isEmpty())
        assertTrue(runCatching { repo.saveFeedback(plan.copy(conversationRevision=99),0) }.isFailure)
        assertTrue(runCatching { repo.saveFeedback(plan.copy(sourceAnswerId="missing"),0) }.isFailure)
        assertTrue(runCatching { repo.saveFeedback(plan,99) }.isFailure)
    }

    @Test fun runCheckpointUsesSourceAndConversationCasAndCannotRecreateDeletedContact() = runBlocking {
        val conversations = ConversationRepository(session)
        val c = Conversation("t27-conversation", "a", false)
        conversations.save(c)
        conversations.saveDraft("a", c.id, "虚构问题")
        val request = AnalysisRequest(requestId = "t27", contactId = "a", conversationId = c.id, userText = "虚构问题")
        val run = AnalysisRun(requestId = request.requestId, contactId = "a", conversationId = c.id,
            status = RunStatus.RUNNING, requestSnapshot = request)
        val repo = TimelineRepository(session)
        repo.saveCurrentRun(run, 0)
        assertEquals(run, TimelineRepository(session).runs("a").single())
        assertTrue(repo.runs("b").isEmpty())
        conversations.updateDraft("a", 0, c.copy(revision = 1), "新虚构问题", 0)
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.saveCurrentRun(run, 0) } }
        ContactRepository(session).delete("a")
        assertTrue(repo.runs("a").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveCurrentRun(run, 0) } }
        Unit
    }
    @Test fun contactEditsArchiveUndoUseCasAndDeletedOwnerCannotReturn() = runBlocking {
        val repo = ContactRepository(session)
        val original = repo.get("a")!!
        val receipt = repo.update(original.copy(alias="新代号", notes="用户手工资料"), original.sourceRevision)
        assertEquals(original.sourceRevision + 1, receipt.after.sourceRevision)
        assertTrue(runCatching { repo.update(original, original.sourceRevision) }.isFailure)
        val archived = repo.update(receipt.after.copy(archivedAt=123L), receipt.after.sourceRevision)
        assertTrue(runCatching { repo.undo(receipt) }.isFailure)
        val restored = repo.undo(archived)
        assertNull(restored.archivedAt)
        repo.delete("a")
        assertTrue(runCatching { repo.update(restored, restored.sourceRevision) }.isFailure)
        assertNull(repo.get("a")); assertNotNull(repo.get("b"))
    }

    private lateinit var db: JunshiDatabase
    private lateinit var session: DataSession
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JunshiDatabase::class.java).build()
        session = DataSession(db, 1)
        ContactRepository(session).save(Contact("a", "虚构甲"))
        ContactRepository(session).save(Contact("b", "虚构乙"))
    }
    @Test fun redactionRulesReloadAreContactScopedCasAndCascade() = runBlocking {
        val repo = RedactionRepository(session)
        val a = RedactionProfile(1, listOf("虚构公司甲"), listOf(RedactionAlias("虚构昵称甲", "朋友甲")))
        repo.save("a", 0, a)
        repo.save("b", 0, RedactionProfile(1, listOf("虚构公司乙")))
        assertEquals(a, RedactionRepository(session).load("a"))
        assertFalse(repo.load("b").terms.contains("虚构公司甲"))
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.save("a", 0, a) } }
        ContactRepository(session).delete("a")
        assertTrue(session.transaction { records("a", OwnedRecordKind.REDACTION.name).isEmpty() })
        assertEquals(listOf("虚构公司乙"), repo.load("b").terms)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.save("a", 0, a) } }
        Unit
    }
    @After fun close() { db.close() }

    @Test fun importCasDedupIsPerContactAndRestartReloadsConfirmedNamedOther() = runBlocking {
        val imports = ImportRepository(session)
        val item = ChatImport("t21-a", "a", ImportSource.FILE, "synthetic-sha")
        val line = ChatLine("t21-line", item.id, text = "虚构消息", speaker = Speaker.OTHER,
            speakerConfirmed = true, contactId = "a", speakerName = "虚构群成员")
        assertEquals(item.id, imports.save(item, listOf(line), 0))
        assertEquals(item.id, imports.save(item.copy(id = "retry"), emptyList(), 0))
        assertEquals(1L, ContactRepository(session).get("a")!!.sourceRevision)
        assertThrows(IllegalStateException::class.java) { runBlocking {
            imports.save(item.copy(id = "stale", contentHash = "changed"), listOf(line.copy(id = "stale-line", importId = "stale")), 0)
        } }
        val other = item.copy(id = "t21-b", contactId = "b")
        imports.save(other, listOf(line.copy(id = "t21-b-line", importId = other.id, contactId = "b")), 0)
        val reloaded = ImportRepository(session)
        assertEquals("虚构群成员", reloaded.lines("a", item.id).single().speakerName)
        assertEquals(1, reloaded.list("b").size)
    }

    @Test fun draftAndConversationAdvanceTogetherAndRejectStaleOrWrongOwner() = runBlocking {
        val repo = ConversationRepository(session)
        val initial = Conversation("formal", "a", false)
        repo.save(initial)
        repo.saveDraft("a", "formal", "原草稿")
        val next = initial.copy(revision = 1)
        repo.updateDraft("a", 0, next, "更新草稿", expectedContactRevision = 0)
        assertEquals(next, repo.list("a").single())
        assertEquals("更新草稿", repo.draft("a", "formal"))
        for (write in listOf<suspend () -> Unit>(
            { repo.updateDraft("a", 0, next, "过期草稿", expectedContactRevision = 0) },
            { repo.updateDraft("b", 1, next.copy(contactId = "b", revision = 2), "串线草稿", expectedContactRevision = 0) },
            { repo.updateDraft("a", 1, next.copy(revision = 2), "旧授权来源", expectedContactRevision = 99) },
        )) {
            var rejected = false
            try { write() } catch (_: IllegalArgumentException) { rejected = true }
            catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertEquals(next, repo.list("a").single())
            assertEquals("更新草稿", repo.draft("a", "formal"))
            assertTrue(repo.list("b").isEmpty())
        }
    }
    private suspend fun consent() { ConsentRepository(session).update(true) }
    private suspend fun source(origin: StyleOrigin): StyleSample {
        when (origin) {
            StyleOrigin.IMPORT -> ImportRepository(session).save(ChatImport("imp", "a", contentHash = "synthetic-hash"),
                listOf(ChatLine("line", "imp", speaker = Speaker.ME, text = "您好合成唯一标记", speakerConfirmed = true, contactId = "a")))
            StyleOrigin.EDITED_REPLY -> {
                ConversationRepository(session).save(Conversation("conv", "a"))
                ConversationRepository(session).saveMessage(Message("answer", "conv", MessageRole.ASSISTANT, "合成答案", contactId = "a"))
            }
            StyleOrigin.QUIZ -> Unit
        }
        val contact = if (origin == StyleOrigin.QUIZ) null else "a"
        val stamp = session.stamp(contact)
        return StyleSample("sample", if (contact == null) RelationGroup.DEFAULT else RelationGroup.SOCIAL,
            if (origin == StyleOrigin.IMPORT) "您好合成唯一标记" else "合成唯一标记", origin, contact,
            if (origin == StyleOrigin.IMPORT) "imp" else null,
            if (origin == StyleOrigin.IMPORT) "line" else null,
            if (origin == StyleOrigin.EDITED_REPLY) "answer" else null,
            stamp.consentVersion, stamp.sourceRevision)
    }
    private suspend fun stamp(sample: StyleSample) = session.stamp(sample.sourceContactId)
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected transaction rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun contactsAndForeignKeysIsolateMessagesAndImports() = runBlocking {
        val conversations = ConversationRepository(session)
        conversations.save(Conversation("conv", "a"))
        conversations.saveMessage(Message("m", "conv", text = "只属于甲", contactId = "a"))
        assertTrue(conversations.messages("b", "conv").isEmpty())
        rejected { conversations.saveMessage(Message("bad", "conv", contactId = "b")) }
        rejected { conversations.save(Conversation("conv", "b")) }
        ImportRepository(session).save(ChatImport("i", "a", contentHash = "same"), emptyList())
        ImportRepository(session).save(ChatImport("j", "b", contentHash = "same"), emptyList())
        assertEquals(1, ImportRepository(session).list("b").size)
    }

    @Test fun allThreeStyleOriginsRejectNoConsentPauseRevocationAndLateConsent() = runBlocking {
        for (origin in StyleOrigin.entries) {
            for (mode in listOf("none", "pause", "revoke", "late")) {
                ConsentRepository(session).update(false)
                val sample = source(origin)
                if (mode != "none") consent()
                val ready = sample.copy(consentVersion = ConsentRepository(session).get().consentVersion,
                    sourceRevision = session.stamp(sample.sourceContactId).sourceRevision)
                val captured = stamp(ready)
                when (mode) {
                    "pause" -> ConsentRepository(session).update(true, true)
                    "revoke" -> ConsentRepository(session).update(false)
                    "late" -> ConsentRepository(session).update(true)
                }
                rejected { StyleRepository(session).saveSample(ready, captured) }
                rejected { StyleRepository(session).saveProfile(StyleProfile(relationGroup = ready.relationGroup,
                    sourceRevision = captured.sourceRevision, consentVersion = captured.consentVersion), captured) }
                assertTrue(StyleRepository(session).samples(ready.relationGroup).isEmpty())
                assertNull(StyleRepository(session).profile(ready.relationGroup))
                if (origin == StyleOrigin.IMPORT) ImportRepository(session).delete("a", "imp")
            }
        }
    }

    @Test fun allThreeOriginsRejectLateSourceAndStaleDerivedDescription() = runBlocking {
        consent()
        for (origin in StyleOrigin.entries) {
            val sample = source(origin)
            val captured = stamp(sample)
            ContactRepository(session).save(Contact("a", "来源改变"))
            rejected { StyleRepository(session).saveSample(sample, captured) }
            rejected { StyleRepository(session).saveProfile(StyleProfile(sourceRevision = captured.sourceRevision,
                consentVersion = captured.consentVersion), captured) }
            if (origin == StyleOrigin.IMPORT) ImportRepository(session).delete("a", "imp")
        }
    }

    @Test fun sourceDeletionClearsSamplesAndGeneratedDescriptionsAndRecomputes() = runBlocking {
        consent()
        val imported = source(StyleOrigin.IMPORT)
        StyleRepository(session).saveSample(imported, stamp(imported))
        ImportRepository(session).save(ChatImport("remainingImport", "a", contentHash = "remainingHash"),
            listOf(ChatLine("remainingLine", "remainingImport", speaker = Speaker.ME, text = "好啊", speakerConfirmed = true, contactId = "a")))
        val remainingStamp = session.stamp("a")
        val remainingSample = imported.copy(id = "remaining", text = "好啊", sourceImportId = "remainingImport",
            sourceMessageId = "remainingLine", sourceRevision = remainingStamp.sourceRevision, consentVersion = remainingStamp.consentVersion)
        StyleRepository(session).saveSample(remainingSample, remainingStamp)
        val current = session.stamp()
        StyleRepository(session).saveProfile(StyleProfile(relationGroup = RelationGroup.SOCIAL, description = "合成唯一标记",
            sourceRevision = current.sourceRevision, consentVersion = current.consentVersion), current)
        ImportRepository(session).delete("a", "imp")
        val remaining = StyleRepository(session).samples(RelationGroup.SOCIAL)
        assertEquals(listOf("好啊"), remaining.map { it.text })
        val profile = requireNotNull(StyleRepository(session).profile(RelationGroup.SOCIAL))
        assertEquals("", profile.description)
        assertFalse(profile.features.usesHonorific)
        ContactRepository(session).delete("a")
        assertTrue(ImportRepository(session).list("a").isEmpty())
        assertFalse(encode(remaining).contains("合成唯一标记"))
    }

    @Test fun deletionOfContactCascadesEveryRelatedSource() = runBlocking {
        consent()
        val imported = source(StyleOrigin.IMPORT)
        StyleRepository(session).saveSample(imported, stamp(imported))
        ContactRepository(session).delete("a")
        assertTrue(StyleRepository(session).samples(RelationGroup.SOCIAL).isEmpty())
        assertNull(StyleRepository(session).profile(RelationGroup.SOCIAL))
        assertTrue(ImportRepository(session).lines("a", "imp").isEmpty())
        assertNotNull(ContactRepository(session).get("b"))
    }

    @Test fun profileSnapshotAndDeletionCoverAllOwnedRecordsWithoutMixingB() = runBlocking {
        consent()
        val imported = source(StyleOrigin.IMPORT)
        StyleRepository(session).saveSample(imported, stamp(imported))
        val conversations = ConversationRepository(session)
        conversations.save(Conversation("profile-conv", "a", false))
        conversations.saveMessage(Message("profile-message", "profile-conv", MessageRole.ASSISTANT,
            "合成唯一标记", contactId="a"))
        session.transaction {
            put(MemoryRow("profile-memory", "a", encode(MemoryEntry("profile-memory", "a", value="合成唯一标记"))))
            put(MemoryOperationRow("profile-op", "a", 1, encode(MemoryOperation("profile-op", "a"))))
            val request = AnalysisRequest(requestId="profile-run", contactId="a", conversationId="profile-conv")
            val payloads = mapOf(
                OwnedRecordKind.PLAN to encode(ActionPlan("fixture", "a", chosenReply="合成唯一标记")),
                OwnedRecordKind.EVENT to encode(Event("fixture", "a", summary="合成唯一标记")),
                OwnedRecordKind.ANALYSIS_RUN to encode(AnalysisRun("profile-run", "profile-conv", contactId="a", requestSnapshot=request)),
                OwnedRecordKind.NICKNAME to encode(NicknameMapping("fixture", "a", original="合成昵称", replacement="代号")),
                OwnedRecordKind.REDACTION to encode(RedactionProfile()),
                OwnedRecordKind.SUMMARY to encode(TimelineSummary("fixture", request, "合成唯一标记")))
            payloads.forEach { (kind, payload) -> put(OwnedRecordRow("${kind.name}:fixture", "a", kind, payload)) }
        }
        val a = ContactRepository(session).get("a")!!
        val populated = ContactDetailsRepository(session).load("a", a.sourceRevision)
        assertEquals(a, populated.contact)
        assertEquals(listOf("imp"), populated.imports.map { it.id })
        assertTrue(populated.imports.all { it.contactId == "a" })
        assertEquals(listOf("line"), populated.evidence.single().lines.map { it.id })
        assertTrue(populated.evidence.flatMap { it.lines }.all { it.contactId == "a" && it.importId == "imp" })
        assertEquals(listOf("profile-message"), populated.messages.map { it.id })
        assertTrue(populated.messages.all { it.contactId == "a" && it.conversationId == "profile-conv" })
        assertEquals(listOf("profile-run"), populated.runs.map { it.requestId })
        assertTrue(populated.runs.all { it.contactId == "a" && it.requestSnapshot?.contactId == "a" })
        assertEquals(listOf("fixture"), populated.plans.map { it.id })
        assertTrue(populated.plans.all { it.contactId == "a" })
        assertEquals(listOf("fixture"), populated.events.map { it.id })
        assertTrue(populated.events.all { it.contactId == "a" })
        assertEquals(listOf("profile-memory"), populated.memories.map { it.id })
        assertTrue(populated.memories.all { it.contactId == "a" })
        // Legacy analyses with no recorded timestamp stay unknown; reads never backfill now.
        assertEquals(0L, populated.messages.single().createdAt)
        val b = ContactRepository(session).get("b")!!
        val snapshot = ContactDetailsRepository(session).load("b", b.sourceRevision)
        assertEquals("b", snapshot.contact.id); assertTrue(snapshot.messages.isEmpty())
        assertTrue(snapshot.imports.isEmpty()); assertTrue(snapshot.evidence.isEmpty())
        assertTrue(snapshot.memories.isEmpty()); assertTrue(snapshot.runs.isEmpty())
        assertTrue(snapshot.events.isEmpty()); assertTrue(snapshot.plans.isEmpty())
        val before = session.stamp()
        ContactRepository(session).delete("a")
        assertTrue(session.stamp().sourceRevision > before.sourceRevision)
        session.transaction {
            assertNull(contact("a")); assertTrue(conversations("a").isEmpty())
            assertTrue(messages("a", "profile-conv").isEmpty()); assertTrue(imports("a").isEmpty())
            assertTrue(lines("a", "imp").isEmpty()); assertTrue(memories("a").isEmpty())
            assertTrue(operations("a").isEmpty()); assertTrue(files("a").isEmpty())
            OwnedRecordKind.entries.forEach { assertTrue(records("a", it.name).isEmpty()) }
            assertTrue(RelationGroup.entries.all { samples(it.name).none { row -> row.contactId == "a" } })
        }
        assertNull(StyleRepository(session).profile(RelationGroup.SOCIAL))
        assertEquals(snapshot, ContactDetailsRepository(session).load("b", b.sourceRevision))
        assertTrue(runCatching { ContactDetailsRepository(session).load("a", 0) }.isFailure)
    }

    @Test fun undoRestoresEvictedRowsAndOnlyAllowsLatest() = runBlocking {
        consent()
        val memory = MemoryRepository(session)
        fun entry(id: Int) = MemoryEntry(id = "$id", contactId = "a", subjectId = "a", field = "假设", value = "值$id",
            sourceType = MemorySource.ASSISTANT_INFERENCE, updatedAt = id.toLong())
        var first: MemoryOperation? = null
        for (i in 1..6) {
            val op = memory.apply("a", entry(i), session.stamp("a"))
            if (first == null) first = op
        }
        assertEquals(listOf("2", "3", "4", "5", "6"), memory.list("a").map { it.id })
        rejected { memory.undoLatest("a", session.stamp("a"), first!!.id) }
        memory.undoLatest("a", session.stamp("a"))
        assertEquals(listOf("1", "2", "3", "4", "5"), memory.list("a").map { it.id })
        assertTrue(memory.list("b").isEmpty())
        rejected { memory.applyProposal("a", MemoryProposal(scope = MemoryScope.USER, subjectId = "me", field = "姓名", value = "假名", sourceType = MemorySource.USER_EXPLICIT), session.stamp("a")) }
    }

    @Test fun undoCannotOverwriteAnIdClaimedByAnotherContactAndLeavesHistoryIntact() = runBlocking {
        consent()
        val memory = MemoryRepository(session)
        fun entry(id: String, contact: String) = MemoryEntry(id, contact, subjectId = contact, field = "假设", value = "合成值")
        memory.apply("a", entry("shared", "a"), session.stamp("a"))
        memory.apply("a", entry("retained", "a"), session.stamp("a"))
        memory.delete("a", "shared", session.stamp("a"))
        memory.apply("b", entry("shared", "b"), session.stamp("b"))
        val aBefore = memory.list("a")
        val bBefore = memory.list("b")
        val history = memory.operations("a")
        rejected { memory.undoLatest("a", session.stamp("a")) }
        assertEquals(aBefore, memory.list("a"))
        assertEquals(bBefore, memory.list("b"))
        assertEquals(history, memory.operations("a"))
        // Releasing the ID allows the same retained operation to be undone successfully.
        memory.delete("b", "shared", session.stamp("b"))
        memory.undoLatest("a", session.stamp("a"))
        assertEquals(setOf("shared", "retained"), memory.list("a").map { it.id }.toSet())
    }

    @Test fun undoRejectsSnapshotWithAnotherContactBeforeClearingExistingRows() = runBlocking {
        consent()
        val memory = MemoryRepository(session)
        val original = MemoryEntry("own", "a", subjectId = "a", field = "假设", value = "合成值")
        memory.apply("a", original, session.stamp("a"))
        val corrupted = MemoryOperation("corrupt", "a", listOf(original.copy(contactId = "b")), listOf(original), 1)
        session.transaction {
            clearOperations("a")
            put(MemoryOperationRow(corrupted.id, "a", 0, encode(corrupted)))
        }
        val before = memory.list("a")
        val history = memory.operations("a")
        rejected { memory.undoLatest("a", session.stamp("a")) }
        assertEquals(before, memory.list("a"))
        assertEquals(history, memory.operations("a"))
        assertTrue(memory.list("b").isEmpty())
    }

    @Test fun samplesCapAtThirtyAndUseCodepointsInMemory() = runBlocking {
        consent()
        repeat(31) { index ->
            val stamp = session.stamp()
            StyleRepository(session).saveSample(StyleSample("q$index", text = "好啊", consentVersion = stamp.consentVersion,
                sourceRevision = stamp.sourceRevision, createdAt = index.toLong()), stamp)
        }
        assertEquals(30, StyleRepository(session).samples(RelationGroup.DEFAULT).size)
        val text = "😀".repeat(200)
        MemoryRepository(session).apply("a", MemoryEntry("emoji", "a", subjectId = "a", field = "假设", value = text), session.stamp("a"))
        rejected { MemoryRepository(session).apply("a", MemoryEntry("tooLong", "a", subjectId = "a", field = "假设", value = text + "😀"), session.stamp("a")) }
    }

    @Test fun temporaryStoreLeavesNoRowsAndExplicitSaveIsIdempotent() = runBlocking {
        val store = TemporarySessionStore()
        val temporary = TemporarySession("temp", draft = "合成敏感草稿", imports = listOf(ChatImport("i", contentHash = "hash")),
            lines = listOf(ChatLine("l", "i", text = "临时合成内容")))
        val credential = store.begin("temp")
        assertTrue(store.put(credential, temporary))
        assertTrue(ConversationRepository(session).list("a").isEmpty())
        assertTrue(ImportRepository(session).list("a").isEmpty())
        assertNull(TemporarySessionStore().get(credential))
        val repository = ConversationRepository(session)
        val id = repository.saveSelectedTemporary("a", store, credential)
        assertEquals(id, repository.saveSelectedTemporary("a", store, credential))
        assertEquals(1, repository.list("a").size)
        assertEquals(1, ImportRepository(session).list("a").size)
        assertEquals("合成敏感草稿", repository.draft("a", id))
        store.clear()
        assertNull(store.get(credential))
        assertFalse(store.put(credential, temporary))
        val newCredential = store.begin("temp")
        assertTrue(store.put(newCredential, temporary.copy(draft = "同名新会话")))
        val nextId = repository.saveSelectedTemporary("a", store, newCredential)
        assertNotEquals("Reusing a display ID must not restore an old saved session", id, nextId)
        assertEquals("同名新会话", repository.draft("a", nextId))
        rejected { repository.save(Conversation("temporary")) }
    }

    @Test fun schemaVersionOneOpensWithoutDestructiveMigration() {
        val sqlite = db.openHelper.writableDatabase
        sqlite.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        sqlite.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        assertTrue(JunshiDatabase.MIGRATIONS.isEmpty())
    }

    @Test fun aPermanentPathCannotChangeContactImportOrBeReusedWhilePending() = runBlocking {
        val cache = RuntimeEnvironment.getApplication().cacheDir
        val root = Files.createTempDirectory(cache.toPath(), "synthetic-files-").toFile()
        try {
            val file = File(root, "source.txt").apply { writeText("合成甲来源") }
            val files = FileCleanupRepository(session)
            files.register("a", null, file, root)
            files.register("a", null, file, root) // Same source registration is idempotent.
            rejected { files.register("b", null, file, root) }
            ImportRepository(session).save(ChatImport("i", "a", contentHash = "synthetic"), emptyList())
            rejected { files.register("a", "i", file, root) }
            ContactRepository(session).delete("b")
            assertTrue("Deleting B must not delete A's registered file", file.exists())
            assertEquals("a", session.transaction { sourceFile(file.canonicalPath)?.contactId })
            session.transaction {
                removeFile(file.canonicalPath)
                put(PendingFileCleanupRow(file.canonicalPath))
            }
            rejected { files.register("a", null, file, root) }
            assertEquals(listOf(file.canonicalPath), files.retryWith { false })
            assertNotNull(session.transaction { pendingFile(file.canonicalPath) })
            rejected { files.register("a", "i", file, root) }
            assertTrue(files.retry().isEmpty())
            assertFalse(file.exists())
            assertNull(session.transaction { pendingFile(file.canonicalPath) })
        } finally { root.deleteRecursively() }
    }

    @Test fun pendingDeleteAndRegistrationCannotRaceUsingAnOldSnapshot() = runBlocking {
        val root = Files.createTempDirectory(RuntimeEnvironment.getApplication().cacheDir.toPath(), "synthetic-race-").toFile()
        try {
            val file = File(root, "source.txt").apply { writeText("合成待删除内容") }
            val path = file.canonicalPath
            session.transaction { put(PendingFileCleanupRow(path)) }
            val files = FileCleanupRepository(session)
            val deleting = CompletableDeferred<Unit>()
            val releaseDelete = CompletableDeferred<Unit>()
            val registering = CompletableDeferred<Unit>()
            withTimeout(10_000) {
                val retry = async {
                    files.retryWith { target -> deleting.complete(Unit); releaseDelete.await(); target.delete() }
                }
                deleting.await()
                val register = async {
                    registering.complete(Unit)
                    runCatching { files.register("b", null, file, root) }
                }
                registering.await()
                assertFalse("Registration must wait until deletion and queue commit finish", register.isCompleted)
                releaseDelete.complete(Unit)
                assertTrue(retry.await().isEmpty())
                assertTrue("A physically deleted file cannot become a new source", register.await().isFailure)
            }
            assertTrue(session.transaction { files("b") }.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun revokedTemporaryCredentialCannotMigrateAnOldSnapshot() = runBlocking {
        val store = TemporarySessionStore()
        val credential = store.begin("sensitive")
        assertTrue(store.put(credential, TemporarySession("sensitive", draft = "清除前的合成内容")))
        store.clear()
        var rejected = false
        try { ConversationRepository(session).saveSelectedTemporary("a", store, credential) }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertTrue(ConversationRepository(session).list("a").isEmpty())
        val newCredential = store.begin("sensitive")
        assertFalse(store.put(credential, TemporarySession("sensitive", draft = "旧模型迟到内容")))
        assertTrue(store.put(newCredential, TemporarySession("sensitive", draft = "用户新建内容")))
        val saved = ConversationRepository(session).saveSelectedTemporary("a", store, newCredential)
        assertEquals("用户新建内容", ConversationRepository(session).draft("a", saved))
    }
}
