package com.shengjidaguai.goutoujunshi.knowledge
import com.shengjidaguai.goutoujunshi.domain.SafetyLevel
/** Shared T52/T53 local gate. Vocabulary asset is a draft requiring owner review. */
class LocalSafetyClassifier(source:String) {
    private val crisis=source.lineSequence().map(String::trim).filter {it.isNotEmpty() && !it.startsWith("#")}.toList().also {require(it.isNotEmpty())}
    fun classify(text:String):SafetyLevel=if(crisis.any(text::contains)) SafetyLevel.CRISIS else SafetyLevel.NONE
}

/** Cache either a parsed vocabulary or its loading failure; classification performs no I/O. */
class CachedLocalSafetyClassifier(loadVocabulary:()->String) {
    private val classifier=runCatching {LocalSafetyClassifier(loadVocabulary())}
    fun classify(text:String):SafetyLevel=classifier.getOrThrow().classify(text)
}
