package com.shengjidaguai.goutoujunshi.importing

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.shengjidaguai.goutoujunshi.counsel.CounselController
import com.shengjidaguai.goutoujunshi.domain.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ImportTarget(val contact: Contact? = null, val newAlias: String? = null)
data class ImportPreview(
    val id: String = UUID.randomUUID().toString(), val source: ImportSource = ImportSource.PASTE,
    val rows: List<ImportRow> = emptyList(), val unknownJson: Boolean = false,
    val images: List<Uri> = emptyList(), val target: ImportTarget? = null,
    val rightsAccepted: Boolean = false, val busy: Boolean = false, val error: String? = null,
) {
    val canConfirm get() = (target?.newAlias == null || target.newAlias.isNotBlank()) && !busy && error == null && images.isEmpty() && rightsAccepted && target != null &&
        rows.isNotEmpty() && rows.all { it.speaker != null && it.text.isNotBlank() && (it.speaker != Speaker.OTHER || it.name.isNotBlank()) }
}

/** Only RAM. No SavedStateHandle, URI grants persisted, file cache or private text preferences. */
class ImportCoordinator(
    private val controller: CounselController, private val scope: CoroutineScope,
    private val rightsSeen: () -> Boolean, private val rememberRights: () -> Unit,
) {
    private val mutable = MutableStateFlow<ImportPreview?>(null)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var pendingRead: Pair<ImportPreview, () -> String>? = null
    private fun begin(source: ImportSource): ImportPreview {
        job?.cancel(); pendingRead = null
        controller.setImportPending(true)
        return ImportPreview(source = source, rightsAccepted = rightsSeen()).also { mutable.value = it }
    }
    fun cancel() { job?.cancel(); pendingRead = null; mutable.value = null; controller.setImportPending(false) }
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
                    if (controller.state.value.conversation?.id != boundOrigin) { cancel(); return@launch }
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
            mutable.value = begin(ImportSource.SCREENSHOT).copy(images = images.filter { it.scheme == "content" }.take(20), error = "OCR_PENDING")
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
    fun edit(index: Int, row: ImportRow) { mutable.value?.takeIf { !it.busy }?.let { p ->
        mutable.value = p.copy(rows = p.rows.mapIndexed { i, old -> if (i == index) row else old })
    } }
    fun target(target: ImportTarget) { mutable.value?.takeIf { !it.busy }?.let { mutable.value = it.copy(target = target, error = it.error.takeUnless { error -> error == "IMPORT_SAVE" }) } }
    fun acceptRights() { mutable.value?.let { mutable.value = it.copy(rightsAccepted = true) } }
    fun confirm() {
        val preview = mutable.value?.takeIf { it.canConfirm } ?: return
        mutable.value = preview.copy(busy = true)
        controller.commitImport(preview) { success ->
            // The controller owns an IO actor. All preview checks and associated effects must
            // return to the coordinator's Main.immediate dispatcher as one uninterrupted action.
            scope.launch {
                if (mutable.value?.id == preview.id) {
                    if (success) { rememberRights(); pendingRead = null; mutable.value = null; controller.setImportPending(false) }
                    else mutable.value = preview.copy(error = "IMPORT_SAVE")
                }
            }
        }
    }
    fun retry() {
        val preview = mutable.value ?: return
        when (preview.error) {
            "IMPORT_SAVE" -> { mutable.value = preview.copy(error = null); confirm() }
            "IMPORT_INITIALIZE" -> pendingRead?.takeIf { it.first.id == preview.id }?.let {
                parse(preview.copy(error = null), it.second, retryInitialization = true)
            }
        }
    }
}
