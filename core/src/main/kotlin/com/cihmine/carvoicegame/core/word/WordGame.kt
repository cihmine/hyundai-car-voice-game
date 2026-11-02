package com.cihmine.carvoicegame.core.word

import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.Outcome
import com.cihmine.carvoicegame.core.PlayMode
import com.cihmine.carvoicegame.core.Words
import com.cihmine.carvoicegame.core.ad.AdPlanner
import com.cihmine.carvoicegame.core.ad.BrandRound
import com.cihmine.carvoicegame.core.ad.DecisionReason
import com.cihmine.carvoicegame.core.ad.PlanInput
import com.cihmine.carvoicegame.core.ad.PlanSummary
import com.cihmine.carvoicegame.core.hangul.Hangul
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class WordPhase {
    /** 사용자 차례 */
    AWAIT_USER,

    /** 주차 모드에서 시간이 끝났고 힌트가 남아 "힌트를 드릴까요?"를 물은 상태 */
    OFFER_HINT,
    FINISHED,
}

@Serializable
data class WordGameState(
    val roundId: String,
    val kind: GameKind,
    val difficulty: Difficulty,
    val mode: PlayMode,
    /** 초성 대결에서 한 판 동안 유지하는 두 글자 초성 */
    val choseong: String? = null,
    val used: List<String> = emptyList(),
    val lastAiWord: String? = null,
    val hintsLeft: Int,
    val clock: TurnClock,
    val phase: WordPhase = WordPhase.AWAIT_USER,
    val paused: Boolean = false,
    /** 사전에 없는 말을 한 번 재확인했으면 그 말. 같은 말이 다시 오면 오답으로 본다. */
    val pendingConfirm: String? = null,
    val validUserWords: Int = 0,
    val outcome: Outcome? = null,
    /** AI 가 새 단어를 말할 때마다 늘어나는 차례 번호. 지난 차례의 늦은 STT 결과를 버리는 데 쓴다. */
    val turn: Int = 0,
    /** "잘못 들었어"로 되돌릴 수 있는 직전 판정. 한 번 되돌리면 비운다. */
    val lastTurn: LastTurn? = null,
    /** 브랜드 단어 허용 모드(끝말잇기만). null 이면 일반 모드 */
    val brand: BrandRound? = null,
    /** AI 의 직전 단어가 광고 계획으로 고른 광고 단어면 그 캠페인 */
    val lastAiAdCampaignId: String? = null,
    /** 직전 AI 단어 선택의 진단 정보(개발자 패널용) */
    val lastPlan: PlanSummary? = null,
    /** 판 도중 인터넷 사전에서 찾아 더한 단어. 저장·복원 때 다시 넣는다. */
    val learned: List<WordEntry> = emptyList(),
    /** AI 의 직전 단어가 인터넷으로 확인한 한방 단어인지(어려움) */
    val lastAiHanbang: Boolean = false,
)

/** 직전 판정을 되돌리기 위한 기록 */
@Serializable
data class LastTurn(
    val word: String,
    val accepted: Boolean,
    /** 판정 직전의 시계(멈춘 상태) */
    val clockBefore: TurnClock,
    val lastAiWordBefore: String?,
    val usedCountBefore: Int,
    val lastAiAdBefore: String? = null,
    val lastAiHanbangBefore: Boolean = false,
)

enum class RejectReason(val message: String) {
    // 단어장이 표준 사전보다 작으므로 "없는 말"이라고 단정하지 않는다.
    NOT_IN_DICTIONARY("제 단어장에 없는 단어예요"),
    ALREADY_USED("이번 판에서 이미 나온 단어예요"),
    WRONG_START("앞 단어의 끝 글자로 시작하지 않아요"),
    TOO_SHORT("두 글자 이상이어야 해요"),
    WRONG_CHOSEONG("제시한 초성과 맞지 않아요"),
    NOT_TWO_SYLLABLES("두 글자 단어여야 해요"),
}

sealed interface TurnResult {
    /** 사용자 단어 인정. [aiWord] 가 null 이면 AI 가 더 잇지 못해 사용자 승리 */
    data class Accepted(val userWord: String, val aiWord: String?) : TurnResult

    /** 오답. 이유를 설명하고 다시 말하게 한다. 주차 모드면 남은 시간에 5초 추가 */
    data class Rejected(val word: String, val reason: RejectReason) : TurnResult

    /** 인식이 불확실해 다시 묻는다. 시간·힌트 차감 없음 */
    data class Reconfirm(val heard: String?) : TurnResult

    data object NotAccepting : TurnResult
}

sealed interface CorrectionResult {
    /** [word] 판정을 취소했다. 다시 말하게 한다. 인정했던 단어면 AI 단어도 함께 취소되어 [lastAiWord] 로 돌아간다. */
    data class Undone(val word: String, val wasAccepted: Boolean, val lastAiWord: String?) : CorrectionResult

    /** 되돌릴 판정이 없다(이미 정정했거나 아직 답하지 않음). 다시 말해 달라고만 한다. */
    data object NothingToUndo : CorrectionResult
}

sealed interface HintResult {
    data class Hint(val meaning: String, val hintsLeft: Int) : HintResult
    data object NoHintsLeft : HintResult
    data object NoCandidate : HintResult
    data object NotAccepting : HintResult
}

sealed interface TickResult {
    data object None : TickResult
    data object OfferHint : TickResult
    data object TimeUp : TickResult
}

/**
 * 끝말잇기와 초성 대결이 공유하는 규칙 엔진.
 * 매판 AI 가 먼저 말하고, 양쪽이 쓴 단어는 다시 쓸 수 없다.
 * 시각(now, 밀리초)은 호출하는 쪽이 넣는다. 앱은 음성 안내 시작·끝을 [speechStarted]·[speechFinished] 로 알려
 * 안내와 재확인 중에는 타이머가 멈추게 한다.
 */
class WordGame private constructor(
    state: WordGameState,
    baseDictionary: WordDictionary,
    private val random: Random,
    private val rules: RuleConfig,
    private val planner: AdPlanner?,
) {
    var state: WordGameState = state
        private set

    /**
     * 브랜드 모드면 판을 시작할 때 고정한 브랜드 단어를, 판 도중 인터넷에서 찾은 단어를 더한 사전.
     * 사람과 AI 에 똑같이 쓴다.
     */
    private var dictionary = baseDictionary.plus(state.brand?.entries.orEmpty()).plus(state.learned)

    /** 이번 AI 차례에 한방으로 써도 된다고 인터넷으로 확인한 단어. AI 가 한 번 고르면 비운다. */
    private var verifiedDeadEnds: Set<String> = emptySet()
    private val aliases: Map<String, String> = state.brand?.aliases.orEmpty()

    private val picker = WordPicker(random)

    val isFinished: Boolean get() = state.outcome != null

    // ── 사용자 답변 ─────────────────────────────────────────

    /** 사용자가 말하기 시작함. 시간 안에 말을 시작했다면 인식 지연을 시간 초과로 보지 않는다. */
    fun userSpeechStarted(now: Long) {
        if (!acceptingAnswer() || state.clock.isExpired(now)) return
        update { copy(clock = clock.pause(now)) }
    }

    /**
     * STT 후보(n-best, 첫 번째가 가장 유력)를 받아 판정한다. 사전·규칙에 맞는 후보가 있으면 그것을 인정한다.
     * [turn] 을 넘기면 그 차례의 결과일 때만 받는다(종료·정정 뒤 늦게 온 결과 무시).
     */
    fun submit(candidates: List<String>, now: Long, turn: Int? = null): TurnResult {
        if (!acceptingAnswer() || (turn != null && turn != state.turn)) return TurnResult.NotAccepting
        // 시간이 끝났는데 아직 tick 이 돌지 않았을 수 있다. 시간 안에 말을 시작했다면 시계가 멈춰 있어 만료가 아니다.
        if (state.clock.isExpired(now)) return TurnResult.NotAccepting
        // 승인된 다른 표기는 정식 표기로 바꿔 같은 단어로 중복 검사한다.
        val normalized = normalize(candidates)

        normalized.firstOrNull { violation(it) == null }?.let { return accept(it, now) }

        val top = normalized.firstOrNull()
        if (top == null) {
            update { copy(clock = clock.pause(now), lastTurn = null) }
            return TurnResult.Reconfirm(null)
        }
        val reason = violation(top)!!
        if (reason == RejectReason.NOT_IN_DICTIONARY && state.pendingConfirm != top) {
            update { copy(clock = clock.pause(now), pendingConfirm = top, lastTurn = null) }
            return TurnResult.Reconfirm(top)
        }
        update {
            copy(
                clock = if (clock.hasLimit) clock.addBonus(rules.wrongAnswerBonusMs, now) else clock,
                pendingConfirm = null,
                lastTurn = recordTurn(top, accepted = false, now),
            )
        }
        return TurnResult.Rejected(top, reason)
    }

    /**
     * "잘못 들었어". 직전 판정(인정 또는 오답)을 취소하고 판정 전 남은 시간으로 되돌린다.
     * 여러 번 말해도 한 번만 되돌리므로 단어·포인트가 겹쳐 반영되지 않는다. 끝난 판은 바꾸지 않는다.
     */
    fun correctLast(now: Long): CorrectionResult {
        if (isFinished || state.paused) return CorrectionResult.NothingToUndo
        val last = state.lastTurn
        if (last == null) {
            update { copy(clock = clock.pause(now), pendingConfirm = null) }
            return CorrectionResult.NothingToUndo
        }
        update {
            copy(
                used = used.take(last.usedCountBefore),
                lastAiWord = last.lastAiWordBefore,
                lastAiAdCampaignId = last.lastAiAdBefore,
                lastAiHanbang = last.lastAiHanbangBefore,
                validUserWords = if (last.accepted) validUserWords - 1 else validUserWords,
                // 판정 뒤 주행으로 바뀌었으면 주차 제한시간을 되살리지 않는다.
                clock = if (last.clockBefore.hasLimit && !clock.hasLimit) clock else last.clockBefore,
                phase = WordPhase.AWAIT_USER,
                pendingConfirm = null,
                lastTurn = null,
                turn = turn + 1,
            )
        }
        return CorrectionResult.Undone(last.word, last.accepted, state.lastAiWord)
    }

    private fun recordTurn(word: String, accepted: Boolean, now: Long) = LastTurn(
        word = word, accepted = accepted, clockBefore = state.clock.pause(now),
        lastAiWordBefore = state.lastAiWord, usedCountBefore = state.used.size,
        lastAiAdBefore = state.lastAiAdCampaignId,
        lastAiHanbangBefore = state.lastAiHanbang,
    )

    private fun accept(word: String, now: Long): TurnResult {
        val record = recordTurn(word, accepted = true, now)
        val used = state.used + word
        val (aiEntry, plan, hanbang) = chooseAiWord(used.toSet(), word)
        if (aiEntry == null) {
            update {
                copy(
                    used = used, validUserWords = validUserWords + 1, pendingConfirm = null,
                    clock = clock.pause(now), phase = WordPhase.FINISHED, outcome = Outcome.WIN,
                    lastTurn = record,
                )
            }
            return TurnResult.Accepted(word, null)
        }
        update {
            copy(
                used = used + aiEntry.word, lastAiWord = aiEntry.word,
                validUserWords = validUserWords + 1, pendingConfirm = null, clock = clock.reset(),
                lastTurn = record, turn = turn + 1,
                lastAiAdCampaignId = plan?.campaignId?.takeIf { plan.reason == DecisionReason.DIRECT_AD },
                lastPlan = plan,
                lastAiHanbang = hanbang,
                brand = brand?.let { b ->
                    b.copy(
                        adShown = b.adShown || plan?.reason == DecisionReason.DIRECT_AD,
                        steeringUsed = b.steeringUsed + if (plan?.reason == DecisionReason.STEERING) 1 else 0,
                    )
                },
            )
        }
        return TurnResult.Accepted(word, aiEntry.word)
    }

    /**
     * AI 단어 선택. 일반 정책은 브랜드 단어를 고르지 않는다. 브랜드 모드면 광고 계획이 같은 합법 후보 안에서
     * 광고 단어(바로 노출)나 광고로 이어질 일반 단어(유도)를 고를 수 있다. 판정 규칙은 바꾸지 않는다.
     */
    private fun chooseAiWord(used: Set<String>, userWord: String): AiChoice {
        val options = optionsAfter(userWord, used)
        val verified = verifiedDeadEnds
        verifiedDeadEnds = emptySet()
        if (hanbangAllowed()) {
            // 어려움: 인터넷으로도 이을 단어가 없다고 확인한 익숙한 한방 단어가 있으면 쓴다.
            val hanbang = options.filter { it.word in verified && it.isFamiliar && !it.isBrand && isDeadEnd(it.word, used + it.word) }
            if (hanbang.isNotEmpty()) return AiChoice(hanbang.random(random), null, hanbang = true)
        }
        val baseline = picker.pick(
            options = options.filter { !it.isBrand },
            difficulty = state.difficulty,
            avoid = { isDeadEnd(it.word, used + it.word) },
            userFollowUps = { familiarRepliesAfter(it.word, used + it.word) },
        )
        val brand = state.brand
        if (planner == null || brand == null || state.kind != GameKind.WORD_CHAIN) return AiChoice(baseline, null)
        val decision = planner.choose(
            PlanInput(dictionary, options, used, state.difficulty, state.validUserWords + 1, brand, baseline, random),
        )
        return AiChoice(decision.chosen, decision.summary)
    }

    private data class AiChoice(val entry: WordEntry?, val plan: PlanSummary?, val hanbang: Boolean = false)

    /** 이번 AI 차례(방금 답을 인정한 뒤)에 한방 단어를 쓸 수 있는지: 어려움 끝말잇기, 사용자 답 N개 이후 */
    private fun hanbangAllowed(): Boolean =
        state.kind == GameKind.WORD_CHAIN && state.difficulty == Difficulty.HARD &&
            state.validUserWords + 1 >= rules.hardHanbangAfterUserWords

    // ── 인터넷 사전 보조 ────────────────────────────────────
    // 앱이 판정 직전에 필요한 검색을 끝내고 결과를 넣는다. 엔진 판정은 그 뒤 동기·결정적으로 한다.

    /** 인터넷 사전에서 찾은 단어를 이번 판 사전에 더한다. 이미 있는 단어는 건너뛴다. */
    fun learn(entries: List<WordEntry>) {
        val fresh = entries
            .filter { it.word.length >= 2 && it.word.all(Hangul::isSyllable) && dictionary.lookup(it.word) == null }
            .distinctBy { it.word }
            .map { it.copy(isBrand = false, core = false) }
        if (fresh.isEmpty()) return
        dictionary = dictionary.plus(fresh)
        update { copy(learned = learned + fresh) }
    }

    /** STT 후보 중 지금 바로 인정될 단어(판정은 하지 않는다) */
    fun acceptableWord(candidates: List<String>): String? = normalize(candidates).firstOrNull { violation(it) == null }

    /** 규칙(시작 글자·길이·중복)은 맞지만 앱 사전에 없어 인터넷으로 확인할 만한 말. 인정될 후보가 이미 있으면 null */
    fun wordToLookUp(candidates: List<String>): String? {
        if (!acceptingAnswer()) return null
        val normalized = normalize(candidates)
        if (normalized.any { violation(it) == null }) return null
        return normalized.firstOrNull { dictionary.lookup(it) == null && violation(it, checkDictionary = false) == null }
    }

    /** [userWord] 를 인정한 뒤 AI 가 앱 사전으로 이을 단어가 없으면 인터넷에서 찾아볼 첫 글자들(어려움만) */
    fun aiSearchHeads(userWord: String): Set<Char>? {
        if (state.kind != GameKind.WORD_CHAIN || state.difficulty != Difficulty.HARD) return null
        val options = optionsAfter(userWord, state.used.toSet() + userWord).filter { !it.isBrand }
        return if (options.isEmpty()) Hangul.startCharsAfter(userWord.last()) else null
    }

    /**
     * [userWord] 를 인정한 뒤 AI 가 한방으로 쓸 만한 익숙한 후보(앱 사전 기준 이을 단어 없음).
     * 앱이 인터넷으로 정말 없는지 확인해 [setVerifiedDeadEnds] 로 넘긴다. 확인 못 한(오프라인) 후보는 쓰지 않는다.
     */
    fun hanbangCandidates(userWord: String, limit: Int = 3): List<String> {
        if (!hanbangAllowed()) return emptyList()
        val used = state.used.toSet() + userWord
        return optionsAfter(userWord, used)
            .filter { it.isFamiliar && !it.isBrand && isDeadEnd(it.word, used + it.word) }
            .sortedBy { it.word } // 검색 결과와 무관하게 같은 순서
            .take(limit)
            .map { it.word }
    }

    fun setVerifiedDeadEnds(words: Set<String>) {
        verifiedDeadEnds = words
    }

    private fun normalize(candidates: List<String>): List<String> =
        candidates.map(Words::normalize).filter { it.isNotEmpty() }.map { aliases[it] ?: it }.distinct()

    /** "광고 꺼 줘". 바로 유도를 멈춘다. 이번 판의 허용 단어 목록과 포인트 규칙은 그대로다. */
    fun disableAds() {
        val brand = state.brand ?: return
        update { copy(brand = brand.copy(adEnabled = false)) }
    }

    private fun violation(word: String, checkDictionary: Boolean = true): RejectReason? {
        if (checkDictionary && dictionary.lookup(word) == null) return RejectReason.NOT_IN_DICTIONARY
        if (word in state.used) return RejectReason.ALREADY_USED
        return when (state.kind) {
            GameKind.WORD_CHAIN -> when {
                word.length < 2 -> RejectReason.TOO_SHORT
                word.first() !in Hangul.startCharsAfter(state.lastAiWord!!.last()) -> RejectReason.WRONG_START
                else -> null
            }
            GameKind.CHOSEONG -> when {
                word.length != 2 -> RejectReason.NOT_TWO_SYLLABLES
                Hangul.choseongOf(word) != state.choseong -> RejectReason.WRONG_CHOSEONG
                else -> null
            }
            else -> error("단어 게임이 아님: ${state.kind}")
        }
    }

    /** [previous] 다음에 말할 수 있는 단어들 */
    private fun optionsAfter(previous: String, used: Set<String>): List<WordEntry> = when (state.kind) {
        GameKind.WORD_CHAIN -> dictionary.startingWith(Hangul.startCharsAfter(previous.last()))
            .filter { it.word.length >= 2 }
        GameKind.CHOSEONG -> dictionary.withChoseong(state.choseong!!).filter { it.word.length == 2 }
        else -> emptyList()
    }.filter { it.word !in used }

    /** [word] 뒤에 사용자가 이을 수 있는 익숙한 단어 수(AI 의 끝 글자 전략에 쓴다) */
    private fun familiarRepliesAfter(word: String, used: Set<String>): Int =
        if (state.kind == GameKind.WORD_CHAIN) optionsAfter(word, used).count { it.isFamiliar } else 0

    /** 끝말잇기에서 상대가 이을 단어가 없는 한방 단어인지 */
    private fun isDeadEnd(word: String, used: Set<String>): Boolean =
        state.kind == GameKind.WORD_CHAIN && optionsAfter(word, used).isEmpty()

    // ── 힌트·시간 ───────────────────────────────────────────

    /** 단어 뜻을 설명하는 힌트. 정답 단어 자체는 말하지 않는다. 설명이 끝나면 [RuleConfig.hintExtensionMs] 를 새로 준다. */
    fun requestHint(now: Long): HintResult {
        if (isFinished || state.paused) return HintResult.NotAccepting
        if (state.hintsLeft <= 0) return HintResult.NoHintsLeft
        // 힌트로 광고 소개가 나가지 않도록 브랜드 단어는 고르지 않는다.
        val target = optionsAfter(state.lastAiWord ?: return HintResult.NoCandidate, state.used.toSet())
            .filter { !it.isBrand }.minByOrNull { it.familiarity } ?: return HintResult.NoCandidate
        update {
            copy(
                hintsLeft = hintsLeft - 1, phase = WordPhase.AWAIT_USER,
                clock = clock.pause(now).restart(rules.hintExtensionMs),
            )
        }
        return HintResult.Hint(target.meaning, state.hintsLeft)
    }

    /** 시간 초과 힌트 제안을 거절. 시간이 이미 끝났으므로 패배 */
    fun declineHint(now: Long) {
        if (state.phase != WordPhase.OFFER_HINT) return
        finish(Outcome.LOSE, now)
    }

    /** 주기적으로 불러 시간 초과를 확인한다. 주행 모드는 제한시간이 없어 아무 일도 없다. */
    fun tick(now: Long): TickResult {
        if (!acceptingAnswer() || !state.clock.isExpired(now)) return TickResult.None
        val hintAvailable = state.hintsLeft > 0 &&
            optionsAfter(state.lastAiWord!!, state.used.toSet()).isNotEmpty()
        return if (hintAvailable) {
            update { copy(phase = WordPhase.OFFER_HINT, clock = clock.pause(now)) }
            TickResult.OfferHint
        } else {
            finish(Outcome.LOSE, now)
            TickResult.TimeUp
        }
    }

    fun speechStarted(now: Long) = update { copy(clock = clock.pause(now)) }

    fun speechFinished(now: Long) {
        if (acceptingAnswer()) update { copy(clock = clock.start(now)) }
    }

    // ── 진행 제어 ───────────────────────────────────────────

    /** 사용자 일시정지 또는 내비 안내·통화로 음성 우선권을 잃었을 때 */
    fun pause(now: Long) {
        if (isFinished) return
        update { copy(paused = true, clock = clock.pause(now)) }
    }

    /**
     * 재개. 앱이 마지막 내용을 다시 안내하고 [speechFinished] 를 부르면 남은 시간이 흐른다.
     * 힌트 제안 중에 멈췄으면(시간은 이미 0) 답 대기로 돌려 다음 [tick] 에서 다시 힌트를 묻게 한다.
     */
    fun resume() {
        if (isFinished) return
        update { copy(paused = false, phase = if (phase == WordPhase.OFFER_HINT) WordPhase.AWAIT_USER else phase) }
    }

    /**
     * 판 도중 차량 상태 변화. 움직이기 시작하면(또는 상태 불명) 제한시간을 즉시 없애고 힌트 제안도 거둔다.
     * 주차로 바뀌어도 이번 판에는 제한시간을 새로 걸지 않는다 — 규칙은 한 판 동안 고정, 다음 판부터 주차 규칙.
     */
    fun changeMode(mode: PlayMode) {
        if (isFinished || mode == state.mode || mode != PlayMode.DRIVING) return
        update {
            copy(
                mode = mode, clock = rules.clockFor(mode),
                phase = if (phase == WordPhase.OFFER_HINT) WordPhase.AWAIT_USER else phase,
            )
        }
    }

    fun giveUp(now: Long) = finish(Outcome.LOSE, now)

    /** 난이도·게임 변경, 종료 명령. 보상·통계 제외 */
    fun voidRound(now: Long) = finish(Outcome.VOID, now)

    /** 저장용 상태. 앱이 꺼져 있는 동안 시간이 줄지 않도록 시계를 멈춘 상태로 저장한다. */
    fun snapshot(now: Long): WordGameState = state.copy(clock = state.clock.pause(now))

    private fun finish(outcome: Outcome, now: Long) {
        if (isFinished) return
        update { copy(phase = WordPhase.FINISHED, outcome = outcome, clock = clock.pause(now)) }
    }

    private fun acceptingAnswer(): Boolean =
        !isFinished && !state.paused && state.phase == WordPhase.AWAIT_USER

    private inline fun update(change: WordGameState.() -> WordGameState) {
        state = state.change()
    }

    companion object {
        /** 초성 대결로 출제할 초성의 최소 후보 단어 수 */
        const val MIN_CHOSEONG_POOL = 6

        /** 새 판. AI 가 첫 단어를 정해 [WordGameState.lastAiWord] 에 둔다. 안내가 끝난 뒤 [speechFinished] 로 시간을 시작한다. */
        fun start(
            kind: GameKind,
            difficulty: Difficulty,
            mode: PlayMode,
            dictionary: WordDictionary,
            random: Random = Random.Default,
            roundId: String = newRoundId(random),
            rules: RuleConfig = RuleConfig.DEFAULT,
            brand: BrandRound? = null,
            planner: AdPlanner? = null,
        ): WordGame {
            require(kind.isWordGame) { "단어 게임이 아님: $kind" }
            require(brand == null || kind == GameKind.WORD_CHAIN) { "브랜드 단어 모드는 끝말잇기만" }
            val picker = WordPicker(random)
            val choseong = if (kind == GameKind.CHOSEONG) {
                dictionary.choseongPairs(MIN_CHOSEONG_POOL).randomOrNull(random)
                    ?: error("출제할 초성이 없음 — 사전 확인 필요")
            } else {
                null
            }
            val firstOptions = when (kind) {
                GameKind.CHOSEONG -> dictionary.withChoseong(choseong!!).filter { it.word.length == 2 }
                else -> dictionary.entries.filter { it.word.length >= 2 }
            }.filter { !it.isBrand }
            val game = WordGame(
                WordGameState(
                    roundId = roundId, kind = kind, difficulty = difficulty, mode = mode, choseong = choseong,
                    hintsLeft = rules.hintsFor(difficulty), clock = rules.clockFor(mode), brand = brand,
                ),
                dictionary, random, rules, planner,
            )
            // 첫 단어는 난이도와 관계없이 익숙한 단어 중 무작위(전략은 사용자 답부터)
            val first = picker.pick(firstOptions, Difficulty.NORMAL, avoid = { game.isDeadEnd(it.word, setOf(it.word)) })
                ?: error("첫 단어를 고를 수 없음 — 사전 확인 필요")
            game.update { copy(used = listOf(first.word), lastAiWord = first.word) }
            return game
        }

        /** 저장된 판을 불러온다. 재개 안내 전까지는 일시정지 상태다. */
        fun restore(
            state: WordGameState,
            dictionary: WordDictionary,
            random: Random = Random.Default,
            rules: RuleConfig = RuleConfig.DEFAULT,
            planner: AdPlanner? = null,
        ): WordGame = WordGame(state.copy(paused = state.outcome == null), dictionary, random, rules, planner)
    }
}

internal fun newRoundId(random: Random): String =
    buildString { repeat(16) { append("0123456789abcdef"[random.nextInt(16)]) } }
