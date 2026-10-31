package com.cihmine.carvoicegame.core

import kotlinx.serialization.Serializable

@Serializable
enum class GameKind(val label: String) {
    WORD_CHAIN("끝말잇기"),
    CHOSEONG("초성 대결"),
    TWENTY_QUESTIONS("스무고개"),
    GUESS_WHO("양세찬 게임");

    val isWordGame: Boolean get() = this == WORD_CHAIN || this == CHOSEONG

    /** 끝말잇기·초성 대결은 3단계, 스무고개 계열은 쉬움·어려움 2단계 */
    val difficulties: List<Difficulty>
        get() = if (isWordGame) Difficulty.entries else listOf(Difficulty.EASY, Difficulty.HARD)
}

@Serializable
enum class Difficulty(val label: String) {
    EASY("쉬움"),
    NORMAL("보통"),
    HARD("어려움"),
}

/** 한 판 동안 유지되는 게임 모드. 실제 차량 주행 제한(CarUxRestrictions)과는 별개다. */
@Serializable
enum class PlayMode { DRIVING, PARKED }

@Serializable
enum class Outcome {
    WIN,
    LOSE,

    /** 난이도·게임 변경, 종료 명령으로 무효 처리. 통계·보상 제외 */
    VOID,

    /** 스무고개 계열에서 20회를 넘겨 맞힘. 보상 없음 */
    EXTENDED_WIN,

    /** 스무고개 계열에서 정답 공개·포기 */
    REVEALED,
}

/** 승리하면 한 단계 위, 패배하면 한 단계 아래를 제안한다. 끝 단계면 null(재시작만 묻는다). */
fun suggestNextDifficulty(kind: GameKind, current: Difficulty, outcome: Outcome): Difficulty? {
    val levels = kind.difficulties
    val index = levels.indexOf(current)
    return when (outcome) {
        Outcome.WIN -> levels.getOrNull(index + 1)
        Outcome.LOSE, Outcome.REVEALED -> levels.getOrNull(index - 1)
        Outcome.VOID, Outcome.EXTENDED_WIN -> null
    }
}

object Words {
    /** 띄어쓰기·문장부호를 없앤 비교용 형태 */
    fun normalize(raw: String): String = raw.filter { it.isLetterOrDigit() }.lowercase()
}
