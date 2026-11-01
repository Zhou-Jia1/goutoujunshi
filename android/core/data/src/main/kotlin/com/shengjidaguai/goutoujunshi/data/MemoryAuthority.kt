package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*

/** Capacity eviction retains only non-reversible proof, never a second hidden memory value. */
internal const val MAX_MEMORY_ANCHORS=200
internal const val MAX_MEMORY_RECEIPTS=400
internal suspend fun JunshiDao.memoryAnchors(owner: String): List<MemoryAnchor> =
    records(owner,OwnedRecordKind.MEMORY_ANCHOR.name).map { decode(it.payload) }
internal suspend fun JunshiDao.putMemoryAnchor(owner: String, anchor: MemoryAnchor) {
    require(anchor.id.length in 1..128 && anchor.fingerprint.matches(Regex("[0-9a-f]{64}")) && anchor.contentKey.matches(Regex("[0-9a-f]{64}")))
    val id="MEMORY_ANCHOR:${anchor.id}"
    require(ownerOfRecord(id).let { it==null || it==owner })
    put(OwnedRecordRow(id,owner,OwnedRecordKind.MEMORY_ANCHOR,encode(anchor)))
}
internal suspend fun JunshiDao.deleteMemoryAnchor(owner: String,id: String) = deleteRecord(owner,OwnedRecordKind.MEMORY_ANCHOR.name,id)
internal suspend fun JunshiDao.replaceMemoryAnchors(owner: String, anchors: List<MemoryAnchor>) {
    memoryAnchors(owner).forEach { deleteMemoryAnchor(owner,it.id) }
    anchors.forEach { putMemoryAnchor(owner,it) }
}

/** Only active entries and the most recent 20 undo receipts may keep provenance reachable. */
internal suspend fun JunshiDao.collectMemoryAuthority(owner: String) {
    val sourceRows=records(owner,OwnedRecordKind.MEMORY_SOURCE.name).associateBy { it.id.removePrefix("MEMORY_SOURCE:") }
    val operationRows=operations(owner).takeLast(MemoryPolicy.MAX_OPERATIONS)
    val operations=operationRows.map { decode<MemoryOperation>(it.payload) }
    // Anchor snapshots are proof for restoring those entries, never GC roots themselves.
    val reachable=(memories(owner).map { it.id }+operations.flatMap { op -> (op.before+op.after).map { it.id } }).toMutableSet()
    val pending=ArrayDeque(reachable)
    while(pending.isNotEmpty()) {
        sourceRows[pending.removeFirst()]?.let { row ->
            decode<MemorySourceReceipt>(row.payload).memoryDependencies.keys.forEach { if(reachable.add(it)) pending.addLast(it) }
        }
    }
    memoryAnchors(owner).filterNot { it.id in reachable }.forEach { deleteMemoryAnchor(owner,it.id) }
    operationRows.zip(operations).forEach { (row,op) ->
        val bounded=op.copy(beforeAnchors=op.beforeAnchors.filter { it.id in reachable },afterAnchors=op.afterAnchors.filter { it.id in reachable })
        if(bounded!=op) put(row.copy(payload=encode(bounded)))
    }
    sourceRows.filterKeys { it !in reachable }.forEach { (id,_) -> deleteRecord(owner,OwnedRecordKind.MEMORY_SOURCE.name,id) }
    if(memoryAnchors(owner).size>MAX_MEMORY_ANCHORS || records(owner,OwnedRecordKind.MEMORY_SOURCE.name).size>MAX_MEMORY_RECEIPTS)
        throw MemoryWriteRejected("MEMORY_METADATA_LIMIT","记忆来源记录已达上限，请先清理记忆")
}
