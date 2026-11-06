package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.command.CommandParser
import com.cihmine.carvoicegame.core.hangul.Hangul
import com.cihmine.carvoicegame.core.command.ParsedUtterance
import com.cihmine.carvoicegame.core.command.SpeechRate
import com.cihmine.carvoicegame.core.command.VoiceCommand
import com.cihmine.carvoicegame.core.question.AnswerCatalog
import com.cihmine.carvoicegame.core.question.QuestionGame
import com.cihmine.carvoicegame.core.word.RuleConfig
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
    fun `자음이나 모음 하나만 다른 말을 만든다`() {
        val variants = Hangul.oneJamoVariants("구사").toSet()
        assertTrue("부사" in variants && "구시" in variants && "국사" in variants)
        assertFalse("구사" in variants)
        assertTrue(Hangul.oneJamoVariants("부").none())
        assertTrue(Hangul.oneJamoVariants("가나다라마").none())
    }

    @Test
    fun `숫자로 적힌 인식 결과를 읽는 말로 바꾼다`() {
        assertEquals(listOf("구사", "구십사"), Words.spokenForms("94"))
        assertEquals(listOf("오이", "오십이"), Words.spokenForms("52"))
        assertEquals(listOf("일공", "십"), Words.spokenForms("10"))
        assertEquals(listOf("삼기"), Words.spokenForms("3기"))
        assertEquals(listOf("사과"), Words.spokenForms("사 과"))
    }

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
    fun `정해진 문장이 아니어도 그만두는 짧은 말은 종료(실기 시험에서 '종료하자'를 단어로 오인)`() {
        val parser = CommandParser()
        val quit = ParsedUtterance.Command(VoiceCommand.QUIT)
        listOf("종료하자", "게임 끝내자", "이제 그만할게요", "그만두자").forEach { assertEquals(quit, parser.parse(it), it) }
        // 지금 차례에 맞는 단어면 답으로 본다(예: '끝내기')
        assertEquals(ParsedUtterance.NotCommand, parser.parse("끝내기", isGameAnswer = { it == "끝내기" }))
        // 긴 문장은 종료로 보지 않는다
        assertEquals(ParsedUtterance.NotCommand, parser.parse("종료 버튼이 어디 있는지 모르겠는데 알려줄래"))
    }

    @Test
    fun `일상적인 말 - 졌어·모르겠어·그래·괜찮아(실기 시험에서 단어로 오인)`() {
        val parser = CommandParser()
        fun cmd(c: VoiceCommand) = ParsedUtterance.Command(c)
        listOf("내가 졌어", "졌다", "항복", "기권할게").forEach { assertEquals(cmd(VoiceCommand.GIVE_UP), parser.parse(it), it) }
        listOf("모르겠어", "몰라", "생각이 안 나", "잘 모르겠는데").forEach { assertEquals(cmd(VoiceCommand.STUCK), parser.parse(it), it) }
        listOf("그래", "그래요", "좋아요", "응응", "넵", "해 줘").forEach { assertEquals(cmd(VoiceCommand.YES), parser.parse(it), it) }
        listOf("아니야", "괜찮아", "안 할래", "아뇨").forEach { assertEquals(cmd(VoiceCommand.NO), parser.parse(it), it) }
        assertEquals(cmd(VoiceCommand.QUIT), parser.parse("그만하자"))
        // 설정·다시 듣기(실기 시험 문장 그대로)
        assertEquals(cmd(VoiceCommand.FASTER), parser.parse("좀 더 빠르게 말해 줘"))
        assertEquals(cmd(VoiceCommand.FASTER), parser.parse("말하는 속도 높여줘요"))
        assertEquals(cmd(VoiceCommand.SLOWER), parser.parse("천천히 말해 줄래"))
        assertEquals(cmd(VoiceCommand.REPEAT), parser.parse("뭘 뭐라고 했더라"))
        assertEquals(cmd(VoiceCommand.REPEAT), parser.parse("전에 말은 단어가 뭐요?"))
        assertEquals(cmd(VoiceCommand.REPEAT), parser.parse("소리가 안 들려"))
        // 정해진 대답이 아니어도 네/아니요
        assertEquals(cmd(VoiceCommand.YES), parser.parse("또 해 보자"))
        assertEquals(cmd(VoiceCommand.YES), parser.parse("좋아 좋아"))
        assertEquals(cmd(VoiceCommand.NO), parser.parse("안 할래요"))
        assertEquals(cmd(VoiceCommand.NO), parser.parse("아니 괜찮아"))
        assertEquals(com.cihmine.carvoicegame.core.Difficulty.HARD, com.cihmine.carvoicegame.core.command.MenuParser.difficultyIn("어려운 단계로 해보자"))
        // 문장인지(단어 답으로 판정하지 않음)
        assertTrue(CommandParser.looksLikeSentence("지옥이야"))
        assertTrue(CommandParser.looksLikeSentence("이거 맞아"))
        assertFalse(CommandParser.looksLikeSentence("가자미"))
        assertFalse(CommandParser.looksLikeSentence("고등어"))
        // 지금 차례에 맞는 단어면 답으로 본다
        assertEquals(ParsedUtterance.NotCommand, parser.parse("항복", isGameAnswer = { it == "항복" }))
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

/** 시계 동작 시험용 고정 규칙. 기본값(RuleConfig.DEFAULT)이 바뀌어도 시험 내용은 그대로다. */
internal val TEN_SECOND_RULES = RuleConfig(parkedLimitMs = 10_000, hintExtensionMs = 10_000)
