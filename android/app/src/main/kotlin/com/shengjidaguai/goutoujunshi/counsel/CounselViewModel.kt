package com.shengjidaguai.goutoujunshi.counsel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.shengjidaguai.goutoujunshi.data.EncryptedDataStore
import com.shengjidaguai.goutoujunshi.data.TemporarySessionStore
import com.shengjidaguai.goutoujunshi.domain.*
import com.shengjidaguai.goutoujunshi.knowledge.*
import kotlinx.coroutines.*

/** Application lifetime keeps temporary sessions across Activity recreation, never process death. */
private object CounselRuntime {
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
                DemoAnalysisProvider(assembler, application.assets), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        }.also { controller = it }
    }
}

class CounselViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = CounselRuntime.get(application)
    val state = controller.state
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
    fun preparePreview() = controller.preparePreview()
    fun preparePreview(origin: String?) = controller.preparePreview(origin)
    fun analyze() = controller.analyze()
    fun analyze(origin: String?) = controller.analyze(origin)
    fun retry() = controller.retry()
    fun stop() = controller.stop()
    fun newTemporary() = controller.newTemporary()
    fun clearAll() = controller.clearAll()
}
