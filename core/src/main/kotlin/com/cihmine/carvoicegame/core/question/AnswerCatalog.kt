package com.cihmine.carvoicegame.core.question

import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.Words
import com.cihmine.carvoicegame.core.word.readResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** 스무고개·양세찬 게임 정답 한 개. AI 는 [facts] 등 검증된 사실만 근거로 답한다. */
@Serializable
data class AnswerItem(
    val id: String,
    val name: String,
    val category: String,
    val difficulty: Difficulty,
    val aliases: List<String> = emptyList(),
    val facts: List<String> = emptyList(),
    /** 잘못된 항목은 지우지 않고 출제만 중지한다 */
    val disabled: Boolean = false,
) {
    fun matches(guess: String): Boolean {
        val g = Words.normalize(guess)
        return g.isNotEmpty() && (listOf(name) + aliases).any { Words.normalize(it) == g }
    }
}

@Serializable
data class AnswerCatalog(val version: Int, val items: List<AnswerItem>) {

    fun pick(kind: GameKind, difficulty: Difficulty, random: Random): AnswerItem? = items
        .filter { !it.disabled && it.difficulty == difficulty && isFor(kind, it) }
        .randomOrNull(random)

    private fun isFor(kind: GameKind, item: AnswerItem): Boolean = when (kind) {
        GameKind.GUESS_WHO -> item.category == PERSON
        GameKind.TWENTY_QUESTIONS -> item.category != PERSON
        else -> false
    }

    companion object {
        const val PERSON = "인물"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): AnswerCatalog = json.decodeFromString(serializer(), text)

        fun loadSample(): AnswerCatalog = parse(readResource("/data/sample_answers.json"))
    }
}
