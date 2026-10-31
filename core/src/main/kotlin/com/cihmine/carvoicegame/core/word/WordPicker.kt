package com.cihmine.carvoicegame.core.word

import com.cihmine.carvoicegame.core.Difficulty
import kotlin.random.Random

/** AI 쪽 단어 선택. 난이도에 맞는 수준을 먼저 고르고, [avoid] 에 걸리는 단어(한방 단어 등)는 가급적 피한다. */
class WordPicker(private val random: Random) {

    fun pick(options: List<WordEntry>, difficulty: Difficulty, avoid: (WordEntry) -> Boolean = { false }): WordEntry? {
        if (options.isEmpty()) return null
        val tiers = levelTiers(difficulty)
        for (allowAvoided in listOf(false, true)) {
            for (tier in tiers) {
                val pool = options.filter { it.level in tier && (allowAvoided || !avoid(it)) }
                if (pool.isNotEmpty()) return pool.random(random)
            }
        }
        return options.random(random)
    }

    private fun levelTiers(difficulty: Difficulty): List<Set<Int>> = when (difficulty) {
        Difficulty.EASY -> listOf(setOf(1), setOf(2), setOf(3))
        Difficulty.NORMAL -> listOf(setOf(2), setOf(1), setOf(3))
        Difficulty.HARD -> listOf(setOf(3, 2), setOf(1))
    }
}
