package com.cihmine.carvoicegame.speech

import android.content.Context
import android.content.Intent
import com.cihmine.carvoicegame.core.port.SpeechInput
import com.cihmine.carvoicegame.core.port.SpeechOutput

/** 앱에서 쓰는 음성 입출력. 화면에 어떤 엔진인지 보여 주고, 끝날 때 정리한다. */
interface VoiceInput : SpeechInput {
    val engineName: String
    fun shutdown()
}

interface VoiceOutput : SpeechOutput {
    val engineName: String

    /** 말하기 속도 조절 지원 여부. Pleos TTS 는 속도 API 가 없다. */
    val supportsRate: Boolean
    fun shutdown()
}

object Voice {
    private const val PLEOS_SPEECH_SERVICE = "ai.pleos.playground.caas.intent.action.SPEECH_START"

    /** Pleos Connect 음성 서비스가 있는 기기(차량·Pleos 에뮬레이터)인지 */
    fun hasPleosSpeech(context: Context): Boolean =
        context.packageManager.queryIntentServices(Intent(PLEOS_SPEECH_SERVICE), 0).isNotEmpty()

    fun input(context: Context, onReady: (Boolean) -> Unit): VoiceInput =
        if (hasPleosSpeech(context)) PleosSpeechInput(context, onReady)
        else AndroidSpeechInput(context).also { onReady(it.isAvailable) }

    fun output(context: Context, onReady: (Boolean) -> Unit): VoiceOutput =
        if (hasPleosSpeech(context)) PleosSpeechOutput(context, onReady) else AndroidSpeechOutput(context, onReady)
}
