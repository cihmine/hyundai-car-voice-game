package com.cihmine.carvoicegame.game

import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.word.RuleConfig

/** 음성 안내 문장. 차에서 듣기 쉽게 짧게 쓴다. */
object Phrases {
    private val JAMO_NAMES = mapOf(
        'ㄱ' to "기역", 'ㄴ' to "니은", 'ㄷ' to "디귿", 'ㄹ' to "리을", 'ㅁ' to "미음", 'ㅂ' to "비읍",
        'ㅅ' to "시옷", 'ㅇ' to "이응", 'ㅈ' to "지읒", 'ㅊ' to "치읓", 'ㅋ' to "키읔", 'ㅌ' to "티읕",
        'ㅍ' to "피읖", 'ㅎ' to "히읗",
    )

    /** TTS 가 "ㅅㄱ"을 제대로 읽지 못하므로 자모 이름으로 바꾼다. */
    fun spokenChoseong(choseong: String): String = choseong.map { JAMO_NAMES[it] ?: it.toString() }.joinToString(" ")

    /** 받침 유무에 맞춘 은/는 */
    fun withTopic(word: String): String {
        val last = word.lastOrNull() ?: return word
        val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
        return word + if (hasFinal) "은" else "는"
    }

    fun askRules(kind: GameKind) = "${withTopic(kind.label)} 처음이시네요. 규칙을 들어 볼까요?"

    fun rules(kind: GameKind, rules: RuleConfig): String {
        val limit = rules.parkedLimitMs / 1000
        val common = "같은 단어는 한 판에 한 번만 쓸 수 있어요. 틀리면 이유를 알려 드리고 다시 기회를 드려요. " +
            "주차 중에는 ${limit}초 안에 답해 주세요. 잘못 알아들었으면 '잘못 들었어', 그만하려면 '그만'이라고 말씀해 주세요."
        return when (kind) {
            GameKind.WORD_CHAIN -> "제가 먼저 단어를 말하면, 그 단어의 끝 글자로 시작하는 단어를 말해 주세요. $common"
            GameKind.CHOSEONG -> "제가 초성 두 개를 정하면, 그 초성에 맞는 두 글자 단어를 번갈아 말해요. $common"
            else -> common
        }
    }

    fun opening(kind: GameKind, difficulty: Difficulty, choseong: String?, firstWord: String) = when (kind) {
        GameKind.CHOSEONG ->
            "초성 대결, ${difficulty.label} 단계예요. 초성은 ${spokenChoseong(choseong!!)}. 제가 먼저 할게요. $firstWord"
        else -> "끝말잇기, ${difficulty.label} 단계예요. 제가 먼저 할게요. $firstWord"
    }

    /** 사용자가 "그게 뭐야?"처럼 물었을 때만 읽는 승인된 짧은 소개. 소개 문구에 없는 내용은 덧붙이지 않는다. */
    fun brandExplanation(shortExplanation: String, word: String) =
        "$shortExplanation 그럼 '${word.last()}' 자로 이어 주세요."

    // "광고 꺼 줘"라고 직접 말했을 때만 쓰는 대답
    const val AD_OFF = "알겠어요. 이제 상품 이름 같은 단어는 내지 않을게요."
    const val NO_ADS = "알겠어요."

    fun reconfirm(heard: String?) =
        if (heard == null) "잘 못 들었어요. 다시 말씀해 주세요."
        else "$heard, 라고 하셨나요? 제 단어장에서 찾지 못했어요. 맞으면 한 번 더, 아니면 다시 말씀해 주세요."

    fun userWins(word: String, points: Int) =
        "$word! 이어 갈 단어가 생각나지 않네요. 제가 졌어요." + if (points > 0) " ${points}포인트를 드릴게요." else ""

    const val ASK_CONCEDE = "이을 단어가 없는 것 같아요. 이번 판은 제가 이긴 걸로 할까요?"
    const val CONCEDED = "좋아요, 이번 판은 제가 이겼어요. 다음엔 그 글자를 조심하세요."
    const val VOIDED = "그만할게요. 이번 판은 무효로 하고 보상은 없어요."
    const val TIME_UP = "시간이 다 됐어요. 이번 판은 제가 이겼어요."
    const val GAVE_UP = "알겠어요. 이번 판은 제가 이겼어요."
    const val OFFER_HINT = "시간이 다 됐어요. 힌트를 들을까요?"
    const val ASK_QUIT = "게임을 그만할까요?"
    const val ASK_RESUME = "다시 이어서 할까요?"
    // ── 처음 화면·판 끝 ──
    fun menuQuestion(hasSaved: Boolean) = "어떤 게임을 시작할까요? 끝말잇기, 초성 대결이 있어요." +
        if (hasSaved) " 멈춘 판을 이어 하려면 '이어서'라고 말씀해 주세요." else ""

    fun notReady(kind: GameKind) = "${withTopic(kind.label)} 아직 준비 중이에요. 끝말잇기나 초성 대결은 어때요?"

    const val MENU_RETRY = "끝말잇기, 초성 대결 중에 말씀해 주세요."
    const val MENU_RULES = "끝말잇기는 앞 단어의 끝 글자로 시작하는 단어를 번갈아 말해요. 초성 대결은 정해진 초성에 맞는 두 글자 단어를 번갈아 말해요. 어떤 게임을 할까요?"
    const val MENU_LATER = "알겠어요. 하고 싶을 때 게임 이름을 말씀해 주세요."
    const val NOTHING_TO_RESUME = "이어 할 판이 없어요. 끝말잇기, 초성 대결 중에 골라 주세요."

    fun askNextLevel(next: Difficulty) = "한 단계 어렵게, ${next.label} 단계로 해 볼까요?"

    const val PLAY_AGAIN = "한 판 더 할까요?"
    const val SEE_YOU = "알겠어요. 다음에 또 해요."

    const val YES_OR_NO = "네 또는 아니요로 말씀해 주세요."
    const val SAY_AGAIN = "다시 말씀해 주세요."
    const val KEEP_GOING = "계속할게요."
}
