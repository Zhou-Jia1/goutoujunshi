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
private object CounselRuntime {
    private var importer: ImportCoordinator? = null
    @Synchronized fun imports(application: Application): ImportCoordinator = importer ?: ImportCoordinator(get(application),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        { application.getSharedPreferences("import-notice", 0).getBoolean("accepted", false) },
        { application.getSharedPreferences("import-notice", 0).edit().putBoolean("accepted", true).apply() }
    ).also { importer = it }
    @Volatile private var controller: CounselController? = null
    fun get(application: Application): CounselController = controller ?: synchronized(this) {
        controller ?: run {
            val temporary = TemporarySessionStore()
            val store = EncryptedDataStore(application)
            // Knowledge construction is lazy: integrity or missing asset errors become state errors.
            val builder = object : PromptAssembler {
                private val delegate by lazy { PromptBuilder(KnowledgeLibrary(AssetKnowledgeSource(application.assets))) }
                override fun prepare(request: AnalysisRequest) = delegate.prepare(request)
            }
            val assembler = FrozenPromptAssembler(builder)
            CounselController(EncryptedCounselPersistence(store, temporary), temporary, assembler,
                DemoAnalysisProvider(assembler, application.assets), CoroutineScope(SupervisorJob() + Dispatchers.IO),
                VaultModelSettingsRepository(store.vault),
                { saved -> OpenAiCompatibleProvider(saved.connection(), assembler, VaultCredentialSource(store.vault, saved)) })
        }.also { controller = it }
    }
}

class CounselViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = CounselRuntime.get(application)
    val imports = CounselRuntime.imports(application)
    val state = controller.state
    fun receiveShare(intent: Intent) = imports.receive(intent, getApplication<Application>().contentResolver)
    fun importFile(uri: Uri) = imports.file(getApplication<Application>().contentResolver, uri)
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
    fun clearAll() { imports.cancel(); getApplication<Application>().getSharedPreferences("import-notice", 0).edit().clear().apply(); controller.clearAll() }
}
