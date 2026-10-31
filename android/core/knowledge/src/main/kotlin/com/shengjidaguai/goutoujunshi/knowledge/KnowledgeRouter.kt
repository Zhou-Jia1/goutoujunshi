package com.shengjidaguai.goutoujunshi.knowledge

import com.shengjidaguai.goutoujunshi.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable private data class KeywordRule(val keywords: List<String>, val references: List<String>, val priority: Int)
@Serializable private data class RouteConfig(
    val version: Int, val relationTypes: Map<String, String>, val goals: List<String>,
    val defaults: Map<String, List<String>>, val goalRules: Map<String, List<String>>,
    val keywordRules: List<KeywordRule>, val safetyKeywords: List<String>, val safetyReferences: List<String>,
)

class KnowledgeRouter(private val library: KnowledgeLibrary) {
    private val config = Json { ignoreUnknownKeys = true }.decodeFromString<RouteConfig>(library.appAsset("app-prompt/routes.json"))
    init {
        require(config.version == 1)
        require(config.relationTypes.keys == RelationType.entries.map { it.name }.toSet())
        require(config.goals.toSet() == Goal.entries.map { it.name }.toSet())
        require(config.safetyReferences.isNotEmpty() && config.safetyReferences.size <= 3)
    }

    fun scene(type: RelationType): String = config.relationTypes.getValue(type.name).also {
        require(it in setOf("work", "intimate", "family", "social", "mediation"))
    }

    fun select(type: RelationType, goal: Goal, text: String, maxCharacters: Int = 24_000): List<KnowledgeReference> {
        require(maxCharacters > 0)
        val folded = text.lowercase(Locale.ROOT)
        fun matches(words: List<String>) = words.any { it.isNotEmpty() && folded.contains(it.lowercase(Locale.ROOT)) }
        val mandatory = if (matches(config.safetyKeywords)) config.safetyReferences else emptyList()
        val candidates = mandatory + config.keywordRules.withIndex()
            .filter { matches(it.value.keywords) }
            .sortedWith(compareByDescending<IndexedValue<KeywordRule>> { it.value.priority }.thenBy { it.index })
            .flatMap { it.value.references } + config.goalRules.getValue(goal.name) + config.defaults.getValue(scene(type))
        var used = 0
        val chosen = mutableListOf<KnowledgeReference>()
        for (path in candidates.distinct()) {
            require(path.startsWith("references/") && path.endsWith(".md"))
            val reference = library.reference(path)
            if (chosen.size < 3 && used + reference.characters <= maxCharacters) {
                chosen += reference
                used += reference.characters
            } else require(path !in mandatory) { "Required safety reference exceeds budget" }
            if (chosen.size == 3) break
        }
        require(chosen.isNotEmpty()) { "No knowledge fits the configured budget" }
        require(mandatory.all { path -> chosen.any { it.path == path } })
        return chosen
    }
}
