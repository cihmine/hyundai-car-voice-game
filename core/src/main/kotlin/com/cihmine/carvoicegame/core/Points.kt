package com.cihmine.carvoicegame.core

import kotlinx.serialization.Serializable

/**
 * 판 결과별 포인트. 수치는 시제품 임시값이며 테스트 후 확정한다(기획서: "소폭 증가").
 * 무효·연장 성공·정답 공개·패배는 0.
 */
object RewardPolicy {
    fun wordGameWin(difficulty: Difficulty): Int = when (difficulty) {
        Difficulty.EASY -> 10
        Difficulty.NORMAL -> 12
        Difficulty.HARD -> 14
    }

    /** 1~5회, 6~10회, 11~15회, 16~20회 구간. 어려움은 같은 구간의 쉬움보다 조금 더 */
    fun questionSuccess(attempts: Int, difficulty: Difficulty): Int {
        val base = when (attempts) {
            in 1..5 -> 40
            in 6..10 -> 30
            in 11..15 -> 20
            in 16..20 -> 10
            else -> 0
        }
        return if (base > 0 && difficulty == Difficulty.HARD) base + 5 else base
    }

    fun forResult(kind: GameKind, difficulty: Difficulty, outcome: Outcome, attempts: Int = 0): Int = when {
        outcome != Outcome.WIN -> 0
        kind.isWordGame -> wordGameWin(difficulty)
        else -> questionSuccess(attempts, difficulty)
    }
}

/** 누적 획득량과 쿠폰 교환 후 잔액을 따로 기록한다. 같은 판은 두 번 지급하지 않는다. */
@Serializable
data class PointLedger(
    val balance: Int = 0,
    val totalEarned: Int = 0,
    val rewardedRounds: List<String> = emptyList(),
) {
    fun award(roundId: String, points: Int): PointLedger {
        if (points <= 0 || roundId in rewardedRounds) return this
        return copy(
            balance = balance + points,
            totalEarned = totalEarned + points,
            rewardedRounds = (rewardedRounds + roundId).takeLast(MAX_TRACKED_ROUNDS),
        )
    }

    /** 시연용 쿠폰 교환. 잔액이 모자라면 null */
    fun spend(points: Int): PointLedger? = if (points in 1..balance) copy(balance = balance - points) else null

    companion object {
        private const val MAX_TRACKED_ROUNDS = 200
    }
}
