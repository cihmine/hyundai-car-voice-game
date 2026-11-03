package com.cihmine.carvoicegame.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ln

/**
 * 지금 소리 크기(0~1). 화면의 빛나는 원이 AI 목소리·사용자 목소리에 맞춰 커졌다 작아지게 한다.
 * Pleos SDK 의 RMS 는 선형 진폭(에뮬 실측: 무음 ≈ 5, 안내 음성 ≈ 5,700)이라 로그 눈금으로 0~1 에 맞춘다.
 * 안드로이드 기본 인식기는 dB(대략 -2~10)로 준다.
 */
object VoiceLevel {
    private val _ai = MutableStateFlow(0f)
    private val _user = MutableStateFlow(0f)
    val ai: StateFlow<Float> = _ai
    val user: StateFlow<Float> = _user

    /** 이보다 작으면 0(무음·잡음), 이 정도면 1(큰 목소리) */
    private const val FLOOR = 150.0
    private const val LOUD = 8_000.0

    private fun amplitude(rms: Double): Float =
        if (rms <= FLOOR) 0f else (ln(rms / FLOOR) / ln(LOUD / FLOOR)).toFloat().coerceIn(0f, 1f)

    fun ai(rms: Double) { _ai.value = amplitude(rms) }
    fun user(rms: Double) { _user.value = amplitude(rms) }
    fun userDb(db: Float) { _user.value = ((db - 1f) / 9f).coerceIn(0f, 1f) }
    fun aiSilent() { _ai.value = 0f }
    fun userSilent() { _user.value = 0f }
}
