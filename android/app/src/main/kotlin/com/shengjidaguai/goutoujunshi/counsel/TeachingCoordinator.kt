package com.shengjidaguai.goutoujunshi.counsel

import com.shengjidaguai.goutoujunshi.domain.*
import java.time.Instant
import java.time.ZoneId

data class TeachingView(val answerId: String = "", val tip: Tip? = null, val saved: Boolean = false, val habit: String? = null, val receipt:String = "", val sceneRecorded:Boolean = true)

/** One actor owns this helper. Temporary metadata is discarded whenever display ownership changes. */
class TeachingCoordinator(private val now: () -> Long, private val zone: () -> ZoneId) {
    private var temporaryOwner: String? = null
    private var localHabit = HabitState()
    fun reset() { temporaryOwner = null; localHabit = HabitState() }
    fun day(): String = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().toString()
    fun temporaryHabit(owner: String, text: String, disabled: Boolean = false): Boolean {
        if (temporaryOwner != owner) { reset(); temporaryOwner = owner }
        if (disabled) { localHabit = localHabit.copy(disabled = localHabit.disabled + text); return false }
        val show = text.isNotBlank() && localHabit.day != day() && day() !in localHabit.shownDays && text !in localHabit.disabled
        if (show) localHabit = localHabit.copy(day = day(),shownDays=localHabit.shownDays+day())
        return show
    }
    fun view(answer: StructuredAnswer, request: AnalysisRequest, favorites: List<Tip>, showHabit: Boolean): TeachingView {
        val tip = TeachingPolicy.tip(answer)?.takeIf {request.mode!=RequestMode.REWRITE_REPLY || it.example.isNotBlank()}?.copy(id = "tip:${java.util.UUID.randomUUID()}", savedAt = now(),
            scenes = setOf(TipScene((answer.scene?.relationType ?: request.relationType)?.group() ?: RelationGroup.DEFAULT, answer.scene?.goal ?: request.goal ?: Goal.REPLY)))
        return TeachingView(answer.id, tip, favorites.any { it.name == tip?.name }, answer.habit.takeIf { showHabit && it.isNotBlank() }, java.util.UUID.randomUUID().toString(), favorites.any {it.name==tip?.name && it.scenes.containsAll(tip?.scenes.orEmpty())})
    }
}
