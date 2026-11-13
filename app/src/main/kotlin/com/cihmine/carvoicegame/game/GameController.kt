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
import com.cihmine.carvoicegame.core.command.MenuChoice
import com.cihmine.carvoicegame.core.command.MenuParser
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
import kotlinx.coroutines.Dispatchers
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

/** 대답을 기다리는 상태. 예/아니요로 묻는 질문은 두지 않는다(사용자 요청) — 바로 실행하거나 무엇을 할지만 듣는다. */
enum class Ask {
    NONE,

    /** 멈춘 판을 '이어서'라고 말하길 기다린다 */
    RESUME,

    /** 판이 끝나고 다음 게임을 말하길 기다린다 */
    NEXT,
}

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
    /** 처음 화면에서 고른 게임·난이도(터치와 음성이 같은 값을 쓴다) */
    val menuKind: GameKind = GameKind.WORD_CHAIN,
    val menuDifficulty: Difficulty = Difficulty.EASY,
    /** 음성 안내 엔진 준비 결과가 왔는지(실패여도 true) — 안내를 못 해도 듣기는 시작한다 */
    val ttsChecked: Boolean = false,
    /** 사용자 화면 자막: AI 가 지금(마지막으로) 한 말 — 흰색 */
    val aiCaption: String? = null,
    /** 사용자 화면 자막: 사용자가 말하는 중(중간 인식)·마지막으로 인식된 말 — 회색 */
    val userCaption: String? = null,
    /** 사용자 화면(검은 화면·빛나는 원·자막)으로 보는지. 끄면 터치 화면·시연 패널 */
    val stageMode: Boolean = true,
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
        .also { d -> viewModelScope.launch(Dispatchers.Default) { d.accepts("가") } } // 판정 전용 목록을 첫 답 전에 미리 읽어 둔다
    private val catalog = CampaignCatalog.loadDemo()
    private val planner = AdPlanner()
    private val online = OnlineDictionary.fromBuildConfig()
    private val store = FileSaveStore(app.filesDir)
    val vehicle = MockVehicleStatus()

    // 음성 엔진의 준비 콜백이 생성 중에 바로 올 수 있어 상태를 먼저 만든다.
    private val _ui = MutableStateFlow(GameUi(save = store.load() ?: SaveData()))
    val ui: StateFlow<GameUi> = _ui

    private val stt = Voice.input(app) { ok -> _ui.update { it.copy(sttAvailable = ok) }; homeVoiceLater() }
    private val tts = Voice.output(app) { ok -> _ui.update { it.copy(ttsReady = ok, ttsChecked = true) }; homeVoiceLater() }
    private val focus = AudioFocus(app, onLost = { pause(byAudioFocus = true) }, onRegained = ::offerResume)

    private var game: WordGame? = null
    private var listenToken = 0
    private var ticker: Job? = null
    private var pausedByAudioFocus = false

    /** "꺼", "종료" — 화면(액티비티)이 받아서 앱을 닫는다 */
    val closeRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 앱을 켠 뒤 아직 "어떤 게임을 시작할까요?"를 묻지 않음(한 번만 묻는다) */
    private var homePromptPending = true

    /** 처음 화면에서 못 알아들은 말에 한 번 안내했는지(마이크를 계속 켜 두므로 차 안 대화마다 대답하지 않는다) */
    private var homeNudged = false

    /** 앱이 화면에 보이는지(내려가 있으면 처음 화면에서 듣지 않는다) */
    private var foreground = true

    /** 지금 읽고 있는 광고 단어(중단 기록용) */
    private var speakingAd: AdEvent? = null

    /** 판정 전 인터넷 검색 중. 그동안 들어온 말·버튼은 무시한다. */
    private var busy = false

    /** AI 가 마지막 단어를 다 말한 시각과 그 차례(주행 중 한방에 막혔는지 판단) */
    private var aiSpokeAt: Long = 0

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

    private fun showUserCaption(text: String) = _ui.update { it.copy(userCaption = text) }

    fun setStageMode(on: Boolean) = _ui.update { it.copy(stageMode = on) }

    fun setMicGranted(granted: Boolean) {
        _ui.update { it.copy(micGranted = granted) }
        homeVoiceLater()
    }

    // ── 처음 화면 음성 ──────────────────────────────────────
    // 앱을 켜면 "어떤 게임을 시작할까요?"를 한 번 묻고, 처음 화면에 있는 동안 마이크를 계속 켜 둔다.

    fun selectKind(kind: GameKind) {
        if (!kind.isWordGame) return
        _ui.update { it.copy(menuKind = kind, menuDifficulty = it.menuDifficulty.takeIf { d -> d in kind.difficulties } ?: Difficulty.EASY) }
    }

    fun selectDifficulty(difficulty: Difficulty) = _ui.update { it.copy(menuDifficulty = difficulty) }

    /** 음성 엔진 준비·권한 콜백은 생성 도중에도 올 수 있어 한 박자 뒤에 처리한다. */
    private fun homeVoiceLater() {
        viewModelScope.launch { startHomeVoice() }
    }

    /** 처음 화면이고 음성을 쓸 수 있으면: 아직 안 물었으면 묻고, 아니면 조용히 듣는다. */
    private fun startHomeVoice() {
        val u = _ui.value
        if (!foreground || u.screen != Screen.HOME || game != null) return
        if (!u.micGranted || !u.sttAvailable) return
        if (homePromptPending) {
            if (!u.ttsReady) {
                // 음성 안내를 쓸 수 없으면 묻지 않고 듣기만 한다(화면 문구가 대신 안내)
                if (!u.ttsChecked) return
                homePromptPending = false
                return listenHome()
            }
            homePromptPending = false
            homeNudged = false
            val saved = save.wordGame?.takeIf { it.outcome == null } != null
            say(Phrases.menuQuestion(saved)) { listenHome() }
        } else if (u.mic == Mic.OFF) {
            listenHome()
        }
    }

    private fun onHome() = foreground && _ui.value.screen == Screen.HOME && game == null

    private fun listenHome() {
        if (!onHome()) return
        val token = ++listenToken
        _ui.update { it.copy(mic = Mic.LISTENING) }
        if (!_ui.value.micGranted || !_ui.value.sttAvailable) return
        stt.listen(object : SpeechInput.Listener {
            override fun onSpeechStart() = Unit
            override fun onPartial(text: String) {
                if (token == listenToken) showUserCaption(text)
            }

            override fun onResult(candidates: List<String>) {
                if (token == listenToken) onHomeUtterance(candidates.firstOrNull().orEmpty())
            }

            override fun onError(error: SpeechError) {
                if (token != listenToken) return
                // 계속 켜 두는 마이크: 말이 없어 끊기면 바로, 다른 오류면 잠깐 쉬었다가 다시 듣는다.
                if (error == SpeechError.NO_MATCH || error == SpeechError.TIMEOUT) return listenHome()
                viewModelScope.launch {
                    delay(1_500)
                    if (token == listenToken) listenHome()
                }
            }
        })
    }

    private fun onHomeUtterance(text: String) {
        DebugLog.d(TAG) { "home heard: $text" }
        if (!onHome()) return
        if (text.isNotBlank()) {
            log(true, text)
            showUserCaption(text)
        }
        when (val choice = MenuParser.parse(text)) {
            is MenuChoice.Start -> start(choice.kind, choice.difficulty ?: difficultyFor(choice.kind))
            MenuChoice.Any -> start(GameKind.WORD_CHAIN, difficultyFor(GameKind.WORD_CHAIN))
            is MenuChoice.NotReady -> say(Phrases.notReady(choice.kind)) { listenHome() }
            MenuChoice.Resume ->
                if (save.wordGame?.takeIf { it.outcome == null } != null) {
                    continueSaved()
                    resume()
                } else {
                    say(Phrases.NOTHING_TO_RESUME) { listenHome() }
                }
            MenuChoice.Rules -> say(Phrases.MENU_RULES) { listenHome() }
            // "됐어" 같은 말은 차 안 대화에도 흔하다 — 처음 한 번만 대답하고 그 뒤로는 조용히 듣는다
            MenuChoice.Exit -> say(Phrases.SEE_YOU) { closeRequests.tryEmit(Unit) }
            MenuChoice.Later -> if (!homeNudged) {
                homeNudged = true
                say(Phrases.MENU_LATER) { listenHome() }
            } else {
                listenHome()
            }
            // "소리가 안 들려", "뭐라고?" — 질문을 다시 한다
            MenuChoice.Unknown -> if (parser.parse(text) == ParsedUtterance.Command(VoiceCommand.REPEAT)) {
                say(Phrases.menuQuestion(save.wordGame?.takeIf { it.outcome == null } != null)) { listenHome() }
            } else if (!homeNudged && text.isNotBlank()) {
                homeNudged = true
                say(Phrases.MENU_RETRY) { listenHome() }
            } else {
                listenHome()
            }
        }
    }

    /** 음성으로 게임만 말했을 때 쓸 난이도: 화면에서 그 게임을 골라 둔 난이도, 아니면 쉬움 */
    private fun difficultyFor(kind: GameKind): Difficulty =
        _ui.value.menuDifficulty.takeIf { _ui.value.menuKind == kind && it in kind.difficulties } ?: Difficulty.EASY

    fun onAppStarted() {
        foreground = true
        // 판 끝 질문 중에 내려갔다 돌아오면 그 질문의 대답을 다시 듣는다
        val ask = _ui.value.ask
        if (_ui.value.screen == Screen.RESULT && ask == Ask.NEXT) return listenForAnswer()
        startHomeVoice()
    }

    // ── 판 시작·복원 ─────────────────────────────────────────

    fun start(kind: GameKind, difficulty: Difficulty) {
        stopVoice()
        focus.acquire()
        game = WordGame.start(kind, difficulty, vehicle.currentMode(), dictionary, rules = rules, brand = brandRoundFor(kind), planner = planner)
        _ui.update {
            it.copy(screen = Screen.PLAYING, log = emptyList(), lastReward = 0, notice = null, ask = Ask.NONE, menuKind = kind, menuDifficulty = difficulty)
        }
        publish()
        speakOpening()
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
        stopVoice()
        val state = save.wordGame?.takeIf { it.outcome == null } ?: return
        game = WordGame.restore(state, dictionary, rules = rules, planner = planner).also {
            // 꺼져 있는 동안 차가 움직이기 시작했을 수 있다. 홈 화면에선 차량 변화를 판에 반영하지 않으므로 여기서 맞춘다.
            it.changeMode(vehicle.currentMode())
        }
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
        _ui.update { it.copy(mic = Mic.SPEAKING, aiCaption = text, userCaption = null) }
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
                if (token != listenToken) return
                micErrors = 0
                game?.userSpeechStarted(now())
            }

            override fun onPartial(text: String) {
                if (token == listenToken) showUserCaption(text)
            }

            override fun onResult(candidates: List<String>) {
                if (token != listenToken) return
                micErrors = 0
                onUtterance(candidates, turn)
            }

            override fun onError(error: SpeechError) {
                if (token != listenToken) return
                // 말이 없어서 끊긴 것이면 조용히 다시 듣는다. 시간 초과 판정은 엔진 시계가 한다.
                if (error == SpeechError.NO_MATCH || error == SpeechError.TIMEOUT) {
                    // 말을 시작해 멈췄던 시계를 다시 흐르게 한다(이미 흐르는 중이면 그대로).
                    game?.speechFinished(now())
                    return listen()
                }
                micLost(error, token)
            }
        })
    }

    /** 이어진 음성 장치 오류 수. 말이 들리거나 결과가 오면 0 */
    private var micErrors = 0

    /**
     * 마이크·음성 인식 장치 오류(단순 침묵과 다르다). 사용자 잘못이 아니므로 시간 초과 패배로 이어지지 않게 한다.
     * 시계를 멈춘 채 한 번 짧게 다시 연결해 보고, 또 실패하면 판을 멈추고 저장한 뒤 화면·음성으로 알린다.
     */
    private fun micLost(error: SpeechError, token: Int) {
        val g = game ?: return
        g.speechStarted(now()) // 시계 멈춤
        DebugLog.d(TAG) { "stt error $error (#${micErrors + 1})" }
        if (++micErrors <= 1) {
            _ui.update { it.copy(notice = "마이크 다시 연결 중…") }
            viewModelScope.launch {
                delay(1_000)
                if (token != listenToken || game !== g || g.isFinished || g.state.paused) return@launch
                _ui.update { it.copy(notice = null) }
                g.speechFinished(now())
                listen()
            }
            return
        }
        micErrors = 0
        pause(byAudioFocus = false)
        _ui.update { it.copy(notice = Phrases.MIC_LOST) }
        // 마이크가 돌아오면 '이어서'로, 아니면 터치 화면의 '계속'으로 이어 간다
        ask(Ask.RESUME, "${Phrases.MIC_LOST} ${Phrases.SAY_RESUME}")
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
        if (game == null) return onHomeUtterance(text)
        onUtterance(listOf(text), game?.state?.turn)
    }

    /** 질문에 대한 마지막 대답 원문(다음 판 질문에서 게임 이름을 찾는다) */
    private var lastHeard: String? = null

    private fun onUtterance(candidates: List<String>, turn: Int?) {
        DebugLog.d(TAG) { "heard: $candidates ask=${_ui.value.ask}" }
        val g = game ?: return
        if (busy) return
        val top = candidates.first()
        lastHeard = top
        showUserCaption(top)
        log(true, top)
        // 명령어처럼 들려도 지금 차례에 맞는 답(예: 초성 ㅍㄱ 판의 "포기")일 때만 답으로 본다.
        // STT 후보 중 하나라도 맞는 답이면(["포기", "고기"]의 "고기") 명령보다 답을 우선한다.
        val parsed = parser.parse(top) { _ui.value.ask == Ask.NONE && g.acceptableWord(candidates) != null }
        val ask = _ui.value.ask
        if (ask != Ask.NONE) return answerAsk(ask, parsed)
        if (g.state.paused) {
            if (parsed == ParsedUtterance.Command(VoiceCommand.RESUME)) resume()
            return
        }
        when (parsed) {
            is ParsedUtterance.Command -> command(parsed.command)
            is ParsedUtterance.SetSpeechRate -> setRate(SpeechRate.set(parsed.percent))
            ParsedUtterance.ConfirmQuit -> voidRound()
            // "뭘 뭐라고 했더라"처럼 문장인데 명령도 답도 아니면 오답으로 치지 않고 다시 말해 달라고 한다
            ParsedUtterance.NotCommand -> if (CommandParser.looksLikeSentence(top) && g.acceptableWord(candidates) == null) {
                repeatPrompt(Phrases.NOT_UNDERSTOOD)
            } else {
                judge(candidates, turn)
            }
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
                if (r.heardAs != null) {
                    showUserCaption(r.userWord)
                    log(true, r.userWord)
                }
                if (r.aiWord == null) {
                    val points = RewardPolicy.forResult(g.state.kind, g.state.difficulty, Outcome.WIN)
                    finishRound(Phrases.userWins(r.userWord, points))
                } else {
                    persist()
                    val lead = r.heardAs?.let { Phrases.heardAs(r.userWord) }.orEmpty()
                    if (g.state.lastAiAdCampaignId != null) sayAdWord() else say(lead + r.aiWord!!) { aiSpoke() }
                }
            }
            is TurnResult.Rejected -> {
                persist()
                say(Phrases.rejected(r.word, r.reason.message)) { aiSpoke() }
            }
            is TurnResult.Reconfirm -> say(Phrases.reconfirm(r.heard)) { aiSpoke() }
            TurnResult.NotAccepting -> listen()
        }
    }

    private fun command(command: VoiceCommand) {
        val g = game ?: return
        when (command) {
            VoiceCommand.QUIT -> voidRound()
            VoiceCommand.PAUSE -> {
                pause(byAudioFocus = false)
                // 직접 멈춘 경우: 말로 '이어서'만 하면 돌아오게 기다린다(예/아니요 확인은 없음)
                if (game?.state?.paused == true) ask(Ask.RESUME, Phrases.PAUSED_SAY_RESUME)
            }
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
            // "모르겠어" — 도움 요청으로만 본다. 힌트가 남았으면 힌트, 없으면 알려 주고 남은 시간 안에서 계속한다
            // (혼잣말 "모르겠어"로 바로 지지 않게. 확실한 "내가 졌어·포기"는 GIVE_UP 이 바로 끝낸다)
            VoiceCommand.STUCK -> if (g.state.hintsLeft > 0) giveHint() else repeatPrompt(Phrases.HINTS_USED_UP)
            VoiceCommand.REPEAT -> repeatPrompt(null)
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

    // ── 대답 기다리기 ───────────────────────────────────────

    private fun ask(ask: Ask, text: String) {
        askText = text
        askRepeated = false
        _ui.update { it.copy(ask = ask) }
        say(text) { listenForAnswer() }
    }

    /** 지금 기다리는 말의 안내(못 알아들으면 한 번 더 말한다) */
    private var askText: String? = null
    private var askRepeated = false

    /**
     * 대답을 못 알아들었을 때. 마이크를 계속 켜 두므로 상관없는 말마다 되묻지 않고, 처음 한 번만 다시 말한다.
     */
    private fun askAgainOnce() {
        if (askRepeated) return listenForAnswer()
        askRepeated = true
        say(askText ?: "") { listenForAnswer() }
    }

    /** 질문 답을 듣는다. 질문 중에는 엔진이 답을 받지 않으므로 시간도 흐르지 않는다. */
    private fun listenForAnswer() {
        val token = ++listenToken
        _ui.update { it.copy(mic = Mic.LISTENING) }
        if (!_ui.value.micGranted || !_ui.value.sttAvailable) return
        stt.listen(object : SpeechInput.Listener {
            override fun onSpeechStart() = Unit
            override fun onPartial(text: String) {
                if (token == listenToken) showUserCaption(text)
            }

            override fun onResult(candidates: List<String>) {
                if (token == listenToken) onUtterance(candidates, null)
            }

            override fun onError(error: SpeechError) {
                if (token != listenToken) return
                if (error == SpeechError.NO_MATCH || error == SpeechError.TIMEOUT) return listenForAnswer()
                viewModelScope.launch {
                    delay(1_500)
                    if (token == listenToken) listenForAnswer()
                }
            }
        })
    }

    private fun answerAsk(ask: Ask, parsed: ParsedUtterance) {
        DebugLog.d(TAG) { "answer $ask <- $parsed" }
        val command = (parsed as? ParsedUtterance.Command)?.command
        if (ask == Ask.NEXT) return answerNext(command)
        when (command) {
            VoiceCommand.RESUME, VoiceCommand.YES -> resume()
            VoiceCommand.QUIT -> {
                _ui.update { it.copy(ask = Ask.NONE) }
                voidRound()
            }
            VoiceCommand.NO -> _ui.update { it.copy(ask = Ask.NONE, mic = Mic.OFF) }
            else -> askAgainOnce()
        }
    }

    /** 판이 끝난 뒤 다음 판을 고르는 말. "초성 대결 하자"·"어렵게"·"한 판 더"를 그대로 따른다. */
    private fun answerNext(command: VoiceCommand?) {
        val s = game?.state ?: return
        val levels = s.kind.difficulties
        val menu = (lastHeard ?: "").let { MenuParser.parse(it) }
        val target: Pair<GameKind, Difficulty>? = when {
            menu is MenuChoice.Start -> menu.kind to (menu.difficulty ?: Difficulty.EASY.takeIf { menu.kind != s.kind } ?: s.difficulty)
            menu is MenuChoice.Unknown && MenuParser.difficultyIn(lastHeard ?: "")?.takeIf { it in levels } != null ->
                s.kind to MenuParser.difficultyIn(lastHeard ?: "")!!
            command == VoiceCommand.EASIER -> s.kind to (levels.getOrNull(levels.indexOf(s.difficulty) - 1) ?: s.difficulty)
            command == VoiceCommand.HARDER -> s.kind to (levels.getOrNull(levels.indexOf(s.difficulty) + 1) ?: s.difficulty)
            command == VoiceCommand.YES -> s.kind to s.difficulty
            else -> null
        }
        if (target != null) {
            _ui.update { it.copy(ask = Ask.NONE) }
            return start(target.first, target.second)
        }
        if (command == VoiceCommand.NO || command == VoiceCommand.QUIT) {
            _ui.update { it.copy(ask = Ask.NONE) }
            return say(Phrases.SEE_YOU) { goHome() }
        }
        askAgainOnce()
    }

    private fun giveHint() {
        val g = game ?: return
        val timedOut = g.state.phase == WordPhase.OFFER_HINT
        when (val h = g.requestHint(now())) {
            is HintResult.Hint -> {
                persist()
                val lead = if (timedOut) "시간이 다 됐어요. " else ""
                say("${lead}힌트예요. ${h.meaning}. 남은 힌트는 ${h.hintsLeft}번이에요.") { aiSpoke() }
            }
            HintResult.NoCandidate -> if (g.state.lastAiHanbang && !timedOut) {
                concede(Phrases.NO_WORD_LEFT)
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
                        giveHint()
                    }
                    TickResult.TimeUp -> finishRound(Phrases.TIME_UP)
                    TickResult.None -> if (stuckOnHanbang(g)) concede(Phrases.NO_WORD_LEFT)
                }
            }
        }
    }

    /**
     * 주행 중에는 제한시간이 없어 한방 단어에 막히면 끝나지 않는다.
     * AI 가 한방 단어를 말하고 30초가 지나도록 답이 없으면 판을 끝낸다.
     */
    private fun stuckOnHanbang(g: WordGame): Boolean =
        g.state.lastAiHanbang && g.state.mode == PlayMode.DRIVING && !g.state.paused && !busy &&
            _ui.value.ask == Ask.NONE && _ui.value.mic == Mic.LISTENING && now() - aiSpokeAt > 30_000

    /** 묻지 않고 이번 판을 사용자 패로 끝낸다 */
    private fun concede(line: String) {
        val g = game ?: return
        g.giveUp(now())
        finishRound(line)
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
        _ui.update { it.copy(ask = Ask.NONE, notice = if (byAudioFocus) "다른 음성 안내가 있어 멈췄어요" else "잠시 멈춤 — '이어서'라고 말하면 계속해요") }
        persist()
    }

    /** 음성 우선권을 되찾아도 자동으로 재개하지 않고 '이어서'라는 말을 기다린다. */
    private fun offerResume() {
        val g = game ?: return
        if (!pausedByAudioFocus || g.isFinished) return
        pausedByAudioFocus = false
        ask(Ask.RESUME, Phrases.SAY_RESUME)
    }

    fun resume() {
        val g = game ?: return
        if (g.isFinished) return
        focus.acquire()
        g.resume()
        _ui.update { it.copy(notice = null, ask = Ask.NONE) }
        publish()
        repeatPrompt("다시 시작할게요.")
    }

    /** 주행(또는 상태 불명)으로 바뀌면 제한시간을 바로 없앤다. 주차로 돌아와도 이번 판은 그대로. */
    private fun onVehicleChanged(state: VehicleState) {
        val g = game ?: return
        if (g.isFinished || state == VehicleState.PARKED) return
        val hadLimit = g.state.clock.hasLimit
        g.changeMode(vehicle.currentMode())
        if (!hadLimit) return
        persist()
        _ui.update { it.copy(notice = "주행 중에는 제한시간 없이 진행해요") }
    }

    // ── 종료 ───────────────────────────────────────────────

    private fun voidRound() {
        game?.voidRound(now())
        finishRound(Phrases.voided(game?.state?.validUserWords ?: 0))
    }

    private fun finishRound(line: String) {
        val g = game ?: return
        val s = g.state
        val points = RewardPolicy.forResult(s.kind, s.difficulty, s.outcome ?: Outcome.VOID)
        recordFinished(points)
        say(line) {
            _ui.update { it.copy(screen = Screen.RESULT, mic = Mic.OFF) }
            focus.release()
            offerNext(s.outcome ?: Outcome.VOID)
        }
    }

    /**
     * 판이 끝나면 다음에 무엇을 할지 듣는다(이겼고 더 어려운 단계가 있으면 그 단계도 알려 준다).
     * 사용자가 "그만"으로 끝낸 판은 처음 화면으로 간다.
     */
    private fun offerNext(outcome: Outcome) {
        lastHeard = null
        if (outcome == Outcome.VOID) return goHome()
        ask(Ask.NEXT, Phrases.nextGame(suggestedNext().takeIf { outcome == Outcome.WIN }))
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

    fun openLocker() {
        stopVoice()
        _ui.update { it.copy(screen = Screen.LOCKER) }
    }

    fun goHome() {
        stopVoice()
        game?.takeIf { !it.isFinished }?.let { it.pause(now()); persist() }
        game = null
        focus.release()
        homeNudged = false
        _ui.update { it.copy(screen = Screen.HOME, ask = Ask.NONE, notice = null) }
        // 처음 화면에서는 마이크를 계속 켜 둔다(다시 묻지는 않음)
        startHomeVoice()
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
        foreground = false
        stopVoice()
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
