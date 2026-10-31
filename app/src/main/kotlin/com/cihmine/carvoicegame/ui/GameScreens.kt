package com.cihmine.carvoicegame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cihmine.carvoicegame.core.Difficulty
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.Outcome
import com.cihmine.carvoicegame.core.command.VoiceCommand
import com.cihmine.carvoicegame.game.Ask
import com.cihmine.carvoicegame.game.GameController
import com.cihmine.carvoicegame.game.GameUi
import com.cihmine.carvoicegame.game.Mic
import com.cihmine.carvoicegame.game.Screen
import com.cihmine.carvoicegame.vehicle.VehicleState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEMO_COUPON_COST = 50

@Composable
fun GameApp(controller: GameController) {
    val ui by controller.ui.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            SimulationBanner(ui)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                    when (ui.screen) {
                        Screen.HOME -> HomeScreen(ui, controller)
                        Screen.PLAYING -> PlayingScreen(ui, controller)
                        Screen.RESULT -> ResultScreen(ui, controller)
                        Screen.LOCKER -> LockerScreen(ui, controller)
                    }
                }
                DemoPanel(ui, controller)
            }
        }
    }
}

@Composable
private fun SimulationBanner(ui: GameUi) {
    val voice = buildList {
        add(if (ui.ttsReady) "음성 안내: ${ui.ttsEngine}" else "음성 안내 없음(화면 표시)")
        add(if (ui.micGranted && ui.sttAvailable) "음성 인식: ${ui.sttEngine}" else "음성 인식 없음 → 글자 입력 대체 시연")
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF3A2E00)).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("차량 연동 시뮬레이션 · 모의 차량 상태: ${ui.vehicle.label}", color = Color(0xFFFFD54F))
        Text(voice, color = Color(0xFFFFD54F))
    }
}

// ── 홈 ─────────────────────────────────────────────────────

@Composable
private fun HomeScreen(ui: GameUi, c: GameController) {
    var kind by remember { mutableStateOf(GameKind.WORD_CHAIN) }
    var difficulty by remember { mutableStateOf(Difficulty.EASY) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("말게임", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("말로 하는 차량용 게임", style = MaterialTheme.typography.titleMedium)

        val saved = ui.save.wordGame?.takeIf { it.outcome == null }
        if (saved != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("일시정지된 ${saved.kind.label} 판이 있어요", Modifier.weight(1f))
                    Button(onClick = c::continueSaved) { Text("이어하기") }
                }
            }
        }

        Text("게임", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameKind.entries.forEach { k ->
                val ready = k.isWordGame
                FilterChip(
                    selected = kind == k,
                    onClick = { if (ready) kind = k },
                    enabled = ready,
                    label = { Text(if (ready) k.label else "${k.label} (준비 중)", fontSize = 18.sp) },
                )
            }
        }
        Text("난이도", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            kind.difficulties.forEach { d ->
                FilterChip(selected = difficulty == d, onClick = { difficulty = d }, label = { Text(d.label, fontSize = 18.sp) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { c.start(kind, difficulty) }, Modifier.height(64.dp)) { Text("시작", fontSize = 22.sp) }
            OutlinedButton(
                onClick = c::openLocker,
                enabled = ui.vehicle == VehicleState.PARKED,
                modifier = Modifier.height(64.dp),
            ) { Text("보관함 · ${ui.save.ledger.balance}P", fontSize = 20.sp) }
        }
        if (ui.vehicle != VehicleState.PARKED) Text("보관함·쿠폰 교환은 주차 중에만 열 수 있어요")
    }
}

// ── 진행 ───────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayingScreen(ui: GameUi, c: GameController) {
    val round = ui.round ?: return
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "${round.kind.label} · ${round.difficulty.label}" +
                    (round.choseong?.let { " · 초성 $it" } ?: ""),
                style = MaterialTheme.typography.titleLarge,
            )
            Text("제 단어", style = MaterialTheme.typography.titleMedium)
            // 규칙 안내를 묻는 동안에는 첫 단어를 아직 말하지 않았으므로 가린다.
            Text(if (ui.ask == Ask.RULES) "…" else round.lastAiWord ?: "", fontSize = 72.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(timerText(ui), fontSize = 28.sp)
                Text(micText(ui.mic, round.paused), fontSize = 28.sp)
            }
            Text("힌트 ${round.hintsLeft}번 남음 · 사용한 단어 ${round.used.size}개 · 내 단어 ${round.validUserWords}개")
            ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, fontSize = 20.sp) }

            if (ui.ask != Ask.NONE) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { c.answer(true) }, Modifier.height(64.dp).width(140.dp)) { Text("네", fontSize = 22.sp) }
                    OutlinedButton(onClick = { c.answer(false) }, Modifier.height(64.dp).width(140.dp)) {
                        Text("아니요", fontSize = 22.sp)
                    }
                }
            }
            // 화면 폭에 따라 버튼이 넘치지 않게 줄을 바꾼다.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (round.paused) {
                    Button(onClick = c::resume, Modifier.height(56.dp)) { Text("계속", fontSize = 20.sp) }
                } else {
                    OutlinedButton(onClick = { c.press(VoiceCommand.PAUSE) }, Modifier.height(56.dp)) { Text("일시정지") }
                }
                OutlinedButton(onClick = { c.press(VoiceCommand.MISHEARD) }, Modifier.height(56.dp)) { Text("잘못 들었어") }
                OutlinedButton(onClick = { c.press(VoiceCommand.HINT) }, Modifier.height(56.dp)) { Text("힌트") }
                OutlinedButton(onClick = { c.press(VoiceCommand.EXPLAIN_WORD) }, Modifier.height(56.dp)) { Text("뜻") }
                OutlinedButton(onClick = { c.press(VoiceCommand.QUIT) }, Modifier.height(56.dp)) { Text("그만") }
            }
            OutlinedButton(onClick = c::goHome) { Text("처음 화면") }
        }
        Card(Modifier.weight(1f).fillMaxHeight()) {
            LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.log.reversed()) { line ->
                    Text(
                        (if (line.fromUser) "나: " else "AI: ") + line.text,
                        color = if (line.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                    )
                }
            }
        }
    }
}

private fun timerText(ui: GameUi): String {
    val ms = ui.remainingMs ?: ui.round?.clock?.remainingMs
    return if (ui.round?.clock?.hasLimit != true || ms == null) "제한시간 없음" else "남은 시간 ${(ms + 999) / 1000}초"
}

private fun micText(mic: Mic, paused: Boolean) = when {
    paused -> "⏸ 멈춤"
    mic == Mic.SPEAKING -> "🔊 말하는 중"
    mic == Mic.LISTENING -> "🎤 듣는 중"
    else -> "마이크 꺼짐"
}

// ── 결과 ───────────────────────────────────────────────────

@Composable
private fun ResultScreen(ui: GameUi, c: GameController) {
    val round = ui.round ?: return
    val title = when (round.outcome) {
        Outcome.WIN -> "이겼어요!"
        Outcome.LOSE -> "아쉽게 졌어요"
        Outcome.VOID -> "무효 처리된 판"
        else -> "끝"
    }
    val next = c.suggestedNext()
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("${round.kind.label} · ${round.difficulty.label} · 내 단어 ${round.validUserWords}개", fontSize = 22.sp)
        Text(
            if (ui.lastReward > 0) "+${ui.lastReward} 포인트 (잔액 ${ui.save.ledger.balance}P)" else "이번 판 보상 없음",
            fontSize = 22.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { c.start(round.kind, round.difficulty) }, Modifier.height(64.dp)) {
                Text("같은 난이도로 다시", fontSize = 20.sp)
            }
            if (next != null) {
                OutlinedButton(onClick = { c.start(round.kind, next) }, Modifier.height(64.dp)) {
                    Text("${next.label}으로 해 보기", fontSize = 20.sp)
                }
            }
            OutlinedButton(onClick = c::goHome, Modifier.height(64.dp)) { Text("처음 화면", fontSize = 20.sp) }
        }
    }
}

// ── 보관함 ─────────────────────────────────────────────────

@Composable
private fun LockerScreen(ui: GameUi, c: GameController) {
    var message by remember { mutableStateOf<String?>(null) }
    val format = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("보관함", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = c::goHome) { Text("처음 화면") }
        }
        Text("잔액 ${ui.save.ledger.balance}P · 누적 획득 ${ui.save.ledger.totalEarned}P", fontSize = 22.sp)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("데모 주유·충전 쿠폰 (${DEMO_COUPON_COST}P)", fontSize = 20.sp)
                    Text("데모 쿠폰 — 실제 사용 불가. 제휴 전 시연용입니다.", color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = {
                    message = if (c.exchangeDemoCoupon(DEMO_COUPON_COST)) "데모 쿠폰으로 교환했어요(사용 불가)" else "포인트가 모자라요"
                }) { Text("교환") }
            }
        }
        message?.let { Text(it) }
        Text("최근 기록", style = MaterialTheme.typography.titleLarge)
        if (ui.save.history.isEmpty()) Text("아직 기록이 없어요")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.save.history) { r ->
                val result = when (r.outcome) {
                    Outcome.WIN -> "승리"
                    Outcome.LOSE -> "패배"
                    Outcome.VOID -> "무효"
                    else -> r.outcome.name
                }
                Text(
                    "${format.format(Date(r.endedAtEpochMs))}  ${r.kind.label} ${r.difficulty.label} · $result · " +
                        "단어 ${r.userWords}개 · ${if (r.points > 0) "+${r.points}P" else "0P"}",
                    fontSize = 18.sp,
                )
            }
        }
    }
}

// ── 시연 패널 ──────────────────────────────────────────────

@Composable
private fun DemoPanel(ui: GameUi, c: GameController) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.width(300.dp).fillMaxHeight().background(Color(0xFF1E1E24), RoundedCornerShape(0.dp))
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("시연 패널", color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)
        Text("실제 사용자 화면이 아닌 시연·검증용 입력", color = Color.LightGray, fontSize = 13.sp)
        Text("모의 차량 상태", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VehicleState.entries.forEach { s ->
                FilterChip(selected = ui.vehicle == s, onClick = { c.vehicle.set(s) }, label = { Text(s.label) })
            }
        }
        Text("글자 입력 (음성 입력 대체)", color = Color.White)
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { c.typed(text); text = "" }, enabled = ui.screen == Screen.PLAYING, modifier = Modifier.fillMaxWidth()) {
            Text("말한 것으로 보내기")
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = c::simulateAudioInterruption,
            enabled = ui.screen == Screen.PLAYING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("내비 안내 끼어들기(3초)") }
    }
}
