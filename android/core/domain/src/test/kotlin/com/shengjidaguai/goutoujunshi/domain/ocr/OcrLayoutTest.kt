package com.shengjidaguai.goutoujunshi.domain.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrLayoutTest {
    @Test fun actualSyntheticGroupDetectorBoxesAttachNamesWithoutHeightAssumption() {
        fun box(t:String,l:Int,r:Int,top:Int,bottom:Int)=OcrLine(t,.99f,l/720f,top/1280f,r/720f,bottom/1280f)
        val lines=listOf(box("小林",77,142,183,222),box("明天一起讨论方案",97,375,239,281),
            box("小周",80,138,485,520),box("请把重点列出来",98,342,540,578),
            box("小林",79,140,785,820),box("谢谢你的帮助",98,305,841,876))
        val result=OcrLayout.attachNicknames(lines,720f/1280)
        assertEquals(listOf("小林","小周","小林"),listOf(result[1].nickname,result[3].nickname,result[5].nickname))
        assertTrue(listOf(result[0],result[2],result[4]).all { it.suggestedUi })
        assertTrue(listOf(result[1],result[3],result[5]).none { it.suggestedUi })
        assertTrue(OcrLayout.isUi(box("今天09:30",267,392,157,189)))
    }
    @Test fun shortRealMessagesAndMultilineBodyAreNotNicknameSuggestions() {
        fun box(t:String,l:Int,r:Int,top:Int,bottom:Int)=OcrLine(t,.99f,l/720f,top/1280f,r/720f,bottom/1280f)
        for(lines in listOf(
            listOf(box("好的",98,160,240,278),box("我先整理材料",98,350,300,338)),
            listOf(box("继续",98,160,240,278),box("我们一起讨论",118,380,360,398)),
            listOf(box("OK",98,140,240,278),box("我先整理材料",102,350,290,328)))) {
            val result=OcrLayout.attachNicknames(lines,720f/1280)
            assertTrue(result.all { it.nickname==null && !it.suggestedUi })
        }
    }
    @Test fun tilesCoverEveryOriginalRowWithOverlap() {
        val tiles = OcrLayout.tiles(9000)
        assertEquals(0, tiles.first().top)
        assertEquals(9000, tiles.last().bottom)
        assertTrue(tiles.zipWithNext().all { (a,b) -> a.bottom > b.top })
        for (y in 0 until 9000) assertEquals(1, tiles.count { it.owns(y.toFloat()) })
    }
    @Test fun oddOverlapStillOwnsEachRowExactlyOnce() {
        val tiles=OcrLayout.tiles(99,15,3)
        for(y in 0 until 99) assertEquals(1,tiles.count { it.owns(y.toFloat()) })
    }
    @Test fun sameTextFromDifferentOrUnknownGroupParticipantIsNotOverlap() {
        val a=line("合成收到").copy(nickname="合成甲")
        val b=a.copy(nickname="合成乙")
        assertEquals(2,OcrLayout.join(listOf(listOf(a),listOf(b))).size)
        assertEquals(2,OcrLayout.join(listOf(listOf(a),listOf(a.copy(nickname=null)))).size)
        assertEquals(1,OcrLayout.join(listOf(listOf(a),listOf(a))).size)
    }
    @Test fun dedupOnlyAdjacentImagesNeverSameImageRepetitions() {
        val a = listOf(line("甲"), line("重复"), line("重复"))
        val b = listOf(line("重复"), line("重复"), line("乙"))
        assertEquals(listOf("甲","重复","重复","乙"), OcrLayout.join(listOf(a,b)).map { it.text })
        assertEquals(3, OcrLayout.join(listOf(a)).size)
    }
    @Test fun uiSuggestionsAreRecoverableAndMessageTimeIsNotBlanketRemoved() {
        assertTrue(OcrLayout.isUi(line("正在输入…", .5f)))
        assertTrue(OcrLayout.isUi(line("对方正在输入…", .5f)))
        assertTrue(OcrLayout.isUi(line("12:30", .5f)))
        assertFalse(OcrLayout.isUi(line("12:30", .8f)))
        assertTrue(line("核对", confidence=.4f).lowConfidence)
    }
    private fun line(text:String, x:Float=.2f, confidence:Float=.99f) = OcrLine(text,confidence,x,.4f,x+.1f,.45f)
}
