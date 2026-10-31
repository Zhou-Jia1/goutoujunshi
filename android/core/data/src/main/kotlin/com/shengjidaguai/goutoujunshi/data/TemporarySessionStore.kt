package com.shengjidaguai.goutoujunshi.data

import com.shengjidaguai.goutoujunshi.domain.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TemporarySession(
    val id: String,
    val draft: String = "",
    val messages: List<Message> = emptyList(),
    val imports: List<ChatImport> = emptyList(),
    val lines: List<ChatLine> = emptyList(),
    val samples: List<StyleSample> = emptyList(),
    val screenshot: ByteArray? = null,
    val summary: String = "",
    val plans: List<ActionPlan> = emptyList(),
)

/** Opaque process-local write authority. Never persist a credential or pass it to a new store. */
class TemporarySessionCredential internal constructor(val sessionId: String, internal val epoch: Long) {
    internal val instanceId: String = UUID.randomUUID().toString()
}

/** Process memory only. Never connect this to SavedState, serializers, files or background work. */
class TemporarySessionStore {
    private val mutex = Mutex()
    private var epoch = 0L
    private val sessions = mutableMapOf<String, TemporarySession>()
    private val credentials = mutableMapOf<String, TemporarySessionCredential>()

    /** Call only for an explicit user action starting a session, never from a model callback. */
    suspend fun begin(id: String = UUID.randomUUID().toString()): TemporarySessionCredential = mutex.withLock {
        require(id.isNotBlank())
        sessions.remove(id)?.screenshot?.fill(0)
        val credential = TemporarySessionCredential(id, epoch)
        credentials[id] = credential
        sessions[id] = TemporarySession(id)
        credential
    }
    suspend fun put(credential: TemporarySessionCredential, session: TemporarySession): Boolean = mutex.withLock {
        if (!isCurrent(credential) || credential.sessionId != session.id) return@withLock false
        val previous = sessions[session.id]
        if (previous?.screenshot !== session.screenshot) previous?.screenshot?.fill(0)
        sessions[session.id] = session
        true
    }
    suspend fun get(credential: TemporarySessionCredential): TemporarySession? = mutex.withLock {
        if (isCurrent(credential)) sessions[credential.sessionId] else null
    }
    suspend fun remove(credential: TemporarySessionCredential): Boolean = mutex.withLock {
        if (!isCurrent(credential)) return@withLock false
        credentials.remove(credential.sessionId)
        sessions.remove(credential.sessionId)?.screenshot?.fill(0)
        true
    }
    suspend fun clear() = mutex.withLock {
        epoch++
        sessions.values.forEach { it.screenshot?.fill(0) }
        sessions.clear()
        credentials.clear()
    }
    /** Hold the same lease as put/clear until an explicit persistent migration has committed. */
    internal suspend fun <T> withCurrent(credential: TemporarySessionCredential, block: suspend (TemporarySession) -> T): T = mutex.withLock {
        check(isCurrent(credential)) { "Temporary session credential was revoked" }
        block(requireNotNull(sessions[credential.sessionId]))
    }
    private fun isCurrent(credential: TemporarySessionCredential): Boolean =
        credential.epoch == epoch && credentials[credential.sessionId] === credential
}
