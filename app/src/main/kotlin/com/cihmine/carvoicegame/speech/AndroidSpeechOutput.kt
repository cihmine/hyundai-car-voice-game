package com.cihmine.carvoicegame.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 안드로이드 기본 TTS. 속도 조절은 이 구현에서만 지원한다(Pleos TTS 문서에는 속도 API 가 없음).
 * [onDone] 은 메인 스레드에서 한 번만 불린다. [stop] 으로 끊으면 불리지 않는다.
 */
class AndroidSpeechOutput(context: Context, onReady: (Boolean) -> Unit) : VoiceOutput {
    override val engineName = "기기 TTS"
    override val supportsRate = true

    private val main = Handler(Looper.getMainLooper())
    private var pending: Pair<String, () -> Unit>? = null
    private var counter = 0

    // 엔진 연결에 실패하면 생성자 안에서 바로 콜백이 오므로, tts 가 대입된 뒤 메인 스레드에서 처리한다.
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { onReady(status == TextToSpeech.SUCCESS && setKorean()) }
    }

    private fun setKorean(): Boolean {
        val result = tts.setLanguage(Locale.KOREAN)
        Log.i(TAG, "TTS engine=${tts.defaultEngine} setLanguage(ko)=$result")
        return result !in setOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        })
    }

    private fun finish(utteranceId: String?) {
        main.post { deliver(utteranceId) }
    }

    private fun deliver(utteranceId: String?) {
        val current = pending ?: return
        if (current.first != utteranceId) return
        pending = null
        current.second()
    }

    override fun speak(text: String, ratePercent: Int, onDone: () -> Unit) {
        val id = "u${++counter}"
        pending = id to onDone
        tts.setSpeechRate(ratePercent / 100f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        pending = null
        tts.stop()
    }

    override fun shutdown() {
        stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "SpeechOutput"
    }
}
