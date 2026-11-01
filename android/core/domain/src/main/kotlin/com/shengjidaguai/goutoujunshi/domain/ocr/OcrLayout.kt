package com.shengjidaguai.goutoujunshi.domain.ocr

enum class OcrSide { LEFT, RIGHT, UNKNOWN }
data class OcrLine(val text:String, val confidence:Float, val left:Float, val top:Float, val right:Float, val bottom:Float, val suggestedUi:Boolean=false, val nickname:String?=null, val suggestedSide:OcrSide?=null) {
    val side get() = suggestedSide ?: when { (left+right)/2 < .46f -> OcrSide.LEFT; (left+right)/2 > .54f -> OcrSide.RIGHT; else -> OcrSide.UNKNOWN }
    val lowConfidence get() = confidence < .8f
}
data class OcrTile(val top:Int,val bottom:Int,val ownedTop:Int,val ownedBottom:Int) {
    fun owns(y:Float) = y >= ownedTop && y < ownedBottom
}
/** Pure geometry policy; uncertain filtering is a reversible suggestion in the preview. */
object OcrLayout {
    /** OCR detector boxes can give nicknames the same height as body text. Use adjacency,
     * relative indentation and width, never font height alone. Every removal stays reversible. */
    fun attachNicknames(lines:List<OcrLine>,aspectRatio:Float):List<OcrLine> {
        val names=mutableMapOf<Int,OcrLine>()
        lines.forEachIndexed { index,line ->
            val next=lines.getOrNull(index+1) ?: return@forEachIndexed
            val shortName=line.text.trim().let { t -> t.length in 1..12 && t.all { it.isLetterOrDigit() || it in "_· .-" } }
            if(!isUi(line) && !isUi(next) && shortName && line.side==OcrSide.LEFT && next.side==OcrSide.LEFT &&
                next.left-line.left in .012f.. .10f && next.top-line.bottom in 0f..(.05f*aspectRatio) &&
                line.right-line.left < (next.right-next.left)*.65f) names[index+1]=line
        }
        return lines.mapIndexed { index,line -> line.copy(nickname=names[index]?.text,
            confidence=minOf(line.confidence,names[index]?.confidence ?: line.confidence),
            suggestedUi=line.suggestedUi || index+1 in names) }
    }
    fun tiles(height:Int, size:Int=1600, overlap:Int=200):List<OcrTile> {
        require(height>0 && size>overlap && overlap>=0)
        val starts=generateSequence(0) { if(it+size>=height) null else it+size-overlap }.toList()
        return starts.mapIndexed { i,y -> OcrTile(y,minOf(y+size,height),if(i==0) 0 else y+overlap/2,
            if(i==starts.lastIndex) height else y+size-overlap+overlap/2) }
    }
    fun isUi(line:OcrLine):Boolean {
        val t=line.text.trim()
        val center=(line.left+line.right)/2
        return line.suggestedUi || line.top<.035f || line.bottom>.97f ||
            (center in .36f.. .64f && (t.matches(Regex("(?:昨天|今天|星期[一二三四五六日天])?\\s*\\d{1,2}:\\d{2}")) ||
                t.matches(Regex(".{0,16}正在输入[.…]*")) || t in setOf("昨天", "今天", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "正在输入", "正在输入…", "正在输入...", "按住说话", "发送", "输入消息")))
    }
    /** Unknown/known or conflicting group identities are not evidence of an overlapping message. */
    fun sameMessage(aText:String,aSide:OcrSide?,aName:String?,bText:String,bSide:OcrSide?,bName:String?):Boolean =
        aText.trim()==bText.trim() && aSide==bSide && aName.orEmpty().trim()==bName.orEmpty().trim()
    fun <T> adjacentOverlap(previous:List<T>,next:List<T>,same:(T,T)->Boolean):Int =
        (1..minOf(previous.size,next.size)).lastOrNull { n -> previous.takeLast(n).zip(next.take(n)).all { (a,b) -> same(a,b) } } ?: 0
    fun join(images:List<List<OcrLine>>):List<OcrLine> {
        val result=mutableListOf<OcrLine>()
        var previous=emptyList<OcrLine>()
        images.forEach { image ->
            val overlap=adjacentOverlap(previous,image) { a,b -> sameMessage(a.text,a.side,a.nickname,b.text,b.side,b.nickname) }
            result += image.drop(overlap); previous=image
        }
        return result
    }
}
