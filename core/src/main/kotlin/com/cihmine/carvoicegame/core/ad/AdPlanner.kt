package com.cihmine.carvoicegame.core.ad

import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.hangul.Hangul
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordEntry
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * 광고 단어 유도의 수치(docs/AD_WORD_STEERING_DESIGN.md 2·6~8절). 모두 측정 전 개발 기본안이다.
 */
data class AdConfig(
    /** 사용자 유효 답변이 이만큼 쌓인 뒤의 AI 차례부터 광고 단어를 말할 수 있다. */
    val minUserTurns: Int = 4,
    /** 이전 광고 시작 후 다음 광고까지 최소 간격(판을 넘어서도 적용) */
    val minIntervalMs: Long = 10 * 60_000,
    /** 한 판에서 유도(STEERING)로 고를 수 있는 AI 차례 수. 다 쓰면 그 판 유도를 끝낸다. */
    val maxSteeringDecisions: Int = 3,
    val maxCandidates: Int = 12,
    val topReplies: Int = 20,
    val budgetMs: Long = 100,
    /** 최고 기본 점수와 이 차이 안의 후보만 광고 계획에 쓴다. */
    val qualityEpsilon: Double = 0.10,
    /** 유도로 고른 일반 단어의 추정 도달 가능성이 이보다 낮으면 유도하지 않는다. */
    val minReach: Double = 0.15,
    val gameplayWeight: Double = 0.7,
    val minFamiliarFollowUps: Int = 3,
) {
    companion object {
        val DEFAULT = AdConfig()
    }
}

/**
 * 브랜드 단어 허용 모드로 시작한 판의 광고 상태. 허용 목록([campaigns])은 판을 시작할 때 고정해
 * 광고를 끄거나 캠페인이 바뀌어도 이번 판의 판정이 흔들리지 않게 한다.
 */
@Serializable
data class BrandRound(
    val campaigns: List<Campaign>,
    /** "광고 꺼 줘"로 끄면 false. 허용 목록은 그대로 둔다. */
    val adEnabled: Boolean = true,
    /** 이번 판에 광고 단어를 이미 말했는지(한 판 최대 1회) */
    val adShown: Boolean = false,
    val steeringUsed: Int = 0,
    /** 판을 시작할 때 저장소에서 가져온 마지막 광고 시작 시각(벽시계) */
    val lastAdStartedAtEpochMs: Long? = null,
) {
    val entries: List<WordEntry> get() = campaigns.map { it.toEntry() }

    /** 승인된 다른 표기 → 정식 표기 */
    val aliases: Map<String, String> get() = campaigns.flatMap { c -> c.aliases.map { it to c.word } }.toMap()

    fun campaignFor(word: String): Campaign? = campaigns.firstOrNull { it.word == word }
}

@Serializable
enum class DecisionReason { NORMAL, DIRECT_AD, STEERING }

/** 개발자 패널용 진단 정보. 게임 판정에는 쓰지 않는다. */
@Serializable
data class PlanSummary(
    val reason: DecisionReason,
    val word: String?,
    val campaignId: String? = null,
    /** 모델상 추정 도달 가능성(실제 성공률 아님) */
    val reach: Double = 0.0,
    /** 탐색에 포함한 사용자 답의 확률 합. 나머지는 도달 기여 0으로 둔다. */
    val coverage: Double = 0.0,
    val elapsedMs: Long = 0,
    /** 일반 진행으로 돌아간 이유 */
    val fallback: String? = null,
)

data class PlanInput(
    /** 브랜드 단어가 들어간 이번 판의 사전 */
    val dictionary: WordDictionary,
    /** 규칙 엔진이 합법이라고 본 AI 후보 전체(브랜드 단어 포함) */
    val options: List<WordEntry>,
    val used: Set<String>,
    val difficulty: Difficulty,
    /** 방금 인정한 답을 포함한 사용자 유효 답변 수 */
    val validUserWords: Int,
    val brand: BrandRound,
    /** 광고 모듈을 껐을 때 일반 정책이 고른 단어 */
    val baseline: WordEntry?,
    val random: Random,
)

data class Decision(val chosen: WordEntry?, val summary: PlanSummary)

/** 처음에는 학습 없이 편집된 난이도 태그로 사용자 답의 상대 가중치를 정한다(8.1). 측정한 확률이 아니다. */
object ResponseModel {
    fun weight(entry: WordEntry): Double = when (entry.level) {
        1 -> 1.0
        2 -> 0.5
        else -> 0.25
    }
}

/** 모드·빈도·간격·시작 시점 같은 노출 조건 */
class AdEligibilityPolicy(private val config: AdConfig) {
    fun adsAllowed(brand: BrandRound, nowEpochMs: Long): Boolean =
        brand.adEnabled && !brand.adShown &&
            (brand.lastAdStartedAtEpochMs == null || nowEpochMs - brand.lastAdStartedAtEpochMs >= config.minIntervalMs)

    fun directAllowed(validUserWords: Int) = validUserWords >= config.minUserTurns

    /** 다음 AI 차례에 노출 조건을 채울 수 있고, 유도 횟수가 남았을 때만 유도한다. */
    fun steeringAllowed(brand: BrandRound, validUserWords: Int) =
        validUserWords + 1 >= config.minUserTurns && brand.steeringUsed < config.maxSteeringDecisions
}

/**
 * AI 가 말할 합법 단어 중 광고 단어가 자연스럽게 나올 기회를 만드는 단어를 고른다.
 * 규칙 엔진의 판정은 바꾸지 않고, 엔진이 준 후보 안에서만 고른다. 사용자 답은 고를 수 없으므로
 * 최댓값이 아니라 응답 모델의 확률 가중 평균으로 평가한다(7절, 탐색 깊이 H=2: AI → 사용자 → AI).
 */
class AdPlanner(
    private val config: AdConfig = AdConfig.DEFAULT,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val policy = AdEligibilityPolicy(config)

    fun choose(input: PlanInput): Decision {
        val startedNs = nanoTime()
        val now = wallClock()
        fun elapsedMs() = (nanoTime() - startedNs) / 1_000_000
        fun normal(fallback: String?, reach: Double = 0.0, coverage: Double = 0.0) = Decision(
            input.baseline,
            PlanSummary(DecisionReason.NORMAL, input.baseline?.word, reach = reach, coverage = coverage, elapsedMs = elapsedMs(), fallback = fallback),
        )

        val brand = input.brand
        if (!policy.adsAllowed(brand, now)) return normal("광고 꺼짐·이번 판 노출함·간격 제한")
        val live = brand.campaigns.filter { it.isLive(now) }
        if (live.isEmpty()) return normal("유효 캠페인 없음")
        val directOk = policy.directAllowed(input.validUserWords)
        val steerOk = policy.steeringAllowed(brand, input.validUserWords)
        if (!directOk && !steerOk) return normal("노출 시작 전·유도 횟수 소진")

        val ctx = Ctx(input, live, steerOk)
        val scored = input.options.map { it to ctx.gameplay(it, input.used) }
        val maxG = scored.maxOfOrNull { it.second } ?: return normal("후보 없음")
        // 광고 단어도 일반 품질 제한을 통과해야 하고, 지금 노출 조건을 채울 때만 후보에 넣는다.
        val near = scored.filter { (e, g) ->
            g >= maxG - config.qualityEpsilon && (!e.isBrand || (directOk && ctx.adEligible(e.word, input.used)))
        }
        if (near.isEmpty()) return normal("품질 조건을 통과한 후보 없음")

        val baselineReach = input.baseline?.let { ctx.reach(it) } ?: Reach.NONE
        var best: Triple<WordEntry, Double, Reach>? = null
        // 일반 정책은 같은 수준 후보 중 무작위로 고르므로, 평가한 일반 후보의 평균 도달 가능성을 일반 정책의 기대값으로 본다.
        var normalReachSum = 0.0
        var normalCount = 0
        for ((entry, g) in bounded(near, ctx, input.random)) {
            if (elapsedMs() > config.budgetMs) return normal("계획 시간 초과")
            val r = ctx.reach(entry)
            if (!entry.isBrand) {
                normalReachSum += r.q
                normalCount++
            }
            val score = config.gameplayWeight * g + (1 - config.gameplayWeight) * r.q
            if (best == null || score > best.second) best = Triple(entry, score, r)
        }
        val (chosen, _, r) = best ?: return normal("후보 없음")
        val normalExpectation = if (normalCount == 0) 0.0 else normalReachSum / normalCount

        if (chosen.isBrand) {
            val campaign = brand.campaignFor(chosen.word)!!
            return Decision(chosen, PlanSummary(DecisionReason.DIRECT_AD, chosen.word, campaign.campaignId, 1.0, 1.0, elapsedMs()))
        }
        if (!steerOk || r.q < config.minReach) return normal("도달 가능성 낮음", baselineReach.q, baselineReach.coverage)
        if (r.q <= normalExpectation) return normal("일반 선택보다 나아지지 않음", baselineReach.q, baselineReach.coverage)
        return Decision(chosen, PlanSummary(DecisionReason.STEERING, chosen.word, reach = r.q, coverage = r.coverage, elapsedMs = elapsedMs()))
    }

    /**
     * 계산량 제한: 광고 단어 → 사용자 답 하나로 광고까지 이어질 수 있는 단어 → 나머지(무작위) 순으로 최대 [AdConfig.maxCandidates] 개.
     * 무작위로만 자르면 이어질 수 있는 단어를 놓치기 쉬워, 연결 가능성을 싸게 먼저 걸러 앞에 둔다.
     */
    private fun bounded(near: List<Pair<WordEntry, Double>>, ctx: Ctx, random: Random): List<Pair<WordEntry, Double>> {
        val shuffled = near.shuffled(random)
        val (ads, rest) = shuffled.partition { it.first.isBrand }
        val (linkable, others) = rest.partition { ctx.mayLink(it.first) }
        return (ads + linkable + others).take(config.maxCandidates)
    }

    private data class Reach(val q: Double, val coverage: Double) {
        companion object {
            val NONE = Reach(0.0, 0.0)
        }
    }

    private inner class Ctx(private val input: PlanInput, private val live: List<Campaign>, private val steerOk: Boolean) {
        private val dictionary = input.dictionary

        private val adHeads: Set<Char> = live.map { it.word.first() }.toSet()

        fun repliesAfter(word: String, used: Set<String>): List<WordEntry> =
            dictionary.startingWith(Hangul.startCharsAfter(word.last())).filter { it.word.length >= 2 && it.word !in used }

        /** 기본 점수 G(a): 난이도에 맞는 수준일수록 높고, 상대가 이을 수 없는 한방 단어는 0 */
        fun gameplay(entry: WordEntry, used: Set<String>): Double {
            if (repliesAfter(entry.word, used + entry.word).isEmpty()) return 0.0
            return levelScore(entry.level)
        }

        fun levelScore(level: Int): Double = when (input.difficulty) {
            Difficulty.EASY -> when (level) { 1 -> 1.0; 2 -> 0.7; else -> 0.4 }
            Difficulty.NORMAL -> when (level) { 2 -> 1.0; 1 -> 0.8; else -> 0.7 }
            Difficulty.HARD -> when (level) { 1 -> 0.7; else -> 1.0 }
        }

        /** 광고 단어를 지금(또는 [used] 상태에서) 말해도 되는지: 유효 캠페인·미사용·쉬운 후속 단어 수 */
        fun adEligible(word: String, used: Set<String>): Boolean =
            word !in used && live.any { it.word == word } &&
                familiarFollowUps(dictionary, word, used + word) >= config.minFamiliarFollowUps

        fun mayLink(entry: WordEntry): Boolean =
            repliesAfter(entry.word, input.used + entry.word).any { u -> Hangul.startCharsAfter(u.word.last()).any { it in adHeads } }

        /**
         * Q(s,a,H=2). 광고 단어면 1. 일반 단어면 a 와 사용자 답 u 를 차례로 사용 목록에 넣은 뒤,
         * 다음 AI 차례에 품질 조건을 통과하는 광고 단어가 하나라도 있는 답의 확률을 더한다.
         * 상위 [AdConfig.topReplies] 개만 보며 확률을 다시 1로 맞추지 않는다(나머지는 기여 0, coverage 로 따로 기록).
         */
        fun reach(entry: WordEntry): Reach {
            if (entry.isBrand) return Reach(1.0, 1.0)
            if (!steerOk) return Reach.NONE
            val afterAi = input.used + entry.word
            val replies = repliesAfter(entry.word, afterAi)
            val total = replies.sumOf(ResponseModel::weight)
            if (total == 0.0) return Reach.NONE
            val top = replies.sortedWith(compareByDescending<WordEntry> { ResponseModel.weight(it) }.thenBy { it.word })
                .take(config.topReplies)
            var q = 0.0
            var coverage = 0.0
            for (u in top) {
                val p = ResponseModel.weight(u) / total
                coverage += p
                if (nextHasAd(u, afterAi + u.word)) q += p
            }
            return Reach(q, coverage)
        }

        /** 사용자 답 [user] 뒤 AI 차례에 노출 가능한 광고 단어가 하나 이상 있는지(목표가 여럿이어도 한 번만 센다) */
        private fun nextHasAd(user: WordEntry, used: Set<String>): Boolean {
            val heads = Hangul.startCharsAfter(user.word.last())
            val ads = live.filter { it.word.first() in heads && adEligible(it.word, used) }
            if (ads.isEmpty()) return false
            // 품질 조건: 그 상태의 최고 수준 일반 후보와 비교한다(한방 여부는 생략한 근사).
            val bestLevel = repliesAfter(user.word, used).filter { !it.isBrand }.maxOfOrNull { levelScore(it.level) } ?: 0.0
            return levelScore(1) >= bestLevel - config.qualityEpsilon
        }
    }
}
