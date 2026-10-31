package com.cihmine.carvoicegame.core.word

import kotlinx.serialization.Serializable

/**
 * 답변 제한시간. 시각은 밖에서 넣어 주므로(밀리초) 테스트와 저장·복구가 결정적이다.
 * 제한이 없는 주행 모드는 [limitMs]·[remainingMs] 가 null 이다.
 */
@Serializable
data class TurnClock(
    val limitMs: Long?,
    val remainingMs: Long?,
    val runningSinceMs: Long? = null,
) {
    val hasLimit: Boolean get() = limitMs != null
    val isRunning: Boolean get() = runningSinceMs != null

    fun remaining(now: Long): Long? {
        val base = remainingMs ?: return null
        val since = runningSinceMs ?: return base
        return (base - (now - since)).coerceAtLeast(0)
    }

    fun isExpired(now: Long): Boolean = remaining(now) == 0L

    fun start(now: Long): TurnClock = if (remainingMs == null || isRunning) this else copy(runningSinceMs = now)

    fun pause(now: Long): TurnClock = if (!isRunning) this else copy(remainingMs = remaining(now), runningSinceMs = null)

    /** 새 제한시간으로 되돌린다. 안내 음성이 끝난 뒤 [start] 해야 흐른다. */
    fun reset(): TurnClock = copy(remainingMs = limitMs, runningSinceMs = null)

    fun addBonus(ms: Long, now: Long): TurnClock {
        val paused = pause(now)
        return paused.copy(remainingMs = paused.remainingMs?.plus(ms))
    }

    /** 제한시간을 [ms] 로 새로 준다(힌트 연장 등). 멈춘 상태로 두고 안내가 끝난 뒤 [start] 한다. */
    fun restart(ms: Long): TurnClock = if (limitMs == null) this else copy(remainingMs = ms, runningSinceMs = null)
}
