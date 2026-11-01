package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.word.WordEntry
import com.cihmine.carvoicegame.core.word.WordPicker
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 난이도는 AI 가 내는 단어의 어려움이 아니라 AI 의 실력(끝 글자 선택)이다. */
class WordPickerTest {
    private fun core(word: String) = WordEntry(word, 1, "뜻")
    private fun opendict(word: String, level: Int) = WordEntry(word, level, "뜻", core = false)

    /** 단어별로 사용자가 이을 수 있는 익숙한 단어 수 */
    private val followUps = mapOf("가방" to 30, "가지" to 12, "가위" to 5, "가늠" to 1)
    private val options = followUps.keys.map(::core)

    private fun pick(difficulty: Difficulty, opts: List<WordEntry> = options, seed: Int = 1) =
        WordPicker(Random(seed)).pick(opts, difficulty, userFollowUps = { followUps[it.word] ?: 0 })

    @Test
    fun `어려움은 사용자가 이을 단어가 가장 적은 끝 글자를 고른다`() {
        repeat(20) { assertEquals("가늠", pick(Difficulty.HARD, seed = it)?.word) }
    }

    @Test
    fun `쉬움은 사용자가 이을 단어가 가장 많은 끝 글자를 고른다`() {
        repeat(20) { assertEquals("가방", pick(Difficulty.EASY, seed = it)?.word) }
    }

    @Test
    fun `보통은 끝 글자를 가리지 않는다`() {
        val seen = (1..40).mapNotNull { pick(Difficulty.NORMAL, seed = it)?.word }.toSet()
        assertEquals(followUps.keys, seen)
    }

    @Test
    fun `어느 난이도든 직접 고른 일상 단어를 우리말샘 단어보다 먼저 쓴다`() {
        val mixed = listOf(opendict("가렴", 1), opendict("가거주자", 3), core("가방"))
        for (d in Difficulty.entries) repeat(10) { assertEquals("가방", pick(d, mixed, seed = it)?.word, d.name) }
    }

    @Test
    fun `쉬움 AI 는 낯선 단어를 꺼내지 않고 진다 - 보통·어려움은 낯선 단어로 버틴다`() {
        val rare = listOf(opendict("가거주자", 3))
        assertNull(pick(Difficulty.EASY, rare))
        assertEquals("가거주자", pick(Difficulty.NORMAL, rare)?.word)
        assertEquals("가거주자", pick(Difficulty.HARD, rare)?.word)
    }

    @Test
    fun `한방 단어는 다른 후보가 있으면 피한다`() {
        val picked = WordPicker(Random(1)).pick(options, Difficulty.HARD, avoid = { it.word == "가늠" }, userFollowUps = { followUps[it.word] ?: 0 })
        assertEquals("가위", picked?.word)
    }
}
