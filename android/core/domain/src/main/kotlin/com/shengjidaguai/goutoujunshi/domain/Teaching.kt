package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable

@Serializable data class TipScene(val group: RelationGroup = RelationGroup.DEFAULT, val goal: Goal = Goal.REPLY)
@Serializable data class HabitState(val day: String = "", val disabled: Set<String> = emptySet(), val shownDays:Set<String> = emptySet())
/** The model example is never replaced with the entire raw answer. */
object TeachingPolicy {
    fun tip(answer: StructuredAnswer): Tip? = answer.tips.firstOrNull { it.name.isNotBlank() && it.explanation.isNotBlank() }
        ?.let { it.copy(example = it.example.takeIf { part -> part.isNotBlank() && answer.reply.any { reply -> reply.contains(part) } }.orEmpty()) }
    fun merge(previous: Tip?, selected: Tip): Tip = selected.copy(id = previous?.id ?: selected.id,
        savedAt = previous?.savedAt ?: selected.savedAt, scenes = previous?.scenes.orEmpty() + selected.scenes)
}
