package com.cihmine.carvoicegame.core.word

import com.cihmine.carvoicegame.core.hangul.Hangul

/** [level] 1 쉬움 · 2 보통 · 3 어려움. [meaning] 은 힌트로 읽으므로 단어 자체를 포함하지 않는다. */
data class WordEntry(val word: String, val level: Int, val meaning: String)

class WordDictionary(entries: Iterable<WordEntry>) {
    private val byWord: Map<String, WordEntry> = entries.associateBy { it.word }
    private val byFirstChar: Map<Char, List<WordEntry>> = byWord.values.groupBy { it.word.first() }
    private val byChoseong: Map<String, List<WordEntry>> = byWord.values
        .mapNotNull { entry -> Hangul.choseongOf(entry.word)?.let { it to entry } }
        .groupBy({ it.first }, { it.second })

    val size: Int get() = byWord.size
    val entries: Collection<WordEntry> get() = byWord.values

    fun lookup(word: String): WordEntry? = byWord[word]

    fun startingWith(chars: Set<Char>): List<WordEntry> = chars.flatMap { byFirstChar[it].orEmpty() }

    fun withChoseong(choseong: String): List<WordEntry> = byChoseong[choseong].orEmpty()

    /** 두 글자 초성 중 출제할 만큼 후보가 많은 것. 쌍자음 초성은 제외한다. */
    fun choseongPairs(minWords: Int): List<String> = byChoseong
        .filter { (cs, words) -> cs.length == 2 && cs.none { it in Hangul.DOUBLE_CONSONANTS } && words.size >= minWords }
        .keys.sorted()

    companion object {
        /** 한 줄에 `단어<TAB>난이도<TAB>뜻`. `#` 로 시작하는 줄은 주석 */
        fun parseTsv(text: String): WordDictionary = WordDictionary(
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val (word, level, meaning) = line.split('\t', limit = 3)
                    WordEntry(word.trim(), level.trim().toInt(), meaning.trim())
                }
                .toList(),
        )

        /** 앱에 넣는 단어 파일들. 모두 자체 작성한 일반 명사와 뜻풀이다. */
        val BUNDLED_FILES = listOf("/data/sample_words.tsv", "/data/words_more_1.tsv", "/data/words_more_2.tsv")

        fun loadSample(): WordDictionary =
            WordDictionary(BUNDLED_FILES.flatMap { parseTsv(readResource(it)).entries })
    }
}

internal fun readResource(path: String): String =
    requireNotNull(WordDictionary::class.java.getResourceAsStream(path)) { "리소스 없음: $path" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
