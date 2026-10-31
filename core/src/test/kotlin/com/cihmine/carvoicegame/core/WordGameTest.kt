package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.hangul.Hangul
import com.cihmine.carvoicegame.core.save.SaveCodec
import com.cihmine.carvoicegame.core.save.SaveData
import com.cihmine.carvoicegame.core.word.HintResult
import com.cihmine.carvoicegame.core.word.RejectReason
import com.cihmine.carvoicegame.core.word.TickResult
import com.cihmine.carvoicegame.core.word.TurnClock
import com.cihmine.carvoicegame.core.word.TurnResult
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordEntry
import com.cihmine.carvoicegame.core.word.WordGame
import com.cihmine.carvoicegame.core.word.WordGameState
import com.cihmine.carvoicegame.core.word.WordPhase
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordGameTest {
    private fun dict(vararg words: String) = WordDictionary(words.map { WordEntry(it, 1, "뜻") })

    private val chainDict = dict("사과", "과자", "과일", "자석", "석류", "유리", "노력", "역사")

    /** AI 가 [lastAiWord] 를 말한 직후의 사용자 차례 */
    private fun chainGame(
        lastAiWord: String,
        used: List<String> = listOf(lastAiWord),
        mode: PlayMode = PlayMode.PARKED,
        difficulty: Difficulty = Difficulty.EASY,
        dictionary: WordDictionary = chainDict,
        seed: Int = 1,
    ): WordGame = WordGame.restore(
        WordGameState(
            roundId = "r1", kind = GameKind.WORD_CHAIN, difficulty = difficulty, mode = mode,
            used = used, lastAiWord = lastAiWord, hintsLeft = WordGame.hintsFor(difficulty),
            clock = TurnClock.forMode(mode),
        ),
        dictionary, Random(seed),
    ).also { it.resume() }

    @Test
    fun `맞는 단어를 인정하고 AI 가 이어 말한다`() {
        val game = chainGame("사과")
        val result = game.submit(listOf("과자"), now = 0)
        assertEquals(TurnResult.Accepted("과자", "자석"), result)
        assertEquals(listOf("사과", "과자", "자석"), game.state.used)
        assertEquals(1, game.state.validUserWords)
    }

    @Test
    fun `두음법칙 단어를 허용한다`() {
        val game = chainGame("노력")
        assertIs<TurnResult.Accepted>(game.submit(listOf("역사"), now = 0))
    }

    @Test
    fun `이미 나온 단어는 오답이고 주차 모드면 남은 시간에 5초를 더한다`() {
        val game = chainGame("사과", used = listOf("과자", "사과"))
        game.speechFinished(0)
        val result = game.submit(listOf("과자"), now = 4_000)
        assertEquals(TurnResult.Rejected("과자", RejectReason.ALREADY_USED), result)
        assertEquals(11_000, game.state.clock.remaining(4_000))
        assertTrue(!game.state.clock.isRunning, "설명하는 동안 타이머는 멈춘다")
        assertNull(game.state.outcome, "오답만으로 지지 않는다")
    }

    @Test
    fun `끝 글자로 시작하지 않으면 오답`() {
        val game = chainGame("사과")
        assertEquals(TurnResult.Rejected("유리", RejectReason.WRONG_START), game.submit(listOf("유리"), 0))
    }

    @Test
    fun `n-best 후보 중 맞는 단어를 인정한다`() {
        val game = chainGame("사과")
        val result = game.submit(listOf("과짜", "과자"), now = 0)
        assertEquals("과자", (result as TurnResult.Accepted).userWord)
    }

    @Test
    fun `사전에 없는 말은 먼저 재확인하고 같은 말이 또 오면 오답`() {
        val game = chainGame("사과")
        game.speechFinished(0)
        assertEquals(TurnResult.Reconfirm("과짜"), game.submit(listOf("과짜"), now = 3_000))
        assertEquals(7_000, game.state.clock.remaining(9_000), "재확인 중에는 시간이 줄지 않는다")
        game.speechFinished(9_000)
        val second = game.submit(listOf("과짜"), now = 10_000)
        assertEquals(TurnResult.Rejected("과짜", RejectReason.NOT_IN_DICTIONARY), second)
    }

    @Test
    fun `AI 가 이을 단어가 없으면 사용자가 이긴다`() {
        val game = chainGame("노사", dictionary = dict("사과", "노력"))
        assertEquals(TurnResult.Accepted("사과", null), game.submit(listOf("사과"), 0))
        assertEquals(Outcome.WIN, game.state.outcome)
    }

    @Test
    fun `AI 는 한방 단어를 가급적 피한다`() {
        repeat(20) { seed ->
            val game = chainGame("노사", seed = seed)
            val result = game.submit(listOf("사과"), 0) as TurnResult.Accepted
            assertEquals("과자", result.aiWord, "과일은 이어 갈 단어가 없는 한방 단어")
        }
    }

    @Test
    fun `주차 모드 시간 초과 - 힌트가 남으면 힌트를 묻고, 없으면 패배`() {
        val game = chainGame("사과", difficulty = Difficulty.NORMAL)
        game.speechFinished(0)
        assertEquals(TickResult.None, game.tick(9_999))
        assertEquals(TickResult.OfferHint, game.tick(10_000))
        assertEquals(WordPhase.OFFER_HINT, game.state.phase)

        val hint = game.requestHint(10_500)
        assertEquals(HintResult.Hint("뜻", 0), hint)
        game.speechFinished(12_000)
        assertEquals(TickResult.None, game.tick(21_999), "힌트 설명이 끝난 뒤 새로 10초")
        assertEquals(TickResult.TimeUp, game.tick(22_000))
        assertEquals(Outcome.LOSE, game.state.outcome)
    }

    @Test
    fun `힌트 제안을 거절하면 패배`() {
        val game = chainGame("사과")
        game.speechFinished(0)
        game.tick(10_000)
        game.declineHint(10_100)
        assertEquals(Outcome.LOSE, game.state.outcome)
    }

    @Test
    fun `주행 모드는 시간제한이 없다`() {
        val game = chainGame("사과", mode = PlayMode.DRIVING)
        game.speechFinished(0)
        assertEquals(TickResult.None, game.tick(10_000_000))
        assertNull(game.state.outcome)
    }

    @Test
    fun `시간 안에 말하기 시작했으면 인식 지연을 시간 초과로 보지 않는다`() {
        val game = chainGame("사과")
        game.speechFinished(0)
        game.userSpeechStarted(9_000)
        assertEquals(TickResult.None, game.tick(15_000))
        assertIs<TurnResult.Accepted>(game.submit(listOf("과자"), 15_000))
    }

    @Test
    fun `모드를 바꾸면 사용 단어와 힌트는 유지하고 주차로 바꾸면 새로 10초`() {
        val game = chainGame("사과", mode = PlayMode.DRIVING)
        game.changeMode(PlayMode.PARKED, now = 50_000)
        assertEquals(listOf("사과"), game.state.used)
        game.speechFinished(51_000)
        assertEquals(TickResult.None, game.tick(60_999))
        assertEquals(TickResult.OfferHint, game.tick(61_000))
    }

    @Test
    fun `저장 후 복구하면 남은 시간이 그대로이고 꺼져 있던 시간은 쓰지 않는다`() {
        val game = chainGame("사과")
        game.speechFinished(0)
        val saved = SaveCodec.encode(SaveData(wordGame = game.snapshot(now = 4_000)))

        val restored = WordGame.restore(SaveCodec.decode(saved).wordGame!!, chainDict, Random(1))
        assertTrue(restored.state.paused, "복구 직후에는 재개 안내 전까지 일시정지")
        assertEquals(TurnResult.NotAccepting, restored.submit(listOf("과자"), 100_000))
        restored.resume()
        restored.speechFinished(100_000)
        assertEquals(3_000, restored.state.clock.remaining(103_000))
    }

    @Test
    fun `일시정지 중에는 시간이 흐르지 않는다`() {
        val game = chainGame("사과")
        game.speechFinished(0)
        game.pause(2_000)
        assertEquals(TickResult.None, game.tick(60_000))
        game.resume()
        game.speechFinished(60_000)
        assertEquals(8_000, game.state.clock.remaining(60_000))
    }

    @Test
    fun `무효 처리한 판은 VOID 로 끝난다`() {
        val game = chainGame("사과")
        game.voidRound(0)
        assertEquals(Outcome.VOID, game.state.outcome)
        assertEquals(TurnResult.NotAccepting, game.submit(listOf("과자"), 0))
    }

    // ── 초성 대결 ──

    private val choseongDict = dict("사과", "수건", "소금", "시계", "시간", "생각", "사자", "과자")

    private fun choseongGame(): WordGame = WordGame.restore(
        WordGameState(
            roundId = "c1", kind = GameKind.CHOSEONG, difficulty = Difficulty.EASY, mode = PlayMode.DRIVING,
            choseong = "ㅅㄱ", used = listOf("사과"), lastAiWord = "사과", hintsLeft = 2,
            clock = TurnClock.forMode(PlayMode.DRIVING),
        ),
        choseongDict, Random(1),
    ).also { it.resume() }

    @Test
    fun `초성 대결은 제시한 초성을 판 내내 유지한다`() {
        val game = choseongGame()
        val result = game.submit(listOf("수건"), 0) as TurnResult.Accepted
        assertEquals("ㅅㄱ", Hangul.choseongOf(result.aiWord!!))
        assertEquals("ㅅㄱ", game.state.choseong)
    }

    @Test
    fun `초성은 표기 기준 - 동음이표기는 n-best 에서 사전과 초성이 맞는 후보를 인정`() {
        val game = choseongGame()
        val result = game.submit(listOf("시게", "시계"), 0)
        assertEquals("시계", (result as TurnResult.Accepted).userWord)
    }

    @Test
    fun `사전에 있지만 초성이 다르면 오답`() {
        val game = choseongGame()
        assertEquals(TurnResult.Rejected("사자", RejectReason.WRONG_CHOSEONG), game.submit(listOf("사자"), 0))
    }

    @Test
    fun `새 초성 대결은 후보가 충분한 초성을 내고 AI 가 먼저 말한다`() {
        val game = WordGame.start(GameKind.CHOSEONG, Difficulty.EASY, PlayMode.PARKED, choseongDict, Random(3))
        assertEquals("ㅅㄱ", game.state.choseong)
        assertEquals("ㅅㄱ", Hangul.choseongOf(game.state.lastAiWord!!))
        assertEquals(listOf(game.state.lastAiWord), game.state.used)
    }
}
