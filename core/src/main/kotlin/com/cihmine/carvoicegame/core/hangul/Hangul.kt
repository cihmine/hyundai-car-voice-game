package com.cihmine.carvoicegame.core.hangul

object Hangul {
    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3
    private const val JUNG_COUNT = 21
    private const val JONG_COUNT = 28
    private const val CHO_STRIDE = JUNG_COUNT * JONG_COUNT

    private val CHOSEONG = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )
    val DOUBLE_CONSONANTS = setOf('ㄲ', 'ㄸ', 'ㅃ', 'ㅆ', 'ㅉ')

    private const val CHO_NIEUN = 2
    private const val CHO_RIEUL = 5
    private const val CHO_IEUNG = 11

    /** ㅑ ㅕ ㅖ ㅛ ㅠ ㅣ — 두음법칙에서 ㄹ·ㄴ 이 ㅇ 으로 바뀌는 모음 */
    private val IOTIZED_JUNG = setOf(2, 6, 7, 12, 17, 20)

    fun isSyllable(c: Char): Boolean = c.code in BASE..LAST

    fun choseong(c: Char): Char? = if (isSyllable(c)) CHOSEONG[(c.code - BASE) / CHO_STRIDE] else null

    /** 표기 기준 초성열. 완성형 한글이 아닌 글자가 섞이면 null */
    fun choseongOf(word: String): String? = buildString {
        for (c in word) append(choseong(c) ?: return null)
    }

    /**
     * 끝말잇기에서 [lastChar] 다음에 올 수 있는 첫 글자들. 두음법칙을 허용한다.
     * 예: 력 → {력, 역}, 라 → {라, 나}, 녀 → {녀, 여}
     */
    fun startCharsAfter(lastChar: Char): Set<Char> {
        if (!isSyllable(lastChar)) return setOf(lastChar)
        val index = lastChar.code - BASE
        val cho = index / CHO_STRIDE
        val jung = (index % CHO_STRIDE) / JONG_COUNT
        val jong = index % JONG_COUNT
        val result = linkedSetOf(lastChar)
        when (cho) {
            CHO_RIEUL -> result += compose(if (jung in IOTIZED_JUNG) CHO_IEUNG else CHO_NIEUN, jung, jong)
            CHO_NIEUN -> if (jung in IOTIZED_JUNG) result += compose(CHO_IEUNG, jung, jong)
        }
        return result
    }

    /**
     * 자음이나 모음 하나만 다른 말들(음성 오인식 구제용). 예: 구사 → 부사, 평소 → 병소.
     * 두~네 글자 완성형 한글이 아니면 없다. 원래 말은 포함하지 않는다.
     */
    fun oneJamoVariants(word: String): Sequence<String> {
        if (word.length !in 2..4 || !word.all(::isSyllable)) return emptySequence()
        return sequence {
            for (i in word.indices) {
                val index = word[i].code - BASE
                val cho = index / CHO_STRIDE
                val jung = (index % CHO_STRIDE) / JONG_COUNT
                val jong = index % JONG_COUNT
                fun with(c: Char) = word.substring(0, i) + c + word.substring(i + 1)
                for (x in CHOSEONG.indices) if (x != cho) yield(with(compose(x, jung, jong)))
                for (x in 0 until JUNG_COUNT) if (x != jung) yield(with(compose(cho, x, jong)))
                for (x in 0 until JONG_COUNT) if (x != jong) yield(with(compose(cho, jung, x)))
            }
        }
    }

    private fun compose(cho: Int, jung: Int, jong: Int): Char =
        (BASE + cho * CHO_STRIDE + jung * JONG_COUNT + jong).toChar()
}
