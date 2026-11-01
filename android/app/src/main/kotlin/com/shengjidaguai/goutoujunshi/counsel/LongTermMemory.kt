package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.data.*
import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

data class MemoryNotice(val summary: String, val operationId: String, val inference: Boolean = true)
data class MemoryUiReceipt(val identity: String, val channel: String, val owner: String?, val stamp: WriteStamp,
    val requestId: String? = null, val proposal: MemoryProposal? = null, val operationId: String? = null,
    val clearAuthority: MemoryClearAuthority? = null)
/** Sensitive proposal, editor and notices are process RAM only. */
data class MemoryUiState(val ownerId: String? = null, val snapshot: MemorySnapshot = MemorySnapshot(),
    val consentProposal: MemoryProposal? = null, val notice: MemoryNotice? = null,
    val receipt: MemoryUiReceipt? = null, val activeOperation: String? = null, val pendingOperation: Boolean = false, val approvalFinishing: Boolean = false, val failed: Boolean = false, val capacityFull: Boolean = false, val loading: Boolean = false, val totalMemories: Int = 0, val totalImports: Int = 0) {
    val consent get() = snapshot.consent
    val entries get() = snapshot.entries
    val undoOperationId get() = snapshot.operationId
}

/** Display lifetime is separate from network authority; all delayed publication re-enters the actor. */
internal class MemoryDisplayCoordinator(private val scope: CoroutineScope,
    private val dispatch: (suspend () -> Unit) -> Unit,
    private val delayNotice: suspend (Long) -> Unit) {
    val inline = MutableStateFlow(MemoryUiState())
    val page = MutableStateFlow(MemoryUiState())
    val routeEpoch = AtomicLong()
    private val noticeJobs=mutableMapOf<String,Job>()
    @Synchronized fun cancelNotice() { noticeJobs.values.forEach { it.cancel() };noticeJobs.clear() }
    fun closePage() { routeEpoch.incrementAndGet();page.value=MemoryUiState() }
    @Synchronized fun showNotice(summary: String, operationId: String, owner: String, inference: Boolean) {
        val notice=MemoryNotice(summary,operationId,inference)
        if(inline.value.ownerId==owner) inline.value=inline.value.copy(consentProposal=null,notice=notice)
        if(page.value.ownerId==owner) page.value=page.value.copy(notice=notice)
        noticeJobs.remove(owner)?.cancel()
        noticeJobs[owner]=scope.launch {
            delayNotice(8_000)
            dispatch {
                if(inline.value.ownerId==owner && inline.value.notice==notice) inline.value=inline.value.copy(notice=null)
                if(page.value.ownerId==owner && page.value.notice==notice) page.value=page.value.copy(notice=null)
                synchronized(this@MemoryDisplayCoordinator) { noticeJobs.remove(owner) }
            }
        }
    }
}

object MemoryPresentation {
    fun context(owner: String?, snapshot: MemorySnapshot) = MemoryContextPolicy.context(owner,snapshot.consent,snapshot.stamp?.memoryRevision ?: 0,snapshot.outboundEntries())
    fun retention(owner: String?, snapshot: MemorySnapshot) = MemoryContextPolicy.retention(owner,snapshot.consent,snapshot.outboundEntries(),snapshot.anchors)
    fun sourcesRetained(original: OutboundDataContext,current: MemoryRetentionView) = MemoryContextPolicy.sourcesRetained(original,current)
    fun candidate(answer: StructuredAnswer, snapshot: MemorySnapshot, owner: String = ""): MemoryProposal? = answer.memoryProposals.firstOrNull {
        LongTermMemoryPolicy.worthRemembering(it) && LongTermMemoryPolicy.contentFingerprint(owner,it.value) !in snapshot.rejected &&
            snapshot.entries.none { entry -> LongTermMemoryPolicy.contentKey(entry.value)==LongTermMemoryPolicy.contentKey(it.value) }
    }
}
