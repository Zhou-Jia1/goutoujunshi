package com.shengjidaguai.goutoujunshi.knowledge
import com.shengjidaguai.goutoujunshi.domain.SafetyLevel
/** Draft vocabulary from canonical knowledge 17; owner content review remains required. */
class LocalSafetyClassifier(source:String, calmSource:String?=null) {
    private fun words(source:String)=source.lineSequence().map(String::trim).filter {it.isNotEmpty() && !it.startsWith("#")}.toList().also {require(it.isNotEmpty())}
    private val crisis=words(source)
    private val calm=calmSource?.let(::words).orEmpty()
    fun classify(text:String):SafetyLevel=when {
        crisis.any(text::contains)->SafetyLevel.CRISIS
        calm.any(text::contains)->SafetyLevel.CAUTION
        else->SafetyLevel.NONE
    }
}
/** Cache successful parsing and failures; buttons never perform asset I/O. */
class CachedLocalSafetyClassifier(loadVocabulary:()->String, loadCalm:(()->String)?=null) {
    private val classifier=runCatching {LocalSafetyClassifier(loadVocabulary(),loadCalm?.invoke())}
    fun classify(text:String):SafetyLevel=classifier.getOrThrow().classify(text)
}
