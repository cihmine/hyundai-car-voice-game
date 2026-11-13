package com.cihmine.carvoicegame.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cihmine.carvoicegame.core.GameKind
import com.cihmine.carvoicegame.core.Outcome
import com.cihmine.carvoicegame.core.hangul.Hangul
import com.cihmine.carvoicegame.game.GameController
import com.cihmine.carvoicegame.game.GameUi
import com.cihmine.carvoicegame.game.Mic
import com.cihmine.carvoicegame.game.Screen
import com.cihmine.carvoicegame.speech.VoiceLevel
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val Black = Color(0xFF000000)
private val AiText = Color(0xFFFFFFFF)
private val UserText = Color(0xFF9A9AA2)
private val Dim = Color(0xFF55555C)
private val Soft = Color(0xFFB4B4BC) // 심사자가 처음 읽는 소개 문구는 어두운 글자로 두지 않는다

/**
 * 실제 사용자 화면. 검은 화면 가운데 원이 있고, 위쪽에 자막이 나온다.
 * - AI 가 말할 때: 흰 원이 목소리에 맞춰 커졌다 작아지며 반짝이고, 하는 말이 흰 자막으로 나온다.
 * - 사용자가 말할 때: 원이 회색 고리로 바뀌어 목소리 크기에 반응하고, 지금 어떤 말로 인식되는지 회색 자막으로 보여 준다.
 */
@Composable
fun VoiceStage(ui: GameUi, c: GameController) {
    val aiLevel by VoiceLevel.ai.collectAsState()
    val userLevel by VoiceLevel.user.collectAsState()
    Box(Modifier.fillMaxSize().background(Black)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Captions(ui)
            // 가운데 원, 그 왼쪽에 지금 이어야 할 마지막 단어(안내 자막이 바뀌어도 늘 보이게)
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { LastWord(ui) }
                Orb(ui.mic, aiLevel, userLevel)
                Spacer(Modifier.weight(1f))
            }
            StatusLine(ui, c)
        }
        // 오른쪽 위: 터치 화면(게임·난이도 고르기, 시연 패널)으로
        Text(
            "터치 화면 ▸",
            color = Color.White,
            fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).clickable { c.setStageMode(false) },
        )
    }
}

/** 위쪽 자막: AI 가 한 말(흰색) → 그 아래 사용자가 말하는 중·인식된 말(회색) */
@Composable
private fun Captions(ui: GameUi) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            ui.aiCaption ?: "",
            color = AiText,
            fontSize = 36.sp,
            lineHeight = 48.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val user = ui.userCaption
        Text(
            when {
                user != null -> user
                ui.mic == Mic.LISTENING -> "듣고 있어요…"
                else -> ""
            },
            color = if (user != null) UserText else Dim,
            fontSize = 32.sp,
            lineHeight = 42.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 원 왼쪽: 마지막 단어. 끝말잇기는 이어야 할 글자(두음 법칙 글자 포함), 초성 대결은 초성을 함께 보여 준다.
 * 게임 중일 때만 보인다.
 */
@Composable
private fun LastWord(ui: GameUi) {
    val r = ui.round?.takeIf { ui.screen == Screen.PLAYING } ?: return
    Column(Modifier.padding(end = 24.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.kind == GameKind.CHOSEONG) {
            Text("초성", color = Dim, fontSize = 22.sp)
            Text(r.choseong ?: "", color = AiText, fontSize = 64.sp, fontWeight = FontWeight.Bold)
            return@Column
        }
        val word = r.lastAiWord ?: return@Column
        Text("마지막 단어", color = Dim, fontSize = 22.sp)
        Text(word, color = AiText, fontSize = 64.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        val starts = (listOf(word.last()) + (Hangul.startCharsAfter(word.last()) - word.last())).joinToString(" · ")
        Text("$starts (으)로 시작", color = UserText, fontSize = 24.sp)
    }
}

/**
 * 가운데 원.
 * 말하는 중 = 빛나는 흰 원(목소리 크기 + 은은한 반짝임), 듣는 중 = 숨 쉬듯 커졌다 작아지는 회색 고리(사용자 목소리에 반응),
 * 쉬는 중 = 작고 어두운 점.
 */
@Composable
private fun Orb(mic: Mic, aiLevel: Float, userLevel: Float) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val spin by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "spin")
    val flicker by t.animateFloat(0f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "flicker")

    // 엔진이 소리 크기를 주지 않아도 말하는 동안 살아 있게 보이도록 기본 반짝임을 섞는다
    val speakLevel = max(aiLevel, 0.25f + 0.25f * flicker)
    val target = when (mic) {
        Mic.SPEAKING -> 0.62f + 0.30f * speakLevel
        Mic.LISTENING -> 0.42f + 0.06f * breathe + 0.30f * userLevel
        Mic.OFF -> 0.22f
    }
    val scale by animateFloatAsState(target, spring(dampingRatio = 0.55f, stiffness = 260f), label = "scale")

    Canvas(Modifier.size(360.dp)) {
        val c = center
        val r = size.minDimension / 2f * scale
        when (mic) {
            Mic.SPEAKING -> {
                // 바깥 빛 번짐
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
                // 본체: 가운데는 하얗고 가장자리는 살짝 푸른 빛
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFF2F4FF), Color(0xFFC9D2FF)), c, r), r, c)
                // 반짝임: 원 안을 천천히 도는 밝은 점 세 개
                for (i in 0 until 3) {
                    val a = spin + i * (2f * Math.PI.toFloat() / 3f)
                    val p = Offset(c.x + cos(a) * r * 0.45f, c.y + sin(a) * r * 0.45f)
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), p, r * 0.55f), r * 0.55f, p)
                }
            }
            Mic.LISTENING -> {
                drawCircle(Brush.radialGradient(listOf(Color(0x33FFFFFF), Color.Transparent), c, r * 1.4f), r * 1.4f, c)
                drawCircle(Color(0xFF8E8E96).copy(alpha = 0.55f + 0.45f * userLevel), r, c, style = Stroke(width = 6.dp.toPx()))
                drawCircle(Color(0xFF8E8E96).copy(alpha = 0.10f + 0.30f * userLevel), r * 0.85f, c)
            }
            Mic.OFF -> drawCircle(Color(0xFF3A3A40), r, c)
        }
    }
}

/** 아래쪽 작은 정보: 게임·난이도·남은 시간 */
@Composable
private fun StatusLine(ui: GameUi, c: GameController) {
    val round = ui.round
    val info = when (ui.screen) {
        Screen.HOME -> "말게임 — 주차·충전 중에 AI 와 말로 하는 끝말잇기·초성 대결"
        Screen.PLAYING -> round?.let { r ->
            listOfNotNull(
                "${r.kind.label} · ${r.difficulty.label}",
                ui.remainingMs?.let { "남은 시간 ${(it + 999) / 1000}초" },
                "힌트 ${r.hintsLeft}번",
            ).joinToString("  ·  ")
        }
        Screen.RESULT -> round?.let { r ->
            val title = when (r.outcome) {
                Outcome.WIN -> "이겼어요"
                Outcome.LOSE -> "졌어요"
                // 그만둔 판: 이어 간 단어 수를 먼저, 보상 규칙은 보조로
                else -> "단어 ${r.validUserWords}개 이음  ·  승패 미집계"
            }
            "$title  ·  ${if (ui.lastReward > 0) "+${ui.lastReward}P" else "포인트 없음"}  ·  잔액 ${ui.save.ledger.balance}P"
        }
        Screen.LOCKER -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 멈춤·마이크 오류·주행 전환 같은 상태를 기본 화면에도 같은 자리에 크게 보인다(이유 없이 조용해지지 않게)
        if (ui.screen == Screen.PLAYING) {
            val state = ui.notice ?: if (round?.paused == true) "잠시 멈춤 — '이어서'라고 말하면 계속해요" else null
            state?.let { Text(it, color = Color(0xFFFFD54F), fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (info != null) Text(info, color = if (ui.screen == Screen.HOME) Soft else Dim, fontSize = 20.sp)
        if (!ui.micGranted || !ui.sttAvailable) {
            Text("음성 인식을 쓸 수 없어요 — 오른쪽 위 '터치 화면'을 써 주세요", color = Dim, fontSize = 16.sp)
        } else if (ui.screen == Screen.HOME) {
            Text("마이크가 없으면 오른쪽 위 '터치 화면'에서 글자로 해 볼 수 있어요", color = Soft, fontSize = 16.sp)
        }
        Spacer(Modifier.height(4.dp))
    }
}
