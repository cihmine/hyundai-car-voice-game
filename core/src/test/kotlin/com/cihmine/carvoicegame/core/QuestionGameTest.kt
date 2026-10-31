package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.question.AnswerCatalog
import com.cihmine.carvoicegame.core.question.AnswerItem
import com.cihmine.carvoicegame.core.question.AttemptResult
import com.cihmine.carvoicegame.core.question.QuestionGame
import com.cihmine.carvoicegame.core.question.QuestionPhase
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuestionGameTest {
    private val catalog = AnswerCatalog(
        version = 3,
        items = listOf(
            AnswerItem("p1", "이순신", AnswerCatalog.PERSON, Difficulty.EASY, aliases = listOf("충무공")),
            AnswerItem("p2", "홍길동", AnswerCatalog.PERSON, Difficulty.EASY, disabled = true),
            AnswerItem("a1", "기린", "동물", Difficulty.EASY),
        ),
    )

    private fun guessWho() = QuestionGame.start(GameKind.GUESS_WHO, Difficulty.EASY, catalog, Random(1))

    @Test
    fun `양세찬 게임은 출제 중인 인물만 고른다`() {
        repeat(20) { seed ->
            val game = QuestionGame.start(GameKind.GUESS_WHO, Difficulty.EASY, catalog, Random(seed))
            assertEquals("이순신", game.state.answer.name)
        }
        val twenty = QuestionGame.start(GameKind.TWENTY_QUESTIONS, Difficulty.EASY, catalog, Random(1))
        assertEquals("기린", twenty.state.answer.name)
        assertEquals(3, twenty.state.catalogVersion)
    }

    @Test
    fun `질문과 추측을 각각 1회로 세고 별칭과 띄어쓰기를 허용한다`() {
        val game = guessWho()
        assertEquals(AttemptResult.Counted(1, false), game.ask())
        assertEquals(AttemptResult.Wrong(2, false), game.guess("세종대왕"))
        assertEquals(AttemptResult.Correct(3, Outcome.WIN), game.guess("충 무 공"))
        assertEquals(40, RewardPolicy.forResult(GameKind.GUESS_WHO, Difficulty.EASY, Outcome.WIN, 3))
    }

    @Test
    fun `20회를 다 쓰면 연장을 묻고, 연장 뒤 성공은 보상 없음`() {
        val game = guessWho()
        repeat(19) { game.ask() }
        assertEquals(AttemptResult.Counted(20, true), game.ask())
        assertEquals(QuestionPhase.OFFER_EXTENSION, game.state.phase)
        assertEquals(AttemptResult.NotAccepting, game.ask(), "연장 동의 전에는 진행하지 않는다")

        game.acceptExtension()
        assertEquals(AttemptResult.Correct(21, Outcome.EXTENDED_WIN), game.guess("이순신"))
        assertEquals(0, RewardPolicy.forResult(GameKind.GUESS_WHO, Difficulty.EASY, Outcome.EXTENDED_WIN, 21))
        assertEquals(null, suggestNextDifficulty(GameKind.GUESS_WHO, Difficulty.EASY, Outcome.EXTENDED_WIN))
    }

    @Test
    fun `20번째가 추측 실패여도 연장을 묻는다`() {
        val game = guessWho()
        repeat(19) { game.ask() }
        assertEquals(AttemptResult.Wrong(20, true), game.guess("기린"))
    }

    @Test
    fun `정답 공개를 요청하면 알려 주고 무보상으로 끝낸다`() {
        val game = guessWho()
        game.ask()
        assertEquals("이순신", game.reveal())
        assertEquals(Outcome.REVEALED, game.state.outcome)
        assertEquals(0, RewardPolicy.forResult(GameKind.GUESS_WHO, Difficulty.EASY, Outcome.REVEALED, 1))
    }

    @Test
    fun `스무고개 계열은 쉬움과 어려움만 있다`() {
        assertFailsWith<IllegalArgumentException> {
            QuestionGame.start(GameKind.TWENTY_QUESTIONS, Difficulty.NORMAL, catalog, Random(1))
        }
        assertEquals(Difficulty.HARD, suggestNextDifficulty(GameKind.TWENTY_QUESTIONS, Difficulty.EASY, Outcome.WIN))
    }

    @Test
    fun `일시정지 중 질문은 세지 않는다`() {
        val game = guessWho()
        game.pause()
        assertEquals(AttemptResult.NotAccepting, game.ask())
        game.resume()
        assertEquals(AttemptResult.Counted(1, false), game.ask())
    }

    @Test
    fun `보상 구간 - 적게 쓸수록 많이, 어려움은 조금 더`() {
        assertEquals(
            listOf(40, 30, 20, 10, 0),
            listOf(5, 6, 15, 20, 21).map { RewardPolicy.questionSuccess(it, Difficulty.EASY) },
        )
        assertTrue(RewardPolicy.questionSuccess(3, Difficulty.HARD) > RewardPolicy.questionSuccess(3, Difficulty.EASY))
    }
}
