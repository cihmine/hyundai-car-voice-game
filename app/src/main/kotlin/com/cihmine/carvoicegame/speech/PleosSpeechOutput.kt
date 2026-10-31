package com.cihmine.carvoicegame.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.cihmine.carvoicegame.DebugLog
import java.util.Locale

/**
 * Pleos TTS SDK(ON_DEVICE — 앱 등록 없이 사용). 속도 조절 API 가 없어 [speak] 의 속도 값은 쓰지 않는다.
 * SDK 콜백은 다른 스레드에서 올 수 있어 메인 스레드로 넘긴다.
 * onDone 에 발화 id 가 없으므로, 이번 발화의 onStart 를 받은 뒤의 onDone 만 끝으로 본다.
 */
class PleosSpeechOutput(context: Context, onReady: (Boolean) -> Unit) : VoiceOutput {
    override val engineName = "Pleos TTS"
    override val supportsRate = false

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var started = false

    private val events = object : EventListener {
        override fun onStart() {
            DebugLog.d(TAG) { "onStart" }
            main.post { started = true }
        }

        override fun onDone() {
            DebugLog.d(TAG) { "onDone" }
            main.post { if (started) finish() }
        }

        override fun onStop() {
            DebugLog.d(TAG) { "onStop" }
        }

        override fun onReady() {
            DebugLog.d(TAG) { "onReady" }
        }

        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(error: ErrorType) {
            Log.w(TAG, "TTS error $error")
            main.post { finish() }
        }
    }

    init {
        // SDK 는 initialize 성공 뒤에 리스너를 붙여야 한다.
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(events)
                tts.setLocale(Locale.KOREA)
                main.post {
                    ready = true
                    onReady(true)
                }
            }

            override fun onFailure(error: ErrorType) {
                Log.w(TAG, "TTS init failed $error")
                main.post { onReady(false) }
            }
        })
    }

    private fun finish() {
        val done = pending ?: return
        pending = null
        started = false
        done()
    }

    override fun speak(text: String, ratePercent: Int, onDone: () -> Unit) {
        if (!ready) return onDone()
        pending = onDone
        started = false
        // 두 번째 인자는 "마지막 문장 조각" 여부(SDK 기본값 true). false 면 이어질 텍스트를 기다리며 끝나지 않는다.
        tts.speak(text, true)
    }

    override fun stop() {
        pending = null
        started = false
        if (ready) tts.stop()
    }

    override fun shutdown() {
        stop()
        tts.release()
    }

    private companion object {
        const val TAG = "PleosTts"
    }
}
