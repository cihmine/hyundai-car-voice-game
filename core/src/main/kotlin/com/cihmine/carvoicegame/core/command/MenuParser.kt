package com.cihmine.carvoicegame.core.command

import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.Words

/** 처음 화면에서 들은 말의 뜻 */
sealed interface MenuChoice {
    /** 게임을 골랐다. 난이도도 말했으면 [difficulty], 아니면 null(화면에 고른 난이도를 쓴다). */
    data class Start(val kind: GameKind, val difficulty: Difficulty?) : MenuChoice

    /** 아직 준비 중인 게임(스무고개·양세찬 게임) */
    data class NotReady(val kind: GameKind) : MenuChoice

    /** "아무거나", "네가 골라" */
    data object Any : MenuChoice

    /** "이어서" — 멈춘 판 이어 하기 */
    data object Resume : MenuChoice

    /** "규칙 알려 줘", "무슨 게임 있어" */
    data object Rules : MenuChoice

    /** "나중에", "그만", "안 할래" — 지금은 안 함(마이크는 계속 켜 둔다) */
    data object Later : MenuChoice

    /** "꺼", "앱 꺼 줘", "종료" — 앱을 닫는다 */
    data object Exit : MenuChoice

    data object Unknown : MenuChoice
}

/**
 * "어떤 게임을 시작할까요?"에 대한 대답을 해석한다. 문장 안에 들어 있으면 알아듣는다("끝말잇기 하자", "초성 대결 어렵게").
 * 처음 화면은 마이크를 계속 켜 두므로, 게임 이름·정해진 표현이 없는 말은 [MenuChoice.Unknown] 으로 둔다(차 안 대화를 게임 선택으로 오인하지 않게).
 */
object MenuParser {
    private val KINDS = listOf(
        GameKind.WORD_CHAIN to listOf("끝말잇기", "끝말"),
        GameKind.CHOSEONG to listOf("초성대결", "초성게임", "초성"),
        GameKind.TWENTY_QUESTIONS to listOf("스무고개"),
        GameKind.GUESS_WHO to listOf("양세찬", "인물맞히기", "인물맞추기"),
    )
    private val DIFFICULTIES = listOf(
        Difficulty.EASY to listOf("쉬움", "쉽게", "쉬운"),
        Difficulty.NORMAL to listOf("보통"),
        Difficulty.HARD to listOf("어려움", "어렵게", "어려운"),
    )
    private val ANY = listOf("아무거나", "아무게임", "네가골라", "니가골라", "너가골라", "알아서", "추천해")
    private val RESUME = listOf("이어서", "이어하기", "이어할래", "하던거", "아까하던")
    private val LATER = listOf("나중에", "그만", "안할래", "안해", "됐어")
    private val EXIT = listOf("종료", "끝내", "앱꺼", "꺼줘", "나갈래", "나가자")
    private val RULES = listOf("규칙", "무슨게임", "뭐있어", "뭐가있어", "어떤게임있어", "게임뭐있어")

    /** 게임 이름 없이 난이도만 말했을 때("어려운 단계로 해 보자") 그 난이도 */
    fun difficultyIn(utterance: String): Difficulty? {
        val text = Words.normalize(utterance)
        return DIFFICULTIES.firstOrNull { (_, words) -> words.any { it in text } }?.first
    }

    fun parse(utterance: String): MenuChoice {
        val text = Words.normalize(utterance)
        if (text.isEmpty()) return MenuChoice.Unknown
        val kind = KINDS.firstOrNull { (_, words) -> words.any { it in text } }?.first
        if (kind != null) {
            if (!kind.isWordGame) return MenuChoice.NotReady(kind)
            val difficulty = DIFFICULTIES.firstOrNull { (_, words) -> words.any { it in text } }?.first
            return MenuChoice.Start(kind, difficulty?.takeIf { it in kind.difficulties })
        }
        return when {
            RESUME.any { it in text } -> MenuChoice.Resume
            ANY.any { it in text } -> MenuChoice.Any
            RULES.any { it in text } -> MenuChoice.Rules
            text == "꺼" || text == "꺼라" || EXIT.any { it in text } -> MenuChoice.Exit
            LATER.any { it in text } -> MenuChoice.Later
            else -> MenuChoice.Unknown
        }
    }
}
