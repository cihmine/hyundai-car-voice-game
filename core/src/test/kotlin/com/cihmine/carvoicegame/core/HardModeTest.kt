package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.save.SaveCodec
import com.cihmine.carvoicegame.core.save.SaveData
import com.cihmine.carvoicegame.core.word.RuleConfig
import com.cihmine.carvoicegame.core.word.TurnResult
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordEntry
import com.cihmine.carvoicegame.core.word.WordGame
import com.cihmine.carvoicegame.core.word.WordGameState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 어려움: 인터넷 검색으로 버티고, 확인된 한방 단어를 쓴다. 보통·쉬움은 한방을 쓰지 않는다. */
class HardModeTest {
    /** 가방 뒤에 AI 후보는 방석(이어짐)·방릇(릇 으로 이을 단어 없음 = 한방) */
    private val dict = WordDictionary(listOf("가방", "방석", "방릇", "석류", "류머티즘").map { WordEntry(it, 1, "뜻") })

    private fun game(difficulty: Difficulty, validUserWords: Int = 4, lastAiWord: String = "고가", dictionary: WordDictionary = dict) =
        WordGame.restore(
            WordGameState(
                roundId = "r", kind = GameKind.WORD_CHAIN, difficulty = difficulty, mode = PlayMode.DRIVING,
                used = listOf(lastAiWord), lastAiWord = lastAiWord, hintsLeft = 0,
                clock = RuleConfig.DEFAULT.clockFor(PlayMode.DRIVING), validUserWords = validUserWords,
            ),
            dictionary, Random(1),
        ).also { it.resume() }

    private fun aiWord(r: TurnResult) = assertIs<TurnResult.Accepted>(r).aiWord

    @Test
    fun `어려움은 인터넷으로 확인한 한방 단어를 쓴다`() {
        val g = game(Difficulty.HARD)
        assertEquals(listOf("방릇"), g.hanbangCandidates("가방"))
        g.setVerifiedDeadEnds(setOf("방릇"))
        assertEquals("방릇", aiWord(g.submit(listOf("가방"), 0)))
        assertTrue(g.state.lastAiHanbang)
    }

    @Test
    fun `확인하지 못한 한방(오프라인)은 쓰지 않는다`() {
        repeat(10) {
            val g = game(Difficulty.HARD)
            assertEquals("방석", aiWord(g.submit(listOf("가방"), 0)))
            assertEquals(false, g.state.lastAiHanbang)
        }
    }

    @Test
    fun `어려움도 사용자 답 5개 전에는 한방을 쓰지 않는다`() {
        val g = game(Difficulty.HARD, validUserWords = 3) // 이번 답이 4번째
        assertEquals(emptyList(), g.hanbangCandidates("가방"))
        g.setVerifiedDeadEnds(setOf("방릇"))
        assertEquals("방석", aiWord(g.submit(listOf("가방"), 0)))
    }

    @Test
    fun `보통과 쉬움은 한방을 쓰지 않는다`() {
        for (d in listOf(Difficulty.NORMAL, Difficulty.EASY)) {
            val g = game(d)
            assertEquals(emptyList(), g.hanbangCandidates("가방"))
            g.setVerifiedDeadEnds(setOf("방릇"))
            assertEquals("방석", aiWord(g.submit(listOf("가방"), 0)), d.name)
        }
    }

    @Test
    fun `낯선 단어는 한방으로 쓰지 않는다`() {
        val rare = WordDictionary(listOf(WordEntry("가방", 1, "뜻"), WordEntry("방석", 1, "뜻"), WordEntry("석류", 1, "뜻"), WordEntry("류머티즘", 1, "뜻"), WordEntry("방릇", 3, "뜻", core = false)))
        val g = game(Difficulty.HARD, dictionary = rare)
        assertEquals(emptyList(), g.hanbangCandidates("가방"))
    }

    @Test
    fun `사전에 없는 사용자 단어를 인터넷에서 찾으면 인정한다`() {
        val g = game(Difficulty.NORMAL, lastAiWord = "침출")
        assertEquals("출발", g.wordToLookUp(listOf("출발")))
        g.learn(listOf(WordEntry("출발", 2, "길을 떠남"), WordEntry("발바닥", 2, "발의 아래쪽")))
        assertNull(g.wordToLookUp(listOf("출발")))
        assertEquals("발바닥", aiWord(g.submit(listOf("출발"), 0))) // 보통은 인터넷에서 찾은 낯선 단어로도 잇는다
        assertTrue(g.state.learned.any { it.word == "출발" })
    }

    @Test
    fun `시작 글자가 틀린 말은 인터넷으로 찾지 않는다`() {
        val g = game(Difficulty.NORMAL, lastAiWord = "침출")
        assertNull(g.wordToLookUp(listOf("자동차")))
    }

    @Test
    fun `어려움은 앱 사전에서 막히면 찾아볼 첫 글자를 알려 준다 - 보통은 그대로 진다`() {
        val stuck = WordDictionary(listOf(WordEntry("가방", 1, "뜻")))
        assertEquals(setOf('방'), game(Difficulty.HARD, dictionary = stuck).aiSearchHeads("가방"))
        assertNull(game(Difficulty.NORMAL, dictionary = stuck).aiSearchHeads("가방"))

        val g = game(Difficulty.HARD, dictionary = stuck)
        g.learn(listOf(WordEntry("방울", 2, "뜻"), WordEntry("울타리", 2, "뜻")))
        assertEquals("방울", aiWord(g.submit(listOf("가방"), 0)))
    }

    @Test
    fun `인터넷에서 찾은 단어는 저장 후 복원해도 남는다`() {
        val g = game(Difficulty.NORMAL, lastAiWord = "침출")
        g.learn(listOf(WordEntry("출발", 2, "길을 떠남")))
        val restored = WordGame.restore(SaveCodec.decode(SaveCodec.encode(SaveData(wordGame = g.snapshot(0)))).wordGame!!, dict, Random(1))
        restored.resume()
        assertIs<TurnResult.Accepted>(restored.submit(listOf("출발"), 0))
    }

    @Test
    fun `잘못 들었어로 한방 차례를 되돌리면 한방 표시도 돌아간다`() {
        val g = game(Difficulty.HARD)
        g.setVerifiedDeadEnds(setOf("방릇"))
        g.submit(listOf("가방"), 0)
        g.correctLast(0)
        assertEquals(false, g.state.lastAiHanbang)
    }
}
