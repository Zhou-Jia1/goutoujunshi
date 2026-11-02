package com.shengjidaguai.goutoujunshi.domain

import org.junit.Assert.*
import org.junit.Test

class T41StyleDeviationTest {
    @Test fun addedEmojiNoticeUsesActualSelectedExamplesAndExplicitUserEdits() {
        val samples=listOf("好呀","嗯，我们明天聊","谢谢你帮忙").mapIndexed {index,text->StyleSample(id="$index",text=text)}
        assertTrue(StyleDeviationPolicy.addedEmoji(samples,listOf("好呀🙂")))
        assertFalse(StyleDeviationPolicy.addedEmoji(samples,listOf("好呀")))
        assertFalse(StyleDeviationPolicy.addedEmoji(emptyList(),listOf("好呀🙂")))
        val edited=samples.first().copy(userEdit=UserStyleSampleEdit("本人已经采用🙂","ui-only",1,1,1))
        assertFalse(StyleDeviationPolicy.addedEmoji(listOf(edited)+samples.drop(1),listOf("好呀🙂")))
    }
}
