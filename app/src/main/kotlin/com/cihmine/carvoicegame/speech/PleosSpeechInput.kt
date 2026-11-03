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

    /** 요청 번호. 결과·실패·취소·새 요청마다 올려서, 끝난 요청의 늦은 "인식 끝" 신호를 버린다. */
    private var requestNo = 0

    private val results = object : ResultListener {
        override fun onUpdated(text: String, completed: Boolean) {
            main.post { deliver(text, completed) }
        }

        override fun onEndedRecognition() {
            // 최종 결과 없이 끝나면(말이 없었음) 다시 듣게 한다.
            // 에뮬 실측: 이 신호가 최종 결과(onUpdated completed=true)보다 0.01초쯤 먼저 온다 → 잠깐 기다렸다가
            // 그사이 최종 결과가 오지 않았을 때만 "말 없음"으로 본다(같은 요청일 때만).
            main.post {
                val request = requestNo
                main.postDelayed({ if (requestNo == request) fail(SpeechError.NO_MATCH) }, END_GRACE_MS)
            }
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
        requestNo++
        VoiceLevel.userSilent()
        if (text.isBlank()) listener.onError(SpeechError.NO_MATCH) else listener.onResult(listOf(text))
    }

    private fun fail(error: SpeechError) {
        DebugLog.d(TAG) { "fail $error active=${current != null}" }
        val listener = current ?: return
        current = null
        requestNo++
        VoiceLevel.userSilent()
        listener.onError(error)
    }

    override fun listen(listener: SpeechInput.Listener) {
        if (!ready) return listener.onError(SpeechError.OTHER)
        current = listener
        requestNo++
        heardSpeech = false
        stt.request()
    }

    override fun cancel() {
        if (current == null) return
        current = null
        requestNo++
        VoiceLevel.userSilent()
        stt.stop()
    }

    override fun shutdown() {
        cancel()
        stt.release()
    }

    private companion object {
        const val TAG = "PleosStt"
        const val END_GRACE_MS = 300L
    }
}
