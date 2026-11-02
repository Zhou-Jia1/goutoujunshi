package com.shengjidaguai.goutoujunshi.counsel

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T51EncryptedTeachingIntegrationTest {
    private class Sandbox(base: Context): ContextWrapper(base) {
        private val nonce=UUID.randomUUID().toString().replace("-", "")
        private val root=File(base.filesDir,"t51-memory-$nonce").apply { check(mkdirs() && isDirectory) { "Unable to create unique memory test sandbox" } }
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "${baseContext.packageName}.t51.$nonce"
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = File(root,"cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root,"no-backup").apply { mkdirs() }
        fun preferenceFile(name: String): File = File(baseContext.applicationInfo.dataDir,"shared_prefs/t51-$nonce-$name.xml")
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences = baseContext.getSharedPreferences("t51-$nonce-$name",mode)
        override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences("t51-$nonce-$name")
        override fun getDatabasePath(name: String): File = File(root,"databases/$name").also { it.parentFile!!.mkdirs() }
        override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    @Test fun realCipherFavoritesAndDailyMetadataAreIndependentOfLearningConsent()=runBlocking {
        val context=Sandbox(InstrumentationRegistry.getInstrumentation().targetContext)
        val store=EncryptedDataStore(context)
        val temporary=TemporarySessionStore()
        try {
            val session=store.open()
            val contacts=ContactRepository(session)
            contacts.save(Contact("native-owner","合成朋友"))
            val conversation=Conversation("native-conversation","native-owner")
            val conversations=ConversationRepository(session)
            conversations.save(conversation)
            val request=AnalysisRequest(requestId="native-request",conversationId=conversation.id,contactId="native-owner",relationType=RelationType.FRIEND,goal=Goal.REPLY)
            val answer=StructuredAnswer(id="native-answer",reply=listOf("我理解，明天再聊。"),tips=listOf(Tip(name="先接后转",explanation="先理解再说明",example="我理解")),habit="先说事情。")
            conversations.saveMessage(Message("native-message",conversation.id,MessageRole.ASSISTANT,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId=request.requestId,status=MessageStatus.COMPLETED,contactId="native-owner"))
            TimelineRepository(session).save(AnalysisRun(requestId=request.requestId,conversationId=conversation.id,contactId="native-owner",status=RunStatus.COMPLETED,requestSnapshot=request))
            val repo=TeachingRepository(session)
            val tip=TeachingPolicy.tip(answer)!!.copy(id="native-tip",savedAt=10,scenes=setOf(TipScene(RelationGroup.SOCIAL)))
            assertFalse(ConsentRepository(session).get().enabled)
            repo.favorite(tip,request,answer.id,true) {true}
            TipRepository(session).save(tip.copy(id="later",savedAt=20,scenes=setOf(TipScene(RelationGroup.WORK))))
            assertEquals(10L,TipRepository(session).list().single().savedAt)
            assertEquals(2,TipRepository(session).list().single().scenes.size)
            assertTrue(repo.habit(request,answer.id,"2026-11-02",current={true}))
            assertFalse(TeachingRepository(session).habit(request,answer.id,"2026-11-02",current={true}))
            repo.habit(request,answer.id,"2026-11-02",answer.habit) {true}
            assertFalse(repo.habit(request,answer.id,"2026-11-03",current={true}))
            assertTrue(StyleRepository(session).samples(RelationGroup.SOCIAL).isEmpty())
            assertFalse(ConsentRepository(session).get().enabled)
            contacts.delete("native-owner")
            assertEquals("我理解",TipRepository(session).list().single().example)
            repo.favorite(tip,null,answer.id,false) {true}
            assertTrue(TipRepository(session).list().isEmpty())
        } finally {store.clearAll(temporary)}
    }
    @Test fun realCipherControllerRestoresTipsWithoutReplayingHabitOrStartingModel()=runBlocking {
        val store=EncryptedDataStore(Sandbox(InstrumentationRegistry.getInstrumentation().targetContext))
        val temporary=TemporarySessionStore()
        val scopes=mutableListOf<CoroutineScope>()
        var calls=0
        val provider=object:AnalysisProvider {
            override val kind=ProviderKind.BYOK
            override fun analyze(request:AnalysisRequest):Flow<AnalysisChunk> {calls++;error("Restore must not call a model")}
        }
        val assembler=object:PromptAssembler {override fun prepare(request:AnalysisRequest):PreparedPrompt = error("Restore must not prepare a request")}
        suspend fun awaitReady(test:()->Boolean)=withTimeout(10_000) {while(!test()) delay(10)}
        fun controller():CounselController {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);scopes+=scope
            return CounselController(EncryptedCounselPersistence(store,temporary),temporary,assembler,provider,scope)
        }
        try {
            val session=store.open()
            ContactRepository(session).save(Contact("a","合成甲",RelationType.FRIEND))
            ContactRepository(session).save(Contact("b","合成乙",RelationType.FRIEND))
            val conversation=Conversation("restore-conversation","a")
            val repo=ConversationRepository(session);repo.save(conversation)
            val request=AnalysisRequest(requestId="restore-request",contactId="a",conversationId=conversation.id,relationType=RelationType.FRIEND,goal=Goal.REPLY)
            val answer=StructuredAnswer(id="restore-answer",reply=listOf("我理解，明天再聊。"),tips=listOf(Tip(name="先接后转",explanation="先理解再说明",example="我理解")),habit="旧习惯提醒")
            repo.saveMessage(Message("restore-user",conversation.id,MessageRole.USER,requestId=request.requestId,status=MessageStatus.COMPLETED,contactId="a"))
            repo.saveMessage(Message("restore-assistant",conversation.id,MessageRole.ASSISTANT,answerJson=kotlinx.serialization.json.Json.encodeToString(StructuredAnswer.serializer(),answer),requestId=request.requestId,status=MessageStatus.COMPLETED,contactId="a",providerKind=ProviderKind.BYOK))
            TimelineRepository(session).save(AnalysisRun(requestId=request.requestId,conversationId=conversation.id,contactId="a",status=RunStatus.COMPLETED,requestSnapshot=request))
            val teachingRepo=TeachingRepository(session)
            val tip=TeachingPolicy.tip(answer)!!.copy(id="restore-tip",scenes=setOf(TipScene(RelationGroup.SOCIAL)))
            teachingRepo.favorite(tip,request,answer.id,true) {true}
            val first=controller();first.initialize();awaitReady {first.state.value.teaching.tip!=null}
            assertTrue(first.state.value.teaching.saved);assertNull(first.state.value.teaching.habit)
            val receipt=first.state.value.teaching.receipt
            first.selectContact("b");awaitReady {first.state.value.currentContact?.id=="b"}
            teachingRepo.favorite(tip,request,answer.id,false) {true}
            first.selectContact("a");awaitReady {first.state.value.teaching.tip!=null && first.state.value.teaching.receipt!=receipt}
            assertFalse(first.state.value.teaching.saved);assertNull(first.state.value.teaching.habit)
            val beforeLock=first.state.value.teaching.receipt
            first.updateLockState(LockState(true,false));assertNull(first.state.value.teaching.tip)
            first.updateLockState(LockState(true,true));awaitReady {first.state.value.teaching.tip!=null && first.state.value.teaching.receipt!=beforeLock}
            assertNull(first.state.value.teaching.habit)
            scopes.first().cancel()
            val reopened=controller();reopened.initialize();awaitReady {reopened.state.value.teaching.tip!=null}
            assertFalse(reopened.state.value.teaching.saved);assertNull(reopened.state.value.teaching.habit)
            assertNotEquals(first.state.value.teaching.receipt,reopened.state.value.teaching.receipt)
            assertTrue(teachingRepo.habit(request,answer.id,java.time.LocalDate.now().toString(),current={true}))
            assertEquals(0,calls)
        } finally {scopes.forEach {it.cancel()};store.clearAll(temporary)}
    }

}
