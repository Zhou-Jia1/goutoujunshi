package com.shengjidaguai.goutoujunshi.counsel

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shengjidaguai.goutoujunshi.ui.*
import com.shengjidaguai.goutoujunshi.designsystem.JunshiTheme
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T53EncryptedCoolingIntegrationTest {
    @get:Rule val compose=createComposeRule()
    private class Sandbox(base: Context): ContextWrapper(base) {
        private val nonce=UUID.randomUUID().toString().replace("-", "")
        private val root=File(base.filesDir,"t53-memory-$nonce").apply { check(mkdirs() && isDirectory) { "Unable to create unique memory test sandbox" } }
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "${baseContext.packageName}.t53.$nonce"
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = File(root,"cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root,"no-backup").apply { mkdirs() }
        fun preferenceFile(name: String): File = File(baseContext.applicationInfo.dataDir,"shared_prefs/t53-$nonce-$name.xml")
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences = baseContext.getSharedPreferences("t53-$nonce-$name",mode)
        override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences("t53-$nonce-$name")
        override fun getDatabasePath(name: String): File = File(root,"databases/$name").also { it.parentFile!!.mkdirs() }
        override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }


    @Test fun controllerExplicitDraftReopensEncryptedWhileTemporaryActionStaysRamAndOldLockLeaseFails()=runBlocking {
        val context=Sandbox(InstrumentationRegistry.getInstrumentation().targetContext)
        val store=EncryptedDataStore(context);val temporary=TemporarySessionStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val reopenedScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var reopenedStore:EncryptedDataStore?=null
        suspend fun await(test:()->Boolean)=withTimeout(15000) {while(!test()) delay(10)}
        val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
        val safety=CachedLocalSafetyClassifier({assets.open("crisis-keywords.txt").bufferedReader().use {it.readText()}},{assets.open("calm-keywords.txt").bufferedReader().use {it.readText()}})
        fun controller(data:EncryptedDataStore,ram:TemporarySessionStore,ownerScope:CoroutineScope):CounselController {
            val assembler=FrozenPromptAssembler(PromptBuilder(KnowledgeLibrary(AssetKnowledgeSource(assets))))
            val provider=object:AnalysisProvider {
                override val kind=ProviderKind.DEMO
                override fun analyze(request:AnalysisRequest)=flowOf(AnalysisChunk.Completed(StructuredAnswer(id="answer:${request.requestId}",reply=listOf(if(request.contactId==null) "T53_RAM_SELECTED" else "T53_FORMAL_SELECTED"))))
            }
            return CounselController(EncryptedCounselPersistence(data,ram),ram,assembler,provider,ownerScope,wallClockMillis={1000},localSafety=safety::classify)
        }
        suspend fun drain(c:CounselController) {val receipt=c.state.value.tipsReceipt;c.openTips();await {c.state.value.tipsReceipt!=receipt}}
        suspend fun analyzeCurrent(c:CounselController) {
            val previous=c.state.value.answer?.id;c.analyze()
            await {c.state.value.answer?.id!=previous && c.state.value.hasCurrentAnswerSource() && !c.state.value.streaming}
        }
        try {
            val session=store.open();val contacts=ContactRepository(session);val repo=ConversationRepository(session)
            contacts.save(Contact("a","合成甲"));contacts.save(Contact("b","合成乙"))
            val c=controller(store,temporary,scope);c.initialize();await {c.state.value.currentContact?.id=="a" && !c.state.value.loading}
            c.editDraft("合成正式输入");await {c.state.value.draft=="合成正式输入" && c.state.value.draftSaved}
            analyzeCurrent(c)
            val old=c.state.value.safetyActionReceipt();val conversationId=c.state.value.conversation!!.id
            assertNull(repo.list("a").single {it.id==conversationId}.coolingDraft)
            c.updateLockState(LockState(true,false));c.updateLockState(LockState(true,true));drain(c)
            var writes=0
            assertFalse(c.copyReply(old,"T53_FORMAL_SELECTED") {writes++})
            c.saveCoolingDraft(old,"T53_FORMAL_SELECTED");drain(c)
            assertEquals(0,writes);assertNull(repo.list("a").single {it.id==conversationId}.coolingDraft)
            analyzeCurrent(c)
            c.saveCoolingDraft(c.state.value.safetyActionReceipt(),"T53_FORMAL_SELECTED")
            await {c.state.value.visibleCoolingDraft()?.text=="T53_FORMAL_SELECTED"};drain(c)
            val second=EncryptedDataStore(context);reopenedStore=second
            val reopened=ConversationRepository(second.open()).list("a").single {it.id==conversationId}.coolingDraft!!
            assertEquals("T53_FORMAL_SELECTED",reopened.text);assertFalse(reopened.ready(600999));assertTrue(reopened.ready(601000))
            val displayed=androidx.compose.runtime.mutableStateOf(c)
            compose.setContent {val shown by displayed.value.state.collectAsState();JunshiTheme(calm=shown.safetyPresentation().calm) {Surface {
                CounselContent(shown,CounselUiActions(acknowledgeSafety=displayed.value::acknowledgeSafety))
            }}}
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
            compose.onNode(hasText("T53_FORMAL_SELECTED") and hasAnyAncestor(hasTestTag("cooling-draft"))).assertIsDisplayed()
            c.editDraft("我想自杀");await {c.state.value.safety==SafetyLevel.CRISIS}
            compose.waitForIdle();compose.onNodeWithTag("cooling-draft").assertDoesNotExist()
            compose.onAllNodesWithText("T53_FORMAL_SELECTED").assertCountEquals(0)
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("我现在是安全的"))
            compose.onNodeWithText("我现在是安全的").performClick();await {c.state.value.safetyCollapsed}
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("安全帮助"))
            compose.onNodeWithText("安全帮助").assertIsDisplayed()
            compose.onNodeWithTag("cooling-draft").assertDoesNotExist();compose.onNodeWithTag("counsel-answer").assertDoesNotExist()
            assertEquals(reopened,c.state.value.conversation?.coolingDraft)
            assertEquals(reopened,ConversationRepository(second.open()).list("a").single {it.id==conversationId}.coolingDraft)
            c.selectContact("b");await {c.state.value.currentContact?.id=="b"}
            assertFalse(c.copyReply(old,"T53_FORMAL_SELECTED") {writes++});c.saveCoolingDraft(old,"T53_FORMAL_SELECTED");drain(c)
            suspend fun formalRows()=listOf("a","b").associateWith {owner->Triple(repo.list(owner),repo.list(owner).flatMap {repo.messages(owner,it.id)},TimelineRepository(session).runs(owner))}
            val before=formalRows()
            c.newTemporary();await {c.state.value.currentContact==null && c.state.value.conversation?.isTemporary==true}
            c.editDraft("T53_RAM_INPUT");await {c.state.value.draft=="T53_RAM_INPUT"}
            analyzeCurrent(c)
            c.saveCoolingDraft(c.state.value.safetyActionReceipt(),"T53_RAM_SELECTED")
            await {c.state.value.visibleCoolingDraft()?.text=="T53_RAM_SELECTED"};drain(c)
            assertEquals(before,formalRows());assertEquals(0,writes)
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasTestTag("cooling-draft"))
            compose.onNode(hasText("T53_RAM_SELECTED") and hasAnyAncestor(hasTestTag("cooling-draft"))).assertIsDisplayed()
            val imported=CompletableDeferred<Boolean>()
            c.commitImport(com.shengjidaguai.goutoujunshi.importing.ImportPreview(
                rows=listOf(com.shengjidaguai.goutoujunshi.importing.ImportRow("合成对方","我想自杀",speaker=Speaker.THEM)),
                target=com.shengjidaguai.goutoujunshi.importing.ImportTarget(),rightsAccepted=true)) {imported.complete(it)}
            assertTrue(imported.await());assertEquals(SafetyLevel.CRISIS,c.state.value.safety);assertNull(c.state.value.visibleCoolingDraft())
            assertEquals("T53_RAM_SELECTED",c.state.value.conversation?.coolingDraft?.text)
            compose.onNodeWithTag("cooling-draft").assertDoesNotExist();compose.onAllNodesWithText("T53_RAM_SELECTED").assertCountEquals(0)
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("我现在是安全的"));compose.onNodeWithText("我现在是安全的").performClick();await {c.state.value.safetyCollapsed}
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("安全帮助"));compose.onNodeWithText("安全帮助").assertIsDisplayed()
            compose.onNodeWithTag("cooling-draft").assertDoesNotExist();assertEquals(before,formalRows())
            scope.cancel()
            val fresh=controller(second,TemporarySessionStore(),reopenedScope);fresh.initialize();await {!fresh.state.value.loading && fresh.state.value.conversation!=null}
            assertEquals("a",fresh.state.value.currentContact?.id)
            assertEquals(SafetyLevel.CRISIS,fresh.state.value.safety)
            assertNull(fresh.state.value.visibleCoolingDraft())
            assertEquals(reopened,fresh.state.value.conversation?.coolingDraft)
            compose.runOnIdle {displayed.value=fresh}
            compose.onNodeWithTag("cooling-draft").assertDoesNotExist();compose.onAllNodesWithText("T53_FORMAL_SELECTED").assertCountEquals(0)
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("我现在是安全的"));compose.onNodeWithText("我现在是安全的").performClick()
            await {fresh.state.value.safetyCollapsed}
            compose.onNodeWithTag("counsel-list").performScrollToNode(hasText("安全帮助"));compose.onNodeWithText("安全帮助").assertIsDisplayed()
            compose.onNodeWithTag("cooling-draft").assertDoesNotExist();assertFalse(fresh.state.value.safetyPresentation().showOrdinaryStrategy)
            assertEquals(reopened,ConversationRepository(second.open()).list("a").single {it.id==conversationId}.coolingDraft)
            assertNotEquals("T53_RAM_SELECTED",fresh.state.value.visibleCoolingDraft()?.text)
            assertFalse(fresh.state.value.draft.contains("T53_RAM_INPUT"));assertEquals(before,formalRows())
        } finally {scope.cancel();reopenedScope.cancel();reopenedStore?.clearAll(TemporarySessionStore());store.clearAll(temporary)}
    }
}
