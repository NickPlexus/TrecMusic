package com.trec.music.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TWO_PI = 6.2831855f

private data class Star(
    val angle: Float,
    val radiusNorm: Float,
    val size: Float,
    val twinkleSpeed: Float,
    val twinklePhase: Float
)

private data class WarpStreak(
    var angle: Float,
    var headNorm: Float,
    val speed: Float,
    val width: Float
)

private fun sinF(v: Float): Float = sin(v.toDouble()).toFloat()
private fun cosF(v: Float): Float = cos(v.toDouble()).toFloat()
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

@Composable
fun SpaceVisualizer(
    isPlaying: Boolean,
    dominantColor: Color,
    modifier: Modifier = Modifier
) {
    val stars = remember {
        val r = Random(101)
        List(520) {
            val d = r.nextFloat()
            Star(
                angle = r.nextFloat() * TWO_PI,
                radiusNorm = d * d,
                size = 0.5f + r.nextFloat() * 2.2f,
                twinkleSpeed = 0.25f + r.nextFloat() * 1.15f,
                twinklePhase = r.nextFloat() * TWO_PI
            )
        }
    }

    val streaks = remember {
        val r = Random(777)
        MutableList(260) {
            WarpStreak(
                angle = r.nextFloat() * TWO_PI,
                headNorm = r.nextFloat(),
                speed = 0.002f + r.nextFloat() * 0.010f,
                width = 0.7f + r.nextFloat() * 2.8f
            )
        }
    }

    val warp by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing)
    )

    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            t += 0.016f
            if (t > 10000f) t = 0f
            delay(16)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp)
    ) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val maxR = minOf(size.width, size.height) * 0.56f

        val bgR = maxR * (1.02f + 0.22f * warp)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF04070D),
                    Color(0xFF0B172B).copy(alpha = 0.80f),
                    dominantColor.copy(alpha = 0.22f + 0.12f * warp),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = bgR
            ),
            center = Offset(cx, cy),
            radius = bgR
        )

        for (s in stars) {
            val twinkle = (sinF(t * s.twinkleSpeed + s.twinklePhase) + 1f) * 0.5f
            val appear = (sinF(t * 0.17f + s.twinklePhase * 0.7f) + 1f) * 0.5f
            val alpha = (0.12f + twinkle * 0.45f + appear * 0.35f).coerceIn(0f, 1f)
            val r = maxR * (0.05f + s.radiusNorm * 0.95f)
            val px = cx + cosF(s.angle) * r
            val py = cy + sinF(s.angle) * r
            val glow = s.size * (1.5f + twinkle)

            drawCircle(
                color = dominantColor.copy(alpha = alpha * (0.22f + 0.12f * warp)),
                center = Offset(px, py),
                radius = glow
            )
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                center = Offset(px, py),
                radius = s.size
            )
        }

        val accel = lerp(0.25f, 2.2f, warp)
        for (p in streaks) {
            p.headNorm += p.speed * accel
            if (p.headNorm > 1.08f) {
                p.headNorm = 0.02f
                p.angle = (p.angle + 1.73f) % TWO_PI
            }

            val tailNorm = (p.headNorm - (0.05f + 0.18f * warp)).coerceAtLeast(0f)
            val headR = maxR * p.headNorm
            val tailR = maxR * tailNorm

            val hx = cx + cosF(p.angle) * headR
            val hy = cy + sinF(p.angle) * headR
            val tx = cx + cosF(p.angle) * tailR
            val ty = cy + sinF(p.angle) * tailR

            val localAlpha = ((p.headNorm * 0.85f) * warp).coerceIn(0f, 1f)
            if (localAlpha > 0.02f) {
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            dominantColor.copy(alpha = localAlpha * 0.45f),
                            Color.White.copy(alpha = localAlpha)
                        ),
                        start = Offset(tx, ty),
                        end = Offset(hx, hy)
                    ),
                    start = Offset(tx, ty),
                    end = Offset(hx, hy),
                    strokeWidth = p.width * (0.7f + warp * 1.3f)
                )
            }
        }
    }
}
