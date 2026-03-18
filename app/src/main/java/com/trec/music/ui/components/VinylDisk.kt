// ui/components/VinylDisk.kt
//
// ТИП: UI Component
//
// НАЗНАЧЕНИЕ:
// Визуальный компонент виниловой пластинки.
//
// ИЗМЕНЕНИЯ (FINAL RESTORE):
// 1. Visuals: Полностью восстановлена классическая отрисовка (игла, тени, текстура).
// 2. Logic: Gestures подключены к новому MusicViewModel (performVinylScrub) для синхронной перемотки.
// 3. Sensitivity: Улучшен отклик на свайп.

package com.trec.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.trec.music.viewmodel.MusicViewModel
import kotlin.math.PI
import kotlin.math.atan2

@Composable
fun VinylDisk(viewModel: MusicViewModel, modifier: Modifier = Modifier) {
    // Данные из VM
    val currentRotation by animateFloatAsState(
        targetValue = viewModel.vinylRotationAngle,
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "VinylRotation"
    )
    val trackUri = viewModel.currentTrackUri
    val isNeedleEnabled = viewModel.isNeedleEnabled
    val duration = viewModel.duration.toFloat().coerceAtLeast(1f)
    val position = viewModel.currentPosition.toFloat()

    // Анимация иглы (Классическая логика)
    val progress = (position / duration).coerceIn(0f, 1f)
    val needleRotation by animateFloatAsState(
        targetValue = if (viewModel.isPlaying) 5f + (22f * progress) else -72f,
        animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing),
        label = "NeedleAnim"
    )

    var componentSize by remember { mutableStateOf(IntSize.Zero) }
    val center = Offset(componentSize.width / 2f, componentSize.height / 2f)
    var previousAngle by remember { mutableFloatStateOf(0f) }

    // Флаг драга
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.size(340.dp),
        contentAlignment = Alignment.Center
    ) {
        // --- 1. ПЛАСТИНКА (ФИЗИКА) ---
        Box(
            modifier = Modifier
                .size(300.dp)
                .onGloballyPositioned { componentSize = it.size }
                .clip(CircleShape)
                .pointerInput(componentSize) {
                    if (componentSize.width > 0) {
                        val initialPosition = Offset(componentSize.width / 2f, componentSize.height / 2f)
                        val rInitial = initialPosition - center
                        previousAngle = atan2(rInitial.y, rInitial.x)
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val rInitial = offset - center
                            previousAngle = atan2(rInitial.y, rInitial.x)

                            // Сообщаем VM о начале скретча (подготовка звука)
                            viewModel.onScratchStart()
                        },
                        onDragEnd = {
                            isDragging = false
                            // Сообщаем VM о конце скретча (восстановление Play/Pause)
                            viewModel.onScratchEnd()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val rNew = change.position - center
                            val newAngle = atan2(rNew.y, rNew.x)

                            // Вычисляем угол поворота (радианы)
                            var angleDelta = newAngle - previousAngle
                            if (angleDelta > PI) angleDelta -= (2 * PI).toFloat()
                            else if (angleDelta < -PI) angleDelta += (2 * PI).toFloat()

                            // ПЕРЕДАЕМ В VM (Логика перемотки + Звук)
                            viewModel.performVinylScrub(angleDelta)

                            previousAngle = newAngle
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Отрисовка Винила (Классическая)
            Canvas(modifier = Modifier.fillMaxSize().rotate(currentRotation)) {
                drawCircle(Color.Black)
                val sweep = Brush.sweepGradient(
                    0.0f to Color.Black, 0.25f to Color(0xFF222222),
                    0.5f to Color.Black, 0.75f to Color(0xFF222222),
                    1.0f to Color.Black
                )
                drawCircle(brush = sweep)
                // Канавки
                for (i in 25..size.width.toInt() / 2 step 4) {
                    drawCircle(
                        color = Color(0xFF111111),
                        radius = i.toFloat(),
                        style = Stroke(width = 1.5f)
                    )
                }
            }

            // Центральное яблоко
            Box(
                Modifier
                    .size(120.dp)
                    .rotate(currentRotation)
                    .clip(CircleShape)
                    .background(viewModel.dominantColor),
                contentAlignment = Alignment.Center
            ) {
                // Текст всегда отрисовывается, даже под обложкой (на всякий случай)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "TREC",
                        color = Color.White.copy(0.9f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text("RECORDS", color = Color.White.copy(0.6f), fontSize = 10.sp)
                }

                if (trackUri != null) {
                    AsyncImage(
                        model = viewModel.currentCoverUrl ?: trackUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                // Шпиндель
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF111111)))
            }
        }

        // --- 2. ИГЛА (КЛАССИЧЕСКАЯ ОТРИСОВКА) ---
        if (isNeedleEnabled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pivotX = size.width * 0.94f
                val pivotY = size.height * 0.11f
                val armLength = size.width * 0.60f

                // База
                drawCircle(
                    Brush.radialGradient(listOf(Color.White, Color.Gray, Color.DarkGray)),
                    radius = 28.dp.toPx(),
                    center = Offset(pivotX, pivotY)
                )
                drawCircle(Color(0xFF111111), radius = 8.dp.toPx(), center = Offset(pivotX, pivotY))

                rotate(degrees = needleRotation, pivot = Offset(pivotX, pivotY)) {
                    val cwWidth = 40.dp.toPx()
                    val cwHeight = 50.dp.toPx()
                    // Противовес
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(Color.Gray, Color.White, Color.Gray),
                            start = Offset(pivotX - cwWidth, pivotY),
                            end = Offset(pivotX, pivotY)
                        ),
                        topLeft = Offset(pivotX - cwWidth / 2, pivotY - 70.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(cwWidth, cwHeight),
                        cornerRadius = CornerRadius(10f, 10f)
                    )

                    // Трубка
                    val armPath = Path().apply {
                        moveTo(pivotX, pivotY)
                        lineTo(pivotX, pivotY + armLength * 0.65f)
                        cubicTo(
                            pivotX, pivotY + armLength * 0.85f,
                            pivotX - 20f, pivotY + armLength * 0.9f,
                            pivotX - 55f, pivotY + armLength
                        )
                    }
                    drawPath(
                        path = armPath,
                        color = Color.Black.copy(0.3f),
                        style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawPath(
                        path = armPath,
                        brush = Brush.linearGradient(
                            listOf(
                                Color(0xFFAAAAAA),
                                Color.White,
                                Color(0xFFAAAAAA)
                            )
                        ),
                        style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Головка
                    val headX = pivotX - 55f
                    val headY = pivotY + armLength
                    rotate(degrees = 25f, pivot = Offset(headX, headY)) {
                        drawRoundRect(
                            color = Color(0xFF151515),
                            topLeft = Offset(headX - 18.dp.toPx(), headY),
                            size = androidx.compose.ui.geometry.Size(36.dp.toPx(), 50.dp.toPx()),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(headX + 12.dp.toPx(), headY + 15.dp.toPx()),
                            end = Offset(headX + 30.dp.toPx(), headY + 15.dp.toPx()),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}
