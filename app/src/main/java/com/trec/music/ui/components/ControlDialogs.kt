// ui/components/ControlDialogs.kt
//
// ТИП: UI Components (Dialogs)
//
// ЗАВИСИМОСТИ:
// - viewmodel/MusicViewModel.kt
// - data/AudioPresets.kt
// - ui/components/GlassComponents.kt (GlassDialog, GlassButton)
//
// НАЗНАЧЕНИЕ:
// Диалоги управления скоростью и аудио-эффектами.
//
// УЛУЧШЕНИЯ (PREMIUM UI):
// 1. Speed Chips: Быстрый выбор популярных скоростей (0.5, 1.0, 1.25, 1.5, 2.0).
// 2. Dynamic Theming: Используется viewModel.dominantColor для акцентов.
// 3. Scrollable Lists: Оптимизированный список пресетов.

package com.trec.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trec.music.data.AudioPresets
import com.trec.music.viewmodel.MusicViewModel
import java.util.Locale

@Composable
fun SpeedControlDialog(viewModel: MusicViewModel) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        GlassDialog(onDismiss = { showDialog = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Заголовок и кнопка сброса
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        text = "Скорость",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    if (viewModel.playbackSpeed != 1.0f) {
                        Text(
                            text = "Сброс",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .clickable { viewModel.setSpeed(1.0f) }
                                .padding(8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Крупное значение
                Text(
                    text = String.format(Locale.US, "%.2f", viewModel.playbackSpeed) + "x",
                    color = viewModel.dominantColor, // Используем динамический цвет
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(24.dp))

                // Слайдер
                Slider(
                    value = viewModel.playbackSpeed,
                    onValueChange = { viewModel.setSpeed(it) },
                    valueRange = 0.5f..2.0f,
                    steps = 29, // Шаг 0.05
                    colors = SliderDefaults.colors(
                        thumbColor = viewModel.dominantColor,
                        activeTrackColor = viewModel.dominantColor,
                        inactiveTrackColor = Color.White.copy(0.1f)
                    )
                )

                Spacer(Modifier.height(24.dp))

                // Быстрые кнопки (Chips)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
                    speeds.forEach { speed ->
                        val isSelected = kotlin.math.abs(viewModel.playbackSpeed - speed) < 0.01f
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp, 32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) viewModel.dominantColor else Color.White.copy(0.1f)
                                )
                                .clickable { viewModel.setSpeed(speed) }
                        ) {
                            Text(
                                text = if (speed == 1.0f) "1x" else "${speed}x",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                GlassButton("Готово", { showDialog = false }, viewModel.dominantColor, Modifier.fillMaxWidth())
            }
        }
    }

    // Иконка вызова (подсвечивается, если скорость изменена)
    val iconColor = if (viewModel.playbackSpeed != 1.0f) viewModel.dominantColor else Color.White
    IconButton(onClick = { showDialog = true }) {
        Icon(Icons.Rounded.Speed, "Speed", tint = iconColor)
    }
}

@Composable
fun EffectsPresetMenu(viewModel: MusicViewModel) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        GlassDialog(onDismiss = { showDialog = false }) {
            Column {
                Text(
                    text = "Эффекты",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)
                )

                // Список с прокруткой
                Box(Modifier.heightIn(max = 400.dp)) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(AudioPresets.presets) { preset ->
                            val isSelected = viewModel.currentPresetName == preset.name
                            val activeColor = viewModel.dominantColor

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) activeColor.copy(alpha = 0.2f) else Color.Transparent)
                                    .border(1.dp, if (isSelected) activeColor.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable {
                                        viewModel.applyPreset(preset.name)
                                        // Не закрываем диалог сразу, чтобы юзер мог потыкать разные пресеты
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = preset.name,
                                        color = if (isSelected) Color.White else Color.White.copy(0.7f),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 16.sp
                                    )

                                    // Детали пресета (если он меняет скорость/питч)
                                    if (preset.speed != 1.0f || preset.pitch != 1.0f) {
                                        Text(
                                            text = "Speed: ${preset.speed}x  Pitch: ${preset.pitch}x",
                                            color = if (isSelected) activeColor else Color.Gray,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(Icons.Default.Check, null, tint = activeColor)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                GlassTextButton("Закрыть") { showDialog = false }
            }
        }
    }

    // Иконка вызова (подсвечивается, если включен любой эффект кроме Normal)
    IconButton(onClick = { showDialog = true }) {
        Icon(
            Icons.Rounded.AutoFixHigh,
            "FX",
            tint = if (viewModel.currentPresetName != "Normal") viewModel.dominantColor else Color.White
        )
    }
}