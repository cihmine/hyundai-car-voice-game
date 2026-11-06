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
    fun `일상 단어 목록의 단어는 모두 우리말샘 파일에 있다`() {
        val opendict = raw.filter { it.first == "/data/words_opendict.tsv" }.map { it.second.word }.toSet()
        val missing = WordDictionary.everydayWords() - opendict
        assertTrue(missing.isEmpty(), "사전에 없는 일상 단어: $missing")
        assertTrue(WordDictionary.loadSample().entries.count { it.familiarity == 0 } > 1_500)
    }

    @Test
    fun `가족 게임에 맞지 않는 성적·성매매·성폭력 단어와 뜻풀이가 없다`() {
        // tools/fetch-opendict.mjs·server/api/word.js 의 걸러내기 기준과 같게 유지한다.
        val badMeaning = listOf(
            "낮잡아", "속되게", "비속", "얕잡아", "욕으로", "생식기", "성교", "음란",
            "매춘", "매음", "성매매", "창기", "창녀", "창가(", "유곽", "몸을 파", "강간", "간음", "음탕",
            "성욕", "성적 ", "성적인", "성적(", "성기(", "성기 ", "성행위", "애무", "정액(",
        )
        val badWord = setOf("자위", "성기", "간통", "눈엣가시", "화대", "섹스어필", "섹시녀", "섹시미", "섹기")
        val bad = raw.filter { (_, e) -> e.word in badWord || badMeaning.any { it in e.meaning } }
        assertTrue(bad.isEmpty(), "부적절: ${bad.map { "${it.second.word}(${it.second.meaning.take(20)})" }}")
    }

    @Test
    fun `판정 전용 목록은 정렬돼 있고 한글 두~네 글자이며 부적절한 말이 없다`() {
        val words = WordDictionary.acceptWords()
        assertTrue(words.size > 50_000, "판정 전용 단어 수: ${words.size}")
        assertTrue(words.zipWithNext().all { (a, b) -> a < b }, "정렬·중복 오류 — 이진 탐색이 깨진다")
        assertTrue(words.all { it.length in 2..4 && it.all(Hangul::isSyllable) })
        val badParts = listOf("좆", "씹", "병신", "지랄", "개새", "창녀", "니미", "불알", "보지", "자지")
        val bad = words.filter { w -> badParts.any { it in w } }
        assertTrue(bad.isEmpty(), "부적절: $bad")
    }

    @Test
    fun `실기 시험에서 거절됐던 흔한 말을 서버 없이 인정한다`() {
        val dictionary = WordDictionary.loadSample()
        val missing = listOf("구형", "구더기", "소고기", "타악기", "조폐", "강화", "가자미", "컴퓨터", "스마트폰", "삼겹살", "떡볶이")
            .filterNot(dictionary::accepts)
        assertTrue(missing.isEmpty(), "거절됨: $missing")
        assertTrue(!dictionary.accepts("가나다바")) // 가나다라는 우리말샘 표제어라 예시로 못 쓴다
    }

    @Test
    fun `판정 전용 단어는 AI 후보에 들어가지 않는다`() {
        val dictionary = WordDictionary.loadSample()
        val aiWords = dictionary.entries.mapTo(HashSet()) { it.word }
        val acceptOnly = WordDictionary.acceptWords().first { it !in aiWords }
        assertTrue(dictionary.accepts(acceptOnly))
        assertTrue(dictionary.lookup(acceptOnly) == null)
        assertTrue(dictionary.startingWith(setOf(acceptOnly.first())).none { it.word == acceptOnly })
        assertTrue(dictionary.plus(listOf(WordEntry("테스트말", 1, "시험"))).accepts(acceptOnly), "판 중 단어를 더해도 목록이 유지돼야 한다")
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
