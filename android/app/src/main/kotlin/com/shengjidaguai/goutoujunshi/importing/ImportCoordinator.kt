package com.shengjidaguai.goutoujunshi.importing

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.shengjidaguai.goutoujunshi.counsel.CounselController
import com.shengjidaguai.goutoujunshi.domain.*
import java.util.UUID
import com.shengjidaguai.goutoujunshi.domain.ocr.*
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ImportTarget(val contact: Contact? = null, val newAlias: String? = null)
data class ImportPreview(
    val id: String = UUID.randomUUID().toString(), val source: ImportSource = ImportSource.PASTE,
    val rows: List<ImportRow> = emptyList(), val unknownJson: Boolean = false,
    val images: List<Uri> = emptyList(), val target: ImportTarget? = null,
    val imageIds: List<String> = images.map { UUID.randomUUID().toString() },
    val imageLabels: Map<String,Int> = imageIds.withIndex().associate { it.value to it.index+1 },
    val mySide: OcrSide? = null, val recognized: Boolean = false,
    val rightsAccepted: Boolean = false, val busy: Boolean = false, val error: String? = null,
) {
    val canConfirm get() = (target?.newAlias == null || target.newAlias.isNotBlank()) && !busy && error == null && (source != ImportSource.SCREENSHOT || (images.isNotEmpty() && recognized && mySide != null)) && rightsAccepted && target != null &&
        rows.size<=MAX_IMPORT_ROWS && rows.sumOf { it.text.toByteArray(Charsets.UTF_8).size }<=MAX_IMPORT_BYTES && rows.any { !it.excluded } && rows.filterNot { it.excluded }.all { (it.ocrConfidence == null || it.ocrConfidence >= .8f || it.reviewed) && it.speaker != null && it.text.isNotBlank() && (it.speaker != Speaker.OTHER || it.name.isNotBlank()) }
}

/** Only RAM. No SavedStateHandle, URI grants persisted, file cache or private text preferences. */
class ImportCoordinator(
    private val controller: CounselController, private val scope: CoroutineScope,
    private val rightsSeen: () -> Boolean, private val rememberRights: () -> Unit,
    private val readImages: (suspend (List<Uri>) -> List<List<OcrLine>>)? = null,
) {
    private val mutable = MutableStateFlow<ImportPreview?>(null)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var authority: Long? = null
    private var selectionRevision=0L
    internal fun beginSelection():SelectionAuthority? {
        val (epoch,state)=controller.importSelectionState()
        val conversation=state.conversation?.id ?: return null
        if(state.loading || !state.lockState.canDisplaySensitiveContent || mutable.value!=null) return null
        return SelectionAuthority(epoch,conversation,state.currentContact?.id,mutable.value?.id,++selectionRevision)
    }
    internal fun selectionIsCurrent(ticket:SelectionAuthority):Boolean {
        val (epoch,state)=controller.importSelectionState()
        return ticket.epoch==epoch && ticket.conversation==state.conversation?.id && ticket.owner==state.currentContact?.id &&
            ticket.preview==mutable.value?.id && ticket.revision==selectionRevision && state.lockState.canDisplaySensitiveContent
    }
    private var imageRows: Map<String,List<ImportRow>> = emptyMap()
    init { scope.launch { controller.state.collect { state ->
        if (mutable.value?.source == ImportSource.SCREENSHOT && (!state.lockState.canDisplaySensitiveContent ||
            (authority != null && controller.importAuthority != authority))) this@ImportCoordinator.cancel()
    } } }
    private var pendingRead: Pair<ImportPreview, () -> String>? = null
    private fun begin(source: ImportSource): ImportPreview {
        job?.cancel(); authority = null; imageRows = emptyMap(); pendingRead = null
        controller.setImportPending(true)
        return ImportPreview(source = source, rightsAccepted = rightsSeen()).also { mutable.value = it }
    }
    fun cancel() { job?.cancel(); authority = null; imageRows = emptyMap(); pendingRead = null; mutable.value = null; controller.setImportPending(false) }
    fun text(text: String, source: ImportSource = ImportSource.PASTE) {
        val preview = begin(source)
        parse(preview, { text })
    }
    fun file(resolver: ContentResolver, uri: Uri) {
        val preview = begin(ImportSource.FILE)
        if (uri.scheme != "content") { mutable.value = preview.copy(error = "IMPORT_READ"); return }
        parse(preview, { resolver.openInputStream(uri)?.use(ChatImportParser::read) ?: error("IMPORT_READ") })
    }
    private fun parse(preview: ImportPreview, read: () -> String, retryInitialization: Boolean = false) {
        val origin = controller.state.value.conversation?.id
        pendingRead = preview to read
        mutable.value = preview.copy(busy = true)
        job = scope.launch {
            try {
                val boundOrigin = origin ?: if (retryInitialization) {
                    suspendCancellableCoroutine<String?> { continuation ->
                        controller.retryStartup { conversation ->
                            if (continuation.isActive) continuation.resume(conversation)
                        }
                    } ?: error("IMPORT_INITIALIZE")
                } else controller.state.first {
                    !it.loading && (it.conversation != null || it.error != null)
                }.conversation?.id ?: error("IMPORT_INITIALIZE")
                val parsed = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    ChatImportParser.parse(read()) { context.ensureActive() }
                }
                if (mutable.value?.id == preview.id) {
                    if (controller.state.value.conversation?.id != boundOrigin) { this@ImportCoordinator.cancel(); return@launch }
                    pendingRead = null
                    mutable.value = preview.copy(rows = parsed.rows, unknownJson = parsed.unknownJson)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (mutable.value?.id == preview.id) mutable.value = preview.copy(error =
                    when (e.message) { "IMPORT_TOO_LARGE" -> "IMPORT_TOO_LARGE"; "IMPORT_INITIALIZE" -> "IMPORT_INITIALIZE"; else -> "IMPORT_READ" })
            }
        }
    }
    /** Every explicit delivery is handled once; consuming extras prevents recreation replay. */
    @Suppress("DEPRECATION")
    fun receive(intent: Intent, resolver: ContentResolver? = null) {
        if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        try {
        val type = intent.type.orEmpty()
        if (type.startsWith("image/")) {
            val images = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            val shared=images.ifEmpty { intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty() }
            images(shared)
        } else if (type == "text/plain") {
            val text = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.joinToString("\n")
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            } else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (text != null) text(text, ImportSource.SHARE)
            else {
                val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                val preview = begin(ImportSource.SHARE)
                if (resolver == null || uris.isEmpty() || uris.size > 20 || uris.any { it.scheme != "content" }) mutable.value = preview.copy(error = "IMPORT_READ")
                else parse(preview, read = {
                    val combined = StringBuilder()
                    uris.forEach { uri ->
                        combined.append(resolver.openInputStream(uri)?.use(ChatImportParser::read) ?: error("IMPORT_READ")).append('\n')
                        require(combined.toString().toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) { "IMPORT_TOO_LARGE" }
                    }
                    combined.toString()
                })
            }
        }
        } catch (_: Exception) {
            mutable.value = begin(ImportSource.SHARE).copy(error = "IMPORT_READ")
        } finally {
        intent.removeExtra(Intent.EXTRA_TEXT); intent.removeExtra(Intent.EXTRA_STREAM)
        intent.clipData = null; intent.action = null; intent.data = null; intent.replaceExtras(null as android.os.Bundle?)
        }
    }
    fun images(uris: List<Uri>) {
        val p = begin(ImportSource.SCREENSHOT)
        authority = controller.importAuthority
        val ids=uris.map { UUID.randomUUID().toString() }
        mutable.value = p.copy(images = uris, imageIds = ids, imageLabels=ids.withIndex().associate { it.value to it.index+1 },
            error = if(uris.size !in 1..20 || uris.any { it.scheme != "content" }) "OCR_READ" else null)
    }
    fun recognize() {
        val p=mutable.value?.takeIf { !it.busy && it.source==ImportSource.SCREENSHOT && it.images.isNotEmpty() } ?: return
        if(p.images.size !in 1..ScreenshotPhotoPicker.MAX_IMAGES || p.images.any { it.scheme!="content" }) return
        if(readImages==null) { mutable.value=p.copy(error="OCR_UNAVAILABLE"); return }
        val token=authority
        mutable.value=p.copy(busy=true,error=null)
        job=scope.launch {
            try {
                controller.state.first { !it.loading }
                val result=readImages.invoke(p.images)
                require(result.size==p.images.size && result.sumOf { it.size }<=MAX_IMPORT_ROWS && result.flatten().sumOf { it.text.toByteArray(Charsets.UTF_8).size }<=MAX_IMPORT_BYTES) { "OCR_LIMIT" }
                if(mutable.value?.id!=p.id) return@launch
                if(controller.importAuthority!=token || !controller.state.value.lockState.canDisplaySensitiveContent) { this@ImportCoordinator.cancel();return@launch }
                imageRows=p.imageIds.zip(result.mapIndexed { imageIndex,lines ->
                    lines.mapIndexed { i,line -> ImportRow(name=line.nickname.orEmpty(),text=line.text,id="${p.imageIds[imageIndex]}:$i",
                        imageId=p.imageIds[imageIndex],side=line.side,ocrConfidence=line.confidence,
                        excluded=OcrLayout.isUi(line)) }
                }).toMap()
                mutable.value=p.copy(recognized=true,rows=orderedRows(p),error=if(result.all { it.isEmpty() }) "OCR_EMPTY" else null)
            } catch(e:TimeoutCancellationException) { if(mutable.value?.id==p.id) mutable.value=p.copy(error="OCR_TIMEOUT") }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(mutable.value?.id==p.id) mutable.value=p.copy(error=when(e.message) { "OCR_LIMIT" -> "OCR_LIMIT"; "OCR_READ" -> "OCR_READ"; else -> "OCR_UNAVAILABLE" }) }
        }
    }
    private fun orderedRows(p:ImportPreview):List<ImportRow> {
        val result=mutableListOf<ImportRow>(); var previous=emptyList<ImportRow>()
        p.imageIds.forEach { id ->
            val rows=imageRows[id].orEmpty()
            val visible=rows.filterNot { it.excluded }
            val overlap=OcrLayout.adjacentOverlap(previous,visible) { a,b -> OcrLayout.sameMessage(a.text,a.side,a.name,b.text,b.side,b.name) }
            val duplicate=visible.take(overlap).map { it.id }.toSet()
            result+=rows.map { row -> if(row.id in duplicate && row.exclusionOverride==null) row.copy(excluded=true) else row }
            previous=visible
        }
        return result
    }
    fun moveImage(previewId:String,imageId:String,delta:Int) {
        val p=mutable.value?.takeIf { it.id==previewId && !it.busy } ?: return
        val from=p.imageIds.indexOf(imageId); val to=from+delta
        if(from<0 || to !in p.images.indices) return
        val ids=p.imageIds.toMutableList().apply { add(to,removeAt(from)) }
        val uris=p.images.toMutableList().apply { add(to,removeAt(from)) }
        val next=p.copy(imageIds=ids,images=uris)
        mutable.value=next.copy(rows=if(p.recognized) orderedRows(next) else next.rows)
    }
    fun side(previewId:String,value:OcrSide) {
        val p=mutable.value?.takeIf { it.id==previewId && !it.busy && value!=OcrSide.UNKNOWN } ?: return
        val group=p.rows.map { it.name }.filter { it.isNotBlank() }.distinct().size>1
        fun mapped(row:ImportRow)=if(row.side==OcrSide.UNKNOWN) row else row.copy(speaker=if(row.side==value) Speaker.ME else if(group && row.name.isNotBlank()) Speaker.OTHER else Speaker.THEM)
        mutable.value=p.copy(mySide=value,rows=p.rows.map(::mapped))
        imageRows=imageRows.mapValues { (_,rows) -> rows.map(::mapped) }
    }
    fun ifCurrent(previewId:String, action:()->Unit) { if(mutable.value?.id==previewId) action() }
    fun editRow(previewId:String,rowId:String,update:(ImportRow)->ImportRow) {
        val p=mutable.value?.takeIf { it.id==previewId && !it.busy } ?: return
        val next=p.rows.map { if(it.id==rowId) update(it) else it }
        mutable.value=p.copy(rows=next)
        // Retain edits by identity when the image order changes.
        val updated=next.firstOrNull { it.id==rowId } ?: return
        updated.imageId?.let { id -> imageRows=imageRows+(id to imageRows[id].orEmpty().map { if(it.id==rowId) updated.copy(excluded=updated.exclusionOverride ?: it.excluded) else it }) }
    }
    fun edit(index: Int, row: ImportRow) { mutable.value?.takeIf { !it.busy }?.let { p ->
        mutable.value = p.copy(rows = p.rows.mapIndexed { i, old -> if (i == index) row else old })
    } }
    fun target(target: ImportTarget) { mutable.value?.takeIf { !it.busy }?.let { mutable.value = it.copy(target = target, error = it.error.takeUnless { error -> error == "IMPORT_SAVE" }) } }
    fun acceptRights() { mutable.value?.let { mutable.value = it.copy(rightsAccepted = true) } }
    fun confirm() {
        val preview = mutable.value?.takeIf { it.canConfirm } ?: return
        if(preview.source==ImportSource.SCREENSHOT && (controller.importAuthority!=authority || !controller.state.value.lockState.canDisplaySensitiveContent)) { cancel();return }
        mutable.value = preview.copy(busy = true)
        if(preview.source==ImportSource.SCREENSHOT) authority=controller.importAuthority+1
        controller.commitImport(preview.copy(rows=preview.rows.filterNot { it.excluded })) { success ->
            // The controller owns an IO actor. All preview checks and associated effects must
            // return to the coordinator's Main.immediate dispatcher as one uninterrupted action.
            scope.launch {
                if (mutable.value?.id == preview.id) {
                    if (success) { rememberRights(); pendingRead = null; imageRows=emptyMap(); authority=null; mutable.value = null; controller.setImportPending(false) }
                    else mutable.value = preview.copy(error = "IMPORT_SAVE")
                }
            }
        }
    }
    fun retry() {
        val preview = mutable.value ?: return
        when (preview.error) {
            "OCR_UNAVAILABLE", "OCR_TIMEOUT", "OCR_EMPTY", "OCR_READ", "OCR_LIMIT" -> recognize()
            "IMPORT_SAVE" -> { mutable.value = preview.copy(error = null); confirm() }
            "IMPORT_INITIALIZE" -> pendingRead?.takeIf { it.first.id == preview.id }?.let {
                parse(preview.copy(error = null), it.second, retryInitialization = true)
            }
        }
    }
}
