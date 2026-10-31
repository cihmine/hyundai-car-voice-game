package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.hangul.Hangul
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordEntry
import com.cihmine.carvoicegame.core.word.readResource
import kotlin.test.Test
import kotlin.test.assertTrue

/** 앱에 넣는 단어 파일 검사. 단어를 추가할 때 실수(중복·한 글자·뜻에 정답 노출)를 막는다. */
class DictionaryDataTest {
    private val raw: List<Pair<String, WordEntry>> = WordDictionary.BUNDLED_FILES.flatMap { file ->
        WordDictionary.parseTsv(readResource(file)).entries.map { file to it }
    }

    @Test
    fun `파일 사이에도 같은 단어가 두 번 들어가지 않는다`() {
        val lines = WordDictionary.BUNDLED_FILES.flatMap { file ->
            readResource(file).lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.substringBefore('\t').trim() to file }
                .toList()
        }
        val duplicates = lines.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "중복 단어: $duplicates")
    }

    @Test
    fun `모든 단어는 두 글자 이상 한글이고 난이도는 1~3`() {
        val bad = raw.filter { (_, e) -> e.word.length < 2 || !e.word.all(Hangul::isSyllable) || e.level !in 1..3 }
        assertTrue(bad.isEmpty(), "형식 오류: ${bad.map { "${it.first}: ${it.second.word}" }}")
    }

    @Test
    fun `뜻풀이에 단어 자체가 들어가지 않는다 - 힌트로 정답이 새지 않게`() {
        val leaking = raw.filter { (_, e) -> e.meaning.isBlank() || e.word in e.meaning }
        assertTrue(leaking.isEmpty(), "뜻에 정답 노출: ${leaking.map { it.second.word }}")
    }

    @Test
    fun `초성 대결로 낼 수 있는 초성이 충분하다`() {
        val pairs = WordDictionary.loadSample().choseongPairs(6)
        assertTrue(pairs.size >= 5, "출제 가능한 초성 ${pairs.size}개: $pairs")
    }

    @Test
    fun `끝말잇기 단어 대부분은 이어 갈 단어가 있다`() {
        val dict = WordDictionary.loadSample()
        val words = dict.entries.filter { it.word.length >= 2 }
        val connectable = words.count { e ->
            dict.startingWith(Hangul.startCharsAfter(e.word.last())).any { it.word.length >= 2 && it.word != e.word }
        }
        val ratio = connectable.toDouble() / words.size
        println("사전 ${dict.size}단어, 이어 갈 단어가 있는 비율 ${"%.0f".format(ratio * 100)}%")
        assertTrue(ratio >= 0.5, "이어 갈 단어가 있는 비율이 낮음: $ratio")
    }
}
