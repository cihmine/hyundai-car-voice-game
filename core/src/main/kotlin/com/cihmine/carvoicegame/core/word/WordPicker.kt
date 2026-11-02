package com.cihmine.carvoicegame.core.word

import com.cihmine.carvoicegame.core.Difficulty
import kotlin.random.Random

/**
 * AI 쪽 단어 선택. 난이도는 AI 가 내는 단어의 어려움이 아니라 **AI 의 실력**이다.
 * - 어느 난이도든 익숙한 단어부터 말한다(익숙함 단계 0 → 1 → 2).
 * - 쉬움: 사용자가 일상 단어로 이을 수 있는 끝 글자를 골라 준다. 일상 단어(0단계)가 떨어지면 AI 가 진다.
 * - 보통: 끝 글자는 고르지 않고 무작위. 막히면 낯선 단어(2단계)까지 쓴다.
 * - 어려움: 사용자가 이을 쉬운 단어가 적은 끝 글자를 골라 압박한다. 막히면 낯선 단어까지 쓴다.
 * 상대가 이을 단어가 아예 없는 한방 단어([avoid])는 내지 않는다. 그것밖에 없으면 AI 가 진다.
 */
class WordPicker(private val random: Random) {

    /**
     * [userFollowUps] 는 그 단어 뒤에 사용자가 이을 수 있는 익숙한 단어 수.
     * 반환 null 은 AI 가 더 잇지 못함(사용자 승).
     */
    fun pick(
        options: List<WordEntry>,
        difficulty: Difficulty,
        avoid: (WordEntry) -> Boolean = { false },
        userFollowUps: (WordEntry) -> Int = { 0 },
    ): WordEntry? {
        // 한방 단어는 여기서 내지 않는다(어려움의 확인된 한방은 WordGame 이 따로 고른다). 남은 게 한방뿐이면 AI 가 진다.
        val usable = options.filter { it.familiarity <= maxFamiliarity(difficulty) && !avoid(it) }
        val tier = usable.minOfOrNull { it.familiarity } ?: return null
        return choose(usable.filter { it.familiarity == tier }, difficulty, userFollowUps)
    }

    /**
     * 쉬움은 직접 고른 일상 단어(0단계)만 쓴다. 우리말샘 "쉬운" 단어(1단계)도 실제로는 낯선 말이 많아,
     * 그런 말을 꺼내느니 AI 가 져 준다(가족용 쉬움은 사용자가 이기는 경험이 중요).
     */
    private fun maxFamiliarity(difficulty: Difficulty) = if (difficulty == Difficulty.EASY) 0 else 2

    private fun choose(pool: List<WordEntry>, difficulty: Difficulty, userFollowUps: (WordEntry) -> Int): WordEntry {
        if (difficulty == Difficulty.NORMAL || pool.size == 1) return pool.random(random)
        // 이을 단어 수로 줄 세워 쉬움은 많은 쪽, 어려움은 적은 쪽 3분의 1 안에서 고른다(늘 같은 단어가 되지 않게).
        val sorted = pool.map { it to userFollowUps(it) }.sortedBy { it.second }
        val band = maxOf(1, sorted.size / 3)
        val picked = if (difficulty == Difficulty.HARD) sorted.take(band) else sorted.takeLast(band)
        return picked.random(random).first
    }
}
