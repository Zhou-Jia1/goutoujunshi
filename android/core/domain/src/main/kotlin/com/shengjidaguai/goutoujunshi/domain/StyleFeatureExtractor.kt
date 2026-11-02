package com.shengjidaguai.goutoujunshi.domain

import kotlinx.serialization.Serializable

@Serializable data class StyleFeatures(
    val averageSentenceLength:Double=0.0, val emojiCount:Int=0, val emojiFrequency:Double=0.0,
    val emojiMessageRatio:Double=0.0, val stickerRatio:Double=0.0,
    val particles:Map<String,Int> = emptyMap(), val punctuation:Map<String,Int> = emptyMap(),
    val usesPeriod:Boolean=false, val usesTilde:Boolean=false, val usesExclamation:Boolean=false,
    val singleMessageRatio:Double=0.0, val usesHonorific:Boolean=false,
)

object StyleFeatureExtractor {
    /** Codepoint counts include punctuation. messageGroups permits measuring single-message turns. */
    fun extract(messages:List<String>, stickerCount:Int=0, messageGroups:List<Int> = List(messages.size) { 1 }):StyleFeatures {
        require(stickerCount>=0 && messageGroups.all { it>0 })
        val nonempty=messages.filter { it.isNotBlank() }
        if(nonempty.isEmpty()) return StyleFeatures(stickerRatio=if(stickerCount>0) 1.0 else 0.0)
        val points=nonempty.flatMap { it.codePoints().toArray().toList() }
        val sentences=nonempty.flatMap { it.split(Regex("(?<=[。！？!?])")) }.filter { it.isNotBlank() }
        val emoji=points.count(::isEmoji)
        val particles=listOf("啊","呀","啦","呢","吧","哦","嘛","呗","哈","哎","嗯","哟")
            .associateWith { p -> nonempty.sumOf { s -> s.windowed(p.length).count { it==p } } }
            .filterValues { it>0 }.entries.sortedWith(compareByDescending<Map.Entry<String,Int>> { it.value }.thenBy { it.key }).take(10).associate { it.toPair() }
        val punctuation=points.filter { it in "。.!！?？～~，,、；;：:…".codePoints().toArray() }.groupingBy { String(Character.toChars(it)) }.eachCount()
        return StyleFeatures(
            averageSentenceLength=sentences.sumOf { it.codePointCount(0,it.length) }.toDouble()/sentences.size,
            emojiCount=emoji,emojiFrequency=emoji.toDouble()/points.size,
            emojiMessageRatio=nonempty.count { it.codePoints().anyMatch(::isEmoji) }.toDouble()/nonempty.size,
            stickerRatio=stickerCount.toDouble()/(nonempty.size+stickerCount),particles=particles,punctuation=punctuation,
            usesPeriod=points.any { it=='.'.code || it=='。'.code },usesTilde=points.any { it=='~'.code || it=='～'.code },
            usesExclamation=points.any { it=='!'.code || it=='！'.code },
            singleMessageRatio=if(messageGroups.isEmpty()) 0.0 else messageGroups.count { it==1 }.toDouble()/messageGroups.size,
            usesHonorific=nonempty.any { "您" in it },
        )
    }
    fun extractConfirmedUserLines(lines:List<ChatLine>):StyleFeatures = extract(lines.filter { it.speaker==Speaker.ME && it.speakerConfirmed }.map { it.text })
    private fun isEmoji(cp:Int):Boolean = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF
}

/** Local source validation projects only style; no contact facts or foreign raw messages. */
@Serializable data class StyleOutboundContext(val description:String="",val samples:List<StyleSample> = emptyList(),val revision:Long=0,val consentVersion:Long=0,val sourceAliases:List<String> = emptyList())

object StyleDeviationPolicy {
    fun addedEmoji(samples:List<StyleSample>,replies:List<String>):Boolean = samples.isNotEmpty() &&
        StyleFeatureExtractor.extract(samples.map {it.effectiveText}).emojiCount==0 && StyleFeatureExtractor.extract(replies).emojiCount>0
}
