package com.cihmine.carvoicegame.core.command

import com.cihmine.carvoicegame.core.Words
import kotlinx.serialization.Serializable

@Serializable
enum class VoiceCommand {
    QUIT, PAUSE, RESUME, EASIER, HARDER, FASTER, SLOWER, RULES, HINT, GIVE_UP, YES, NO,

    /** "잘못 들었어" — 직전 인식·판정 취소 */
    MISHEARD, EXPLAIN_WORD,

    /** "광고 꺼 줘" — 브랜드 단어 유도를 바로 멈춘다(포인트 불이익 없음) */
    AD_OFF,

    /** "모르겠어", "생각이 안 나" — 막혔음. 힌트가 남았으면 힌트, 없으면 "제가 이긴 걸로 할까요?" */
    STUCK,

    /** "뭐라고?", "소리가 안 들려", "전에 말한 단어가 뭐야" — 마지막 안내를 다시 말한다 */
    REPEAT,
}

sealed interface ParsedUtterance {
    data class Command(val command: VoiceCommand) : ParsedUtterance

    /** "속도 110퍼센트로" 처럼 직접 지정한 안내 속도 */
    data class SetSpeechRate(val percent: Int) : ParsedUtterance

    /** "아 됐다" 처럼 종료인지 답변인지 불분명. "게임을 종료할까요?"로 확인한다(시간·횟수 차감 없음). */
    data object ConfirmQuit : ParsedUtterance

    /** 명령이 아님 — 게임 답변으로 넘기거나, 미등록 표현이면 AI 에 의도 해석을 맡긴다. */
    data object NotCommand : ParsedUtterance
}

/**
 * 자주 쓰는 명령은 앱이 직접 처리한다. 개인 명령 사전(사용자가 확인한 표현)을 기본 사전보다 먼저 본다.
 * [isGameAnswer] 가 참이면 명령어처럼 들려도 답변으로 본다(예: 초성 ㅍㄱ 판의 "포기").
 */
class CommandParser(private val personal: Map<String, VoiceCommand> = emptyMap()) {

    fun parse(utterance: String, isGameAnswer: (String) -> Boolean = { false }): ParsedUtterance {
        val text = Words.normalize(utterance)
        if (text.isEmpty()) return ParsedUtterance.NotCommand
        if (isGameAnswer(text)) return ParsedUtterance.NotCommand
        personal[text]?.let { return ParsedUtterance.Command(it) }
        BUILT_IN[text]?.let { return ParsedUtterance.Command(it) }
        RATE.matchEntire(text)?.let { return ParsedUtterance.SetSpeechRate(it.groupValues[1].toInt()) }
        // "종료하자", "게임 끝내자", "이제 그만할게요"처럼 정해진 문장이 아니어도 그만두는 말이면 종료로 본다(짧은 말만)
        if (text.length <= 10 && QUIT_STEMS.any { it in text }) return ParsedUtterance.Command(VoiceCommand.QUIT)
        // "내가 졌어", "항복" — 진 걸 인정. "모르겠어", "생각이 안 나" — 막힘(실기 시험에서 단어로 오인해 사전 검색까지 함)
        if (text.length <= 10 && GIVE_UP_STEMS.any { it in text }) return ParsedUtterance.Command(VoiceCommand.GIVE_UP)
        // "좀 더 빠르게 말해 줘", "말하는 속도 높여 줘요"처럼 정해진 문장이 아니어도 설정 말로 본다(실기 시험에서 단어로 오인)
        if (text.length <= 16) {
            if (FASTER.containsMatchIn(text)) return ParsedUtterance.Command(VoiceCommand.FASTER)
            if (SLOWER.containsMatchIn(text)) return ParsedUtterance.Command(VoiceCommand.SLOWER)
            if (REPEAT_STEMS.any { it in text }) return ParsedUtterance.Command(VoiceCommand.REPEAT)
        }
        if (text.length <= 10 && STUCK_STEMS.any { it in text }) return ParsedUtterance.Command(VoiceCommand.STUCK)
        if (text in AMBIGUOUS_QUIT) return ParsedUtterance.ConfirmQuit
        // "또 해 보자", "좋아 좋아", "안 할래요"처럼 정해진 대답이 아니어도 짧은 말이면 네/아니요로 본다(아니요를 먼저 본다)
        if (text.length <= 10) {
            if (NO_STEMS.any { it in text }) return ParsedUtterance.Command(VoiceCommand.NO)
            if (YES_STEMS.any { it in text }) return ParsedUtterance.Command(VoiceCommand.YES)
        }
        return ParsedUtterance.NotCommand
    }

    companion object {
        private val RATE = Regex("(?:말|안내)?속도(\\d{2,3})(?:퍼센트|%)(?:로)?(?:해줘|해)?")
        private val QUIT_STEMS = listOf("종료", "그만하", "그만할", "그만두", "끝내", "끝낼", "게임끝")
        private val FASTER = Regex("빠르게|빨리|속도.*(높|올|빠르)|더빨")
        private val SLOWER = Regex("천천히|느리게|느릿|속도.*(낮|내려|줄|늦)")
        private val REPEAT_STEMS = listOf(
            "뭐라고", "뭐라구", "뭐라그", "다시말해", "다시한번", "한번더말해", "못들었", "안들려", "안들렸", "잘안들",
            "뭐였", "단어가뭐", "단어뭐", "뭐라했", "뭐라고했", "다시들려",
        )

        /**
         * 단어 하나가 아니라 문장처럼 들리는 말(띄어쓰기가 있거나 "~줘/~요/~야"로 끝나는 네 글자 이상).
         * 끝말잇기 답으로 판정해 "앞 단어의 끝 글자로 시작하지 않아요"라고 하지 않고, 못 알아들었다고 말한다.
         */
        fun looksLikeSentence(raw: String): Boolean {
            val text = Words.normalize(raw)
            if (raw.trim().contains(' ')) return true
            return text.length >= 4 && SENTENCE_ENDINGS.any { text.endsWith(it) }
        }

        private val SENTENCE_ENDINGS = listOf("줘", "줘요", "요", "야", "까", "더라", "했어", "거든", "는데", "잖아", "어줘")

        private val NO_STEMS = listOf("안할", "안해", "안들을", "싫", "아니", "아뇨", "괜찮", "다음에", "나중에", "됐습니다")
        private val YES_STEMS = listOf("해보자", "하자", "할래", "할게", "좋아", "좋지", "좋습", "그래", "한판더", "또해", "다시해", "들을래", "알려줘", "부탁")

        private val GIVE_UP_STEMS = listOf("졌어", "졌다", "졌네", "졌습니다", "내가졌", "항복", "기권", "포기할", "포기하")
        private val STUCK_STEMS = listOf("모르겠", "몰라", "생각이안나", "생각안나", "생각이안난", "못하겠", "못찾겠", "어렵다", "어려워")
        private val AMBIGUOUS_QUIT = setOf("아됐다", "됐다", "됐어", "아됐어", "그냥됐어")

        private val BUILT_IN: Map<String, VoiceCommand> = buildMap {
            fun add(command: VoiceCommand, vararg phrases: String) = phrases.forEach { put(Words.normalize(it), command) }
            add(VoiceCommand.QUIT, "그만", "그만해", "그만하자", "그만할래", "게임 종료", "종료", "끝내자")
            add(VoiceCommand.PAUSE, "잠깐 멈춰", "멈춰", "일시정지", "잠깐만", "잠깐")
            add(VoiceCommand.RESUME, "이어서 하자", "이어서", "계속하자", "계속해")
            add(VoiceCommand.EASIER, "쉽게 해 줘", "쉽게", "더 쉽게")
            add(VoiceCommand.HARDER, "어렵게 해 줘", "어렵게", "더 어렵게")
            add(VoiceCommand.FASTER, "말을 빨리 해 줘", "빨리 해 줘", "좀 빨리 말해 줘", "빨리 말해 줘")
            add(VoiceCommand.SLOWER, "천천히 해 줘", "천천히 말해 줘", "말을 천천히 해 줘")
            add(VoiceCommand.RULES, "규칙 알려 줘", "규칙", "규칙 설명해 줘")
            add(VoiceCommand.HINT, "힌트", "힌트 줘", "힌트 주세요")
            add(VoiceCommand.GIVE_UP, "포기", "포기할래", "정답 알려 줘")
            add(VoiceCommand.YES, "네", "예", "응", "좋아", "그래",
                "네네", "넵", "예예", "응응", "어", "그래요", "그래그래", "좋아요", "좋지", "좋습니다", "오케이", "콜", "맞아", "맞아요",
                "그럼", "그러자", "그러죠", "당연하지", "해 줘", "줘", "주세요", "할래", "하자", "해 볼래", "들을래", "알려 줘")
            add(VoiceCommand.NO, "아니", "아니요", "아니오", "싫어",
                "아니야", "아니에요", "아뇨", "노", "싫어요", "괜찮아", "괜찮아요", "필요 없어", "안 할래", "안 들을래", "안 해", "됐어요")
            add(VoiceCommand.MISHEARD, "잘못 들었어", "잘못 알아들었어", "그거 아니야", "그 말 아니야", "다시 들어 줘")
            add(VoiceCommand.EXPLAIN_WORD, "뜻이 뭐야", "무슨 뜻이야", "그게 무슨 뜻이야", "뜻 알려 줘", "그게 뭐야", "뭐야 그게", "그게 뭔데", "무슨 차야",
                "그런 단어가 있어", "그런 단어도 있어", "그런 말이 있어", "그런 말도 있어", "그게 단어야", "처음 들어 봐", "그게 뭔데 그래")
            add(VoiceCommand.AD_OFF, "광고 꺼 줘", "광고 끄기", "광고 꺼", "광고 그만", "광고 없이 해 줘")
        }
    }
}

/** 안내 속도. 느리게·기본·빠르게 3단계지만 내부는 퍼센트로 관리하고 마지막 값을 유지한다. */
object SpeechRate {
    const val SLOW = 80
    const val NORMAL = 100
    const val FAST = 120
    private const val STEP = 20
    private val RANGE = 50..200

    fun faster(current: Int) = (current + STEP).coerceIn(RANGE)
    fun slower(current: Int) = (current - STEP).coerceIn(RANGE)
    fun set(percent: Int) = percent.coerceIn(RANGE)
}
