package com.cihmine.carvoicegame.core.word

import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.PlayMode

/**
 * 단어 게임의 시간·힌트 규칙. 문서(docs/PLAN.md 3.1)와 코드 값이 갈라지지 않도록 여기에만 둔다.
 * 수치는 개발 기본안이며 플레이 테스트 후 바꿀 수 있다.
 */
data class RuleConfig(
    /** 주차 모드 기본 답변 시간. AI 가 말하기를 끝낸 뒤부터 잰다. */
    val parkedLimitMs: Long = 15_000,
    /** 주차 모드 오답 후 남은 시간에 더하는 시간. 안내 음성 시간은 포함하지 않는다. */
    val wrongAnswerBonusMs: Long = 5_000,
    /** 힌트 설명이 끝난 뒤 새로 주는 시간. 기본 답변 시간과 따로 설정한다. */
    val hintExtensionMs: Long = 15_000,
    val easyHints: Int = 2,
    val normalHints: Int = 1,
    val hardHints: Int = 0,
    /** 어려움 AI 가 한방 단어(사용자가 이을 단어가 없는 말)를 쓰기 시작하는 사용자 유효 답변 수 */
    val hardHanbangAfterUserWords: Int = 5,
) {
    fun hintsFor(difficulty: Difficulty): Int = when (difficulty) {
        Difficulty.EASY -> easyHints
        Difficulty.NORMAL -> normalHints
        Difficulty.HARD -> hardHints
    }

    /** 주행 모드는 기다리는 동안 자동 패배가 없도록 제한시간이 없다. */
    fun clockFor(mode: PlayMode): TurnClock = when (mode) {
        PlayMode.PARKED -> TurnClock(parkedLimitMs, parkedLimitMs)
        PlayMode.DRIVING -> TurnClock(null, null)
    }

    companion object {
        val DEFAULT = RuleConfig()
    }
}
