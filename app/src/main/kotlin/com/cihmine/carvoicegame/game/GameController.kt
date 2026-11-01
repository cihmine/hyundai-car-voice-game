package com.cihmine.carvoicegame.game

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cihmine.carvoicegame.DebugLog
import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.Outcome
import com.cihmine.carvoicegame.core.RewardPolicy
import com.cihmine.carvoicegame.core.ad.AdEvent
import com.cihmine.carvoicegame.core.ad.AdEventType
import com.cihmine.carvoicegame.core.ad.AdPlanner
import com.cihmine.carvoicegame.core.ad.BrandRound
import com.cihmine.carvoicegame.core.ad.CampaignCatalog
import com.cihmine.carvoicegame.core.command.CommandParser
import com.cihmine.carvoicegame.core.command.ParsedUtterance
import com.cihmine.carvoicegame.core.command.SpeechRate
import com.cihmine.carvoicegame.core.command.VoiceCommand
import com.cihmine.carvoicegame.core.hangul.Hangul
import com.cihmine.carvoicegame.core.PlayMode
import com.cihmine.carvoicegame.core.port.SpeechError
import com.cihmine.carvoicegame.core.port.SpeechInput
import com.cihmine.carvoicegame.core.save.RoundRecord
import com.cihmine.carvoicegame.core.save.SaveData
import com.cihmine.carvoicegame.core.suggestNextDifficulty
import com.cihmine.carvoicegame.core.word.CorrectionResult
import com.cihmine.carvoicegame.core.word.HintResult
import com.cihmine.carvoicegame.core.word.RuleConfig
import com.cihmine.carvoicegame.core.word.TickResult
import com.cihmine.carvoicegame.core.word.TurnResult
import com.cihmine.carvoicegame.core.word.WordDictionary
import com.cihmine.carvoicegame.core.word.WordGame
import com.cihmine.carvoicegame.core.word.WordGameState
import com.cihmine.carvoicegame.core.word.WordPhase
import com.cihmine.carvoicegame.data.FileSaveStore
import com.cihmine.carvoicegame.data.OnlineDictionary
import com.cihmine.carvoicegame.speech.Voice
import com.cihmine.carvoicegame.speech.AudioFocus
import com.cihmine.carvoicegame.vehicle.MockVehicleStatus
import com.cihmine.carvoicegame.vehicle.VehicleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { HOME, PLAYING, RESULT, LOCKER }

enum class Mic { OFF, SPEAKING, LISTENING }

/** 예/아니요로 답을 기다리는 질문 */
enum class Ask { NONE, RULES, HINT, QUIT, RESUME, CONCEDE }

data class LogLine(val fromUser: Boolean, val text: String)

data class GameUi(
    val screen: Screen = Screen.HOME,
    val round: WordGameState? = null,
    val mic: Mic = Mic.OFF,
    val ask: Ask = Ask.NONE,
    val remainingMs: Long? = null,
    val log: List<LogLine> = emptyList(),
    val save: SaveData = SaveData(),
    val lastReward: Int = 0,
    val vehicle: VehicleState = VehicleState.PARKED,
    val ttsReady: Boolean = false,
    val sttAvailable: Boolean = false,
    val ttsEngine: String = "",
    val sttEngine: String = "",
    val micGranted: Boolean = false,
    val notice: String? = null,
    /** 단어 검색 중계 서버 주소가 설정돼 있는지(실제 연결 여부는 요청 때 판단) */
    val onlineDictionary: Boolean = false,
)

/**
 * 끝말잇기·초성 대결 한 판의 음성 진행. 판정·시간·보상은 [WordGame]·[RewardPolicy] 가 정하고,
 * 여기서는 말하기 → 듣기 → 판정 → 저장 순서와 질문(힌트·종료 확인 등)만 다룬다.
 * 시각은 단조 시계(elapsedRealtime). 상태가 바뀔 때마다 바로 저장한다.
 */
class GameController(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val TAG = "GameFlow"
    }

    private val rules = RuleConfig.DEFAULT
    private val dictionary = WordDictionary.loadSample()
    private val catalog = CampaignCatalog.loadDemo()
    private val planner = AdPlanner()
    private val online = OnlineDictionary.fromBuildConfig()
    private val store = FileSaveStore(app.filesDir)
    val vehicle = MockVehicleStatus()

    // 음성 엔진의 준비 콜백이 생성 중에 바로 올 수 있어 상태를 먼저 만든다.
    private val _ui = MutableStateFlow(GameUi(save = store.load() ?: SaveData()))
    val ui: StateFlow<GameUi> = _ui

    private val stt = Voice.input(app) { ok -> _ui.update { it.copy(sttAvailable = ok) } }
    private val tts = Voice.output(app) { ok -> _ui.update { it.copy(ttsReady = ok) } }
    private val focus = AudioFocus(app, onLost = { pause(byAudioFocus = true) }, onRegained = ::offerResume)

    private var game: WordGame? = null
    private var listenToken = 0
    private var ticker: Job? = null
    private var pausedByAudioFocus = false

    /** 지금 읽고 있는 광고 단어(중단 기록용) */
    private var speakingAd: AdEvent? = null

    /** 판정 전 인터넷 검색 중. 그동안 들어온 말·버튼은 무시한다. */
    private var busy = false

    /** AI 가 마지막 단어를 다 말한 시각과 그 차례(주행 중 한방에 막혔는지 판단) */
    private var aiSpokeAt: Long = 0
    private var concedeOfferedTurn = -1

    private val save get() = _ui.value.save
    private val parser get() = CommandParser(save.personalCommands)

    init {
        _ui.update { it.copy(sttEngine = stt.engineName, ttsEngine = tts.engineName, onlineDictionary = online != null) }
        viewModelScope.launch {
            vehicle.state.collect { state ->
                _ui.update { it.copy(vehicle = state) }
                onVehicleChanged(state)
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    fun setMicGranted(granted: Boolean) = _ui.update { it.copy(micGranted = granted) }

    // ── 판 시작·복원 ─────────────────────────────────────────

    fun start(kind: GameKind, difficulty: Difficulty) {
        stopVoice()
        focus.acquire()
        game = WordGame.start(kind, difficulty, vehicle.currentMode(), dictionary, rules = rules, brand = brandRoundFor(kind), planner = planner)
        _ui.update { it.copy(screen = Screen.PLAYING, log = emptyList(), lastReward = 0, notice = null) }
        publish()
        if (kind !in save.rulesOffered) {
            updateSave { copy(rulesOffered = rulesOffered + kind) }
            ask(Ask.RULES, Phrases.askRules(kind))
        } else {
            speakOpening()
        }
    }

    /**
     * 브랜드 단어 허용 모드를 골랐으면 지금 유효한 데모 캠페인으로 이번 판 허용 목록을 고정한다.
     * 광고 간격은 판을 넘어 적용되도록 저장된 마지막 광고 시각을 넘긴다.
     */
    private fun brandRoundFor(kind: GameKind): BrandRound? {
        if (kind != GameKind.WORD_CHAIN || !save.brandMode) return null
        val campaigns = catalog.eligibleForRound(dictionary, System.currentTimeMillis())
        if (campaigns.isEmpty()) return null
        return BrandRound(campaigns, lastAdStartedAtEpochMs = save.adLog.lastStartedAtEpochMs)
    }

    fun setBrandMode(on: Boolean) = updateSave { copy(brandMode = on) }

    /** 강제 종료 등으로 남은 판. 일시정지 상태로 불러오고 사용자가 이어하기를 눌러야 진행한다. */
    fun continueSaved() {
        val state = save.wordGame?.takeIf { it.outcome == null } ?: return
        game = WordGame.restore(state, dictionary, rules = rules, planner = planner)
        _ui.update { it.copy(screen = Screen.PLAYING, log = emptyList(), notice = "일시정지된 판을 불러왔어요") }
        publish()
    }

    private fun speakOpening() {
        val s = game?.state ?: return
        say(Phrases.opening(s.kind, s.difficulty, s.choseong, s.lastAiWord!!)) { aiSpoke() }
    }

    // ── 말하기·듣기 ─────────────────────────────────────────

    private fun say(text: String, then: () -> Unit) {
        DebugLog.d(TAG) { "say: $text" }
        stt.cancel()
        listenToken++
        game?.speechStarted(now())
        log(false, text)
        _ui.update { it.copy(mic = Mic.SPEAKING) }
        if (_ui.value.ttsReady) tts.speak(text, save.speechRatePercent, then) else then()
    }

    /** AI 차례 안내가 끝남 → 사용자 시간이 흐르기 시작하고 듣는다. */
    private fun aiSpoke() {
        val g = game ?: return
        g.speechFinished(now())
        aiSpokeAt = now()
        publish()
        listen()
    }

    private fun listen() {
        val g = game ?: return
        if (g.isFinished || g.state.paused) return
        val token = ++listenToken
        val turn = g.state.turn
        _ui.update { it.copy(mic = Mic.LISTENING) }
        ensureTicker()
        if (!_ui.value.micGranted || !_ui.value.sttAvailable) return // 시연 패널 글자 입력으로 진행
        stt.listen(object : SpeechInput.Listener {
            override fun onSpeechStart() {
                if (token == listenToken) game?.userSpeechStarted(now())
            }

            override fun onResult(candidates: List<String>) {
                if (token == listenToken) onUtterance(candidates, turn)
            }

            override fun onError(error: SpeechError) {
                if (token != listenToken) return
                // 말이 없어서 끊긴 것이면 조용히 다시 듣는다. 시간 초과 판정은 엔진 시계가 한다.
                if (error == SpeechError.NO_MATCH || error == SpeechError.TIMEOUT) listen()
                else _ui.update { it.copy(mic = Mic.OFF, notice = "음성 인식 오류(${error.name}) — 글자 입력을 써 주세요") }
            }
        })
    }

    /** 화면 버튼. 음성 명령과 같은 처리를 한다. */
    fun press(command: VoiceCommand) {
        val g = game ?: return
        if (g.isFinished || g.state.paused || busy) return
        stt.cancel()
        listenToken++
        command(command)
    }

    /** 시연 패널: 내비 안내·통화로 음성 우선권을 잠깐 잃었다가 3초 뒤 되찾는 상황 */
    fun simulateAudioInterruption() {
        pause(byAudioFocus = true)
        viewModelScope.launch {
            delay(3_000)
            offerResume()
        }
    }

    /** 시연 패널 글자 입력. 음성 인식 결과와 같은 길로 처리한다. */
    fun typed(text: String) {
        if (text.isBlank() || busy) return
        stt.cancel()
        listenToken++
        onUtterance(listOf(text), game?.state?.turn)
    }

    private fun onUtterance(candidates: List<String>, turn: Int?) {
        DebugLog.d(TAG) { "heard: $candidates ask=${_ui.value.ask}" }
        val g = game ?: return
        if (busy) return
        val top = candidates.first()
        log(true, top)
        val parsed = parser.parse(top) { dictionary.lookup(it) != null && _ui.value.ask == Ask.NONE }
        val ask = _ui.value.ask
        if (ask != Ask.NONE) return answerAsk(ask, parsed)
        if (g.state.paused) {
            if (parsed == ParsedUtterance.Command(VoiceCommand.RESUME)) resume()
            return
        }
        when (parsed) {
            is ParsedUtterance.Command -> command(parsed.command)
            is ParsedUtterance.SetSpeechRate -> setRate(SpeechRate.set(parsed.percent))
            ParsedUtterance.ConfirmQuit -> ask(Ask.QUIT, Phrases.ASK_QUIT)
            ParsedUtterance.NotCommand -> judge(candidates, turn)
        }
    }

    /**
     * 판정 전에 인터넷 사전으로 보조한다(서버가 설정돼 있을 때만, 최대 3초).
     * - 앱 사전에 없는 사용자 말 → 정말 있는 명사면 이번 판 사전에 더해 인정한다.
     * - 어려움: AI 가 앱 사전으로 막히면 이을 단어를 찾아오고, 한방 후보는 정말 이을 단어가 없는지 확인한다.
     * 검색하는 동안 사용자 시계는 멈춘다. 실패하면 앱 사전만으로 판정한다.
     */
    private fun judge(candidates: List<String>, turn: Int?) {
        val g = game ?: return
        val dict = online
        val userWord = g.acceptableWord(candidates)
        val toLookUp = g.wordToLookUp(candidates)
        val word = userWord ?: toLookUp
        val needsHelp = word != null && (toLookUp != null || g.aiSearchHeads(word) != null || g.hanbangCandidates(word).isNotEmpty())
        if (dict == null || !needsHelp) return judgeNow(candidates, turn)

        busy = true
        g.speechStarted(now()) // 검색하는 동안 사용자 시간을 멈춘다
        _ui.update { it.copy(notice = "단어를 찾아보는 중…") }
        viewModelScope.launch {
            withTimeoutOrNull(3_000) {
                if (toLookUp != null) dict.exact(toLookUp)?.let { g.learn(it.entries) }
                val accepted = g.acceptableWord(candidates) ?: return@withTimeoutOrNull
                g.aiSearchHeads(accepted)?.forEach { head -> dict.startingWith(head)?.let { g.learn(it.entries) } }
                // 한방 후보: 끝 글자(두음 변형 포함)로 시작하는 단어가 인터넷에도 하나도 없을 때만 쓴다.
                val verified = g.hanbangCandidates(accepted).map { candidate ->
                    async {
                        val results = Hangul.startCharsAfter(candidate.last()).map { dict.startingWith(it) }
                        when {
                            results.any { it == null } -> null // 확인 실패(오프라인)면 쓰지 않는다
                            results.all { it!!.anyWords == 0 } -> candidate
                            else -> {
                                results.forEach { g.learn(it!!.entries) }
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull().toSet()
                g.setVerifiedDeadEnds(verified)
            }
            busy = false
            _ui.update { it.copy(notice = null) }
            judgeNow(candidates, turn)
        }
    }

    private fun judgeNow(candidates: List<String>, turn: Int?) {
        val g = game ?: return
        when (val r = g.submit(candidates, now(), turn)) {
            is TurnResult.Accepted -> {
                if (r.aiWord == null) {
                    val points = RewardPolicy.forResult(g.state.kind, g.state.difficulty, Outcome.WIN)
                    finishRound(Phrases.userWins(r.userWord, points))
                } else {
                    persist()
                    if (g.state.lastAiAdCampaignId != null) sayAdWord() else say(r.aiWord!!) { aiSpoke() }
                }
            }
            is TurnResult.Rejected -> {
                persist()
                say("${r.word}, ${r.reason.message}. ${Phrases.SAY_AGAIN}") { aiSpoke() }
            }
            is TurnResult.Reconfirm -> say(Phrases.reconfirm(r.heard)) { aiSpoke() }
            TurnResult.NotAccepting -> listen()
        }
    }

    private fun command(command: VoiceCommand) {
        val g = game ?: return
        when (command) {
            VoiceCommand.QUIT -> voidRound()
            VoiceCommand.PAUSE -> pause(byAudioFocus = false)
            VoiceCommand.RESUME, VoiceCommand.YES, VoiceCommand.NO -> repeatPrompt(null)
            VoiceCommand.EASIER, VoiceCommand.HARDER -> changeDifficulty(command == VoiceCommand.HARDER)
            VoiceCommand.FASTER -> setRate(SpeechRate.faster(save.speechRatePercent))
            VoiceCommand.SLOWER -> setRate(SpeechRate.slower(save.speechRatePercent))
            VoiceCommand.RULES -> repeatPrompt(Phrases.rules(g.state.kind, rules))
            VoiceCommand.HINT -> giveHint()
            VoiceCommand.GIVE_UP -> {
                g.giveUp(now())
                finishRound(Phrases.GAVE_UP)
            }
            VoiceCommand.MISHEARD -> when (val c = g.correctLast(now())) {
                is CorrectionResult.Undone -> {
                    persist()
                    say("알겠어요, ${c.word}는 취소할게요. 제 단어는 ${c.lastAiWord}. ${Phrases.SAY_AGAIN}") { aiSpoke() }
                }
                CorrectionResult.NothingToUndo -> say(Phrases.SAY_AGAIN) { aiSpoke() }
            }
            VoiceCommand.AD_OFF -> disableAds()
            VoiceCommand.EXPLAIN_WORD -> {
                if (g.state.lastAiAdCampaignId != null) return explainAd()
                val word = g.state.lastAiWord
                val meaning = word?.let { dictionary.lookup(it)?.meaning }
                repeatPrompt(if (meaning != null) "${Phrases.withTopic(word)} $meaning." else "설명할 단어가 없어요.")
            }
        }
    }

    // ── 브랜드 단어(광고) ──────────────────────────────────

    private fun adEvent(type: AdEventType): AdEvent? {
        val s = game?.state ?: return null
        val id = s.lastAiAdCampaignId ?: return null
        return AdEvent(s.roundId, s.turn, id, type, System.currentTimeMillis())
    }

    private fun recordAd(event: AdEvent?) {
        if (event != null) updateSave { copy(adLog = adLog.record(event)) }
    }

    /** AI 가 고른 브랜드 단어를 다른 단어와 똑같이 읽는다(따로 안내하지 않음). 빈도 제한은 재생 시작부터 소비한다. */
    private fun sayAdWord() {
        val s = game?.state ?: return
        val word = s.lastAiWord ?: return
        val started = adEvent(AdEventType.AD_STARTED)
        recordAd(started)
        speakingAd = started
        say(word) {
            speakingAd = null
            recordAd(adEvent(AdEventType.AD_WORD_COMPLETED))
            aiSpoke()
        }
    }

    /** "그게 뭐야?", "그런 단어가 있어?" — 승인된 짧은 소개만 읽고 같은 차례·같은 남은 시간으로 돌아간다(말하는 동안 시계는 멈춤). */
    private fun explainAd() {
        val s = game?.state ?: return
        val word = s.lastAiWord ?: return
        val campaign = s.brand?.campaignFor(word) ?: return
        recordAd(adEvent(AdEventType.AD_EXPLANATION_REQUESTED))
        say(Phrases.brandExplanation(campaign.shortExplanation, word)) {
            recordAd(adEvent(AdEventType.AD_EXPLANATION_COMPLETED))
            aiSpoke()
        }
    }

    /** "광고 꺼 줘". 이번 판은 유도만 멈추고, 다음 판부터는 일반 모드로 시작한다. */
    private fun disableAds() {
        val g = game ?: return
        val s = g.state
        if (s.brand?.adEnabled != true) return repeatPrompt(Phrases.NO_ADS)
        g.disableAds()
        recordAd(AdEvent(s.roundId, s.turn, s.lastAiAdCampaignId ?: "-", AdEventType.AD_DISABLED, System.currentTimeMillis()))
        updateSave { copy(brandMode = false) }
        persist()
        repeatPrompt(Phrases.AD_OFF)
    }

    /** 안내를 한 뒤(없으면 바로) 지금 차례를 다시 알려 주고 듣는다. */
    private fun repeatPrompt(prefix: String?) {
        val word = game?.state?.lastAiWord
        val text = listOfNotNull(prefix, word?.let { "제 단어는 $it." }).joinToString(" ")
        say(text) { aiSpoke() }
    }

    private fun setRate(percent: Int) {
        if (!tts.supportsRate) return repeatPrompt("이 차량의 음성 안내는 말 속도 조절을 아직 지원하지 않아요.")
        updateSave { copy(speechRatePercent = percent) }
        repeatPrompt("말 속도를 ${percent}퍼센트로 바꿨어요.")
    }

    private fun changeDifficulty(harder: Boolean) {
        val s = game?.state ?: return
        val levels = s.kind.difficulties
        val next = levels.getOrNull(levels.indexOf(s.difficulty) + if (harder) 1 else -1)
        if (next == null) return repeatPrompt(if (harder) "이미 가장 어려운 단계예요." else "이미 가장 쉬운 단계예요.")
        game!!.voidRound(now())
        recordFinished(points = 0)
        say("이번 판은 무효로 하고 ${next.label} 단계로 다시 시작할게요.") { start(s.kind, next) }
    }

    // ── 질문(예/아니요) ─────────────────────────────────────

    private fun ask(ask: Ask, text: String) {
        _ui.update { it.copy(ask = ask) }
        say(text) { listenForAnswer() }
    }

    /** 질문 답을 듣는다. 질문 중에는 엔진이 답을 받지 않으므로 시간도 흐르지 않는다. */
    private fun listenForAnswer() {
        val token = ++listenToken
        _ui.update { it.copy(mic = Mic.LISTENING) }
        if (!_ui.value.micGranted || !_ui.value.sttAvailable) return
        stt.listen(object : SpeechInput.Listener {
            override fun onSpeechStart() = Unit
            override fun onResult(candidates: List<String>) {
                if (token == listenToken) onUtterance(candidates, null)
            }

            override fun onError(error: SpeechError) {
                if (token == listenToken) listenForAnswer()
            }
        })
    }

    /** 화면 버튼으로 예/아니요 */
    fun answer(yes: Boolean) {
        val ask = _ui.value.ask
        if (ask == Ask.NONE) return
        stt.cancel()
        listenToken++
        answerAsk(ask, ParsedUtterance.Command(if (yes) VoiceCommand.YES else VoiceCommand.NO))
    }

    private fun answerAsk(ask: Ask, parsed: ParsedUtterance) {
        DebugLog.d(TAG) { "answer $ask <- $parsed" }
        val command = (parsed as? ParsedUtterance.Command)?.command
        val yes = command == VoiceCommand.YES || (ask == Ask.RESUME && command == VoiceCommand.RESUME) ||
            (ask == Ask.CONCEDE && command == VoiceCommand.GIVE_UP) ||
            (ask == Ask.HINT && command == VoiceCommand.HINT) || (ask == Ask.QUIT && command == VoiceCommand.QUIT)
        val no = command == VoiceCommand.NO
        if (!yes && !no) return say(Phrases.YES_OR_NO) { listenForAnswer() }
        _ui.update { it.copy(ask = Ask.NONE) }
        val g = game ?: return
        when (ask) {
            Ask.RULES -> if (yes) say(Phrases.rules(g.state.kind, rules)) { speakOpening() } else speakOpening()
            Ask.HINT -> if (yes) giveHint() else {
                g.declineHint(now())
                finishRound(Phrases.TIME_UP)
            }
            Ask.QUIT -> if (yes) voidRound() else repeatPrompt(Phrases.KEEP_GOING)
            Ask.RESUME -> if (yes) resume() else _ui.update { it.copy(mic = Mic.OFF) }
            Ask.CONCEDE -> if (yes) {
                g.giveUp(now())
                finishRound(Phrases.CONCEDED)
            } else {
                repeatPrompt(Phrases.KEEP_GOING)
            }
            Ask.NONE -> Unit
        }
    }

    private fun giveHint() {
        val g = game ?: return
        val timedOut = g.state.phase == WordPhase.OFFER_HINT
        when (val h = g.requestHint(now())) {
            is HintResult.Hint -> {
                persist()
                say("힌트예요. ${h.meaning}. 남은 힌트는 ${h.hintsLeft}번이에요.") { aiSpoke() }
            }
            HintResult.NoCandidate -> if (g.state.lastAiHanbang && !timedOut) {
                offerConcede()
            } else if (timedOut) {
                g.declineHint(now())
                finishRound(Phrases.TIME_UP)
            } else {
                repeatPrompt("이번 판은 힌트를 더 드릴 수 없어요.")
            }
            HintResult.NoHintsLeft -> if (timedOut) {
                g.declineHint(now())
                finishRound(Phrases.TIME_UP)
            } else {
                repeatPrompt("이번 판은 힌트를 더 드릴 수 없어요.")
            }
            HintResult.NotAccepting -> Unit
        }
    }

    // ── 시간 ───────────────────────────────────────────────

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(200)
                val g = game ?: break
                if (g.isFinished) break
                _ui.update { it.copy(remainingMs = g.state.clock.remaining(now())) }
                when (g.tick(now())) {
                    TickResult.OfferHint -> {
                        persist()
                        ask(Ask.HINT, Phrases.OFFER_HINT)
                    }
                    TickResult.TimeUp -> finishRound(Phrases.TIME_UP)
                    TickResult.None -> if (stuckOnHanbang(g)) offerConcede()
                }
            }
        }
    }

    /**
     * 주행 중에는 제한시간이 없어 한방 단어에 막히면 끝나지 않는다.
     * AI 가 한방 단어를 말하고 30초가 지나도록 답이 없으면 한 번 물어본다.
     */
    private fun stuckOnHanbang(g: WordGame): Boolean =
        g.state.lastAiHanbang && g.state.mode == PlayMode.DRIVING && !g.state.paused && !busy &&
            _ui.value.ask == Ask.NONE && _ui.value.mic == Mic.LISTENING &&
            concedeOfferedTurn != g.state.turn && now() - aiSpokeAt > 30_000

    private fun offerConcede() {
        val g = game ?: return
        concedeOfferedTurn = g.state.turn
        ask(Ask.CONCEDE, Phrases.ASK_CONCEDE)
    }

    // ── 일시정지·차량 상태 ─────────────────────────────────

    fun pause(byAudioFocus: Boolean) {
        val g = game ?: return
        if (g.isFinished || g.state.paused) return
        speakingAd?.let { recordAd(it.copy(type = AdEventType.AD_INTERRUPTED, atEpochMs = System.currentTimeMillis())) }
        speakingAd = null
        stopVoice()
        g.pause(now())
        pausedByAudioFocus = byAudioFocus
        _ui.update { it.copy(ask = Ask.NONE, notice = if (byAudioFocus) "다른 음성 안내가 있어 멈췄어요" else "일시정지") }
        persist()
    }

    /** 음성 우선권을 되찾으면 자동으로 재개하지 않고 이어 할지 묻는다. */
    private fun offerResume() {
        val g = game ?: return
        if (!pausedByAudioFocus || g.isFinished) return
        pausedByAudioFocus = false
        ask(Ask.RESUME, Phrases.ASK_RESUME)
    }

    fun resume() {
        val g = game ?: return
        if (g.isFinished) return
        focus.acquire()
        g.resume()
        _ui.update { it.copy(notice = null) }
        publish()
        repeatPrompt("다시 시작할게요.")
    }

    /** 주행(또는 상태 불명)으로 바뀌면 제한시간을 바로 없앤다. 주차로 돌아와도 이번 판은 그대로. */
    private fun onVehicleChanged(state: VehicleState) {
        val g = game ?: return
        if (g.isFinished || state == VehicleState.PARKED) return
        val hadLimit = g.state.clock.hasLimit
        val wasOfferingHint = _ui.value.ask == Ask.HINT
        g.changeMode(vehicle.currentMode())
        if (!hadLimit) return
        persist()
        _ui.update { it.copy(notice = "주행 중에는 제한시간 없이 진행해요", ask = if (wasOfferingHint) Ask.NONE else it.ask) }
        if (wasOfferingHint) repeatPrompt("주행 중이라 제한시간 없이 계속할게요.")
    }

    // ── 종료 ───────────────────────────────────────────────

    private fun voidRound() {
        game?.voidRound(now())
        finishRound(Phrases.VOIDED)
    }

    private fun finishRound(line: String) {
        val g = game ?: return
        val s = g.state
        val points = RewardPolicy.forResult(s.kind, s.difficulty, s.outcome ?: Outcome.VOID)
        recordFinished(points)
        say(line) {
            _ui.update { it.copy(screen = Screen.RESULT, mic = Mic.OFF) }
            focus.release()
        }
    }

    /** 결과 기록과 포인트 지급. 같은 판은 기록·지급 모두 한 번만 반영된다(roundId 기준). */
    private fun recordFinished(points: Int) {
        val s = game?.state ?: return
        updateSave {
            copy(ledger = ledger.award(s.roundId, points), wordGame = s).withRecord(
                RoundRecord(
                    s.roundId, s.kind, s.difficulty, s.outcome ?: Outcome.VOID, points, s.validUserWords,
                    System.currentTimeMillis(),
                ),
            )
        }
        _ui.update { it.copy(lastReward = points, round = s, remainingMs = null) }
    }

    fun suggestedNext(): Difficulty? {
        val s = _ui.value.round ?: return null
        return suggestNextDifficulty(s.kind, s.difficulty, s.outcome ?: return null)
    }

    // ── 보관함 ─────────────────────────────────────────────

    fun openLocker() = _ui.update { it.copy(screen = Screen.LOCKER) }

    fun goHome() {
        stopVoice()
        game?.takeIf { !it.isFinished }?.let { it.pause(now()); persist() }
        game = null
        focus.release()
        _ui.update { it.copy(screen = Screen.HOME, ask = Ask.NONE, notice = null) }
    }

    /** 시연용 쿠폰 교환. 실제 쿠폰은 발급되지 않는다. */
    fun exchangeDemoCoupon(cost: Int): Boolean {
        val next = save.ledger.spend(cost) ?: return false
        updateSave { copy(ledger = next) }
        return true
    }

    // ── 공통 ───────────────────────────────────────────────

    private fun stopVoice() {
        stt.cancel()
        tts.stop()
        listenToken++
        _ui.update { it.copy(mic = Mic.OFF) }
    }

    private fun log(fromUser: Boolean, text: String) =
        _ui.update { it.copy(log = (it.log + LogLine(fromUser, text)).takeLast(30)) }

    private fun publish() = _ui.update { it.copy(round = game?.state) }

    private fun persist() {
        val g = game ?: return
        updateSave { copy(wordGame = g.snapshot(now())) }
        publish()
    }

    private fun updateSave(change: SaveData.() -> SaveData) {
        val next = save.change()
        _ui.update { it.copy(save = next) }
        store.save(next)
    }

    /** 화면이 꺼지거나 앱이 내려갈 때 남은 시간을 저장해 둔다. */
    fun onAppStopped() {
        game?.takeIf { !it.isFinished }?.let {
            pause(byAudioFocus = false)
        }
    }

    override fun onCleared() {
        stt.shutdown()
        tts.shutdown()
        focus.release()
    }
}
