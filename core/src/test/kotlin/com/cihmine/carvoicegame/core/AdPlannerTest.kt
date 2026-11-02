package com.cihmine.carvoicegame.core

import com.cihmine.carvoicegame.core.ad.AdConfig
import com.cihmine.carvoicegame.core.ad.AdPlanner
import com.cihmine.carvoicegame.core.ad.BrandRound
import com.cihmine.carvoicegame.core.ad.Campaign
import com.cihmine.carvoicegame.core.ad.CampaignCatalog
import com.cihmine.carvoicegame.core.ad.CampaignProblem
import com.cihmine.carvoicegame.core.ad.CampaignStatus
import com.cihmine.carvoicegame.core.ad.ContentKind
import com.cihmine.carvoicegame.core.ad.DecisionReason
import com.cihmine.carvoicegame.core.word.RejectReason
import com.cihmine.carvoicegame.core.word.RuleConfig
import com.cihmine.carvoicegame.core.word.TurnResult
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordEntry
import com.cihmine.carvoicegame.core.word.WordGame
import com.cihmine.carvoicegame.core.word.WordGameState
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/AD_WORD_STEERING_DESIGN.md 14절 시험 목록(A01~A18) 중 core 에서 확인할 수 있는 것 */
class AdPlannerTest {
    private val now = ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    private fun campaign(word: String, id: String = "c-$word", aliases: List<String> = emptyList(), until: String = "2026-12-31") = Campaign(
        campaignId = id, version = 1, status = CampaignStatus.APPROVED, word = word, aliases = aliases,
        contentKind = ContentKind.DEMO_BRAND, disclosureText = "브랜드 소개 데모 단어예요.",
        shortExplanation = "시연용 예시예요.", validFrom = "2026-01-01", validUntil = until,
    )

    /**
     * 상표→표현→현대 구조를 담은 작은 사전. 상 으로 시작하는 AI 후보는 상표·상자,
     * 상표 뒤 사용자 답은 표현·표정(같은 가중치), 현대 뒤에는 쉬운 단어 셋(대문·대장·대나무).
     */
    private val dict = WordDictionary(
        listOf(
            "국밥", "밥상", "상표", "상자", "표현", "표정", "정원", "원두", "자석", "석류",
            "현관", "관객", "대문", "대장", "대나무", "문어", "장미", "무지개", "그물", "물개", "저녁", "녁녁",
        ).map { WordEntry(it, 1, "뜻") },
    )

    private fun game(
        lastAiWord: String,
        used: List<String> = listOf(lastAiWord),
        validUserWords: Int = 3,
        brand: BrandRound? = BrandRound(listOf(campaign("현대"))),
        planner: AdPlanner = AdPlanner(wallClock = { now }),
        dictionary: WordDictionary = dict,
        seed: Int = 1,
    ): WordGame = WordGame.restore(
        WordGameState(
            roundId = "r1", kind = GameKind.WORD_CHAIN, difficulty = Difficulty.EASY, mode = PlayMode.PARKED,
            used = used, lastAiWord = lastAiWord, hintsLeft = 2, clock = RuleConfig.DEFAULT.clockFor(PlayMode.PARKED),
            validUserWords = validUserWords, brand = brand,
        ),
        dictionary, Random(seed), planner = planner,
    ).also { it.resume() }

    private fun aiWord(result: TurnResult): String? = assertIs<TurnResult.Accepted>(result).aiWord

    @Test
    fun `A01 지금 말할 수 있는 광고 단어가 있으면 바로 노출한다`() {
        val g = game("발표", used = listOf("발표"))
        assertEquals("현대", aiWord(g.submit(listOf("표현"), now = 0)))
        assertEquals("c-현대", g.state.lastAiAdCampaignId)
        assertEquals(DecisionReason.DIRECT_AD, g.state.lastPlan?.reason)
        assertTrue(g.state.brand!!.adShown)
    }

    @Test
    fun `A01 노출 시작 전(유효 답변 4회 미만)에는 광고 단어를 말하지 않는다`() {
        val g = game("발표", used = listOf("발표"), validUserWords = 0)
        assertEquals("현관", aiWord(g.submit(listOf("표현"), now = 0)))
        assertNull(g.state.lastAiAdCampaignId)
    }

    @Test
    fun `A02 A09 상표 표현 현대 경로 - 사용자 답은 확률 평균으로 평가하고 올바른 차례에만 광고 단어를 낸다`() {
        val g = game("국밥", used = listOf("국밥"))
        assertEquals("상표", aiWord(g.submit(listOf("밥상"), now = 0)))
        val plan = g.state.lastPlan!!
        assertEquals(DecisionReason.STEERING, plan.reason)
        // 표현·표정 중 표현만 현대로 이어지므로 1.0(사용자 답을 최대로 가정)이 아니라 0.5
        assertEquals(0.5, plan.reach, 1e-9)
        assertNull(g.state.lastAiAdCampaignId) // 유도 단어는 광고가 아니다

        assertEquals("현대", aiWord(g.submit(listOf("표현"), now = 0)))
        assertEquals("c-현대", g.state.lastAiAdCampaignId)
    }

    @Test
    fun `A03 예상 밖 합법 답은 그대로 인정하고 새 상태에서 다시 고른다`() {
        val g = game("국밥", used = listOf("국밥"))
        g.submit(listOf("밥상"), now = 0)
        assertEquals("정원", aiWord(g.submit(listOf("표정"), now = 0)))
        assertNull(g.state.lastAiAdCampaignId)
        assertEquals(5, g.state.validUserWords)
    }

    @Test
    fun `A04 광고 단어가 이미 쓰였으면 그 경로를 버린다`() {
        val g = game("발표", used = listOf("현대", "발표"))
        assertEquals("현관", aiWord(g.submit(listOf("표현"), now = 0)))
    }

    @Test
    fun `A05 광고 뒤 쉬운 후속 단어가 부족하면 노출하지 않는다`() {
        // 등록 검사: 녁 으로 이을 쉬운 단어는 녁녁 하나뿐(두음 역 없음)
        val check = CampaignCatalog(listOf(campaign("저녁"), campaign("현대"))).check(dict, now)
        assertEquals(listOf(CampaignProblem.FEW_FAMILIAR_FOLLOW_UPS), check.first { it.campaign.word == "저녁" }.problems)
        assertTrue(check.first { it.campaign.word == "현대" }.ok)

        // 실행 중: 대문이 이미 쓰여 후속 단어가 2개만 남으면 현대를 말하지 않는다
        val g = game("발표", used = listOf("대문", "발표"))
        assertEquals("현관", aiWord(g.submit(listOf("표현"), now = 0)))
    }

    @Test
    fun `A05 실제 데모 캠페인 - 사전이 커져 아이오닉도 이을 쉬운 단어(닉네임·익일 등)가 생겨 노출된다`() {
        val checks = CampaignCatalog.loadDemo().check(WordDictionary.loadSample(), now)
        val byWord = checks.associateBy { it.campaign.word }
        assertTrue(byWord.getValue("현대").ok, "현대: ${byWord.getValue("현대")}")
        assertTrue(byWord.getValue("그랜저").ok, "그랜저: ${byWord.getValue("그랜저")}")
        assertTrue(byWord.getValue("아이오닉").ok, "아이오닉: ${byWord.getValue("아이오닉")}")
    }

    @Test
    fun `A06 광고 끔·일반 모드·만료 캠페인이면 유도하지 않는다`() {
        val off = game("발표", used = listOf("발표")).also { it.disableAds() }
        assertEquals("현관", aiWord(off.submit(listOf("표현"), now = 0)))

        val expired = game("발표", used = listOf("발표"), brand = BrandRound(listOf(campaign("현대", until = "2026-10-31"))))
        assertEquals("현관", aiWord(expired.submit(listOf("표현"), now = 0)))

        // 일반 모드는 브랜드 단어가 사전에 없다
        val normal = game("세대", used = listOf("세대"), brand = null)
        assertIs<TurnResult.Reconfirm>(normal.submit(listOf("현대"), now = 0))
    }

    @Test
    fun `A06 광고 간격 10분이 지나지 않았으면 노출하지 않는다`() {
        val g = game("발표", used = listOf("발표"), brand = BrandRound(listOf(campaign("현대")), lastAdStartedAtEpochMs = now - 5 * 60_000))
        assertEquals("현관", aiWord(g.submit(listOf("표현"), now = 0)))
    }

    @Test
    fun `A07 같은 답 뒤에 목표가 둘이어도 도달 가능성을 겹쳐 세지 않는다`() {
        val withTwo = dict.plus(listOf(WordEntry("현수", 1, "뜻"), WordEntry("수박", 1, "뜻"), WordEntry("수건", 1, "뜻"), WordEntry("수레", 1, "뜻")))
        val g = game("국밥", used = listOf("국밥"), brand = BrandRound(listOf(campaign("현대"), campaign("현수"))), dictionary = withTwo)
        assertEquals("상표", aiWord(g.submit(listOf("밥상"), now = 0)))
        assertEquals(0.5, g.state.lastPlan!!.reach, 1e-9)
    }

    @Test
    fun `A08 상위 일부 답만 볼 때 확률을 다시 1로 맞추지 않는다`() {
        val g = game("국밥", used = listOf("국밥"), planner = AdPlanner(AdConfig(topReplies = 1), wallClock = { now }))
        g.submit(listOf("밥상"), now = 0)
        val plan = g.state.lastPlan!!
        // 같은 가중치면 가나다순으로 표정만 남는다 → 도달 0, 포함한 확률 0.5
        assertEquals(DecisionReason.NORMAL, plan.reason)
        assertEquals(0.0, plan.reach, 1e-9)
    }

    @Test
    fun `A10 계획 시간이 넘으면 일반 답을 쓴다`() {
        var t = 0L
        val slow = AdPlanner(wallClock = { now }, nanoTime = { t.also { t += 200_000_000 } })
        val g = game("국밥", used = listOf("국밥"), planner = slow)
        g.submit(listOf("밥상"), now = 0)
        assertEquals(DecisionReason.NORMAL, g.state.lastPlan!!.reason)
        assertEquals("계획 시간 초과", g.state.lastPlan!!.fallback)
    }

    @Test
    fun `A12 설명을 듣는 동안 같은 차례와 남은 시간이 유지되고 보상 상태가 바뀌지 않는다`() {
        val g = game("발표", used = listOf("발표"))
        g.submit(listOf("표현"), now = 0) // AI: 현대
        g.speechFinished(now = 1_000) // 10초 시작
        val before = g.state
        g.speechStarted(now = 4_000) // "그게 뭐야?" → 설명 시작, 6초 남음
        g.speechFinished(now = 20_000) // 설명 끝 → 다시 흐름
        assertEquals(7_000, g.state.clock.remaining(20_000))
        assertEquals(before.turn, g.state.turn)
        assertEquals(before.validUserWords, g.state.validUserWords)
        assertEquals(before.lastAiWord, g.state.lastAiWord)
    }

    @Test
    fun `A15 사용자가 목표 단어를 먼저 말하면 인정하고 AI 노출로 세지 않는다`() {
        val g = game("발현", used = listOf("발현"))
        val ai = aiWord(g.submit(listOf("현대"), now = 0))
        assertTrue(ai!!.startsWith("대"))
        assertNull(g.state.lastAiAdCampaignId)
        assertEquals(false, g.state.brand!!.adShown)

        // 브랜드 단어도 일반 단어와 같은 규칙: 시작 글자가 다르면 오답
        val wrongStart = game("세대", used = listOf("세대"))
        assertEquals(RejectReason.WRONG_START, assertIs<TurnResult.Rejected>(wrongStart.submit(listOf("현대"), now = 0)).reason)
    }

    @Test
    fun `A16 유도 횟수를 다 쓰면 그 판 유도를 끝낸다`() {
        val g = game("국밥", used = listOf("국밥"), brand = BrandRound(listOf(campaign("현대")), steeringUsed = 3))
        g.submit(listOf("밥상"), now = 0)
        assertEquals(DecisionReason.NORMAL, g.state.lastPlan!!.reason)
    }

    @Test
    fun `A16 유도로 고른 차례 수를 센다`() {
        val g = game("국밥", used = listOf("국밥"))
        g.submit(listOf("밥상"), now = 0)
        assertEquals(1, g.state.brand!!.steeringUsed)
    }

    @Test
    fun `A17 승인된 다른 표기는 같은 단어로 중복 검사한다`() {
        val withGrandeur = dict.plus(listOf(WordEntry("미그", 1, "뜻"), WordEntry("저울", 1, "뜻"), WordEntry("저금", 1, "뜻")))
        val brand = BrandRound(listOf(campaign("그랜저", aliases = listOf("그랜져"))))
        val g = game("미그", used = listOf("미그"), brand = brand, dictionary = withGrandeur)
        assertIs<TurnResult.Accepted>(g.submit(listOf("그랜져"), now = 0))
        assertTrue("그랜저" in g.state.used)
        assertTrue("그랜져" !in g.state.used)
    }

    @Test
    fun `A18 광고를 끈 브랜드 모드는 일반 모드와 같은 단어를 고른다`() {
        for (seed in 1..10) {
            val normal = game("국밥", used = listOf("국밥"), brand = null, seed = seed)
            val off = game("국밥", used = listOf("국밥"), seed = seed).also { it.disableAds() }
            assertEquals(normal.submit(listOf("밥상"), now = 0), off.submit(listOf("밥상"), now = 0))
            // 오답 판정과 오답 추가 시간도 같다
            assertEquals(normal.submit(listOf("국밥"), now = 0), off.submit(listOf("국밥"), now = 0))
            assertEquals(normal.state.clock, off.state.clock)
        }
    }

    @Test
    fun `낯선 단어를 되묻는 말은 설명 요청으로 알아듣는다`() {
        val parser = com.cihmine.carvoicegame.core.command.CommandParser()
        for (said in listOf("그게 뭐야?", "그런 단어가 있어?", "그런 말도 있어?", "무슨 차야?", "처음 들어 봐")) {
            assertEquals(
                com.cihmine.carvoicegame.core.command.ParsedUtterance.Command(com.cihmine.carvoicegame.core.command.VoiceCommand.EXPLAIN_WORD),
                parser.parse(said),
                said,
            )
        }
    }

    @Test
    fun `잘못 들었어로 광고 단어 차례를 되돌리면 광고 표시도 이전으로 돌아가지만 노출 사실은 남는다`() {
        val g = game("발표", used = listOf("발표"))
        g.submit(listOf("표현"), now = 0)
        g.correctLast(now = 0)
        assertNull(g.state.lastAiAdCampaignId)
        assertTrue(g.state.brand!!.adShown)
    }

    @Test
    fun `저장 후 복원해도 브랜드 단어와 광고 상태가 유지된다`() {
        val g = game("발표", used = listOf("발표"))
        g.submit(listOf("표현"), now = 0)
        val restored = WordGame.restore(
            com.cihmine.carvoicegame.core.save.SaveCodec.decode(
                com.cihmine.carvoicegame.core.save.SaveCodec.encode(com.cihmine.carvoicegame.core.save.SaveData(wordGame = g.snapshot(0))),
            ).wordGame!!,
            dict, Random(1), planner = AdPlanner(wallClock = { now }),
        )
        assertEquals("c-현대", restored.state.lastAiAdCampaignId)
        assertTrue(restored.state.brand!!.adShown)
        restored.resume()
        assertIs<TurnResult.Accepted>(restored.submit(listOf("대문"), now = 0)) // 현대 → 대문
    }

    @Test
    fun `실제 사전에서 계획 시간 측정(참고용)`() {
        val dictionary = WordDictionary.loadSample()
        val brand = BrandRound(CampaignCatalog.loadDemo().eligibleForRound(dictionary, now))
        val planner = AdPlanner(wallClock = { now })
        val users = dictionary.entries.filter { it.level == 1 && it.word.length in 2..3 }.shuffled(Random(3)).take(60)
        val times = mutableListOf<Long>()
        var fallbacks = 0
        for (u in users) {
            val g = WordGame.restore(
                WordGameState(
                    roundId = "p", kind = GameKind.WORD_CHAIN, difficulty = Difficulty.EASY, mode = PlayMode.DRIVING,
                    used = listOf("가${u.word.first()}"), lastAiWord = "가${u.word.first()}", hintsLeft = 0,
                    clock = RuleConfig.DEFAULT.clockFor(PlayMode.DRIVING), validUserWords = 3, brand = brand,
                ),
                dictionary, Random(1), planner = planner,
            ).also { it.resume() }
            if (g.submit(listOf(u.word), now = 0) !is TurnResult.Accepted) continue
            val plan = g.state.lastPlan ?: continue
            times += plan.elapsedMs
            if (plan.fallback == "계획 시간 초과") fallbacks++
        }
        times.sort()
        println("계획 시간(ms) n=${times.size} p50=${times[times.size / 2]} p95=${times[(times.size * 95) / 100]} 초과=$fallbacks")
        assertTrue(times.isNotEmpty())
    }
}
