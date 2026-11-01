package com.cihmine.carvoicegame.core.ad

import com.cihmine.carvoicegame.core.hangul.Hangul
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

@Serializable
enum class CampaignStatus { DRAFT, APPROVED, PAUSED, EXPIRED }

/** 실제 제휴 광고인지, 제휴 없이 시연용으로 만든 브랜드 소개인지 */
@Serializable
enum class ContentKind { DEMO_BRAND, PAID_SPONSOR }

/**
 * 광고주가 등록한 단어 하나와 승인된 소개 문구(docs/AD_WORD_STEERING_DESIGN.md 5.2).
 * 단어와 설명은 별개다 — 소개 문구에 없는 가격·출시일·성능을 앱이 만들어 말하지 않는다.
 */
@Serializable
data class Campaign(
    val campaignId: String,
    val version: Int,
    val status: CampaignStatus,
    /** 게임에서 쓰는 정식 표기(완성형 한글 2자 이상) */
    val word: String,
    /** 승인된 다른 표기. 같은 단어로 보고 중복 검사한다. */
    val aliases: List<String> = emptyList(),
    val contentKind: ContentKind,
    /** 광고 단어를 말하기 직전에 읽는 짧은 고지 */
    val disclosureText: String,
    val shortExplanation: String,
    val longExplanation: String? = null,
    val evidenceUrl: String? = null,
    val verifiedAt: String? = null,
    /** yyyy-MM-dd, 한국 시간 기준 그날 0시부터 */
    val validFrom: String,
    /** yyyy-MM-dd, 한국 시간 기준 그날 끝까지 */
    val validUntil: String,
    val priorityWeight: Double = 1.0,
) {
    fun isLive(nowEpochMs: Long): Boolean =
        status == CampaignStatus.APPROVED && nowEpochMs >= startOf(validFrom) && nowEpochMs < startOf(validUntil, plusDays = 1)

    /** 게임 사전에 넣을 항목. 쉬운 단어(1)로 둔다. 뜻풀이 자리에는 승인된 짧은 소개를 쓴다. */
    fun toEntry(): WordEntry = WordEntry(word, level = 1, meaning = shortExplanation, isBrand = true)

    private fun startOf(date: String, plusDays: Long = 0): Long =
        LocalDate.parse(date).plusDays(plusDays).atStartOfDay(KST).toInstant().toEpochMilli()
}

private val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** 등록 검사에서 자동 노출 목록에서 빠진 이유 */
enum class CampaignProblem {
    NOT_LIVE,
    BAD_WORD,
    MISSING_TEXT,

    /** 광고 단어 뒤에 사용자가 이을 쉬운 일반 단어가 부족(한방 노출 방지) */
    FEW_FAMILIAR_FOLLOW_UPS,
}

data class CampaignCheck(val campaign: Campaign, val familiarFollowUps: Int, val problems: List<CampaignProblem>) {
    val ok: Boolean get() = problems.isEmpty()
}

/** 앱에 들어가는 캠페인 목록. 네트워크 없이 로컬 파일과 승인 문구로 동작한다. */
class CampaignCatalog(val campaigns: List<Campaign>) {

    /**
     * 등록·검증(AD01). 승인·기간·표기·문구를 보고, 전체 사전에서 광고 단어 뒤에 이을 쉬운 단어가
     * [minFamiliarFollowUps] 개 이상인지 센다. 통과한 것만 판의 허용 목록과 자동 노출 후보가 된다.
     */
    fun check(dictionary: WordDictionary, nowEpochMs: Long, minFamiliarFollowUps: Int = AdConfig.DEFAULT.minFamiliarFollowUps): List<CampaignCheck> =
        campaigns.map { c ->
            val problems = mutableListOf<CampaignProblem>()
            if (!c.isLive(nowEpochMs)) problems += CampaignProblem.NOT_LIVE
            val wordOk = c.word.length >= 2 && c.word.all(Hangul::isSyllable)
            if (!wordOk) problems += CampaignProblem.BAD_WORD
            if (c.disclosureText.isBlank() || c.shortExplanation.isBlank()) problems += CampaignProblem.MISSING_TEXT
            val follow = if (wordOk) familiarFollowUps(dictionary, c.word, setOf(c.word)) else 0
            if (wordOk && follow < minFamiliarFollowUps) problems += CampaignProblem.FEW_FAMILIAR_FOLLOW_UPS
            CampaignCheck(c, follow, problems)
        }

    /** 이번 판에 허용할 캠페인. 판을 시작할 때 한 번 정하고 판 도중에는 바꾸지 않는다. */
    fun eligibleForRound(dictionary: WordDictionary, nowEpochMs: Long, config: AdConfig = AdConfig.DEFAULT): List<Campaign> =
        check(dictionary, nowEpochMs, config.minFamiliarFollowUps).filter { it.ok }.map { it.campaign }
            .distinctBy { it.word } // 한 단어가 여러 캠페인에 있으면 하나만(우선순위 높은 것은 목록 앞에 둔다)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CampaignCatalog = CampaignCatalog(json.decodeFromString<List<Campaign>>(text))

        fun loadDemo(): CampaignCatalog = parse(
            requireNotNull(CampaignCatalog::class.java.getResourceAsStream("/data/demo_campaigns.json")) { "demo_campaigns.json 없음" }
                .bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }
}

/** [word] 다음에 이을 수 있는 쉬운(1단계) 일반 단어 수. 브랜드 단어와 이미 쓴 단어는 세지 않는다. */
internal fun familiarFollowUps(dictionary: WordDictionary, word: String, used: Set<String>): Int =
    dictionary.startingWith(Hangul.startCharsAfter(word.last()))
        .count { it.level == 1 && !it.isBrand && it.word.length >= 2 && it.word !in used }
