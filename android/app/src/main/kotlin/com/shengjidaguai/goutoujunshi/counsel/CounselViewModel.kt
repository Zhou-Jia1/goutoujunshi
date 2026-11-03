package com.shengjidaguai.goutoujunshi.counsel

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.shengjidaguai.goutoujunshi.importing.*
import androidx.lifecycle.AndroidViewModel
import com.shengjidaguai.goutoujunshi.data.network.*
import com.shengjidaguai.goutoujunshi.data.EncryptedDataStore
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.*

/** Application lifetime keeps temporary sessions across Activity recreation, never process death. */
internal object CounselRuntime {
    private var importer: ImportCoordinator? = null
    @Volatile private var encrypted:EncryptedDataStore?=null
    fun store(application:Application):EncryptedDataStore = encrypted ?: synchronized(this) {encrypted ?: EncryptedDataStore(application).also {encrypted=it}}
    @Synchronized fun imports(application: Application): ImportCoordinator = importer ?: ImportCoordinator(get(application),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        { application.getSharedPreferences("import-notice", 0).getBoolean("accepted", false) },
        { application.getSharedPreferences("import-notice", 0).edit().putBoolean("accepted", true).apply() },
        com.shengjidaguai.goutoujunshi.data.ocr.PaddleImageReader(application)::read
    ).also { importer = it }
    internal fun localSafetyClassifier(loadVocabulary:()->String):(String)->SafetyLevel = CachedLocalSafetyClassifier(loadVocabulary)::classify
    fun withNotificationDisplay(block:(Boolean)->Unit) {val c=controller;if(c==null) block(false) else c.withNotificationDisplay(block)}
    @Volatile private var controller: CounselController? = null
    fun get(application: Application): CounselController = controller ?: synchronized(this) {
        controller ?: run {
            val temporary = TemporarySessionStore()
            val store = store(application)
            // Knowledge construction is lazy: integrity or missing asset errors become state errors.
            val builder = object : PromptAssembler {
                private val delegate by lazy { PromptBuilder(KnowledgeLibrary(AssetKnowledgeSource(application.assets))) }
                override fun prepare(request: AnalysisRequest) = delegate.prepare(request)
            }
            val assembler = FrozenPromptAssembler(builder)
            val localSafety=CachedLocalSafetyClassifier(
                { application.assets.open("crisis-keywords.txt").bufferedReader(Charsets.UTF_8).use {it.readText()} },
                { application.assets.open("calm-keywords.txt").bufferedReader(Charsets.UTF_8).use {it.readText()} })::classify
            CounselController(EncryptedCounselPersistence(store, temporary, com.shengjidaguai.goutoujunshi.followup.FollowupScheduler(application)), temporary, assembler,
                DemoAnalysisProvider(assembler, application.assets), CoroutineScope(SupervisorJob() + Dispatchers.IO),
                VaultModelSettingsRepository(store.vault),
                { saved -> OpenAiCompatibleProvider(saved.connection(), assembler, VaultCredentialSource(store.vault, saved)) },onPrivacyLock={com.shengjidaguai.goutoujunshi.followup.FollowupScheduler.hidePrivate(application)},localSafety=localSafety,styleBiasPreferences=LocalStyleBiasPreferences(application.getSharedPreferences("style-bias",0)))
        }.also { controller = it }
    }
}

class CounselViewModel internal constructor(application: Application, private val controller: CounselController, val imports: ImportCoordinator) : AndroidViewModel(application) {
    constructor(application: Application):this(application,CounselRuntime.get(application),CounselRuntime.imports(application))
    private val pickerSelection=ImportSelectionSession(imports)
    fun copyReply(receipt:SafetyActionReceipt,text:String,write:(String)->Unit)=controller.copyReply(receipt,text,write)
    fun acknowledgeSafety(receipt:SafetyActionReceipt,collapsed:Boolean)=controller.acknowledgeSafety(receipt,collapsed)
    fun saveCoolingDraft(receipt:SafetyActionReceipt,text:String)=controller.saveCoolingDraft(receipt,text)
    fun removeTip(id:String,receipt:String)=controller.removeTip(id,receipt)
    fun saveTipScene(origin:String?,receipt:String)=controller.toggleTip(origin,receipt,true)
    fun openTips()=controller.openTips()
    fun toggleTip(origin:String?,answerId:String)=controller.toggleTip(origin,answerId)
    fun dismissHabit(origin:String?,answerId:String,never:Boolean)=controller.dismissHabit(origin,answerId,never)
    fun enterPractice(origin:String?)=controller.enterPractice(origin)
    fun editPracticeInput(origin:String?,id:String,text:String)=controller.editPracticeInput(origin,id,text)
    fun practiceDifficulty(origin:String?,id:String,difficulty:PracticeDifficulty)=controller.practiceDifficulty(origin,id,difficulty)
    fun previewPractice(origin:String?,id:String,end:Boolean)=controller.previewPractice(origin,id,end)
    fun sendPractice(origin:String?,id:String,receipt:String)=controller.sendPractice(origin,id,receipt)
    fun exitPractice(origin:String?,id:String)=controller.exitPractice(origin,id)
    fun savePracticeSummary(origin:String?,id:String,receipt:String)=controller.savePracticeSummary(origin,id,receipt)
    fun practiceForContact(id:String)=controller.practiceForContact(id)
    fun recheckReminderPermission()=controller.recheckReminderPermission()
    fun notSent(origin:String?,id:String)=controller.notSent(origin,id)
    fun scheduleFollowup(offer:FollowupOffer,due:Long,permission:Boolean)=controller.scheduleFollowup(offer,due,permission)
    fun cancelFollowup(origin:String?,id:String)=controller.cancelFollowup(origin,id)
    fun openFollowup(id:String,generation:String)=controller.openFollowup(id,generation)
    val state = controller.state
    val memory = controller.memory
    val stylePage=controller.stylePage
    fun openStyle()=controller.openStyle()
    fun closeStyle()=controller.closeStyle()
    fun editStyle(group:RelationGroup,text:String,receipt:String)=controller.editStyle(group,text,receipt)
    fun editStyleSample(id:String,text:String,receipt:String)=controller.editStyleSample(id,text,receipt)
    fun deleteStyle(id:String,receipt:String)=controller.deleteStyle(id,receipt)
    fun resetStyle(group:RelationGroup,receipt:String)=controller.resetStyle(group,receipt)
    fun saveStyleQuiz(answers:List<String>,receipt:String)=controller.saveStyleQuiz(answers,receipt)
    fun previewStyle(group:RelationGroup,receipt:String)=controller.previewStyle(group,receipt)
    val memoryPage = controller.memoryPage
    fun openMemory(owner: String?) = controller.openMemory(owner)
    fun closeMemory() = controller.closeMemory()
    fun acceptMemory(receipt: MemoryUiReceipt) = controller.acceptMemory(receipt)
    fun declineMemory(receipt: MemoryUiReceipt) = controller.declineMemory(receipt)
    fun setMemoryEnabled(enabled: Boolean, paused: Boolean, receipt: MemoryUiReceipt) = controller.setMemoryEnabled(enabled,paused,receipt)
    fun addMemory(owner: String, group: MemoryScope, value: String, stamp: MemoryUiReceipt, operation: String) = controller.addMemory(owner,group,value,stamp,operation)
    fun editMemory(owner: String, entry: MemoryEntry, stamp: MemoryUiReceipt, operation: String) = controller.editMemory(owner,entry,stamp,operation)
    fun deleteMemory(owner: String,id: String,stamp: MemoryUiReceipt) = controller.deleteMemory(owner,id,stamp)
    fun markMemoryWrong(owner: String,id: String,stamp: MemoryUiReceipt) = controller.markMemoryWrong(owner,id,stamp)
    fun undoMemory(owner: String,id: String,stamp: MemoryUiReceipt) = controller.undoMemory(owner,id,stamp)
    fun clearMemories(receipt: MemoryUiReceipt) = controller.clearMemories(receipt)
    val details = controller.details
    fun openContactDetails(id: String) = controller.openContactDetails(id)
    fun closeContactDetails() = controller.closeContactDetails()
    fun editContact(next: Contact, expected: Long) = controller.editContact(next, expected)
    fun undoContactEdit() = controller.undoContactEdit()
    fun receiveShare(intent: Intent) = imports.receive(intent, getApplication<Application>().contentResolver)
    fun launchImagePicker(launch:()->Unit)=pickerSelection.launch(PickerKind.IMAGE,launch)
    fun launchFilePicker(launch:()->Unit)=pickerSelection.launch(PickerKind.FILE,launch)
    fun importImages(uris: List<Uri>) { if(pickerSelection.consume(PickerKind.IMAGE) && uris.isNotEmpty()) imports.images(uris) }
    fun importFile(uri: Uri?) { if(pickerSelection.consume(PickerKind.FILE) && uri!=null) imports.file(getApplication<Application>().contentResolver,uri) }
    fun importPaste(text: String) = imports.text(text)
    init { initialize() }
    fun initialize() = controller.initialize()
    fun editDraft(text: String) = controller.editDraft(text)
    fun editDraft(text: String, stamp: DraftEditStamp) = controller.editDraft(text, stamp)
    fun pasteText(text: String) = controller.pasteText(text)
    fun pasteText(text: String, stamp: DraftEditStamp) = controller.pasteText(text, stamp)
    fun selectContact(id: String) = controller.selectContact(id)
    fun createContact(alias: String, relation: RelationType) = controller.createContact(alias, relation)
    fun deleteContact(id: String) = controller.deleteContact(id)
    fun setRelation(value: RelationType) = controller.setRelation(value)
    fun setGoal(value: Goal) = controller.setGoal(value)
    fun setEmotion(value: Int?) = controller.setEmotion(value)
    fun setStyleBias(value: Float) = controller.setStyleBias(value)
    fun setTone(value: Tone) = controller.setTone(value)
    fun saveRedactionProfile(origin: String?, terms: List<String>, aliases: List<RedactionAlias>) = controller.saveRedactionProfile(origin, terms, aliases)
    fun preparePreview() = controller.preparePreview()
    fun preparePreview(origin: String?) = controller.preparePreview(origin)
    fun analyze() = controller.analyze()
    fun analyze(origin: String?) = controller.analyze(origin)
    fun changeScene(origin: String?, id: String, relation: RelationType, goal: Goal, formality: String) = controller.changeScene(origin, id, relation, goal, formality)
    fun rewrite(origin: String?, id: String, tone: Tone, bias: Float) = controller.rewrite(origin, id, tone, bias)
    fun feedback(origin: String?, id: String, edited: String?) = controller.feedback(origin, id, edited)
    fun retry() = controller.retry()
    fun stop() = controller.stop()
    fun newTemporary() = controller.newTemporary()
    fun saveModelSettings(settings: SavedModelSettings, key: String?, removeKey: Boolean = false) = controller.saveModelSettings(settings, key, removeKey)
    fun testModelConnection() = controller.testModelConnection()
    fun cancelModelTest() = controller.cancelModelTest()
    fun clearAll() { com.shengjidaguai.goutoujunshi.floating.FloatingRuntime.clearAll(getApplication<Application>()); imports.cancel(); getApplication<Application>().getSharedPreferences("import-notice", 0).edit().clear().apply(); getApplication<Application>().getSharedPreferences("followup-settings",0).edit().clear().apply(); controller.clearAll() }
}
