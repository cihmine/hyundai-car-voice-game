package com.cihmine.carvoicegame.core.save

import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.Outcome
import com.cihmine.carvoicegame.core.PointLedger
import com.cihmine.carvoicegame.core.command.SpeechRate
import com.cihmine.carvoicegame.core.command.VoiceCommand
import com.cihmine.carvoicegame.core.question.QuestionGameState
import com.cihmine.carvoicegame.core.word.WordGameState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 기기 안 프로필 하나의 저장 내용. 답변 확정·상태 변경마다 저장한다(강제 종료 콜백에 의존하지 않음).
 * 음성 녹음과 대화 원문은 저장하지 않는다.
 */
@Serializable
data class SaveData(
    val wordGame: WordGameState? = null,
    val questionGame: QuestionGameState? = null,
    val ledger: PointLedger = PointLedger(),
    val speechRatePercent: Int = SpeechRate.NORMAL,
    /** 최초 이용 시 규칙 안내를 이미 물어본 게임 */
    val rulesOffered: Set<GameKind> = emptySet(),
    val personalCommands: Map<String, VoiceCommand> = emptyMap(),
    /** 끝난 판 기록(최근 순). 같은 판은 한 번만 남긴다. */
    val history: List<RoundRecord> = emptyList(),
) {
    fun withRecord(record: RoundRecord): SaveData =
        if (history.any { it.roundId == record.roundId }) this
        else copy(history = (listOf(record) + history).take(MAX_HISTORY))

    companion object {
        const val MAX_HISTORY = 50
    }

    val hasUnfinishedRound: Boolean
        get() = (wordGame != null && wordGame.outcome == null) ||
            (questionGame != null && questionGame.outcome == null)
}

@Serializable
data class RoundRecord(
    val roundId: String,
    val kind: GameKind,
    val difficulty: Difficulty,
    val outcome: Outcome,
    val points: Int,
    /** 사용자가 인정받은 단어 수 */
    val userWords: Int,
    val endedAtEpochMs: Long,
)

object SaveCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(data: SaveData): String = json.encodeToString(SaveData.serializer(), data)

    fun decode(text: String): SaveData = json.decodeFromString(SaveData.serializer(), text)
}

/** 저장소. 앱에서는 파일로, 테스트에서는 메모리로 구현한다. */
interface SaveStore {
    fun load(): SaveData?
    fun save(data: SaveData)
}
