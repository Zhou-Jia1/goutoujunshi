package com.shengjidaguai.goutoujunshi.counsel

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T52EncryptedPracticeIntegrationTest {
    private class Sandbox(base: Context): ContextWrapper(base) {
        private val nonce=UUID.randomUUID().toString().replace("-", "")
        private val root=File(base.filesDir,"t52-memory-$nonce").apply { check(mkdirs() && isDirectory) { "Unable to create unique memory test sandbox" } }
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "${baseContext.packageName}.t52.$nonce"
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = File(root,"cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root,"no-backup").apply { mkdirs() }
        fun preferenceFile(name: String): File = File(baseContext.applicationInfo.dataDir,"shared_prefs/t52-$nonce-$name.xml")
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences = baseContext.getSharedPreferences("t52-$nonce-$name",mode)
        override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences("t52-$nonce-$name")
        override fun getDatabasePath(name: String): File = File(root,"databases/$name").also { it.parentFile!!.mkdirs() }
        override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    @Test fun realCipherPracticeStaysRamAndOnlyExplicitSummarySurvivesRestart()=runBlocking {
        val context=Sandbox(InstrumentationRegistry.getInstrumentation().targetContext)
        val store=EncryptedDataStore(context);val temporary=TemporarySessionStore()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val outputs=mutableListOf<Channel<AnalysisChunk>>();val calls=mutableListOf<AnalysisRequest>()
        val provider=object:AnalysisProvider {
            override val kind=ProviderKind.DEMO
            override fun analyze(request:AnalysisRequest)=flow {
                val output=Channel<AnalysisChunk>(Channel.UNLIMITED)
                synchronized(calls) {outputs+=output;calls+=request}
                for(chunk in output) emit(chunk)
            }
        }
        suspend fun await(test:()->Boolean)=withTimeout(15000) {while(!test()) delay(10)}
        try {
            val session=store.open();ContactRepository(session).save(Contact("a","合成练习对方"))
            val conversation=Conversation("native-practice-conversation","a");ConversationRepository(session).save(conversation)
            val lines=listOf(ChatLine("native-line","native-import",1,Speaker.THEM,"你打算什么时候完成？",speakerConfirmed=true,contactId="a"))
            ImportRepository(session).save(ChatImport("native-import","a",ImportSource.PASTE,"native-synthetic-hash"),lines,0)
            val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
            val assembler=FrozenPromptAssembler(PromptBuilder(KnowledgeLibrary(AssetKnowledgeSource(assets))))
            val safety=LocalSafetyClassifier(assets.open("crisis-keywords.txt").bufferedReader(Charsets.UTF_8).use {it.readText()})
            val controller=CounselController(EncryptedCounselPersistence(store,temporary),temporary,assembler,provider,scope,localSafety=safety::classify)
            controller.initialize();await {controller.state.value.currentContact?.id=="a"}
            controller.enterPractice(conversation.id);await {controller.state.value.practice!=null}
            val id=controller.state.value.practice!!.id
            var lastConfirmedReceipt:String?=null
            suspend fun send(end:Boolean=false) {
                val before=synchronized(calls) {calls.size}
                val previous=lastConfirmedReceipt
                controller.previewPractice(conversation.id,id,end)
                await {controller.state.value.let {it.preview!=null && it.error==null && !it.practicePreviewReceipt.isNullOrBlank() && it.practicePreviewReceipt!=previous}}
                val shownReceipt=checkNotNull(controller.state.value.practicePreviewReceipt)
                assertNotEquals(previous,shownReceipt)
                lastConfirmedReceipt=shownReceipt
                controller.sendPractice(conversation.id,id,shownReceipt);await {synchronized(calls) {calls.size==before+1}}
            }
            suspend fun finish(json:String) {outputs.last().send(AnalysisChunk.Completed(StructuredAnswer(freeText=json)));await {!controller.state.value.streaming}}
            suspend fun emptyOrdinary() {
                assertTrue(ConversationRepository(session).messages("a",conversation.id).isEmpty())
                assertTrue(TimelineRepository(session).runs("a").isEmpty())
                assertEquals("",ConversationRepository(session).draft("a",conversation.id).orEmpty())
                assertFalse(ConsentRepository(session).get().enabled)
                assertTrue(StyleRepository(session).samples(RelationGroup.SOCIAL).isEmpty())
            }
            send();emptyOrdinary();finish("{\"counterpartLine\":\"你打算怎么安排？\"}");emptyOrdinary()
            controller.editPracticeInput(conversation.id,id,"我明天发方案");await {controller.state.value.practice?.draft=="我明天发方案"};send()
            outputs.last().send(AnalysisChunk.Failed(AnalysisError("NETWORK",retryable=true)));await {controller.state.value.error!=null};emptyOrdinary()
            val failed=synchronized(calls) {calls.last().requestId}
            val failedReceipt=checkNotNull(lastConfirmedReceipt)
            controller.retry()
            await {controller.state.value.let {it.preview!=null && it.error==null && !it.practicePreviewReceipt.isNullOrBlank() && it.practicePreviewReceipt!=failedReceipt}}
            val retryReceipt=checkNotNull(controller.state.value.practicePreviewReceipt)
            assertNotEquals(failedReceipt,retryReceipt)
            lastConfirmedReceipt=retryReceipt
            controller.sendPractice(conversation.id,id,retryReceipt);await {synchronized(calls) {calls.size==3}};assertEquals(failed,calls.last().requestId)
            finish("{\"counterpartLine\":\"几点发？\",\"feedback\":\"你说明了时间，可以再提出明确确认请求。\",\"betterVersion\":\"明天下午发方案，请你确认时间。\"}");emptyOrdinary()
            send(true);finish("{\"name\":\"确认下一步\",\"explanation\":\"安排后附一个确认请求。\",\"example\":\"明天下午请你确认时间。\"}")
            assertTrue(TipRepository(session).list().isEmpty())
            val view=controller.state.value.practice!!;controller.savePracticeSummary(conversation.id,id,view.summaryReceipt!!);await {controller.state.value.practice?.saved==true}
            val tip=TipRepository(session).list().single();assertEquals("确认下一步",tip.name);assertFalse(kotlinx.serialization.json.Json.encodeToString(Tip.serializer(),tip).contains("我明天发方案"));emptyOrdinary()
            controller.exitPractice(conversation.id,id);await {controller.state.value.practice==null}
            controller.enterPractice(conversation.id);await {controller.state.value.practice!=null}
            controller.editPracticeInput(conversation.id,controller.state.value.practice!!.id,"我想自杀")
            assertEquals(SafetyLevel.CRISIS,controller.state.value.safety);assertNull(controller.state.value.practice);emptyOrdinary()
            assertEquals(4,synchronized(calls) {calls.size})
            scope.cancel()
            val reopened=EncryptedCounselPersistence(store,TemporarySessionStore());reopened.initialize();val loaded=reopened.loadOrCreate(ContactRepository(session).get("a")!!)
            assertTrue(loaded.messages.isEmpty());assertTrue(loaded.runs.isEmpty());assertEquals("确认下一步",reopened.tips().single().name)
        } finally {scope.cancel();store.clearAll(temporary)}
    }
    @Test fun deletedConversationDuringSourceCheckNeverRecreatesFormalRecords()=runBlocking {
        for(action in listOf("start","retry","complete","favorite")) {
            val context=Sandbox(InstrumentationRegistry.getInstrumentation().targetContext)
            val store=EncryptedDataStore(context);val temporary=TemporarySessionStore()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            var hold=false
            val calls=java.util.concurrent.atomic.AtomicInteger()
            val responses=Channel<AnalysisChunk>(Channel.UNLIMITED)
            val provider=object:AnalysisProvider {
                override val kind=ProviderKind.DEMO
                override fun analyze(request:AnalysisRequest)=flow {calls.incrementAndGet();for(chunk in responses) emit(chunk)}
            }
            suspend fun await(test:()->Boolean)=withTimeout(15000) {while(!test()) delay(10)}
            try {
                val session=store.open();ContactRepository(session).save(Contact("a","合成对方"))
                val conversation=Conversation("deleted-conversation","a");val conversations=ConversationRepository(session);conversations.save(conversation)
                ImportRepository(session).save(ChatImport("i","a",ImportSource.PASTE,"deleted-source-hash"),listOf(ChatLine("l","i",1,Speaker.THEM,"合成对方问题",speakerConfirmed=true,contactId="a")),0)
                val actual=EncryptedCounselPersistence(store,temporary)
                val persistence=object:CounselPersistence by actual {
                    override suspend fun practiceSourceSnapshot(contact:Contact,conversationId:String):StoredCounsel? {
                        if(hold) {entered.complete(Unit);release.await()}
                        return actual.practiceSourceSnapshot(contact,conversationId)
                    }
                }
                val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
                val safety=LocalSafetyClassifier(assets.open("crisis-keywords.txt").bufferedReader(Charsets.UTF_8).use {it.readText()})
                val assembler=FrozenPromptAssembler(PromptBuilder(KnowledgeLibrary(AssetKnowledgeSource(assets))))
                val controller=CounselController(persistence,temporary,assembler,provider,scope,localSafety=safety::classify)
                controller.initialize();await {controller.state.value.currentContact!=null};controller.enterPractice(conversation.id);await {controller.state.value.practice!=null}
                val id=controller.state.value.practice!!.id
                controller.previewPractice(conversation.id,id,action=="favorite");await {controller.state.value.let {it.preview!=null && it.error==null && !it.practicePreviewReceipt.isNullOrBlank()}}
                val shownReceipt=checkNotNull(controller.state.value.practicePreviewReceipt)
                if(action!="start") {
                    controller.sendPractice(conversation.id,id,shownReceipt);await {calls.get()==1}
                    if(action=="retry") {responses.send(AnalysisChunk.Failed(AnalysisError("NETWORK",retryable=true)));await {controller.state.value.error!=null}}
                    if(action=="favorite") {responses.send(AnalysisChunk.Completed(StructuredAnswer(freeText="{\"name\":\"确认下一步\",\"explanation\":\"安排后附确认请求。\",\"example\":\"明天下午请你确认时间。\"}")));await {controller.state.value.practice?.summary!=null}}
                }
                hold=true
                when(action) {
                    "start"->controller.sendPractice(conversation.id,id,shownReceipt)
                    "retry"->controller.retry()
                    "complete"->responses.send(AnalysisChunk.Completed(StructuredAnswer(freeText="{\"counterpartLine\":\"旧问题\"}")))
                    "favorite"->controller.savePracticeSummary(conversation.id,id,controller.state.value.practice!!.summaryReceipt!!)
                }
                withTimeout(15000) {entered.await()}
                conversations.delete("a",conversation.id)
                release.complete(Unit)
                await {controller.state.value.practice==null}
                assertNull(action,controller.state.value.answer);assertNull(action,controller.state.value.teaching.tip)
                assertTrue(action,conversations.list("a").isEmpty());assertTrue(action,conversations.messages("a",conversation.id).isEmpty());assertTrue(action,TimelineRepository(session).runs("a").isEmpty());assertTrue(action,TipRepository(session).list().isEmpty())
                assertEquals(action,if(action=="start")0 else 1,calls.get());assertFalse(ConsentRepository(session).get().enabled)
            } finally {release.complete(Unit);scope.cancel();store.clearAll(temporary)}
        }
    }

}
