package com.cihmine.carvoicegame.core.question

import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.Outcome
import com.cihmine.carvoicegame.core.word.newRoundId
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class QuestionPhase {
    ASKING,

    /** 20회를 모두 써서 "포인트 없이 계속할까요?"를 물은 상태 */
    OFFER_EXTENSION,
    FINISHED,
}

@Serializable
data class QuestionGameState(
    val roundId: String,
    val kind: GameKind,
    val difficulty: Difficulty,
    /** 판 시작 때 복사해 둔 정답. 정답 목록이 갱신돼도 진행 중인 판은 바뀌지 않는다. */
    val answer: AnswerItem,
    val catalogVersion: Int,
    val attempts: Int = 0,
    val extended: Boolean = false,
    val phase: QuestionPhase = QuestionPhase.ASKING,
    val paused: Boolean = false,
    val outcome: Outcome? = null,
)

sealed interface AttemptResult {
    /** 질문 1회 사용. [limitReached] 면 AI 답변 뒤 연장 여부를 묻는다. */
    data class Counted(val attempts: Int, val limitReached: Boolean) : AttemptResult

    data class Correct(val attempts: Int, val outcome: Outcome) : AttemptResult

    data class Wrong(val attempts: Int, val limitReached: Boolean) : AttemptResult

    data object NotAccepting : AttemptResult
}

/**
 * 스무고개와 양세찬 게임. 사용자가 질문하고 AI 가 답한다.
 * 질문 하나와 정답 추측 한 번을 각각 1회로 센다. 음성 재확인·앱 조작 명령은 세지 않는다(앱이 이 메서드를 부르지 않음).
 * 시간제한·힌트는 없다.
 */
class QuestionGame private constructor(state: QuestionGameState) {
    var state: QuestionGameState = state
        private set

    val isFinished: Boolean get() = state.outcome != null

    fun ask(): AttemptResult {
        if (!accepting()) return AttemptResult.NotAccepting
        val attempts = state.attempts + 1
        val limitReached = reachesLimit(attempts)
        update { copy(attempts = attempts, phase = if (limitReached) QuestionPhase.OFFER_EXTENSION else phase) }
        return AttemptResult.Counted(attempts, limitReached)
    }

    fun guess(text: String): AttemptResult {
        if (!accepting()) return AttemptResult.NotAccepting
        val attempts = state.attempts + 1
        if (state.answer.matches(text)) {
            val outcome = if (attempts > LIMIT) Outcome.EXTENDED_WIN else Outcome.WIN
            update { copy(attempts = attempts, phase = QuestionPhase.FINISHED, outcome = outcome) }
            return AttemptResult.Correct(attempts, outcome)
        }
        val limitReached = reachesLimit(attempts)
        update { copy(attempts = attempts, phase = if (limitReached) QuestionPhase.OFFER_EXTENSION else phase) }
        return AttemptResult.Wrong(attempts, limitReached)
    }

    /** 20회 이후 포인트 없이 같은 정답으로 계속 */
    fun acceptExtension() {
        if (state.phase != QuestionPhase.OFFER_EXTENSION) return
        update { copy(extended = true, phase = QuestionPhase.ASKING) }
    }

    /** 정답 공개 요청·포기. 정답을 알려 주고 무보상으로 끝낸다. */
    fun reveal(): String {
        if (!isFinished) update { copy(phase = QuestionPhase.FINISHED, outcome = Outcome.REVEALED) }
        return state.answer.name
    }

    fun pause() {
        if (!isFinished) update { copy(paused = true) }
    }

    fun resume() {
        if (!isFinished) update { copy(paused = false) }
    }

    fun voidRound() {
        if (!isFinished) update { copy(phase = QuestionPhase.FINISHED, outcome = Outcome.VOID) }
    }

    private fun reachesLimit(attempts: Int) = !state.extended && attempts == LIMIT

    private fun accepting() = !isFinished && !state.paused && state.phase == QuestionPhase.ASKING

    private inline fun update(change: QuestionGameState.() -> QuestionGameState) {
        state = state.change()
    }

    companion object {
        const val LIMIT = 20

        fun start(
            kind: GameKind,
            difficulty: Difficulty,
            catalog: AnswerCatalog,
            random: Random = Random.Default,
            roundId: String = newRoundId(random),
        ): QuestionGame {
            require(!kind.isWordGame) { "질문 게임이 아님: $kind" }
            require(difficulty in kind.difficulties) { "${kind.label} 은 ${difficulty.label} 난이도가 없음" }
            val answer = catalog.pick(kind, difficulty, random) ?: error("출제할 정답이 없음 — 정답 목록 확인 필요")
            return QuestionGame(
                QuestionGameState(
                    roundId = roundId, kind = kind, difficulty = difficulty,
                    answer = answer, catalogVersion = catalog.version,
                ),
            )
        }

        fun restore(state: QuestionGameState): QuestionGame =
            QuestionGame(state.copy(paused = state.outcome == null))
    }
}
