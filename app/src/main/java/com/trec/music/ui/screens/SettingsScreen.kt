// ui/screens/SettingsScreen.kt
//
//

package com.trec.music.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trec.music.PrefsManager
import com.trec.music.ui.components.GlassButton
import com.trec.music.ui.components.GlassDialog
import com.trec.music.ui.components.GlassTextButton
import com.trec.music.ui.theme.TrecRed
import com.trec.music.utils.DebugLogger
import com.trec.music.viewmodel.MusicViewModel
import androidx.navigation.NavController
import java.util.Locale
import kotlin.math.abs

@Composable
fun SettingsScreen(viewModel: MusicViewModel, navController: NavController) {
    val context = LocalContext.current
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    val availableColors = listOf(
        TrecRed, Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
        Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF00BCD4), Color(0xFF009688),
        Color(0xFF4CAF50), Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFFF5722)
    )

    if (showResetConfirmation) {
        GlassDialog(onDismiss = { showResetConfirmation = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Сбросить библиотеку?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Текущая папка будет забыта. Плейлисты могут сломаться.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextButton("Отмена") { showResetConfirmation = false }
                    GlassButton("Сбросить", {
                        PrefsManager(context).clearFolder()
                        viewModel.stopAndClear()
                        viewModel.playlist.clear()
                        viewModel.refreshPlaylists()
                        Toast.makeText(context, "Сброшено", Toast.LENGTH_SHORT).show()
                        showResetConfirmation = false
                    }, TrecRed, Modifier.weight(1f))
                }
            }
        }
    }

    if (showSleepTimerDialog) {
        GlassDialog(onDismiss = { showSleepTimerDialog = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Таймер сна", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

                if (viewModel.sleepTimerRemainingFormatted != null) {
                    Text(
                        text = viewModel.sleepTimerRemainingFormatted!!,
                        color = viewModel.dominantColor,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    GlassButton("Отключить", { viewModel.cancelSleepTimer() }, Color.Gray, Modifier.fillMaxWidth())
                } else {
                    Spacer(Modifier.height(16.dp))
                    val options = listOf(15, 30, 45, 60)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { min ->
                            GlassButton(
                                text = "$min минут",
                                onClick = {
                                    viewModel.startSleepTimer(min)
                                    showSleepTimerDialog = false
                                },
                                color = Color.White.copy(0.1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                GlassTextButton("Закрыть") { showSleepTimerDialog = false }
            }
        }
    }

    if (showAppInfoDialog) {
        GlassDialog(onDismiss = { showAppInfoDialog = false }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text("О приложении", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("TREC Music 1.0.0", color = viewModel.dominantColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Локальный музыкальный плеер с офлайн-библиотекой, плейлистами, DSP-эффектами, текстами песен и дополнительными модулями.",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(14.dp))
                Text("Основные функции:", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text("• Плейлисты и избранное", color = Color.LightGray, fontSize = 13.sp)
                Text("• DSP: реверс, караоке, скорость, пресеты", color = Color.LightGray, fontSize = 13.sp)
                Text("• Тексты песен (online)", color = Color.LightGray, fontSize = 13.sp)
                Text("• Радио и диктофон (опционально)", color = Color.LightGray, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))
                GlassButton("Закрыть", { showAppInfoDialog = false }, TrecRed, Modifier.fillMaxWidth())
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
    ) {
        Spacer(Modifier.height(60.dp))

        Text("Настройки", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(24.dp))

        // ==========================================
        // 1. ВИЗУАЛИЗАЦИЯ (VISUALS)
        // ==========================================
        SectionHeader("Внешний вид")

        // 1.1 Динамический цвет
        SettingsToggle(
            "Динамический цвет",
            "Фон подстраивается под обложку",
            Icons.Rounded.ColorLens,
            viewModel.isDynamicColorEnabled,
            { viewModel.isDynamicColorEnabled = it },
            viewModel.dominantColor
        )

        AnimatedVisibility(
            visible = !viewModel.isDynamicColorEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                availableColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(2.dp, if (viewModel.staticColor == color) Color.White else Color.Transparent, CircleShape)
                            .clickable { viewModel.staticColor = color }
                    )
                }
            }
        }

        SettingsToggle(
            "Режим Винила",
            "Вращающаяся пластинка в плеере",
            Icons.Rounded.Album,
            viewModel.isVinylModeEnabled,
            {
                viewModel.isVinylModeEnabled = it
                if (!it) {
                    viewModel.isNeedleEnabled = false
                    viewModel.isScratchSoundEnabled = false
                }
            },
            viewModel.dominantColor
        )

        AnimatedVisibility(visible = viewModel.isVinylModeEnabled, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(start = 16.dp)) {
                SettingsToggleSmall("Анимация иглы", viewModel.isNeedleEnabled, { viewModel.toggleNeedle() }, viewModel.dominantColor)
                SettingsToggleSmall("Звук скретча", viewModel.isScratchSoundEnabled, { viewModel.toggleScratchSound() }, viewModel.dominantColor)
            }
        }

        // 1.3 Display Options
        SettingsToggle(
            "Не выключать экран",
            "Экран будет гореть пока открыт плеер",
            Icons.Rounded.Smartphone,
            viewModel.keepScreenOn,
            { viewModel.keepScreenOn = it },
            viewModel.dominantColor
        )

        SettingsToggleSmall(
            "Показывать расширения (.mp3)",
            viewModel.showFilename,
            { viewModel.showFilename = it },
            viewModel.dominantColor
        )

        HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 16.dp))

        // ==========================================
        // 2. МОДУЛИ (MODULES)
        // ==========================================
        SectionHeader("Функции")

        SettingsToggle("Диктофон (Студия)", "Вкладка записи", Icons.Rounded.Mic, viewModel.isRecorderFeatureEnabled, { viewModel.isRecorderFeatureEnabled = it }, viewModel.dominantColor)
        SettingsToggle("Радио", "Интернет-радио", Icons.Rounded.Radio, viewModel.isRadioEnabled, { viewModel.isRadioEnabled = it }, viewModel.dominantColor)

        SettingsToggle(
            "DSP Эффекты",
            "Глобальный процессор обработки",
            Icons.Rounded.GraphicEq,
            viewModel.isDspFeatureEnabled,
            { viewModel.isDspFeatureEnabled = it },
            viewModel.dominantColor
        )

        AnimatedVisibility(visible = viewModel.isDspFeatureEnabled, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(start = 16.dp)) {
                SettingsToggleSmall("Реверс (Reverse)", viewModel.isReverseFeatureEnabled, { viewModel.isReverseFeatureEnabled = it }, viewModel.dominantColor)
                SettingsToggleSmall("Караоке (Vocal Remover)", viewModel.isKaraokeFeatureEnabled, { viewModel.isKaraokeFeatureEnabled = it }, viewModel.dominantColor)
                SettingsToggleSmall("Скорость / Питч", viewModel.isSpeedFeatureEnabled, { viewModel.isSpeedFeatureEnabled = it }, viewModel.dominantColor)
            }
        }

        HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 16.dp))

        // ==========================================
        // 3. AUDIO EXPERT (NEW)
        // ==========================================
        SectionHeader("Аудио Эксперт")

        SettingsToggle(
            "Skip Silence",
            "Пропускать тишину в треках",
            Icons.Rounded.SkipNext,
            viewModel.skipSilenceEnabled,
            { viewModel.skipSilenceEnabled = it },
            viewModel.dominantColor
        )

        SettingsToggle(
            "Mono Audio",
            "Суммировать каналы (для 1 наушника)",
            Icons.Rounded.Headphones,
            viewModel.monoAudio,
            { viewModel.monoAudio = it },
            viewModel.dominantColor
        )

        // Crossfade Slider
        SettingsSlider(
            title = "Crossfade",
            value = viewModel.crossfadeMs.toFloat(),
            range = 0f..12000f,
            onValueChange = { viewModel.crossfadeMs = it.toInt() },
            label = if (viewModel.crossfadeMs == 0) "Выкл" else "${viewModel.crossfadeMs / 1000} сек",
            activeColor = viewModel.dominantColor
        )

        // Balance Slider
        SettingsSlider(
            title = "Баланс L / R",
            value = viewModel.audioBalance,
            range = -1f..1f,
            onValueChange = { viewModel.audioBalance = it },
            label = if (abs(viewModel.audioBalance) < 0.1f) "Центр" else if (viewModel.audioBalance < 0) "Left" else "Right",
            activeColor = viewModel.dominantColor
        )

        HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 16.dp))

        // ==========================================
        // 4. BEHAVIOR & DATA
        // ==========================================
        SectionHeader("Система")

        SettingsItem(Icons.Default.Timer, "Таймер сна") {
            showSleepTimerDialog = true
        }
        if (viewModel.sleepTimerRemainingFormatted != null) {
            Text(
                "Осталось: ${viewModel.sleepTimerRemainingFormatted}",
                color = viewModel.dominantColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 56.dp, bottom = 12.dp)
            )
        }

        SettingsToggle(
            "Shake to Skip",
            "Встряхнуть для переключения",
            Icons.Rounded.Vibration,
            viewModel.isShakeEnabled,
            { viewModel.toggleShake() },
            viewModel.dominantColor
        )

        // Cache Management
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.DeleteSweep, null, tint = Color.Gray)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Кэш обработки", color = Color.White, fontSize = 16.sp)
                Text(if (viewModel.reverseCacheSize == "0 MB") "Чисто" else viewModel.reverseCacheSize, color = Color.Gray, fontSize = 12.sp)
            }
            if (viewModel.reverseCacheSize != "0 MB") {
                TextButton(onClick = { viewModel.clearReverseCache(context) }) { Text("Очистить", color = TrecRed) }
            }
        }

        SettingsItem(Icons.Default.FolderOpen, "Выбрать папку музыки") { showResetConfirmation = true }
        SettingsItem(Icons.Default.Equalizer, "Системный эквалайзер") { viewModel.openSystemEqualizer(context) }
        SettingsItem(Icons.Default.Info, "Информация о приложении") { showAppInfoDialog = true }

        HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 16.dp))

        // ==========================================
        // 5. ЮРИДИЧЕСКОЕ
        // ==========================================
        SectionHeader("Юридическая информация")
        SettingsItem(Icons.Default.Policy, "Политика конфиденциальности") { navController.navigate("privacy") }
        SettingsItem(Icons.Default.Gavel, "Условия использования") { navController.navigate("terms") }

        Spacer(Modifier.height(32.dp))

        // --- FOOTER ---
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = { DebugLogger.isVisible = !DebugLogger.isVisible }) {
                Text(if (DebugLogger.isVisible) "Скрыть консоль" else "Режим отладки", color = Color.DarkGray, fontSize = 12.sp)
            }
            Text("TREC Music v1.0.0", color = Color.DarkGray, fontSize = 12.sp)
            Spacer(Modifier.height(32.dp))
        }
    }
}

// --- КОМПОНЕНТЫ ---

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = TrecRed,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color
) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (checked) activeColor else Color.Gray)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = Color.Gray, fontSize = 12.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = activeColor, checkedTrackColor = activeColor.copy(0.3f), uncheckedThumbColor = Color.LightGray, uncheckedTrackColor = Color.DarkGray)
        )
    }
}

@Composable
fun SettingsToggleSmall(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color
) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(40.dp))
        Text(title, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(checkedThumbColor = activeColor, checkedTrackColor = activeColor.copy(0.3f))
        )
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.Gray)
        Spacer(Modifier.width(16.dp))
        Text(title, color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = Color.DarkGray)
    }
}

@Composable
fun SettingsSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    label: String,
    activeColor: Color
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(label, color = activeColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = activeColor, activeTrackColor = activeColor, inactiveTrackColor = Color.DarkGray)
        )
    }
}









