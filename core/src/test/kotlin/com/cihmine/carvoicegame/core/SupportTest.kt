package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.command.CommandParser
import com.cihmine.carvoicegame.core.command.ParsedUtterance
import com.cihmine.carvoicegame.core.command.SpeechRate
import com.cihmine.carvoicegame.core.command.VoiceCommand
import com.cihmine.carvoicegame.core.question.AnswerCatalog
import com.cihmine.carvoicegame.core.question.QuestionGame
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordGame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportTest {
    @Test
    fun `같은 판 보상은 한 번만 지급한다`() {
        val ledger = PointLedger().award("r1", 10).award("r1", 10).award("r2", 12)
        assertEquals(22, ledger.balance)
        assertEquals(22, ledger.totalEarned)
        val spent = ledger.spend(20)!!
        assertEquals(2, spent.balance)
        assertEquals(22, spent.totalEarned, "교환해도 누적 획득량은 그대로")
        assertNull(spent.spend(5))
    }

    @Test
    fun `기본 명령과 개인 표현을 해석한다`() {
        val parser = CommandParser(personal = mapOf("그만하랑께" to VoiceCommand.QUIT))
        assertEquals(ParsedUtterance.Command(VoiceCommand.PAUSE), parser.parse("잠깐 멈춰"))
        assertEquals(ParsedUtterance.Command(VoiceCommand.FASTER), parser.parse("좀 빨리 말해 줘."))
        assertEquals(ParsedUtterance.Command(VoiceCommand.QUIT), parser.parse("그만하랑께"))
        assertEquals(ParsedUtterance.SetSpeechRate(110), parser.parse("속도 110퍼센트로"))
        assertEquals(ParsedUtterance.ConfirmQuit, parser.parse("아 됐다"))
        assertEquals(ParsedUtterance.NotCommand, parser.parse("stop"), "미등록 표현은 AI 해석으로 넘긴다")
    }

    @Test
    fun `게임 답변이면 명령어처럼 들려도 답변으로 본다`() {
        val parser = CommandParser()
        assertEquals(ParsedUtterance.Command(VoiceCommand.GIVE_UP), parser.parse("포기"))
        assertEquals(ParsedUtterance.NotCommand, parser.parse("포기", isGameAnswer = { it == "포기" }))
    }

    @Test
    fun `안내 속도는 범위 안에서 조절한다`() {
        assertEquals(120, SpeechRate.faster(100))
        assertEquals(200, SpeechRate.faster(195))
        assertEquals(50, SpeechRate.set(10))
    }

    @Test
    fun `난이도 제안 - 이기면 위, 지면 아래, 끝 단계면 재시작만`() {
        assertEquals(Difficulty.NORMAL, suggestNextDifficulty(GameKind.WORD_CHAIN, Difficulty.EASY, Outcome.WIN))
        assertEquals(Difficulty.EASY, suggestNextDifficulty(GameKind.WORD_CHAIN, Difficulty.NORMAL, Outcome.LOSE))
        assertNull(suggestNextDifficulty(GameKind.WORD_CHAIN, Difficulty.HARD, Outcome.WIN))
        assertNull(suggestNextDifficulty(GameKind.WORD_CHAIN, Difficulty.EASY, Outcome.LOSE))
    }

    @Test
    fun `샘플 사전 - 힌트 뜻에 단어가 들어가지 않고 초성 대결 출제가 가능하다`() {
        val dict = WordDictionary.loadSample()
        assertTrue(dict.size >= 90)
        dict.entries.forEach { assertFalse(it.meaning.contains(it.word), "힌트가 정답을 말함: ${it.word}") }
        assertTrue(dict.choseongPairs(WordGame.MIN_CHOSEONG_POOL).containsAll(listOf("ㅅㄱ", "ㄱㅂ", "ㅂㄹ")))
    }

    @Test
    fun `샘플 데이터로 네 게임 모두 시작할 수 있다`() {
        val dict = WordDictionary.loadSample()
        val catalog = AnswerCatalog.loadSample()
        repeat(30) { seed ->
            for (difficulty in Difficulty.entries) {
                WordGame.start(GameKind.WORD_CHAIN, difficulty, PlayMode.PARKED, dict, Random(seed))
                WordGame.start(GameKind.CHOSEONG, difficulty, PlayMode.DRIVING, dict, Random(seed))
            }
            for (difficulty in listOf(Difficulty.EASY, Difficulty.HARD)) {
                QuestionGame.start(GameKind.TWENTY_QUESTIONS, difficulty, catalog, Random(seed))
                QuestionGame.start(GameKind.GUESS_WHO, difficulty, catalog, Random(seed))
            }
        }
    }
}
