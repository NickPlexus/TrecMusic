// ui/components/TrackComponents.kt
//
// ТИП: UI Kit (Track Related)
//
// ИЗМЕНЕНИЯ:
// 1. Show Filename Support: TrackRow теперь использует viewModel.getTrackTitle(track)
//    вместо track.title, чтобы уважать настройку отображения расширений.

package com.trec.music.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.ui.theme.TrecRed
import com.trec.music.utils.formatTime
import com.trec.music.viewmodel.MusicViewModel

@Composable
fun VinylPlaceholder(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF101010))
            .border(1.dp, Color.White.copy(0.1f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Имитация винила
        Box(Modifier.fillMaxSize(0.4f).clip(CircleShape).background(color))
        Box(Modifier.fillMaxSize(0.1f).clip(CircleShape).background(Color.Black))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: TrecTrackEnhanced,
    index: Int,
    viewModel: MusicViewModel,
    isSelectionMode: Boolean = false,
    isEditMode: Boolean = false,
    onRemoveClick: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    val isPlaying = viewModel.currentTrackUri == track.uri
    // Если играет - берем доминантный цвет, иначе генерируем по хэшу (для стабильности)
    val color = if(isPlaying) viewModel.dominantColor else TrecRed
    var showMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Haptic Feedback (Вибрация)
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    // Контекстное меню
    if (showMenu) {
        TrackContextMenu(
            track = track,
            viewModel = viewModel,
            onDismiss = { showMenu = false },
            onRemoveFromPlaylist = if (isSelectionMode) onRemoveClick else null,
            onDeleteFromDevice = {
                viewModel.deleteFileFromDevice(context, track)
            }
        )
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (!isEditMode) {
                        {
                            vibrate()
                            showMenu = true
                        }
                    } else null
                )
                .background(if (isPlaying) Color.White.copy(0.08f) else Color.Transparent)
                .padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- 1. ОБЛОЖКА ---
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                // Плейсхолдер на случай если картинки нет
                VinylPlaceholder(color = if (isPlaying) color else Color.DarkGray, modifier = Modifier.fillMaxSize())

                // Реальная обложка
                AsyncImage(
                    model = track.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )

                // Эквалайзер поверх, если играет
                if (isPlaying) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(0.5f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Equalizer, null, tint = color)
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            // --- 2. ИНФОРМАЦИЯ ---
            Column(Modifier.weight(1f)) {
                Text(
                    // UPD: Используем метод из VM для поддержки Show Filename
                    text = viewModel.getTrackTitle(track),
                    color = if (isPlaying) color else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 16.sp
                )
                Text(
                    text = track.artist ?: "Неизвестный исполнитель",
                    color = Color.White.copy(0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }

            // --- 3. ДЛИТЕЛЬНОСТЬ ---
            Text(
                text = if (track.durationMs > 0) formatTime(track.durationMs) else "",
                color = if (isPlaying) color.copy(alpha = 0.8f) else Color.White.copy(0.3f),
                fontSize = 12.sp,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Разделитель
        HorizontalDivider(
            color = Color.White.copy(alpha = 0.05f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = 76.dp) // Отступ под текстом
        )
    }
}

// --- ДИАЛОГИ ---

@Composable
fun TrackContextMenu(
    track: TrecTrackEnhanced,
    viewModel: MusicViewModel,
    onDismiss: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onDeleteFromDevice: () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showInfo) TrackInfoDialog(track, viewModel) { showInfo = false; onDismiss() }
    if (showAddToPlaylist) AddToPlaylistDialog(track, viewModel) { showAddToPlaylist = false; onDismiss() }

    if (showDeleteConfirm) {
        GlassDialog(onDismiss = { showDeleteConfirm = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Удалить файл?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Файл будет удален с устройства навсегда.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextButton("Отмена") { showDeleteConfirm = false }
                    GlassButton("Удалить", {
                        onDeleteFromDevice()
                        showDeleteConfirm = false
                        onDismiss()
                    }, TrecRed, Modifier.weight(1f))
                }
            }
        }
    }

    // Главное меню
    if (!showInfo && !showAddToPlaylist && !showDeleteConfirm) {
        GlassDialog(onDismiss = onDismiss) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist ?: "Неизвестный исполнитель",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(24.dp))

                // Кнопки действий
                GlassButton("Информация", { showInfo = true }, viewModel.dominantColor, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))

                GlassButton("Добавить в плейлист...", { showAddToPlaylist = true }, Color(0xFF333333), Modifier.fillMaxWidth())

                if (onRemoveFromPlaylist != null) {
                    Spacer(Modifier.height(8.dp))
                    GlassButton("Убрать из текущего", {
                        onRemoveFromPlaylist()
                        onDismiss()
                    }, Color(0xFF333333), Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(24.dp))

                // Деструктивное действие
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showDeleteConfirm = true }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DeleteForever, null, tint = TrecRed, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить с устройства", color = TrecRed, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))
                GlassTextButton("Закрыть", onDismiss)
            }
        }
    }
}

@Composable
fun TrackInfoDialog(track: TrecTrackEnhanced, viewModel: MusicViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Получаем метаданные (Битрейт, Формат и т.д.)
    val info = remember(track.uri) { viewModel.getTrackMetadataForUri(context, track.uri) }

    GlassDialog(onDismiss = onDismiss) {
        Column {
            Text("Свойства трека", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // Используем InfoRow из GlassComponents.kt
            info.forEach { (k, v) -> InfoRow(label = k, value = v) }

            Spacer(Modifier.height(24.dp))
            GlassButton("OK", onDismiss, TrecRed, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun AddToPlaylistDialog(track: TrecTrackEnhanced, viewModel: MusicViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    GlassDialog(onDismiss = onDismiss) {
        Column {
            Text("Добавить в плейлист", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            if (viewModel.userPlaylists.isEmpty()) {
                Text("Нет плейлистов. Создайте их в медиатеке.", color = Color.Gray)
            } else {
                // Список плейлистов для выбора
                viewModel.userPlaylists.forEach { playlistName ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addTrackToPlaylist(playlistName, track.uri.toString())
                                Toast.makeText(context, "Добавлено в $playlistName", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color.Gray)
                        Spacer(Modifier.width(16.dp))
                        Text(playlistName, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.Add, null, tint = TrecRed)
                    }
                    HorizontalDivider(color = Color.White.copy(0.1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            GlassTextButton("Отмена", onDismiss)
        }
    }
}
