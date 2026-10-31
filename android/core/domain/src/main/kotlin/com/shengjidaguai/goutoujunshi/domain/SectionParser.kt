package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.decodeFromString

/** Stateful per-response parser. A marker prefix is held until it is complete or finish is called. */
class SectionParser {
    private data class Segment(val id:SectionId, val text:StringBuilder=StringBuilder())
    private val raw=StringBuilder()
    private val pending=StringBuilder()
    private val segments=mutableListOf(Segment(SectionId.FREE_TEXT))
    private var finished:StructuredAnswer?=null

    fun feed(delta:String):List<AnalysisChunk.Section> {
        check(finished == null) { "Parser already finished" }
        raw.append(delta); pending.append(delta)
        return drain(false)
    }
    private fun drain(final:Boolean):List<AnalysisChunk.Section> {
        val emitted=mutableListOf<AnalysisChunk.Section>()
        fun append(text:String) {
            if(text.isEmpty()) return
            segments.last().text.append(text)
            emitted += AnalysisChunk.Section(segments.last().id,text)
        }
        while(pending.isNotEmpty()) {
            if(pending.startsWith("[[")) {
                val end=pending.indexOf("]]")
                if(end < 0) {
                    if(final) { append(pending.toString()); pending.clear() }
                    break
                }
                val marker=pending.substring(2,end)
                val id=SectionId.entries.firstOrNull { it.name == marker }
                segments += Segment(id ?: SectionId.FREE_TEXT)
                if(id == null) append(pending.substring(0,end+2))
                pending.delete(0,end+2)
            } else {
                val start=pending.indexOf("[[")
                val count=if(start >= 0) start else pending.length - if(!final && pending.last() == '[') 1 else 0
                if(count == 0) break
                append(pending.substring(0,count)); pending.delete(0,count)
            }
        }
        return emitted
    }
    fun finish():StructuredAnswer {
        finished?.let { return it }
        drain(true)
        // A final single '[' must remain visible as literal data.
        if(pending.isNotEmpty()) { segments.last().text.append(pending); pending.clear() }
        val fallback=mutableListOf<String>()
        fun bodies(id:SectionId)=segments.filter { it.id==id }.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
        fun text(id:SectionId)=bodies(id).joinToString("\n")
        fun lines(id:SectionId)=bodies(id).flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
        fun fields(line:String,count:Int):List<String>? {
            val parts=line.split('|',limit=count)
            return if(parts.size==count && parts.all { it.isNotBlank() }) parts.map { it.trim() } else { fallback += line; null }
        }
        var safety=SafetyLevel.NONE
        bodies(SectionId.SAFETY).forEach { body ->
            val parsed=SafetyLevel.entries.firstOrNull { it.name.equals(body,ignoreCase=true) }
            if(parsed==null) fallback+=body else safety=safety.upgrade(parsed)
        }
        var scene:Scene?=null
        lines(SectionId.SCENE).forEach { line -> fields(line,3)?.let { f ->
            val relation=RelationType.entries.firstOrNull { it.name.equals(f[0],true) }
            val goal=Goal.entries.firstOrNull { it.name.equals(f[1],true) }
            if(relation==null || goal==null || f[2].lowercase() !in listOf("low","medium","high")) fallback+=line
            else scene=Scene(relation,goal,f[2].lowercase())
        } }
        val alternatives=lines(SectionId.ALT).mapNotNull { line -> fields(line,2)?.let { Alternative(it[0],it[1]) } }
        val facts=lines(SectionId.FACTS).mapNotNull { line -> fields(line,3)?.let { f ->
            if(f[0] != "F") { fallback+=line; null } else Fact(f[1],f[2].split(Regex("[,，\\s]+|、")).filter { it.isNotBlank() })
        } }
        val guesses=lines(SectionId.GUESSES).mapNotNull { line -> fields(line,3)?.let { f ->
            val confidence=Confidence.entries.firstOrNull { it.name.equals(f[2],true) }
            if(f[0]!="G" || confidence==null) { fallback+=line; null } else Guess(f[1],confidence)
        } }
        val tips=lines(SectionId.TIP).mapNotNull { line -> fields(line,3)?.let { Tip(name=it[0],explanation=it[1],example=it[2]) } }
        val proposals=mutableListOf<MemoryProposal>()
        val json=Json { ignoreUnknownKeys=true }
        bodies(SectionId.MEMORY).forEach { body ->
            try {
                val array=json.parseToJsonElement(body) as? JsonArray
                if(array==null) fallback+=body else array.forEach { element ->
                    try { proposals+=json.decodeFromString<MemoryProposal>(element.toString()) }
                    catch (_:IllegalArgumentException) { fallback+=element.toString() }
                }
            } catch (_:IllegalArgumentException) { fallback+=body }
        }
        fallback.addAll(0,bodies(SectionId.FREE_TEXT))
        // Preserve overflow in fallback instead of silently dropping model content.
        alternatives.drop(2).forEach { fallback += "${it.label}|${it.reply}" }
        val questions=lines(SectionId.ASK)
        fallback+=questions.drop(3)
        val answer=StructuredAnswer(safety=safety,scene=scene,empathy=text(SectionId.EMPATHY),verdict=text(SectionId.VERDICT),
            reply=bodies(SectionId.REPLY).flatMap { it.split("+++") }.map { it.trim() }.filter { it.isNotEmpty() },
            alternatives=alternatives.take(2),why=lines(SectionId.WHY),cost=text(SectionId.COST),situation=text(SectionId.SITUATION),
            facts=facts,guesses=guesses,unknowns=lines(SectionId.UNKNOWNS),risk=text(SectionId.RISK),tips=tips,habit=text(SectionId.HABIT),
            next=text(SectionId.NEXT),ask=questions.take(3),memoryProposals=proposals,freeText=fallback.joinToString("\n"),rawText=raw.toString(),
            sections=SectionId.entries.mapNotNull { id -> text(id).takeIf { it.isNotEmpty() }?.let { id to it } }.toMap())
        finished=answer
        return answer
    }
}
