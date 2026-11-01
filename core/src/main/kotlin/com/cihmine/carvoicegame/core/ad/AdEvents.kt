package com.cihmine.carvoicegame.core.ad

import kotlinx.serialization.Serializable

@Serializable
enum class AdEventType {
    /** 브랜드 고지와 광고 단어 재생 시작. 빈도 제한은 여기서부터 소비한다. */
    AD_STARTED,
    AD_WORD_COMPLETED,
    AD_EXPLANATION_REQUESTED,
    AD_EXPLANATION_COMPLETED,
    AD_INTERRUPTED,
    AD_DISABLED,
}

/** 기기 안에만 남기는 광고 기록. 음성·위치·차량 식별 정보는 넣지 않는다. */
@Serializable
data class AdEvent(
    val roundId: String,
    val aiTurn: Int,
    val campaignId: String,
    val type: AdEventType,
    val atEpochMs: Long,
) {
    val key: String get() = "$roundId|$aiTurn|$campaignId|$type"
}

/**
 * 광고 기록. 같은 키(판·AI 차례·캠페인·종류)는 한 번만 남겨 재시작·콜백 재전송에서 중복 집계하지 않는다.
 * 사용자가 말한 광고 단어는 노출로 기록하지 않는다(앱은 AI 가 고른 광고 단어에만 기록을 남긴다).
 */
@Serializable
data class AdEventLog(
    val events: List<AdEvent> = emptyList(),
    /** 다음 광고 간격 계산용. 오래된 기록을 지워도 남도록 따로 둔다. */
    val lastStartedAtEpochMs: Long? = null,
) {
    fun record(event: AdEvent): AdEventLog {
        if (events.any { it.key == event.key }) return this
        return AdEventLog(
            events = (events + event).takeLast(MAX_EVENTS),
            lastStartedAtEpochMs = if (event.type == AdEventType.AD_STARTED) event.atEpochMs else lastStartedAtEpochMs,
        )
    }

    fun count(type: AdEventType): Int = events.count { it.type == type }

    companion object {
        const val MAX_EVENTS = 200
    }
}
