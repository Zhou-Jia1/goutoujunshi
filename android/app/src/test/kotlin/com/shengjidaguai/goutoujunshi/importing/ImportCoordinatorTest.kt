package com.shengjidaguai.goutoujunshi.importing

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest.Fixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImportCoordinatorTest {
    @Test fun coldShareWaitsForInitializationWithoutDroppingPayloadOrSpeakerGate() = runBlocking {
        val f = Fixture()
        val importer = ImportCoordinator(f.controller, f.scope, { false }, {})
        try {
            importer.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "虚构冷启动：消息"))
            assertTrue(importer.state.value!!.busy)
            f.controller.initialize()
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            assertEquals("消息", importer.state.value!!.rows.single().text)
            assertFalse(importer.state.value!!.canConfirm)
            f.controller.editDraft("用户问题"); f.controller.analyze()
            assertTrue(f.provider.calls.isEmpty())
        } finally { importer.cancel(); f.close() }
    }

    @Test fun coldShareInitializationFailureShowsRetryAndRetainsOnlyInMemoryPayload() = runBlocking {
        val f = Fixture(); f.persistence.initializeFailures = 1
        val importer = ImportCoordinator(f.controller, f.scope, { false }, {})
        try {
            importer.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "虚构冷启动：保留消息"))
            f.controller.initialize()
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            assertEquals("IMPORT_INITIALIZE", importer.state.value!!.error)
            importer.retry()
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            assertNull(importer.state.value!!.error)
            assertEquals("保留消息", importer.state.value!!.rows.single().text)
            assertTrue(f.persistence.saved.isEmpty())
        } finally { importer.cancel(); f.close() }
    }

    @Test fun startupFailureIsNotErasedByShareQueuedWhileInitializeSuspends() = runBlocking {
        val f = Fixture(); f.persistence.initializeFailures = 1
        f.persistence.beforeInitialize = CompletableDeferred()
        f.controller.initialize()
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val importerScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        val importer = ImportCoordinator(f.controller, importerScope, { false }, {})
        try {
            importer.text("虚构：保留消息")
            f.persistence.beforeInitialize!!.complete(Unit)
            // Start the parse waiter only after the queued invalidation has been processed.
            scheduler.runCurrent()
            assertFalse(importer.state.value!!.busy)
            assertEquals("IMPORT_INITIALIZE", importer.state.value!!.error)
            assertTrue(f.controller.state.value.error!!.retryable)
        } finally { importer.cancel(); importerScope.cancel(); f.close() }
    }

    @Test fun repeatedStartupAndInitialLoadFailuresAlwaysAcknowledgeRetryWithoutStateFlowTransitions() = runBlocking {
        for (contactLoad in listOf(false, true)) {
            val f = Fixture()
            if (contactLoad) {
                f.persistence.people += com.shengjidaguai.goutoujunshi.domain.Contact("synthetic", "虚构联系人")
                f.persistence.failedLoadId = "synthetic"
            } else f.persistence.initializeFailures = 2
            f.controller.initialize()
            val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
            val importerScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
            val importer = ImportCoordinator(f.controller, importerScope, { false }, {})
            try {
                importer.text("虚构：保留消息"); scheduler.runCurrent()
                assertEquals("IMPORT_INITIALIZE", importer.state.value!!.error)
                if (contactLoad) f.persistence.failedLoadId = "synthetic"
                importer.retry(); scheduler.runCurrent()
                assertFalse(importer.state.value!!.busy)
                assertEquals("IMPORT_INITIALIZE", importer.state.value!!.error)
                importer.retry()
                withTimeout(3000) { while (importer.state.value?.busy == true) { scheduler.runCurrent(); yield() } }
                assertNull(importer.state.value!!.error)
                assertEquals("保留消息", importer.state.value!!.rows.single().text)
            } finally { importer.cancel(); importerScope.cancel(); f.close() }
        }
    }

    @Test fun initialContactLoadFailureCanRetryWithoutHangingColdShare() = runBlocking {
        val f = Fixture()
        f.persistence.people += com.shengjidaguai.goutoujunshi.domain.Contact("synthetic", "虚构初始联系人")
        f.persistence.failedLoadId = "synthetic"
        val importer = ImportCoordinator(f.controller, f.scope, { false }, {})
        try {
            importer.text("虚构：保留消息")
            f.controller.initialize()
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            assertEquals("IMPORT_INITIALIZE", importer.state.value!!.error)
            importer.retry()
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            assertEquals("保留消息", importer.state.value!!.rows.single().text)
            assertNull(importer.state.value!!.error)
        } finally { importer.cancel(); f.close() }
    }

    @Test fun explicitShareConsumedOnceAndIdenticalSubsequentShareIsAccepted() = runBlocking {
        val f = Fixture(); f.controller.initialize()
        val importer = ImportCoordinator(f.controller, f.scope, { false }, {})
        try {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "虚构：消息")
            importer.receive(intent)
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            val id = importer.state.value!!.id
            assertNull(intent.action); assertNull(intent.extras)
            importer.receive(intent); assertEquals(id, importer.state.value!!.id)
            importer.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "虚构：消息"))
            assertNotEquals(id, importer.state.value!!.id)
        } finally { importer.cancel(); f.close() }
    }
    @Test fun multipleTextAndImagesHaveSeparateUnconfirmedPaths() = runBlocking {
        val f = Fixture(); f.controller.initialize()
        val importer = ImportCoordinator(f.controller, f.scope, { false }, {})
        try {
            importer.receive(Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
                .putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf("虚构甲：甲", "虚构乙：乙")))
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            assertEquals(2, importer.state.value!!.rows.size); assertFalse(importer.state.value!!.canConfirm)
            importer.receive(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.parse("content://synthetic/1"), Uri.parse("content://synthetic/2"))))
            assertNull(importer.state.value!!.error)
            assertEquals(2, importer.state.value!!.images.size); assertTrue(importer.state.value!!.rows.isEmpty())
            assertFalse(importer.state.value!!.canConfirm)
        } finally { importer.cancel(); f.close() }
    }
    @Test fun newShareAndCancelRevokeSuspendedImportUiPublication() = runBlocking {
        for (cancelInstead in listOf(false, true)) {
            val f = Fixture(); f.controller.initialize(); f.controller.createContact("虚构目标", com.shengjidaguai.goutoujunshi.domain.RelationType.FRIEND)
            val contact = f.controller.state.value.currentContact!!
            f.controller.newTemporary()
            val importer = ImportCoordinator(f.controller, f.scope, { true }, {})
            try {
                importer.text("虚构甲：旧消息")
                withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
                importer.target(ImportTarget(contact))
                importer.edit(0, importer.state.value!!.rows[0].copy(speaker = com.shengjidaguai.goutoujunshi.domain.Speaker.THEM))
                f.persistence.beforeImport = CompletableDeferred()
                importer.confirm()
                if (cancelInstead) importer.cancel() else importer.text("虚构乙：新消息")
                f.persistence.beforeImport!!.complete(Unit)
                withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
                assertNull(f.controller.state.value.currentContact)
                if (cancelInstead) assertNull(importer.state.value)
                else assertEquals("新消息", importer.state.value!!.rows.single().text)
            } finally { importer.cancel(); f.close() }
        }
    }

    @Test fun newShareDuringPostSaveReloadKeepsNewPreviewAndOldTemporarySession() = runBlocking {
        val f = Fixture(); f.controller.initialize(); f.controller.createContact("虚构目标", com.shengjidaguai.goutoujunshi.domain.RelationType.FRIEND)
        val contact = f.controller.state.value.currentContact!!
        f.controller.newTemporary(); f.controller.editDraft("临时问题")
        val importer = ImportCoordinator(f.controller, f.scope, { true }, {})
        try {
            importer.text("虚构甲：旧消息")
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            importer.target(ImportTarget(contact))
            importer.edit(0, importer.state.value!!.rows[0].copy(speaker = com.shengjidaguai.goutoujunshi.domain.Speaker.THEM))
            f.persistence.beforeLoad = CompletableDeferred()
            importer.confirm()
            assertEquals(1, f.persistence.imports.size)
            importer.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "虚构乙：新消息"))
            f.persistence.beforeLoad!!.complete(Unit)
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            assertEquals("新消息", importer.state.value!!.rows.single().text)
            assertNull(f.controller.state.value.currentContact)
            f.controller.editDraft("临时仍可编辑"); assertNull(f.controller.state.value.error)
        } finally { importer.cancel(); f.close() }
    }

    @Test fun actorCompletionReturnsToCoordinatorDispatcherAndCannotClearNewPreviewOrCancel() = runBlocking {
        for (next in listOf("begin", "cancel", "complete")) {
            val label = ThreadLocal<String>()
            val actorScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
            val coordinatorScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
            val actorScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(actorScheduler) + label.asContextElement("actor"))
            val coordinatorScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(coordinatorScheduler) + label.asContextElement("coordinator"))
            val f = Fixture(scope = actorScope)
            f.controller.initialize(); actorScheduler.runCurrent()
            var rightsWrites = 0
            val importer = ImportCoordinator(f.controller, coordinatorScope, { true }, {
                assertEquals("coordinator", label.get()); rightsWrites++
            })
            try {
                importer.text("虚构甲：旧消息")
                withTimeout(3000) { while (importer.state.value?.busy == true) {
                    coordinatorScheduler.runCurrent(); actorScheduler.runCurrent(); yield()
                } }
                importer.target(ImportTarget())
                importer.edit(0, importer.state.value!!.rows.single().copy(speaker = com.shengjidaguai.goutoujunshi.domain.Speaker.THEM))
                importer.confirm(); actorScheduler.runCurrent()
                // IO work is complete, but its Main continuation has not run yet.
                assertTrue(importer.state.value!!.busy)
                assertEquals(0, rightsWrites)
                if (next == "begin") importer.text("虚构乙：新消息")
                else if (next == "cancel") importer.cancel()
                coordinatorScheduler.runCurrent(); actorScheduler.runCurrent()
                when (next) {
                    "begin" -> {
                        withTimeout(3000) { while (importer.state.value?.busy == true) {
                            coordinatorScheduler.runCurrent(); actorScheduler.runCurrent(); yield()
                        } }
                        assertEquals("新消息", importer.state.value!!.rows.single().text)
                        f.controller.editDraft("问题"); f.controller.analyze(); actorScheduler.runCurrent()
                        assertTrue(f.provider.calls.isEmpty())
                        assertEquals(0, rightsWrites)
                    }
                    "cancel" -> { assertNull(importer.state.value); assertEquals(0, rightsWrites) }
                    else -> { assertNull(importer.state.value); assertEquals(1, rightsWrites) }
                }
            } finally { importer.cancel(); coordinatorScope.cancel(); f.close() }
        }
    }

    @Test fun clipboardTextIsNotASavedDraftAndPrivateFileUriIsRejected() = runBlocking {
        val f = Fixture(); f.controller.initialize()
        val importer = ImportCoordinator(f.controller, f.scope, { false }, {})
        try {
            importer.text("虚构：粘贴消息")
            withTimeout(3000) { while (importer.state.value?.busy == true) yield() }
            assertEquals("", f.controller.state.value.draft)
            importer.file(org.robolectric.RuntimeEnvironment.getApplication().contentResolver, Uri.parse("file:///data/private"))
            assertEquals("IMPORT_READ", importer.state.value!!.error)
            assertTrue(f.persistence.saved.isEmpty())
        } finally { importer.cancel(); f.close() }
    }
}
