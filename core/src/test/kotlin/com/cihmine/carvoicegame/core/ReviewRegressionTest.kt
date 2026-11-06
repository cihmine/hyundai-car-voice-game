package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.word.CorrectionResult
import com.cihmine.carvoicegame.core.word.RuleConfig
import com.cihmine.carvoicegame.core.word.TickResult
import com.cihmine.carvoicegame.core.word.TurnResult
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordEntry
import com.cihmine.carvoicegame.core.word.WordGame
import com.cihmine.carvoicegame.core.word.WordGameState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 2026-11-02 codex 점검에서 나온 지적을 재현하는 테스트 */
class ReviewRegressionTest {
    private fun dict(vararg words: String) = WordDictionary(words.map { WordEntry(it, 1, "뜻") })

    private val chainDict = dict("사과", "과자", "과일", "자석", "석류", "유리", "노력", "역사")

    private fun chainGame(
        lastAiWord: String,
        dictionary: WordDictionary = chainDict,
        difficulty: Difficulty = Difficulty.EASY,
        mode: PlayMode = PlayMode.PARKED,
    ): WordGame = WordGame.restore(
        WordGameState(
            roundId = "r1", kind = GameKind.WORD_CHAIN, difficulty = difficulty, mode = mode,
            used = listOf(lastAiWord), lastAiWord = lastAiWord, hintsLeft = RuleConfig.DEFAULT.hintsFor(difficulty),
            clock = TEN_SECOND_RULES.clockFor(mode),
        ),
        dictionary, Random(1), rules = TEN_SECOND_RULES,
    ).also { it.resume() }

    @Test
    fun `힌트 제안 중 일시정지 후 재개하면 다시 힌트를 제안할 수 있다`() {
        val g = chainGame("사과")
        g.speechFinished(0)
        assertEquals(TickResult.OfferHint, g.tick(10_000))
        g.pause(11_000)
        g.resume()
        g.speechFinished(20_000) // 재개 안내가 끝남
        assertEquals(TickResult.OfferHint, g.tick(20_200), "멈춘 판으로 남지 않고 다시 힌트를 묻는다")
    }

    @Test
    fun `쉬움·보통·어려움(확인 전) 모두 한방 단어밖에 없으면 내지 않고 사용자가 이긴다`() {
        for (difficulty in Difficulty.entries.filter { it in GameKind.WORD_CHAIN.difficulties }) {
            val g = chainGame("경기", dict("경기", "기부", "부엌"), difficulty)
            val r = g.submit(listOf("기부"), 0)
            assertEquals(TurnResult.Accepted("기부", null), r, "$difficulty: 확인되지 않은 한방 단어를 냈다")
        }
    }

    @Test
    fun `주행으로 바뀐 뒤 잘못 들었어 하면 주차 제한시간이 되살아나지 않는다`() {
        val g = chainGame("사과")
        g.speechFinished(0)
        assertIs<TurnResult.Accepted>(g.submit(listOf("과자"), 1_000))
        g.changeMode(PlayMode.DRIVING)
        assertIs<CorrectionResult.Undone>(g.correctLast(2_000))
        assertFalse(g.state.clock.hasLimit, "주행 중인데 제한시간이 있다")
    }

    @Test
    fun `제한시간이 지난 뒤 들어온 답은 받지 않는다(다음 tick 에서 힌트 제안)`() {
        val g = chainGame("사과")
        g.speechFinished(0)
        assertEquals(TurnResult.NotAccepting, g.submit(listOf("과자"), 10_001))
        assertEquals(TickResult.OfferHint, g.tick(10_001))
    }

    private fun l1(word: String) = WordEntry(word, 1, "뜻", core = false)
    private fun core(word: String) = WordEntry(word, 1, "뜻")

    @Test
    fun `쉬움 AI 는 우리말샘 단어(난이도 1 포함)를 내지 않고 진다`() {
        val d = WordDictionary(listOf(core("모자"), core("자두"), l1("두렁"), core("렁렁")))
        assertEquals(TurnResult.Accepted("자두", null), chainGame("모자", d).submit(listOf("자두"), 0))
        assertIs<TurnResult.Accepted>(chainGame("모자", d, Difficulty.NORMAL).submit(listOf("자두"), 0)).also {
            assertEquals("두렁", it.aiWord, "보통은 우리말샘 단어로 버틴다")
        }
    }

    @Test
    fun `쉬움은 일상 단어로 이을 수 있는 끝 글자를 고른다(우리말샘 단어 수는 세지 않음)`() {
        // 가방 뒤에는 우리말샘 단어만 셋, 가지 뒤에는 일상 단어 하나
        val d = WordDictionary(listOf(core("모자"), core("자가"), core("가방"), core("가지"), l1("방석"), l1("방울"), l1("방패"), core("지도")))
        repeat(10) { seed ->
            val g = WordGame.restore(chainGame("모자", d).state, d, Random(seed)).also { it.resume() }
            assertEquals("가지", assertIs<TurnResult.Accepted>(g.submit(listOf("자가"), 0)).aiWord)
        }
    }

    @Test
    fun `쉬움 첫 단어는 일상 단어로 이을 수 있는 끝 글자다`() {
        val d = WordDictionary(listOf(core("가방"), core("가지"), l1("방석"), l1("방울"), core("지도"), core("도토리"), core("이불"), core("불꽃")))
        repeat(20) { seed ->
            val first = WordGame.start(GameKind.WORD_CHAIN, Difficulty.EASY, PlayMode.PARKED, d, Random(seed)).state.lastAiWord
            assertTrue(first != "가방", "seed $seed: 일상 단어로 못 잇는 첫 단어 $first")
        }
    }

    @Test
    fun `시간 안에 말을 시작했으면 결과가 늦게 와도 인정한다`() {
        val g = chainGame("사과")
        g.speechFinished(0)
        g.userSpeechStarted(9_000)
        assertIs<TurnResult.Accepted>(g.submit(listOf("과자"), 12_000))
    }
}
