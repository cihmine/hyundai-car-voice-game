package com.cihmine.carvoicegame.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 게임 동안 음성 우선권을 잡는다. 내비 안내·통화·차량 AI 호출로 잃으면 [onLost], 되찾으면 [onRegained].
 * Pleos 오디오 가이드: 재생 전 요청, LOSS → 정지, LOSS_TRANSIENT → 일시정지.
 */
class AudioFocus(context: Context, private val onLost: () -> Unit, private val onRegained: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var held = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onLost()
                AudioManager.AUDIOFOCUS_GAIN -> onRegained()
            }
        }
        .build()

    fun acquire(): Boolean {
        if (held) return true
        held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun release() {
        if (!held) return
        audio.abandonAudioFocusRequest(request)
        held = false
    }
}
