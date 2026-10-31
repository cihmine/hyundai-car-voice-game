package com.cihmine.carvoicegame.core.command

import com.cihmine.carvoicegame.core.Words
import kotlinx.serialization.Serializable

@Serializable
enum class VoiceCommand {
    QUIT, PAUSE, RESUME, EASIER, HARDER, FASTER, SLOWER, RULES, HINT, GIVE_UP, YES, NO,

    /** "잘못 들었어" — 직전 인식·판정 취소 */
    MISHEARD, EXPLAIN_WORD,
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
        if (text in AMBIGUOUS_QUIT) return ParsedUtterance.ConfirmQuit
        return ParsedUtterance.NotCommand
    }

    companion object {
        private val RATE = Regex("(?:말|안내)?속도(\\d{2,3})(?:퍼센트|%)(?:로)?(?:해줘|해)?")
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
            add(VoiceCommand.YES, "네", "예", "응", "좋아", "그래")
            add(VoiceCommand.NO, "아니", "아니요", "아니오", "싫어")
            add(VoiceCommand.MISHEARD, "잘못 들었어", "잘못 알아들었어", "그거 아니야", "그 말 아니야", "다시 들어 줘")
            add(VoiceCommand.EXPLAIN_WORD, "뜻이 뭐야", "무슨 뜻이야", "그게 무슨 뜻이야", "뜻 알려 줘")
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
