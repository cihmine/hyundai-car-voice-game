package com.cihmine.carvoicegame.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import com.cihmine.carvoicegame.DebugLog
import com.cihmine.carvoicegame.core.port.SpeechError
import com.cihmine.carvoicegame.core.port.SpeechInput
import java.util.Locale

/**
 * Pleos STT SDK(ON_DEVICE). 결과는 문자열 1개(n-best 없음)라 후보 1개로 넘긴다.
 * 첫 부분 인식 결과가 오면 말하기 시작으로 본다. 콜백은 메인 스레드로 넘기고, 취소한 요청의 결과는 버린다.
 */
class PleosSpeechInput(context: Context, onReady: (Boolean) -> Unit) : VoiceInput {
    override val engineName = "Pleos STT"

    private val main = Handler(Looper.getMainLooper())
    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private var ready = false
    private var current: SpeechInput.Listener? = null
    private var heardSpeech = false

    private val results = object : ResultListener {
        override fun onUpdated(text: String, completed: Boolean) {
            main.post { deliver(text, completed) }
        }

        override fun onEndedRecognition() {
            // 최종 결과 없이 끝나면(말이 없었음) 다시 듣게 한다.
            main.post { fail(SpeechError.NO_MATCH) }
        }

        override fun onError(error: ErrorType) {
            Log.w(TAG, "STT error $error")
            main.post {
                fail(
                    when (error) {
                        ErrorType.TIMEOUT -> SpeechError.TIMEOUT
                        ErrorType.NETWORK_ERROR, ErrorType.SERVER_INTERNAL_ERROR -> SpeechError.NETWORK
                        ErrorType.PERMISSION_DENIED, ErrorType.MIC_ERROR -> SpeechError.PERMISSION
                        else -> SpeechError.OTHER
                    },
                )
            }
        }

        override fun onStartedRecognition() = Unit
        override fun onUpdatedEpdData(start: Long, end: Long) = Unit
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) {
            main.post { if (current != null) VoiceLevel.user(rms) }
        }
    }

    init {
        // SDK 는 initialize 성공 뒤에 리스너를 붙여야 한다.
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                stt.addListener(results)
                stt.setLocale(Locale.KOREA)
                main.post {
                    ready = true
                    onReady(true)
                }
            }

            override fun onFailure(error: ErrorType) {
                Log.w(TAG, "STT init failed $error")
                main.post { onReady(false) }
            }
        })
    }

    private fun deliver(text: String, completed: Boolean) {
        DebugLog.d(TAG) { "onUpdated \"$text\" completed=$completed active=${current != null}" }
        val listener = current ?: return
        if (text.isNotBlank() && !heardSpeech) {
            heardSpeech = true
            listener.onSpeechStart()
        }
        if (!completed) {
            if (text.isNotBlank()) listener.onPartial(text)
            return
        }
        current = null
        VoiceLevel.userSilent()
        if (text.isBlank()) listener.onError(SpeechError.NO_MATCH) else listener.onResult(listOf(text))
    }

    private fun fail(error: SpeechError) {
        DebugLog.d(TAG) { "fail $error active=${current != null}" }
        val listener = current ?: return
        current = null
        VoiceLevel.userSilent()
        listener.onError(error)
    }

    override fun listen(listener: SpeechInput.Listener) {
        if (!ready) return listener.onError(SpeechError.OTHER)
        current = listener
        heardSpeech = false
        stt.request()
    }

    override fun cancel() {
        if (current == null) return
        current = null
        VoiceLevel.userSilent()
        stt.stop()
    }

    override fun shutdown() {
        cancel()
        stt.release()
    }

    private companion object {
        const val TAG = "PleosStt"
    }
}
