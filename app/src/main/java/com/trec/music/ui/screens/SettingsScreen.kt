// ui/screens/SettingsScreen.kt
//
//

package com.trec.music.ui.screens

import android.Manifest
import android.graphics.Color as AndroidColor
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.trec.music.PrefsManager
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.ui.components.GlassButton
import com.trec.music.ui.components.GlassDialog
import com.trec.music.ui.components.GlassTextButton
import com.trec.music.ui.LocalBottomOverlayPadding
import com.trec.music.ui.theme.TrecRed
import com.trec.music.ui.theme.liquidAccent
import com.trec.music.viewmodel.MusicViewModel
import androidx.navigation.NavController
import java.util.Locale
import kotlin.math.abs

@Composable
fun SettingsScreen(viewModel: MusicViewModel, navController: NavController) {
    val context = LocalContext.current
    val bottomOverlay = LocalBottomOverlayPadding.current
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val accent = viewModel.dominantColor.liquidAccent()

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.isRecorderFeatureEnabled = true
            Toast.makeText(context, "Диктофон включен", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.isRecorderFeatureEnabled = false
            Toast.makeText(context, "Для диктофона нужно разрешение микрофона", Toast.LENGTH_LONG).show()
        }
    }

    val availableColors = listOf(
        TrecRed, Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
        Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF00BCD4), Color(0xFF009688),
        Color(0xFF4CAF50), Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFFF5722)
    )

    LaunchedEffect(Unit) {
        viewModel.refreshAppCacheSize(context)
    }

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
                    }, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
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
                        color = accent,
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

    if (showClearCacheDialog) {
        GlassDialog(onDismiss = { showClearCacheDialog = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Очистить кэш приложения?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Удалится кэш обложек и кэш обработки. Это безопасно: при необходимости всё будет пересчитано/загружено заново.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextButton("Отмена") { showClearCacheDialog = false }
                    GlassButton("Очистить", {
                        viewModel.clearAppCache(context)
                        Toast.makeText(context, "Кэш очищен", Toast.LENGTH_SHORT).show()
                        showClearCacheDialog = false
                    }, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                }
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
                Text("TREC Music 1.0.0", color = accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                GlassButton("Закрыть", { showAppInfoDialog = false }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
            }
        }
    }

    if (showArchiveDialog) {
        ArchiveTracksDialog(
            viewModel = viewModel,
            onDismiss = { showArchiveDialog = false }
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
            .padding(bottom = bottomOverlay + 24.dp)
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
            accent
        )

        AnimatedVisibility(
            visible = !viewModel.isDynamicColorEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.useSpectrumColorPicker = !viewModel.useSpectrumColorPicker },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Полный спектр", color = Color.LightGray, fontSize = 14.sp)
                        Text(
                            if (viewModel.useSpectrumColorPicker) "Выбор цветом ползунка" else "Быстрый выбор кружками",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = viewModel.useSpectrumColorPicker,
                        onCheckedChange = { viewModel.useSpectrumColorPicker = it },
                        modifier = Modifier.scale(0.82f),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = viewModel.staticColor.liquidAccent().copy(alpha = 0.58f),
                            checkedBorderColor = viewModel.staticColor.liquidAccent().copy(alpha = 0.9f),
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color.DarkGray.copy(alpha = 0.7f),
                            uncheckedBorderColor = Color.White.copy(alpha = 0.18f)
                        )
                    )
                }

                if (viewModel.useSpectrumColorPicker) {
                    SpectrumColorSlider(
                        color = viewModel.staticColor,
                        onColorChange = { viewModel.staticColor = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        availableColors.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        2.dp,
                                        if (viewModel.staticColor.toArgb() == color.toArgb()) Color.White else Color.Transparent,
                                        CircleShape
                                    )
                                    .clickable { viewModel.staticColor = color }
                            )
                        }
                    }
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
            accent
        )

        AnimatedVisibility(visible = viewModel.isVinylModeEnabled, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(start = 16.dp)) {
                SettingsToggleSmall("Анимация иглы", viewModel.isNeedleEnabled, { viewModel.toggleNeedle() }, accent)
                SettingsToggleSmall("Звук скретча", viewModel.isScratchSoundEnabled, { viewModel.toggleScratchSound() }, accent)
            }
        }

        // 1.3 Display Options
        SettingsToggle(
            "Не выключать экран",
            "Экран будет гореть пока открыт плеер",
            Icons.Rounded.Smartphone,
            viewModel.keepScreenOn,
            { viewModel.keepScreenOn = it },
            accent
        )

        SettingsToggleSmall(
            "Показывать расширения (.mp3)",
            viewModel.showFilename,
            { viewModel.showFilename = it },
            accent
        )

        HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 16.dp))

        // ==========================================
        // 2. МОДУЛИ (MODULES)
        // ==========================================
        SectionHeader("Функции")

        SettingsToggle(
            "Диктофон (Студия)",
            "Вкладка записи (по умолчанию выключена)",
            Icons.Rounded.Mic,
            viewModel.isRecorderFeatureEnabled,
            { enable ->
                if (!enable) {
                    viewModel.isRecorderFeatureEnabled = false
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        viewModel.isRecorderFeatureEnabled = true
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            accent
        )

        SettingsToggle(
            "Радио",
            "Интернет-радио (бета, по умолчанию выключено)",
            Icons.Rounded.Radio,
            viewModel.isRadioEnabled,
            { viewModel.isRadioEnabled = it },
            accent
        )

        SettingsToggle(
            "DSP Эффекты",
            "Глобальный процессор обработки",
            Icons.Rounded.GraphicEq,
            viewModel.isDspFeatureEnabled,
            { viewModel.isDspFeatureEnabled = it },
            accent
        )

        AnimatedVisibility(visible = viewModel.isDspFeatureEnabled, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(start = 16.dp)) {
                SettingsToggleSmall("Реверс (Reverse)", viewModel.isReverseFeatureEnabled, { viewModel.isReverseFeatureEnabled = it }, accent)
                SettingsToggleSmall("Караоке (AI UMXL, ~113MB)", viewModel.isKaraokeFeatureEnabled, { viewModel.isKaraokeFeatureEnabled = it }, accent)
                SettingsToggleSmall("Скорость / Питч", viewModel.isSpeedFeatureEnabled, { viewModel.isSpeedFeatureEnabled = it }, accent)
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
            accent
        )

        SettingsToggle(
            "Mono Audio",
            "Суммировать каналы (для 1 наушника)",
            Icons.Rounded.Headphones,
            viewModel.monoAudio,
            { viewModel.monoAudio = it },
            accent
        )

        // Crossfade Slider
        SettingsSlider(
            title = "Кроссфейд",
            value = viewModel.crossfadeMs.toFloat(),
            range = 0f..12000f,
            onValueChange = { viewModel.crossfadeMs = it.toInt() },
            label = if (viewModel.crossfadeMs == 0) "Выкл" else "${viewModel.crossfadeMs / 1000} сек",
            activeColor = accent
        )

        // Balance Slider
        SettingsSlider(
            title = "Баланс L / R",
            value = viewModel.audioBalance,
            range = -1f..1f,
            onValueChange = { viewModel.audioBalance = it },
            label = if (abs(viewModel.audioBalance) < 0.1f) "Центр" else if (viewModel.audioBalance < 0) "Лево" else "Право",
            activeColor = accent
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
                color = accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 56.dp, bottom = 12.dp)
            )
        }

        SettingsItem(Icons.Rounded.Archive, "Архив треков") {
            showArchiveDialog = true
        }
        if (viewModel.archivedTracks.isNotEmpty()) {
            Text(
                "${viewModel.archivedTracks.size} в архиве",
                color = accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 56.dp, bottom = 12.dp)
            )
        }

        SettingsToggle(
            "Встряхнуть для переключения",
            "Встряхните телефон, чтобы переключить трек",
            Icons.Rounded.Vibration,
            viewModel.isShakeEnabled,
            { viewModel.toggleShake() },
            accent
        )

        // Cache Management (обложки + обработка)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.DeleteSweep, null, tint = Color.Gray)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Кэш", color = Color.White, fontSize = 16.sp)
                Text(if (viewModel.appCacheSize == "0 MB") "Чисто" else viewModel.appCacheSize, color = Color.Gray, fontSize = 12.sp)
            }
            if (viewModel.appCacheSize != "0 MB") {
                TextButton(onClick = { showClearCacheDialog = true }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.primary)
                }
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
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ArchiveTracksDialog(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accent = viewModel.dominantColor.liquidAccent()
    val archivedTracks = viewModel.archivedTracksSnapshot()
    var deleteCandidate by remember { mutableStateOf<TrecTrackEnhanced?>(null) }

    deleteCandidate?.let { track ->
        GlassDialog(onDismiss = { deleteCandidate = null }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Удалить файл?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Файл будет удалён с устройства навсегда, даже если он сейчас в архиве.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextButton("Отмена") { deleteCandidate = null }
                    GlassButton("Удалить", {
                        viewModel.deleteFileFromDevice(context, track)
                        deleteCandidate = null
                    }, accent, Modifier.weight(1f))
                }
            }
        }
    }

    GlassDialog(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Archive, null, tint = accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Архив треков", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (archivedTracks.isEmpty()) "Здесь будут скрытые треки" else "${archivedTracks.size} треков",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(16.dp))

            if (archivedTracks.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Inventory2, null, tint = Color.White.copy(alpha = 0.22f), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Архив пуст", color = Color.White.copy(alpha = 0.72f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Сюда попадут треки, которые надо спрятать, но не удалять.", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                ) {
                    items(
                        items = archivedTracks,
                        key = { it.uri.toString() }
                    ) { track ->
                        ArchivedTrackRow(
                            track = track,
                            viewModel = viewModel,
                            accent = accent,
                            onPlay = { viewModel.playFromArchive(track) },
                            onRestore = { viewModel.restoreArchivedTrack(context, track) },
                            onDelete = { deleteCandidate = track }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            GlassTextButton("Закрыть", onDismiss)
        }
    }
}

@Composable
private fun ArchivedTrackRow(
    track: TrecTrackEnhanced,
    viewModel: MusicViewModel,
    accent: Color,
    onPlay: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val coverUrl = viewModel.getCoverUrlForTrack(track)
    LaunchedEffect(track.uri, coverUrl) {
        if (coverUrl == null) viewModel.ensureCoverForTrack(track)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            )
            AsyncImage(
                model = coverUrl ?: track.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
            )
        }

        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = viewModel.getTrackTitle(track),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.getDisplayArtist(),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.PlayArrow, null, tint = accent)
        }
        IconButton(onClick = onRestore, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Unarchive, null, tint = Color.White.copy(alpha = 0.76f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.DeleteForever, null, tint = accent.copy(alpha = 0.9f))
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(start = 74.dp))
}

@Composable
private fun SpectrumColorSlider(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val hue = remember(color) { hueOf(color) }
    val previewColor = spectrumColor(hue)
    val spectrum = remember {
        listOf(0f, 30f, 60f, 120f, 180f, 220f, 270f, 315f, 360f).map(::spectrumColor)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Акцент приложения", color = Color.LightGray, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(previewColor.hexRgb(), color = previewColor.liquidAccent(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(previewColor)
                        .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
            ) {
                drawLine(
                    brush = Brush.horizontalGradient(spectrum),
                    start = Offset(9.dp.toPx(), size.height / 2f),
                    end = Offset(size.width - 9.dp.toPx(), size.height / 2f),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.22f),
                    start = Offset(9.dp.toPx(), size.height / 2f),
                    end = Offset(size.width - 9.dp.toPx(), size.height / 2f),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            Slider(
                value = hue,
                onValueChange = { onColorChange(spectrumColor(it)) },
                valueRange = 0f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = previewColor.liquidAccent(),
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
    }
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
    val accent = activeColor.liquidAccent()
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            null,
            tint = if (checked) accent else Color.Gray,
            modifier = if (checked) Modifier.shadow(8.dp, CircleShape) else Modifier
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = Color.Gray, fontSize = 12.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (checked) Modifier.shadow(10.dp, CircleShape) else Modifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent.copy(0.55f),
                checkedBorderColor = accent.copy(0.9f),
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color.DarkGray.copy(alpha = 0.7f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.18f)
            )
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
    val accent = activeColor.liquidAccent()
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(40.dp))
        Text(title, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .scale(0.8f)
                .then(if (checked) Modifier.shadow(8.dp, CircleShape) else Modifier),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent.copy(0.55f),
                checkedBorderColor = accent.copy(0.9f),
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color.DarkGray.copy(alpha = 0.7f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.18f)
            )
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
    val accent = activeColor.liquidAccent()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(label, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color.DarkGray)
        )
    }
}

private fun hueOf(color: Color): Float {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    return hsv[0].coerceIn(0f, 360f)
}

private fun spectrumColor(hue: Float): Color {
    return Color(
        AndroidColor.HSVToColor(
            floatArrayOf(
                hue.coerceIn(0f, 360f),
                0.92f,
                0.96f
            )
        )
    )
}

private fun Color.hexRgb(): String {
    return String.format(Locale.US, "#%06X", toArgb() and 0x00FFFFFF)
}









