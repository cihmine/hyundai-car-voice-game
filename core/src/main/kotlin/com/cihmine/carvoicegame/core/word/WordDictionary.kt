package com.cihmine.carvoicegame.core.word

import com.cihmine.carvoicegame.core.hangul.Hangul
import kotlinx.serialization.Serializable

/**
 * [level] 1 쉬움 · 2 보통 · 3 어려움. [meaning] 은 힌트로 읽으므로 단어 자체를 포함하지 않는다.
 * [isBrand] 는 브랜드 단어 허용 모드에서만 들어가는 광고 단어. AI 는 광고 계획을 거쳐서만 말한다.
 * [core] 는 직접 고른 일상 단어(우리말샘에서 받은 단어는 false).
 */
@Serializable
data class WordEntry(
    val word: String,
    val level: Int,
    val meaning: String,
    val isBrand: Boolean = false,
    val core: Boolean = true,
) {
    /**
     * 익숙함 단계. 0 직접 고른 일상 단어 · 1 우리말샘 인기 상위 · 2 나머지.
     * AI 는 난이도와 관계없이 익숙한 단어부터 말한다 — 난이도는 단어가 아니라 AI 의 수(끝 글자 선택)로 정한다.
     */
    val familiarity: Int get() = if (core) 0 else if (level == 1) 1 else 2

    val isFamiliar: Boolean get() = familiarity <= 1
}

/**
 * 단어장. [acceptOnly] 는 사용자 답을 인정할 때만 쓰는 표제어 목록(정렬됨)으로, AI 는 말하지 않고 힌트에도 쓰지 않는다.
 * 그래서 목록이 커져도 AI 실력과 난이도는 그대로이고, 서버 없이도 흔한 말이 거절되지 않는다.
 */
class WordDictionary(entries: Iterable<WordEntry>, private val acceptOnly: Lazy<List<String>> = lazyOf(emptyList())) {
    private val byWord: Map<String, WordEntry> = entries.associateBy { it.word }
    private val byFirstChar: Map<Char, List<WordEntry>> = byWord.values.groupBy { it.word.first() }
    private val byChoseong: Map<String, List<WordEntry>> = byWord.values
        .mapNotNull { entry -> Hangul.choseongOf(entry.word)?.let { it to entry } }
        .groupBy({ it.first }, { it.second })

    val size: Int get() = byWord.size
    val entries: Collection<WordEntry> get() = byWord.values

    fun lookup(word: String): WordEntry? = byWord[word]

    /** 사용자 답으로 인정할 수 있는 단어인지 */
    fun accepts(word: String): Boolean = word in byWord || acceptOnly.value.binarySearch(word) >= 0

    /** [extra] 를 더한 사전. 같은 단어가 있으면 [extra] 쪽을 쓴다(브랜드 단어로 표시). */
    fun plus(extra: Collection<WordEntry>): WordDictionary =
        if (extra.isEmpty()) this else WordDictionary(byWord.values + extra, acceptOnly)

    fun startingWith(chars: Set<Char>): List<WordEntry> = chars.flatMap { byFirstChar[it].orEmpty() }

    fun withChoseong(choseong: String): List<WordEntry> = byChoseong[choseong].orEmpty()

    /** 두 글자 초성 중 출제할 만큼 후보가 많은 것. 쌍자음 초성은 제외한다. */
    fun choseongPairs(minWords: Int): List<String> = byChoseong
        .filter { (cs, words) -> cs.length == 2 && cs.none { it in Hangul.DOUBLE_CONSONANTS } && words.size >= minWords }
        .keys.sorted()

    companion object {
        /** 한 줄에 `단어<TAB>난이도<TAB>뜻`. `#` 로 시작하는 줄은 주석. [core] 는 직접 고른 단어 파일인지 */
        fun parseTsv(text: String, core: Boolean = true): WordDictionary = WordDictionary(
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val (word, level, meaning) = line.split('\t', limit = 3)
                    WordEntry(word.trim(), level.trim().toInt(), meaning.trim(), core = core)
                }
                .toList(),
        )

        /**
         * 앱에 넣는 단어 파일들. words_more_* 까지는 자체 작성, words_opendict.tsv 는
         * 국립국어원 우리말샘(CC BY-SA 2.0 KR)에서 tools/fetch-opendict.mjs 로 받은 것이다.
         */
        val BUNDLED_FILES = listOf(
            "/data/sample_words.tsv", "/data/words_more_1.tsv", "/data/words_more_2.tsv",
            "/data/words_more_3.tsv", "/data/words_opendict.tsv",
        )

        private const val OPENDICT_FILE = "/data/words_opendict.tsv"

        /** 우리말샘 단어 중 직접 고른 일상 단어. 직접 쓴 단어와 같은 0단계(core)로 다룬다. */
        const val EVERYDAY_FILE = "/data/everyday_opendict.txt"

        /** 판정 전용 표제어(우리말샘, tools/fetch-opendict-accept.mjs). 정렬돼 있어야 한다. */
        const val ACCEPT_FILE = "/data/words_accept.txt"

        fun acceptWords(): List<String> = readResource(ACCEPT_FILE).lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

        fun everydayWords(): Set<String> = readResource(EVERYDAY_FILE).lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

        fun loadSample(): WordDictionary {
            val everyday = everydayWords()
            return WordDictionary(
                BUNDLED_FILES.flatMap { file ->
                    parseTsv(readResource(file), core = file != OPENDICT_FILE).entries
                        .map { if (!it.core && it.word in everyday) it.copy(core = true) else it }
                },
                lazy(::acceptWords),
            )
        }
    }
}

internal fun readResource(path: String): String =
    requireNotNull(WordDictionary::class.java.getResourceAsStream(path)) { "리소스 없음: $path" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
