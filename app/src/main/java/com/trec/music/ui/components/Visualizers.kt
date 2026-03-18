// ui/components/Visualizers.kt
//
// ТИП: UI Components (Visual Effects)
//
// СОДЕРЖАНИЕ:
// 1. LiveVisualizer: Столбики (используется в MiniPlayer).
// 2. RippleVinylVisualizer: Мягкие волны и винил (для Диктофона).
// 3. EnhancedBreathingBackground: Аврора-фон (используется в Плеере и на Главной).

package com.trec.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.trec.music.ui.theme.TrecBlack
import com.trec.music.ui.theme.TrecRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ==========================================
// 1. LIVE VISUALIZER (СТОЛБИКИ)
// ==========================================
@Composable
fun LiveVisualizer(isPlaying: Boolean, color: Color) {
    val heights = remember { mutableStateListOf(4f, 6f, 4f, 6f, 4f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                for (i in heights.indices) {
                    val base = 10f
                    val random = Random.nextFloat() * 30f
                    heights[i] = base + random
                }
                delay(70)
            }
        } else {
            for (i in heights.indices) heights[i] = 4f
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        heights.forEachIndexed { index, h ->
            val animatedHeight by animateFloatAsState(
                targetValue = h,
                animationSpec = tween(70, easing = LinearEasing),
                label = "bar_$index"
            )

            Box(
                Modifier
                    .width(4.dp)
                    .height(animatedHeight.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(
                            listOf(color, color.copy(alpha = 0.5f))
                        )
                    )
            )
        }
    }
}

// ==========================================
// 2. RIPPLE VINYL (ВОЛНЫ + ПЛАСТИНКА)
// ==========================================
@Composable
fun RippleVinylVisualizer(
    amplitude: Int, // 0..32767
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    // Вращение пластинки
    val infiniteTransition = rememberInfiniteTransition(label = "VinylSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing)),
        label = "Rot"
    )

    // Список активных волн (прогресс от 0.0 до 1.0)
    val waves = remember { mutableStateListOf<Float>() }

    // Логика добавления волн (реакция на громкость)
    LaunchedEffect(amplitude) {
        // Порог чувствительности (чтобы не реагировать на тишину)
        if (isRecording && amplitude > 800) {
            // Добавляем новую волну, если список пуст или последняя волна немного отошла
            if (waves.isEmpty() || waves.last() > 0.15f) {
                waves.add(0f)
            }
        }
    }

    // Анимационный цикл (движение волн)
    LaunchedEffect(isRecording) {
        while (isActive) {
            if (isRecording) {
                // Двигаем все существующие волны
                for (i in waves.indices) {
                    waves[i] += 0.01f // Скорость расхождения
                }
                // Удаляем те, что ушли за край (стали >= 1.0)
                waves.removeAll { it >= 1f }
            } else {
                if (waves.isNotEmpty()) waves.clear()
            }
            delay(16) // ~60 FPS
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // СЛОЙ 1: ВОЛНЫ (Под пластинкой)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val maxRadius = size.width / 2
            val vinylRadius = 60.dp.toPx()

            waves.forEach { progress ->
                val currentRadius = vinylRadius + (maxRadius - vinylRadius) * progress

                // Мягкое затухание
                val alpha = (1f - progress) * 0.25f // Максимум 25% прозрачности (очень мягко)

                drawCircle(
                    color = TrecRed.copy(alpha = alpha),
                    radius = currentRadius,
                    center = Offset(cx, cy),
                    // ОЧЕНЬ ТОЛСТАЯ ЛИНИЯ для эффекта размытия/волны
                    style = Stroke(width = 40.dp.toPx())
                )
            }
        }

        // СЛОЙ 2: ПЛАСТИНКА
        Canvas(modifier = Modifier.size(120.dp)) {
            rotate(rotation) {
                val r = size.width / 2
                drawCircle(Color(0xFF151515))

                // Декор
                drawCircle(Color.White.copy(0.05f), radius = r * 0.8f, style = Stroke(1.dp.toPx()))
                drawCircle(Color.White.copy(0.05f), radius = r * 0.65f, style = Stroke(1.dp.toPx()))
                drawCircle(Color.White.copy(0.05f), radius = r * 0.5f, style = Stroke(1.dp.toPx()))

                // Яблоко
                drawCircle(TrecRed, radius = r * 0.35f)
                drawCircle(Color.Black, radius = r * 0.05f)
            }
        }
    }
}

// ==========================================
// 3. AURORA BACKGROUND (ФОН)
// ==========================================

// Обертка для совместимости (если где-то используется старое имя)
@Composable
fun BreathingBackground(color: Color) {
    EnhancedBreathingBackground(color)
}

@Composable
fun EnhancedBreathingBackground(
    color: Color,
    modifier: Modifier = Modifier
) {
    // Генерируем палитру на основе основного цвета
    val primaryColor = color.copy(alpha = 0.45f)
    val secondaryColor = remember(color) { shiftHue(color, 40f).copy(alpha = 0.35f) }
    val tertiaryColor = remember(color) { shiftHue(color, -40f).copy(alpha = 0.30f) }

    val infiniteTransition = rememberInfiniteTransition(label = "Aurora")

    // Анимация вращения пятен
    val t by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f, // 2*PI
        animationSpec = infiniteRepeatable(
            animation = tween(25000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t"
    )

    // Анимация пульсации
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(modifier = modifier.fillMaxSize().background(TrecBlack)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val baseRadius = minOf(w, h) * 0.7f

            // Пятно 1 (Основное)
            val x1 = w * 0.5f + (cos(t) * w * 0.15f).toFloat()
            val y1 = h * 0.4f + (sin(t * 0.5f) * h * 0.1f).toFloat()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor, Color.Transparent),
                    center = Offset(x1, y1),
                    radius = baseRadius * scale
                ),
                center = Offset(x1, y1),
                radius = baseRadius * scale,
                blendMode = BlendMode.Screen // Важно для эффекта свечения
            )

            // Пятно 2 (Вторичное)
            val x2 = w * 0.2f + (sin(t * 0.8f) * w * 0.2f).toFloat()
            val y2 = h * 0.8f + (cos(t * 0.8f) * h * 0.15f).toFloat()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondaryColor, Color.Transparent),
                    center = Offset(x2, y2),
                    radius = baseRadius * 0.8f * (2.2f - scale)
                ),
                center = Offset(x2, y2),
                radius = baseRadius * 0.8f * (2.2f - scale),
                blendMode = BlendMode.Screen
            )

            // Пятно 3 (Третичное)
            val x3 = w * 0.8f + (cos(t * 1.2f) * w * 0.15f).toFloat()
            val y3 = h * 0.3f + (sin(t * 1.2f) * h * 0.2f).toFloat()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tertiaryColor, Color.Transparent),
                    center = Offset(x3, y3),
                    radius = baseRadius * 0.6f
                ),
                center = Offset(x3, y3),
                radius = baseRadius * 0.6f,
                blendMode = BlendMode.Screen
            )
        }
    }
}

// Хелпер для сдвига оттенка (Hue Shift)
private fun shiftHue(color: Color, shift: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(
        (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()
    ), hsv)
    hsv[0] = (hsv[0] + shift + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}