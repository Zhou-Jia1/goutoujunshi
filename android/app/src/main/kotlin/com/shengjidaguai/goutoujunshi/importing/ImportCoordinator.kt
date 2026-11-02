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
internal data class ImportCommitResult(val success:Boolean,val error:String?=null)
data class ImportPreview(
    val id: String = UUID.randomUUID().toString(), val source: ImportSource = ImportSource.PASTE,
    val rows: List<ImportRow> = emptyList(), val unknownJson: Boolean = false,
    val images: List<Uri> = emptyList(), val target: ImportTarget? = null,
    val imageIds: List<String> = images.map { UUID.randomUUID().toString() },
    val imageLabels: Map<String,Int> = imageIds.withIndex().associate { it.value to it.index+1 },
    val floatingSource: FloatingSourceContext? = null, val captureSession: Long? = null, val mySide: OcrSide? = null, val recognized: Boolean = false,
    val rightsAccepted: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val captureMoreAvailable:Boolean=false,val captureMoreEligible:Boolean=false,val captureContinuationId:String?=null,
    val captureMoreNeedsContinuation:Boolean=false,
    val createdAt:Long=System.currentTimeMillis(),
    val captureFrames:List<List<ImportRow>> = emptyList(),
) {
    val canConfirm get() = !captureMoreNeedsContinuation && (target?.newAlias == null || target.newAlias.isNotBlank()) && !busy && error == null && (source != ImportSource.SCREENSHOT || ((images.isNotEmpty() || captureSession != null) && recognized && mySide != null)) && rightsAccepted && target != null &&
        rows.size<=MAX_IMPORT_ROWS && rows.sumOf { it.text.toByteArray(Charsets.UTF_8).size }<=MAX_IMPORT_BYTES && rows.any { !it.excluded } && rows.filterNot { it.excluded }.all { (it.ocrConfidence == null || it.ocrConfidence >= .8f || it.reviewed) && it.speaker != null && it.text.isNotBlank() && (it.speaker != Speaker.OTHER || it.name.isNotBlank()) }
}

/** Storage retries must preserve the edited preview; only OCR failures need another frame. */
internal fun ImportPreview.retryNeedsCapture() = captureSession!=null && error?.startsWith("OCR_")==true

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
    @Volatile private var selectionRevision=0L
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
    private var more:com.shengjidaguai.goutoujunshi.counsel.FloatingCaptureMore?=null
    private var pendingMore:String?=null
    private var moreImages=emptyList<String>()
    init { scope.launch { controller.state.collect { state ->
        if (!state.lockState.canDisplaySensitiveContent || (mutable.value?.source == ImportSource.SCREENSHOT &&
            (authority != null && controller.importAuthority != authority))) this@ImportCoordinator.cancel()
    } } }
    private var pendingRead: Pair<ImportPreview, () -> String>? = null
    private fun begin(source: ImportSource,moreId:String?=null): ImportPreview {
        val change={ selectionRevision++;job?.cancel();authority=null;imageRows=emptyMap();pendingRead=null;mutable.value=null;more=null;moreImages=emptyList();pendingMore=null }
        if(moreId!=null) controller.continueImportPending(moreId,change) else controller.setImportPending(true,change)
        return ImportPreview(source = source, rightsAccepted = rightsSeen()).also { mutable.value = it }
    }
    fun cancel() { controller.setImportPending(false) {
        selectionRevision++;job?.cancel();authority=null;imageRows=emptyMap();pendingRead=null;mutable.value=null;more=null;moreImages=emptyList();pendingMore=null
    } }

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
    /** Returns a scoped RAM preview; newer manual imports invalidate this owner. */
    suspend fun beginCapture(session:Long,commit:((()->Unit)->Boolean)?=null,current:()->Boolean):String? {
        val existing=mutable.value
        if(existing?.captureSession==session && current()) return existing.id
        val selection=++selectionRevision
        val expected=controller.importAuthority
        val moreId=pendingMore?.takeIf { controller.captureMore(it)?.before?.ticket==session }
        val switched=suspendCancellableCoroutine<Long?> { continuation ->
            controller.captureTemporary(expected,moreId,{ continuation.isActive && current() && selectionRevision==selection && mutable.value===existing },commit ?: { action -> if(current()) { action();true } else false }) {
                if(continuation.isActive) continuation.resume(it)
            }
        }
        if(switched==null || controller.importAuthority!=switched) return null
        if(!current() || selectionRevision!=selection || mutable.value!==existing) return null
        val p=begin(ImportSource.SCREENSHOT,moreId).copy(captureSession=session)
        more=moreId?.let(controller::captureMore)
        more?.before?.rows?.groupBy { it.imageId ?: "continued:${more!!.id}" }?.let { groups -> moreImages=groups.keys.toList();imageRows=groups }
        authority=controller.importAuthority;mutable.value=p.copy(captureMoreAvailable=more!=null)
        return p.id
    }
    internal fun prepareCaptureMore(origin:String?,receiptId:String,ticket:Long):Boolean {
        if(mutable.value!=null) return false
        val lease=controller.prepareFloatingMore(origin,receiptId,ticket) ?: return false
        pendingMore=lease.id;return true
    }
    internal fun discardPreparedCaptureMore(session:Long) {
        val id=pendingMore ?: return
        if(controller.captureMore(id)?.before?.ticket==session) controller.dropFloatingMore(id)
        pendingMore=null
    }
    private fun continuationEligible(p:ImportPreview):Boolean {
        val lease=more ?: return false
        return controller.captureMore(lease.id)!=null && p.target!=null && p.target.newAlias==null && p.target.contact?.id==lease.before.contactId && p.floatingSource==lease.before.binding?.source
    }
    private fun withoutContinuation(p:ImportPreview):ImportPreview {
        val next=p.copy(imageIds=p.imageIds.filterNot { it in moreImages },captureContinuationId=null,mySide=null,captureMoreEligible=false)
        val sameFormalOwner=more?.before?.contactId?.let { next.target?.contact?.id==it && next.target.newAlias==null }==true
        return next.copy(rows=orderedRows(next),captureMoreEligible=continuationEligible(next),captureMoreNeedsContinuation=sameFormalOwner)
    }
    fun continueCaptureMore() {
        val p=mutable.value?.takeIf { !it.busy && continuationEligible(it) } ?: return
        val lease=checkNotNull(more)
        val next=p.copy(imageIds=p.imageIds.filterNot { it in moreImages }+moreImages,captureContinuationId=lease.id,mySide=null,captureMoreNeedsContinuation=false)
        mutable.value=next.copy(rows=orderedRows(next))
    }
    fun cancelCapture(session:Long) { if(mutable.value?.captureSession==session) cancel() }
    fun captured(previewId:String,lines:List<OcrLine>):Boolean {
        val p=mutable.value?.takeIf { it.id==previewId && it.captureSession!=null } ?: return false
        if(controller.importAuthority!=authority || !controller.state.value.lockState.canDisplaySensitiveContent) return false
        if(lines.isEmpty()) { mutable.value=p.copy(error="OCR_EMPTY");return true }
        require(p.imageIds.size<20 && p.rows.size+lines.size<=MAX_IMPORT_ROWS && p.rows.sumOf { it.text.toByteArray(Charsets.UTF_8).size }+lines.sumOf { it.text.toByteArray(Charsets.UTF_8).size }<=MAX_IMPORT_BYTES)
        require(lines.size<=MAX_IMPORT_ROWS && lines.sumOf { it.text.toByteArray(Charsets.UTF_8).size }<=MAX_IMPORT_BYTES)
        val id=UUID.randomUUID().toString()
        val rows=lines.mapIndexed { i,line -> ImportRow(name=line.nickname.orEmpty(),text=line.text,id="$id:$i",imageId=id,
            side=line.side,ocrConfidence=line.confidence,excluded=OcrLayout.isUi(line)) }
        // User scrolled upwards: newest capture precedes the previous screen.
        imageRows=imageRows+(id to rows)
        val next=p.copy(imageIds=listOf(id)+p.imageIds,recognized=true,mySide=null,error=if(lines.isEmpty()) "OCR_EMPTY" else null)
        mutable.value=next.copy(rows=orderedRows(next));return true
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
    /** Same adjacency identity as orderedRows; excluded overlaps point to their reviewed retained row. */
    private fun reviewedFrames(p:ImportPreview):List<List<ImportRow>> {
        val displayed=p.rows.associateBy {it.id};var previous=emptyList<ImportRow>()
        val frames=mutableListOf<List<ImportRow>>()
        p.imageIds.forEach {id->
            val visible=imageRows[id].orEmpty().filterNot {it.excluded}
            val overlap=OcrLayout.adjacentOverlap(previous,visible) {a,b->OcrLayout.sameMessage(a.text,a.side,a.name,b.text,b.side,b.name)}
            val resolved=visible.mapIndexed {index,row->
                val shown=displayed[row.id] ?: row
                if(!shown.excluded) shown
                else if(index<overlap && shown.exclusionOverride==null) {
                    val kept=previous[previous.size-overlap+index]
                    row.copy(id=kept.id,speaker=kept.speaker,excluded=false)
                } else shown
            }.filterNot {it.excluded}
            if(id !in moreImages && resolved.isNotEmpty()) frames+=resolved
            previous=resolved
        }
        return frames
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
        // A user's reversible UI review can expose a previously blocked page boundary.
        if(p.captureSession!=null) mutable.value=p.copy(rows=orderedRows(p))
    }
    fun edit(index: Int, row: ImportRow) { mutable.value?.takeIf { !it.busy }?.let { p ->
        mutable.value = p.copy(rows = p.rows.mapIndexed { i, old -> if (i == index) row else old })
    } }
    fun floatingSource(source: FloatingSourceContext?) { mutable.value?.takeIf { !it.busy && it.captureSession!=null }?.let { mutable.value=if(more==null) it.copy(floatingSource=source) else withoutContinuation(it.copy(floatingSource=source)) } }
    fun target(target: ImportTarget) { mutable.value?.takeIf { !it.busy }?.let {
        val next=it.copy(target=target,error=it.error.takeUnless { error -> error=="IMPORT_SAVE" || error=="CAPTURE_HISTORY_REVIEW" })
        mutable.value=if(more==null) next else withoutContinuation(next)
    } }
    fun acceptRights() { mutable.value?.let { mutable.value = it.copy(rightsAccepted = true) } }
    fun confirm() {
        val preview = mutable.value?.takeIf { it.canConfirm } ?: return
        if(preview.source==ImportSource.SCREENSHOT && (controller.importAuthority!=authority || !controller.state.value.lockState.canDisplaySensitiveContent)) { cancel();return }
        mutable.value = preview.copy(busy = true)
        if(preview.source==ImportSource.SCREENSHOT) authority=controller.importAuthority+1
        controller.commitImportResult(preview.copy(rows=preview.rows.filterNot { it.excluded },captureFrames=reviewedFrames(preview))) { result ->
            // The controller owns an IO actor. All preview checks and associated effects must
            // return to the coordinator's Main.immediate dispatcher as one uninterrupted action.
            scope.launch {
                if (mutable.value?.id == preview.id) {
                    if (result.success) { rememberRights(); pendingRead = null; imageRows=emptyMap(); more=null;moreImages=emptyList();pendingMore=null;authority=null; mutable.value = null; controller.setImportPending(false) }
                    else mutable.value = preview.copy(error = result.error ?: "IMPORT_SAVE")
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
