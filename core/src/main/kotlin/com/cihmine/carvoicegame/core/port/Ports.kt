package com.cihmine.carvoicegame.core.port

import com.cihmine.carvoicegame.core.PlayMode
import com.cihmine.carvoicegame.core.command.VoiceCommand
import com.cihmine.carvoicegame.core.question.AnswerItem

/*
 * 차량·음성·AI 연결부. 규칙 엔진은 이 인터페이스에만 기대고, 구현은 교체할 수 있다.
 * 지금: 안드로이드 기본 SpeechRecognizer·TextToSpeech → Pleos STT·TTS SDK 가 열리면 교체.
 */

/** 음성 인식. 결과는 n-best 후보(첫 번째가 가장 유력) */
interface SpeechInput {
    fun listen(listener: Listener)
    fun cancel()

    interface Listener {
        fun onSpeechStart()
        fun onResult(candidates: List<String>)
        fun onError(error: SpeechError)
    }
}

enum class SpeechError { NO_MATCH, TIMEOUT, NETWORK, AUDIO_FOCUS_LOST, PERMISSION, OTHER }

/** 음성 안내. [ratePercent] 는 100 이 기본 속도 */
interface SpeechOutput {
    fun speak(text: String, ratePercent: Int, onDone: () -> Unit)
    fun stop()
}

/** 판 시작 모드를 정할 차량 상태. Pleos Vehicle SDK 로 구현한다. */
interface VehicleStatus {
    fun currentMode(): PlayMode

    /** 실제 주행 제한(CarUxRestrictions)으로 화면·기능을 막아야 하는지. 게임 모드와 별개 */
    fun isUxRestricted(): Boolean
}

/**
 * 외부 AI. 제공사는 미정이라 교체 가능하게 둔다. AI 키는 APK 에 넣지 않고 경량 서버를 거친다.
 * 점수·타이머·횟수·중복 판정은 AI 가 아닌 규칙 엔진이 정한다.
 */
interface AiClient {
    /** 기본·개인 명령 사전에 없는 표현의 의도. 모르면 null */
    suspend fun interpretCommand(utterance: String, gameContext: String): VoiceCommand?

    /** 스무고개 계열 질문에 대한 짧은 답. [answer] 의 검증된 사실만 근거로 하고, 근거가 없으면 "애매해요" */
    suspend fun answerQuestion(answer: AnswerItem, question: String): String
}
