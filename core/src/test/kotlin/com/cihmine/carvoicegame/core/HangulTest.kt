package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.hangul.Hangul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HangulTest {
    @Test
    fun `초성은 표기 기준으로 뽑는다`() {
        assertEquals("ㅅㄱ", Hangul.choseongOf("시계"))
        assertEquals("ㅅㄱ", Hangul.choseongOf("사과"))
        assertEquals("ㄲㅊ", Hangul.choseongOf("꽃차"))
        assertNull(Hangul.choseongOf("abc"))
    }

    @Test
    fun `두음법칙으로 이어 갈 수 있는 첫 글자`() {
        assertEquals(setOf('력', '역'), Hangul.startCharsAfter('력'))
        assertEquals(setOf('라', '나'), Hangul.startCharsAfter('라'))
        assertEquals(setOf('례', '예'), Hangul.startCharsAfter('례'))
        assertEquals(setOf('녀', '여'), Hangul.startCharsAfter('녀'))
        assertEquals(setOf('래', '내'), Hangul.startCharsAfter('래'))
        assertEquals(setOf('류', '유'), Hangul.startCharsAfter('류'))
        assertEquals(setOf('가'), Hangul.startCharsAfter('가'))
        assertEquals(setOf('나'), Hangul.startCharsAfter('나'))
    }

    @Test
    fun `비교용 정규화는 띄어쓰기와 문장부호를 없앤다`() {
        assertEquals("이순신장군", Words.normalize(" 이순신 장군! "))
    }
}
