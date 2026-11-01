package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ContactDetailsTest {
    @Test fun contactEditPreservesDirtyFormalDraftWhenPrerequisiteFlushKeepsFailing() = runBlocking {
        for (archive in listOf(false, true)) {
            val f = CounselControllerTest.Fixture()
            try {
                f.controller.initialize(); f.controller.createContact("虚构草稿联系人", RelationType.FRIEND)
                val owner = f.controller.state.value.currentContact!!
                val originalConversation = f.controller.state.value.conversation
                f.persistence.updateFailures=2
                f.controller.editDraft("仅RAM中尚未保存的虚构草稿")
                assertEquals("STORAGE_UNAVAILABLE", f.controller.state.value.error?.code)
                f.controller.openContactDetails(owner.id)
                f.controller.editContact(owner.copy(alias="不应提交的新代号", archivedAt=if(archive) 123 else null), owner.sourceRevision)
                assertEquals(owner, f.controller.state.value.currentContact)
                assertEquals(originalConversation, f.controller.state.value.conversation)
                assertEquals("仅RAM中尚未保存的虚构草稿", f.controller.state.value.draft)
                assertFalse(f.controller.state.value.draftSaved)
                assertEquals(0, f.persistence.contactEditAttempts)
                assertTrue(f.controller.details.value.failed)
                assertTrue(f.controller.state.value.error?.retryable == true)
                f.controller.retry()
                assertEquals("仅RAM中尚未保存的虚构草稿", f.persistence.saved.getValue(owner.id).draft)
                assertEquals(owner, f.persistence.people.single())
                assertEquals(0, f.persistence.contactEditAttempts)
                assertEquals(1, f.persistence.draftWrites)
                assertTrue(f.controller.state.value.draftSaved)
                assertTrue(f.provider.calls.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun realCompletionPersistsTimeOnceAndDetailSortsItAfterRealImport() = runBlocking {
        for (failureStage in listOf("beforeAssistant", "afterAssistant")) {
            var now = System.currentTimeMillis() + 60_000L
            var clockReads = 0
            val f = CounselControllerTest.Fixture(wallClock = { clockReads++; now })
            try {
                f.controller.initialize(); f.controller.createContact("虚构时间测试", RelationType.FRIEND)
                val owner = f.controller.state.value.currentContact!!
                val preview = com.shengjidaguai.goutoujunshi.importing.ImportPreview(
                    rows = listOf(com.shengjidaguai.goutoujunshi.importing.ImportRow("虚构对象", "虚构导入消息", speaker=Speaker.THEM)),
                    target = com.shengjidaguai.goutoujunshi.importing.ImportTarget(owner), rightsAccepted=true)
                val imported = CompletableDeferred<Boolean>()
                f.controller.commitImport(preview) { imported.complete(it) }
                assertTrue(withTimeout(2000) { imported.await() })
                f.controller.analyze()
                withTimeout(2000) { while(f.provider.calls.isEmpty()) yield() }
                if (failureStage == "beforeAssistant") f.persistence.assistantFailures=1 else f.persistence.runFailures=1
                val firstReceiptTime = now
                f.provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(id="timed-answer", reply=listOf("虚构建议"))))
                withTimeout(2000) { while(f.controller.state.value.error == null) yield() }
                if (failureStage == "afterAssistant") assertEquals(firstReceiptTime,
                    f.persistence.saved.getValue(owner.id).messages.single { it.role == MessageRole.ASSISTANT }.createdAt)
                now += 60_000L
                f.controller.retry()
                withTimeout(2000) { while(f.controller.state.value.answer == null) yield() }
                f.controller.openContactDetails(owner.id)
                withTimeout(2000) { while(f.controller.details.value.data == null) yield() }
                val detail = f.controller.details.value.data!!
                val assistant = detail.messages.single { it.role == MessageRole.ASSISTANT }
                assertEquals(firstReceiptTime, assistant.createdAt)
                assertEquals("A storage retry must not read a new completion time", 1, clockReads)
                assertTrue(assistant.createdAt >= detail.imports.single().importedAt)
                val timeline = com.shengjidaguai.goutoujunshi.ui.contactTimeline(detail)
                assertTrue(timeline.first() is com.shengjidaguai.goutoujunshi.ui.ContactTimelineItem.Analysis)
                assertTrue(timeline[1] is com.shengjidaguai.goutoujunshi.ui.ContactTimelineItem.Imported)
                assertEquals(1, f.provider.calls.size)
                f.controller.closeContactDetails(); f.controller.openContactDetails(owner.id)
                assertEquals(assistant.createdAt, f.controller.details.value.data!!.messages.single { it.role == MessageRole.ASSISTANT }.createdAt)
            } finally { f.close() }
        }
    }
    @Test fun contactDeletionFailureKeepsDetailsRetryAndNeverClaimsCleanupSuccess() = runBlocking {
        val f=CounselControllerTest.Fixture()
        try {
            f.persistence.people += Contact("a", "虚构甲")
            f.controller.initialize(); f.controller.openContactDetails("a")
            f.persistence.cleanupFailures=1
            f.controller.deleteContact("a")
            assertTrue(f.controller.details.value.failed)
            assertTrue(f.controller.details.value.deletePending)
            assertNull(f.controller.details.value.data)
            assertTrue(f.persistence.cleanupPending)
            f.controller.deleteContact("a")
            assertFalse(f.persistence.cleanupPending)
            assertNull(f.controller.details.value.ownerId)
        } finally { f.close() }
    }

    @Test fun timelineSortsAcrossKindsAndKeepsUndatedActionsUnknownAndScoped() {
        val a=Contact("a", "虚构甲")
        val data=com.shengjidaguai.goutoujunshi.data.ContactDetails(a,
            imports=listOf(ChatImport("late", "a", importedAt=200), ChatImport("other", "b", importedAt=999)),
            messages=listOf(Message("early", role=MessageRole.ASSISTANT, contactId="a", createdAt=100)),
            plans=listOf(ActionPlan("undated", "a", status=PlanStatus.SENT)))
        val timeline=com.shengjidaguai.goutoujunshi.ui.contactTimeline(data)
        assertEquals(listOf(200L,100L,null), timeline.map { it.time })
        assertTrue(timeline.last() is com.shengjidaguai.goutoujunshi.ui.ContactTimelineItem.Action)
    }

    @Test fun everyRelationHasAListGroupAndMediationKeepsItsScene() {
        val groups = RelationType.entries.map { com.shengjidaguai.goutoujunshi.ui.contactListGroup(it) }
        assertEquals(16, groups.size)
        assertEquals(setOf(RelationGroup.WORK, RelationGroup.INTIMATE, RelationGroup.FAMILY, RelationGroup.SOCIAL), groups.toSet())
        assertEquals(RelationGroup.SOCIAL, com.shengjidaguai.goutoujunshi.ui.contactListGroup(RelationType.MEDIATION))
        assertEquals(RelationGroup.MEDIATION, RelationType.MEDIATION.group())
    }

    @Test fun lateDetailCannotPublishAfterAnotherOwnerCloseDeleteOrClear() = runBlocking {
        for (action in listOf("switch", "close", "delete", "clear")) {
            val f = CounselControllerTest.Fixture()
            try {
                f.persistence.people += listOf(Contact("a", "虚构甲"), Contact("b", "虚构乙"))
                f.controller.initialize()
                val gate = CompletableDeferred<Unit>(); f.persistence.beforeDetails = gate
                f.controller.openContactDetails("a")
                when(action) {
                    "switch" -> f.controller.openContactDetails("b")
                    "close" -> f.controller.closeContactDetails()
                    "delete" -> f.controller.deleteContact("a")
                    "clear" -> f.controller.clearAll()
                }
                gate.complete(Unit); yield()
                if (action == "switch") assertEquals("b", f.controller.details.value.data?.contact?.id)
                else assertNull(f.controller.details.value.data)
            } finally { f.close() }
        }
    }
    @Test fun editingAndDeletingAWhileBStreamsDoNotCancelBOrSendLocalNotes() = runBlocking {
        val f = CounselControllerTest.Fixture()
        try {
            val a = Contact("a", "虚构甲"); val b = Contact("b", "虚构乙")
            f.persistence.people += listOf(a, b); f.controller.initialize(); f.controller.selectContact("b")
            f.controller.editDraft("虚构问题"); f.controller.analyze(); withTimeout(2000) { while(f.provider.calls.isEmpty()) yield() }
            assertTrue(f.controller.state.value.streaming)
            f.controller.editContact(a.copy(notes="仅本机手工资料"), 0)
            assertTrue(f.controller.state.value.streaming)
            f.controller.deleteContact("a")
            assertTrue(f.controller.state.value.streaming)
            assertEquals("b", f.controller.state.value.currentContact?.id)
            assertEquals("", f.provider.calls.single().contactContext)
            f.provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(id="answer", reply=listOf("虚构建议"))))
            withTimeout(2000) { while(f.controller.state.value.answer == null) yield() }; assertNotNull(f.controller.state.value.answer)
        } finally { f.close() }
    }
    @Test fun archiveExcludesStartupAndSelectionAndUndoCannotOverrideLaterEdit() = runBlocking {
        val f = CounselControllerTest.Fixture()
        try {
            val archived = Contact("a", "归档虚构甲", archivedAt=1)
            f.persistence.people += listOf(archived, Contact("b", "虚构乙"))
            f.controller.initialize(); assertEquals("b", f.controller.state.value.currentContact?.id)
            f.controller.selectContact("a"); assertEquals("b", f.controller.state.value.currentContact?.id)
            f.controller.editContact(archived.copy(archivedAt=null), 0)
            f.controller.selectContact("a"); assertEquals("a", f.controller.state.value.currentContact?.id)
            val a = f.controller.state.value.currentContact!!
            f.controller.editContact(a.copy(archivedAt=2), a.sourceRevision)
            assertNull(f.controller.state.value.currentContact)
            f.controller.undoContactEdit()
            assertNull(f.persistence.people.first { it.id == "a" }.archivedAt)
        } finally { f.close() }
    }
}
