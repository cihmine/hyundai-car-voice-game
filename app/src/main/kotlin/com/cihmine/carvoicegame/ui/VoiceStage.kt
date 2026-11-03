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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.cihmine.carvoicegame.core.Outcome
import com.cihmine.carvoicegame.game.Ask
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
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Orb(ui.mic, aiLevel, userLevel)
            }
            StatusLine(ui, c)
        }
        // 오른쪽 위: 터치 화면(게임·난이도 고르기, 시연 패널)으로
        Text(
            "터치 화면 ▸",
            color = Dim,
            fontSize = 16.sp,
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

/** 아래쪽 작은 정보: 게임·난이도·남은 시간, 질문 중이면 네/아니요 버튼(음성이 안 될 때 대신) */
@Composable
private fun StatusLine(ui: GameUi, c: GameController) {
    val round = ui.round
    val info = when (ui.screen) {
        Screen.HOME -> "끝말잇기 · 초성 대결"
        Screen.PLAYING -> round?.let { r ->
            listOfNotNull(
                // 질문·안내가 나오면 자막에서 제 단어가 사라지므로 여기 늘 보여 준다
                r.lastAiWord?.let { "제 단어 $it" },
                "${r.kind.label} · ${r.difficulty.label}",
                ui.remainingMs?.let { "남은 시간 ${(it + 999) / 1000}초" },
                "힌트 ${r.hintsLeft}번",
            ).joinToString("  ·  ")
        }
        Screen.RESULT -> round?.let { r ->
            val title = when (r.outcome) {
                Outcome.WIN -> "이겼어요"
                Outcome.LOSE -> "졌어요"
                else -> "끝"
            }
            "$title  ·  ${if (ui.lastReward > 0) "+${ui.lastReward}P" else "보상 없음"}  ·  잔액 ${ui.save.ledger.balance}P"
        }
        Screen.LOCKER -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (ui.ask != Ask.NONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill("네") { c.answer(true) }
                Pill("아니요") { c.answer(false) }
            }
        }
        if (info != null) Text(info, color = Dim, fontSize = 20.sp)
        if (!ui.micGranted || !ui.sttAvailable) Text("음성 인식을 쓸 수 없어요 — 오른쪽 위 '터치 화면'을 써 주세요", color = Dim, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.background(Color(0xFF1C1C20), RoundedCornerShape(28.dp)).clickable(onClick = onClick).padding(horizontal = 36.dp, vertical = 14.dp),
    ) { Text(label, color = Color.White, fontSize = 22.sp) }
}
