package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.ad.AdEvent
import com.cihmine.carvoicegame.core.ad.AdEventLog
import com.cihmine.carvoicegame.core.ad.AdEventType
import com.cihmine.carvoicegame.core.save.SaveCodec
import com.cihmine.carvoicegame.core.save.SaveData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A14: 앱 재시작·TTS 콜백 재전송에서 광고 기록이 겹쳐 쌓이지 않는다. */
class AdEventDedupTest {
    private fun event(type: AdEventType, turn: Int = 5, at: Long = 1_000) = AdEvent("r1", turn, "c1", type, at)

    @Test
    fun `같은 판 같은 차례 같은 종류는 한 번만 남는다`() {
        val log = AdEventLog()
            .record(event(AdEventType.AD_STARTED))
            .record(event(AdEventType.AD_STARTED, at = 2_000)) // 재시작 후 같은 단어를 다시 읽음
            .record(event(AdEventType.AD_WORD_COMPLETED))
            .record(event(AdEventType.AD_WORD_COMPLETED)) // 완료 콜백 두 번
        assertEquals(1, log.count(AdEventType.AD_STARTED))
        assertEquals(1, log.count(AdEventType.AD_WORD_COMPLETED))
        assertEquals(1_000, log.lastStartedAtEpochMs) // 간격 계산은 처음 시작 시각 기준
    }

    @Test
    fun `다른 차례의 광고는 따로 센다`() {
        val log = AdEventLog().record(event(AdEventType.AD_STARTED, turn = 5)).record(event(AdEventType.AD_STARTED, turn = 9, at = 9_000))
        assertEquals(2, log.count(AdEventType.AD_STARTED))
        assertEquals(9_000, log.lastStartedAtEpochMs)
    }

    @Test
    fun `저장 후 불러와도 기록과 마지막 시작 시각이 유지된다`() {
        val save = SaveData(adLog = AdEventLog().record(event(AdEventType.AD_STARTED)))
        val loaded = SaveCodec.decode(SaveCodec.encode(save))
        assertEquals(1, loaded.adLog.record(event(AdEventType.AD_STARTED)).count(AdEventType.AD_STARTED))
        assertEquals(1_000, loaded.adLog.lastStartedAtEpochMs)
    }

    @Test
    fun `예전 저장 파일에는 광고 기록이 없어도 읽힌다`() {
        val loaded = SaveCodec.decode("""{"speechRatePercent":100}""")
        assertNull(loaded.adLog.lastStartedAtEpochMs)
        assertEquals(false, loaded.brandMode)
    }

    @Test
    fun `기록은 최대 개수까지만 남기되 마지막 시작 시각은 지워지지 않는다`() {
        var log = AdEventLog().record(event(AdEventType.AD_STARTED, turn = 0, at = 7))
        repeat(AdEventLog.MAX_EVENTS + 10) { log = log.record(event(AdEventType.AD_EXPLANATION_REQUESTED, turn = it + 1)) }
        assertEquals(AdEventLog.MAX_EVENTS, log.events.size)
        assertEquals(0, log.count(AdEventType.AD_STARTED))
        assertEquals(7, log.lastStartedAtEpochMs)
    }
}
