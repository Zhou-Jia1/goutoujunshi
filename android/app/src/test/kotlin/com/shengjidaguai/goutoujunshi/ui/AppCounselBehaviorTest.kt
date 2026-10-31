package com.shengjidaguai.goutoujunshi.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.shengjidaguai.goutoujunshi.R
import com.shengjidaguai.goutoujunshi.counsel.CounselState
import com.shengjidaguai.goutoujunshi.counsel.DraftEditStamp
import com.shengjidaguai.goutoujunshi.counsel.CounselControllerTest
import com.shengjidaguai.goutoujunshi.domain.RelationType
import com.shengjidaguai.goutoujunshi.domain.AnalysisChunk
import com.shengjidaguai.goutoujunshi.domain.StructuredAnswer
import kotlinx.coroutines.runBlocking
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.domain.Contact
import com.shengjidaguai.goutoujunshi.designsystem.R as DsR
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppCounselBehaviorTest {
    @Test fun crisisHidesMalformedOrdinaryContentInCompletedAndStoppedStreamsAndLockHidesEverything() {
        val ordinary = "虚构普通策略必须隐藏"
        val state = mutableStateOf(syntheticCounselState("empty").copy(answer = StructuredAnswer(
            safety = com.shengjidaguai.goutoujunshi.domain.SafetyLevel.CRISIS,
            reply = listOf(ordinary), verdict = ordinary, why = listOf(ordinary),
            freeText = ordinary, rawText = ordinary, formatDisordered = true)))
        compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) { CounselContent(state.value, CounselUiActions()) } } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-safety"))
        compose.onAllNodesWithText(ordinary, substring = true).assertCountEquals(0)
        compose.onNodeWithTag("counsel-answer").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(answer = null, partial = mapOf(
            com.shengjidaguai.goutoujunshi.domain.SectionId.SAFETY to "CRISIS",
            com.shengjidaguai.goutoujunshi.domain.SectionId.REPLY to ordinary,
            com.shengjidaguai.goutoujunshi.domain.SectionId.FREE_TEXT to ordinary)) }
        compose.onNodeWithTag("counsel-safety").assertIsDisplayed()
        compose.onAllNodesWithText(ordinary, substring = true).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(safety = com.shengjidaguai.goutoujunshi.domain.SafetyLevel.CRISIS,
            error = com.shengjidaguai.goutoujunshi.counsel.CounselError("STORAGE_UNAVAILABLE", true),
            partial = mapOf(com.shengjidaguai.goutoujunshi.domain.SectionId.REPLY to ordinary)) }
        compose.onNodeWithTag("counsel-safety").assertIsDisplayed()
        compose.onNodeWithTag("counsel-answer").assertDoesNotExist()
        compose.onAllNodesWithText(ordinary, substring = true).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(draft = ordinary,
            lockState = com.shengjidaguai.goutoujunshi.domain.LockState(enabled = true)) }
        compose.onNodeWithText(text(R.string.counsel_locked)).assertIsDisplayed()
        compose.onAllNodesWithText(ordinary, substring = true).assertCountEquals(0)
        compose.onNodeWithTag("counsel-list").assertDoesNotExist()
    }
    @Test fun balanceErrorOpensSettingsAndMissingReplyStillDisplaysWholeHumanText() {
        val state = mutableStateOf(syntheticCounselState("empty").copy(
            error = com.shengjidaguai.goutoujunshi.counsel.CounselError("INSUFFICIENT_BALANCE")))
        var opens = 0
        compose.setContent { JunshiTheme { Surface(Modifier.fillMaxSize()) {
            CounselContent(state.value, CounselUiActions(openSettings = { opens++ }))
        } } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(DsR.string.ds_settings)))
        compose.onNodeWithText(text(DsR.string.ds_settings)).performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            state.value = state.value.copy(error = null, answer = com.shengjidaguai.goutoujunshi.domain.SectionParser().apply {
                feed("[[SITUATION]]虚构局势仍可参考")
            }.finish())
        }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("[[SITUATION]]虚构局势仍可参考"))
        compose.onNodeWithText("[[SITUATION]]虚构局势仍可参考").assertIsDisplayed()
    }
    @Test fun stoppedOrFailedPartialReplyCannotBeCopiedAsCompletedAnswer() {
        val state = syntheticCounselState("streaming").copy(streaming = false,
            error = com.shengjidaguai.goutoujunshi.counsel.CounselError("NETWORK", true))
        var copies = 0
        compose.setContent { JunshiTheme {
            CompositionLocalProvider(LocalCounselClipboard provides CounselClipboard(read = { null }, write = { copies++ })) {
                Surface(Modifier.fillMaxSize()) { CounselContent(state, CounselUiActions()) }
            }
        } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-answer"))
        compose.onNodeWithText(text(DsR.string.ds_copy_all)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, copies) }
    }
    // Use Compose 1.12's StandardTestDispatcher-aware synchronization for dialog roots.
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun text(id: Int) = context.getString(id)

    @Test fun clipboardReadRequiresExplicitPasteClick() {
        var reads = 0
        var pasted: String? = null
        val state = mutableStateOf(syntheticCounselState("empty"))
        val clipboard = CounselClipboard(read = { reads++; "虚构剪贴板内容" }, write = {})
        compose.setContent {
            JunshiTheme {
                CompositionLocalProvider(LocalCounselClipboard provides clipboard) {
                    Surface(Modifier.fillMaxSize()) {
                        CounselContent(state.value, CounselUiActions(pasteText = { value, stamp -> pasted = value; state.value = state.value.copy(draft = value, draftEditAck = stamp) }))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, reads)
        assertNull(pasted)
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(DsR.string.ds_paste)))
        compose.onNodeWithText(text(DsR.string.ds_paste)).performClick()
        compose.runOnIdle {
            assertEquals(1, reads)
            assertEquals("虚构剪贴板内容", pasted)
            assertEquals("虚构剪贴板内容", state.value.draft)
        }
        // A subsequent state update/recomposition must not read it again.
        compose.runOnIdle { state.value = state.value.copy(emotion = 4) }
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun demoLabelSurvivesEmptyStreamingAndCompletedStates() {
        val state = mutableStateOf(syntheticCounselState("empty"))
        compose.setContent { JunshiTheme { Surface { CounselContent(state.value, CounselUiActions()) } } }
        fun assertDemo() = compose.onNode(hasText(text(DsR.string.ds_demo)) and hasAnyAncestor(hasTestTag("model-status"))).assertIsDisplayed()
        assertDemo()
        compose.runOnIdle { state.value = syntheticCounselState("streaming") }
        assertDemo()
        compose.runOnIdle { state.value = syntheticCounselState("completed") }
        assertDemo()
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-answer"))
        compose.onNode(hasText(text(DsR.string.ds_demo)) and hasAnyAncestor(hasTestTag("counsel-answer"))).assertIsDisplayed()
    }

    @Test fun modelPillExplanationMatchesDemoLiveMissingKeyAndOffline() {
        val state = mutableStateOf(syntheticCounselState("empty"))
        compose.setContent { JunshiTheme { Surface { CounselContent(state.value, CounselUiActions()) } } }
        val settings = com.shengjidaguai.goutoujunshi.data.network.ModelSettingsSnapshot(
            com.shengjidaguai.goutoujunshi.data.network.SavedModelSettings(true, "https://synthetic.example", "synthetic", "Synthetic"), "••••1234")
        val cases = listOf(
            syntheticCounselState("empty") to R.string.demo_explanation,
            syntheticCounselState("empty").copy(demo = false, modelSettings = settings) to R.string.live_explanation,
            syntheticCounselState("empty").copy(demo = false, modelSettings = settings.copy(maskedKey = null)) to R.string.model_not_configured_notice,
            syntheticCounselState("empty").copy(demo = false, modelSettings = settings,
                error = com.shengjidaguai.goutoujunshi.counsel.CounselError("NETWORK")) to R.string.model_error_network,
        )
        cases.forEach { (snapshot, explanation) ->
            compose.runOnIdle { state.value = snapshot }
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("model-status"))).performClick()
            compose.onNode(hasText(text(explanation)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            if (!snapshot.demo) compose.onNode(hasText(text(R.string.demo_explanation)) and hasAnyAncestor(isDialog())).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.close)).performClick()
        }
    }

    @Test fun copiedReplyIsReadableFromAndroidClipboardAndMarkedSensitive() {
        val state = syntheticCounselState("completed")
        compose.setContent { JunshiTheme { Surface { CounselContent(state, CounselUiActions()) } } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(DsR.string.ds_copy)))
        compose.onNodeWithText(text(DsR.string.ds_copy)).performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip!!
            assertEquals(state.answer!!.reply.single(), clip.getItemAt(0).coerceToText(context).toString())
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }
        compose.onNodeWithText(text(R.string.copied)).assertIsDisplayed()
    }

    @Test fun dismissedContactDialogNeverInvokesCreation() {
        var creations = 0
        compose.setContent {
            JunshiTheme { Surface { CounselContent(syntheticCounselState("empty"), CounselUiActions(createContact = { _, _ -> creations++ })) } }
        }
        compose.onNodeWithText(text(R.string.temporary_title)).performClick()
        // Bound time while the popup transitions into a dialog containing a text field.
        // Robolectric must not continually advance the field/window animation clock in
        // pursuit of global idle; each input and dismiss still runs through real semantics.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(text(DsR.string.ds_create)).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        repeat(3) {
            compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)) }
            compose.mainClock.advanceTimeByFrame()
        }
        // This interaction deliberately owns both clocks. Do not ask Espresso to force
        // repeated dialog re-layout between each focused text-field semantic operation.
        compose.runWithoutImplicitWait {
            val aliasField = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            aliasField.assertIsDisplayed().performTextInput("虚构代号甲")
            repeat(3) {
                compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)) }
                compose.mainClock.advanceTimeByFrame()
            }
            aliasField.assertTextContains("虚构代号甲")
            compose.runOnUiThread { assertEquals(0, creations) }
            compose.onNodeWithText(text(R.string.close)).performClick()
            compose.mainClock.advanceTimeBy(1_000)
            repeat(3) {
                compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)) }
                compose.mainClock.advanceTimeByFrame()
            }
            compose.runOnUiThread { assertEquals(0, creations) }
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun loadingWithoutConversationDoesNotSendOrPrepare() {
        var sends = 0
        var previews = 0
        compose.setContent {
            JunshiTheme { Surface {
                CounselContent(CounselState(loading = true, draft = "虚构初始化输入"),
                    CounselUiActions(analyze = { sends++ }, preparePreview = { previews++ }))
            } }
        }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(DsR.string.ds_send)))
        compose.onNodeWithText(text(DsR.string.ds_send)).performClick()
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(R.string.preview_request)))
        compose.onNodeWithText(text(R.string.preview_request)).performClick()
        compose.runOnIdle { assertEquals(0, sends); assertEquals(0, previews) }
    }

    @Test fun screenshotEntryExplainsNextStageWithoutImporting() {
        compose.setContent { JunshiTheme { Surface { CounselContent(syntheticCounselState("empty"), CounselUiActions()) } } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(DsR.string.ds_screenshots)))
        compose.onNodeWithText(text(DsR.string.ds_screenshots)).performClick()
        compose.onNodeWithText(text(R.string.import_ocr_pending)).assertIsDisplayed()
    }

    @Test fun contactSwitcherOffersExplicitTemporarySession() {
        var temporarySessions = 0
        val contact = Contact(id = "synthetic-contact", alias = "虚构代号乙")
        compose.setContent {
            JunshiTheme { Surface {
                CounselContent(syntheticCounselState("empty").copy(currentContact = contact, contacts = listOf(contact)),
                    CounselUiActions(newTemporary = { temporarySessions++ }))
            } }
        }
        compose.onNodeWithText(contact.alias).performClick()
        compose.onNodeWithText(text(R.string.temporary_title)).performClick()
        compose.runOnIdle { assertEquals(1, temporarySessions) }
    }

    @Test fun delayedDraftEchoDoesNotReplaceLatestTextOrMoveSelection() {
        val state = mutableStateOf(syntheticCounselState("empty"))
        val edits = mutableListOf<String>()
        compose.setContent { JunshiTheme { Surface {
            CounselContent(state.value, CounselUiActions(editDraft = { value, _ -> edits += value }))
        } } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-composer"))
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("counsel-composer")))
        field.performTextInput("synthetic_m1_sa")
        field.performTextInput("ved_draft")
        val latest = "synthetic_m1_saved_draft"
        compose.runOnIdle { state.value = state.value.copy(draft = edits.first()) }
        field.assertTextEquals(latest)
        assertEquals(TextRange(latest.length), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.performTextInputSelection(TextRange(5))
        compose.runOnIdle { state.value = state.value.copy(draft = "synthetic_m1_save") }
        field.assertTextEquals(latest)
        assertEquals(TextRange(5), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.performTextInput("X")
        val inserted = latest.take(5) + "X" + latest.drop(5)
        field.assertTextEquals(inserted)
        compose.runOnIdle { state.value = state.value.copy(draft = inserted) }
        field.assertTextEquals(inserted)
        assertEquals(TextRange(6), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @Test fun abaDraftAcknowledgementsRequireMatchingEditorAndSequence() {
        val state = mutableStateOf(syntheticCounselState("empty"))
        val edits = mutableListOf<Pair<String, DraftEditStamp>>()
        compose.setContent { JunshiTheme { Surface {
            CounselContent(state.value, CounselUiActions(editDraft = { value, stamp -> edits += value to stamp }))
        } } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-composer"))
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("counsel-composer")))
        field.performTextReplacement("a"); field.performTextReplacement("ab"); field.performTextReplacement("a")
        assertEquals(listOf("a", "ab", "a"), edits.map { it.first })
        assertEquals(listOf(1L, 2L, 3L), edits.map { it.second.sequence })
        edits.forEach { (value, stamp) ->
            compose.runOnIdle { state.value = state.value.copy(draft = value, draftEditAck = stamp) }
            field.assertTextEquals("a")
            assertEquals(TextRange(1), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        }
        // After the latest real acknowledgement, an external update can be accepted again.
        compose.runOnIdle { state.value = state.value.copy(draft = "external synthetic update", draftEditAck = null) }
        field.assertTextEquals("external synthetic update")
    }

    @Test fun retainedConversationARenderCannotEditOrSendIntoRealControllerB() {
        val f = CounselControllerTest.Fixture()
        try {
            f.controller.initialize(); f.controller.createContact("Synthetic UI A", RelationType.FRIEND)
            val a = f.controller.state.value.currentContact!!
            f.controller.createContact("Synthetic UI B", RelationType.BOSS)
            val b = f.controller.state.value.currentContact!!
            f.controller.selectContact(a.id)
            val renderedA = f.controller.state.value
            compose.setContent { JunshiTheme { Surface {
                CounselContent(renderedA, CounselUiActions(editDraft = f.controller::editDraft, pasteText = f.controller::pasteText,
                    analyze = f.controller::analyze, preparePreview = f.controller::preparePreview))
            } } }
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-composer"))
            f.controller.selectContact(b.id)
            f.controller.editDraft("synthetic B stream"); f.controller.analyze()
            compose.waitUntil { f.provider.calls.size == 1 }
            val before = f.controller.state.value
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("counsel-composer"))).performTextInput("stale A commit")
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(DsR.string.ds_send)))
            compose.onNodeWithText(text(DsR.string.ds_send)).performClick()
            assertEquals(before, f.controller.state.value)
            runBlocking { f.provider.outputs.single().send(AnalysisChunk.Completed(StructuredAnswer(reply = listOf("synthetic B answer")))) }
            compose.waitUntil { f.controller.state.value.answer != null }
            assertEquals(1, f.provider.calls.size)
            assertEquals("synthetic B stream", f.persistence.saved.getValue(b.id).draft)
        } finally { f.close() }
    }

    @Test fun pasteOpensImportWithoutReplacingPendingDraftAndSwitchResetsEditor() {
        val state = mutableStateOf(syntheticCounselState("empty"))
        var pasted: String? = null
        compose.setContent { JunshiTheme {
            CompositionLocalProvider(LocalCounselClipboard provides CounselClipboard({ "synthetic_pasted" }, {})) {
                Surface { CounselContent(state.value, CounselUiActions(pasteText = { value, _ -> pasted = value })) }
            }
        } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-composer"))
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("counsel-composer")))
        field.performTextInput("synthetic_pending")
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(text(DsR.string.ds_paste)))
        compose.onNodeWithText(text(DsR.string.ds_paste)).performClick()
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-composer"))
        field.assertTextEquals("synthetic_pending")
        assertEquals("synthetic_pasted", pasted)
        compose.runOnIdle { state.value = state.value.copy(draft = "synthetic_pending") }
        field.assertTextEquals("synthetic_pending")
        compose.runOnIdle {
            state.value = state.value.copy(conversation = state.value.conversation!!.copy(id = "synthetic-second-session"), draft = "synthetic_other_contact")
        }
        field.assertTextEquals("synthetic_other_contact")
    }

    @Test fun immediateSendQueuesVisibleDraftBeforeAnalysis() = verifyImmediateSubmission(preview = false)
    @Test fun immediatePreviewQueuesVisibleDraftBeforePreparation() = verifyImmediateSubmission(preview = true)

    private fun verifyImmediateSubmission(preview: Boolean) {
        val state = syntheticCounselState("empty")
        val queued = mutableListOf<() -> Unit>()
        var persisted = state.draft
        var submitted: String? = null
        compose.setContent { JunshiTheme { Surface {
            CounselContent(state, CounselUiActions(
                editDraft = { value, _ -> queued += { persisted = value } },
                analyze = { queued += { submitted = persisted } },
                preparePreview = { queued += { submitted = persisted } },
            ))
        } } }
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("counsel-composer"))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("counsel-composer"))).performTextInput("synthetic_latest_visible")
        val label = text(if (preview) R.string.preview_request else DsR.string.ds_send)
        compose.onNodeWithTag("counsel-list").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle {
            assertEquals("", persisted) // The backend has acknowledged no keystrokes yet.
            queued.forEach { it() }
            assertEquals("synthetic_latest_visible", submitted)
        }
    }
}
