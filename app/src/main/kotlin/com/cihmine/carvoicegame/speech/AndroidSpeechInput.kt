package com.cihmine.carvoicegame.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.cihmine.carvoicegame.core.port.SpeechError
import com.cihmine.carvoicegame.core.port.SpeechInput

/**
 * 안드로이드 기본 음성 인식. Pleos 음성 서비스가 없는 기기(일반 폰·에뮬레이터)에서 쓴다.
 * 메인 스레드에서만 호출한다. 결과는 n-best 후보(Pleos STT 는 1개).
 */
class AndroidSpeechInput(private val context: Context) : VoiceInput {
    override val engineName = "기기 STT"

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(listener: SpeechInput.Listener) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private var delivered = false

            override fun onBeginningOfSpeech() = listener.onSpeechStart()

            override fun onResults(results: Bundle?) {
                if (delivered) return
                delivered = true
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (list.isEmpty()) listener.onError(SpeechError.NO_MATCH) else listener.onResult(list)
            }

            override fun onError(error: Int) {
                if (delivered) return
                delivered = true
                listener.onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NO_MATCH
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.TIMEOUT
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> SpeechError.NETWORK
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION
                        else -> SpeechError.OTHER
                    },
                )
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5),
        )
    }

    override fun cancel() {
        recognizer?.run {
            cancel()
            destroy()
        }
        recognizer = null
    }

    override fun shutdown() = cancel()
}
