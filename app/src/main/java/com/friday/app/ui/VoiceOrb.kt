package com.friday.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.friday.app.core.VoiceState
import kotlin.math.PI
import kotlin.math.sin

fun voiceStateColor(state: VoiceState): Color = when (state) {
    VoiceState.IDLE -> Fx.Blue
    VoiceState.LISTENING -> Fx.Cyan
    VoiceState.PROCESSING -> Color(0xFF7FA8FF)
    VoiceState.SPEAKING -> Fx.Cyan
    VoiceState.INTERRUPTED -> Fx.TextSecondary
    VoiceState.ERROR -> Fx.Warning
}

fun voiceStateLabel(state: VoiceState): String = when (state) {
    VoiceState.IDLE -> "Ready"
    VoiceState.LISTENING -> "Listening"
    VoiceState.PROCESSING -> "Thinking"
    VoiceState.SPEAKING -> "Speaking"
    VoiceState.INTERRUPTED -> "Stopped"
    VoiceState.ERROR -> "Needs attention"
}

/**
 * FRIDAY's presence indicator. Every visual mode maps 1:1 to the real [VoiceState]:
 * idle = slow breathing, listening = rings driven by live microphone level, thinking = orbiting
 * arcs, speaking = rhythmic waveform ring, error = amber static ring.
 */
@Composable
fun VoiceOrb(state: VoiceState, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "breath")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (state == VoiceState.PROCESSING) 1400 else 9000, easing = LinearEasing)), label = "spin")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "phase")
    val color by animateColorAsState(voiceStateColor(state), tween(400), label = "color")
    val mic by animateFloatAsState(if (state == VoiceState.LISTENING) level else 0f, tween(120), label = "mic")

    Canvas(modifier.semantics { contentDescription = "FRIDAY status: ${voiceStateLabel(state)}" }) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.30f
        val breathe = (sin(breath * 2 * PI).toFloat() + 1f) / 2f
        val active = state != VoiceState.IDLE && state != VoiceState.ERROR

        // Ambient halo: subtle, not neon.
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = if (active) .22f else .12f), Color.Transparent), c, r * 2.1f), r * 2.1f, c)

        // Core sphere.
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .95f), color.copy(alpha = .35f), Fx.Background), Offset(c.x - r * .25f, c.y - r * .3f), r * 1.35f), r * (0.92f + 0.04f * breathe + 0.10f * mic), c)
        drawCircle(Color.White.copy(alpha = .10f), r * .35f, Offset(c.x - r * .3f, c.y - r * .35f))

        when (state) {
            VoiceState.LISTENING -> for (i in 0 until 3) {
                val k = ((breath * 3f + i / 3f) % 1f)
                drawCircle(color.copy(alpha = (1f - k) * (0.25f + 0.55f * mic)), r * (1.05f + k * (0.55f + 0.5f * mic)), c, style = Stroke(1.5f * density))
            }
            VoiceState.PROCESSING -> for (i in 0 until 3) {
                val rr = r * (1.22f + i * 0.14f)
                drawArc(color.copy(alpha = .75f - i * .2f), spin * (if (i % 2 == 0) 1 else -1) + i * 70f, 70f + i * 25f, false,
                    Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(2f * density, cap = StrokeCap.Round))
            }
            VoiceState.SPEAKING -> {
                val bars = 48
                for (i in 0 until bars) {
                    val a = i * 2 * PI / bars
                    val amp = 0.5f + 0.5f * sin(phase * 2 + i * 0.7f).toFloat() * sin(phase + i * 0.23f).toFloat()
                    val inner = r * 1.12f
                    val outer = inner + r * (0.08f + 0.28f * amp)
                    val cos = kotlin.math.cos(a).toFloat(); val sn = sin(a).toFloat()
                    drawLine(color.copy(alpha = .8f), Offset(c.x + cos * inner, c.y + sn * inner), Offset(c.x + cos * outer, c.y + sn * outer), 2f * density, StrokeCap.Round)
                }
            }
            VoiceState.ERROR -> drawCircle(color.copy(alpha = .8f), r * 1.18f, c, style = Stroke(1.5f * density))
            else -> {
                drawCircle(color.copy(alpha = .18f + .12f * breathe), r * (1.18f + .05f * breathe), c, style = Stroke(1f * density))
                val rr = r * 1.42f
                drawArc(color.copy(alpha = .35f), spin, 40f, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(1.2f * density, cap = StrokeCap.Round))
            }
        }
    }
}
