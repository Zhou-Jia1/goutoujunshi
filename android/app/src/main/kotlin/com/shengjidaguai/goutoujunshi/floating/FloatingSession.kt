package com.shengjidaguai.goutoujunshi.floating

import com.shengjidaguai.goutoujunshi.domain.*

/** RAM only: neither process death nor an old permission result can enable the service. */
class FloatingSession {
    private var generation = 0L
    private var pending: Long? = null
    @Synchronized fun epoch() = generation
    @Synchronized fun request(): Long = (++generation).also { pending = it }
    @Synchronized fun valid(ticket: Long) = pending == ticket
    @Synchronized fun accept(ticket: Long): Boolean {
        if (!valid(ticket)) return false
        pending = null
        return true
    }
    @Synchronized fun stop() { generation++; pending = null }
    /** Cancellation belongs to its request/accepted service, never a newer user's request. */
    @Synchronized fun cancel(ticket: Long) { if(generation==ticket) stop() }
}
data class FloatingPoint(val x: Int, val y: Int)
data class FloatingBounds(val left: Int, val top: Int, val right: Int, val bottom: Int, val size: Int, val reachable: Int) {
    fun dock(x: Int, y: Int) = FloatingPoint(
        if (x + size / 2 < (left + right) / 2) left - size + reachable else right - reachable,
        y.coerceIn(top, maxOf(top, bottom - size)))
}
fun floatingContentAllowed(lock: LockState) = SafetyPolicy.evaluate(null, SafetyLevel.NONE, lockState = lock).canDisplaySensitiveContent
