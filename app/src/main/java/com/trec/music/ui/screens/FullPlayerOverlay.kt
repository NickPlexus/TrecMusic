// ui/screens/FullPlayerOverlay.kt
//
// ТИП: UI Screen (Overlay)
//
// ИЗМЕНЕНИЯ:
// 1. Granular DSP Visibility: Кнопки (Reverse, Karaoke, Speed) теперь скрываются,
//    если соответствующие функции отключены в настройках.
// 2. Lyrics Integration: Добавлена кнопка и диалог для текстов песен.

package com.trec.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.trec.music.ui.components.*
import com.trec.music.ui.components.SpaceVisualizer
import com.trec.music.utils.formatTime
import com.trec.music.viewmodel.MusicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullPlayerOverlay(viewModel: MusicViewModel, onClose: () -> Unit) {
    val animatedDominantColor by animateColorAsState(targetValue = viewModel.dominantColor, animationSpec = tween(1500))
    val context = LocalContext.current
    var showInfoDialog by remember { mutableStateOf(false) }

    if (showInfoDialog) {
        val infoState = produceState(initialValue = mapOf("Загрузка..." to ""), key1 = viewModel.currentTrackUri) {
            value = withContext(Dispatchers.IO) {
                viewModel.getTrackMetadata(context)
            }
        }

        GlassDialog(onDismiss = { showInfoDialog = false }) {
            Column {
                Text("Информация о треке", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                infoState.value.forEach { (k, v) ->
                    InfoRow(label = k, value = v)
                }
                Spacer(Modifier.height(24.dp))
                GlassButton("Закрыть", { showInfoDialog = false }, viewModel.dominantColor, Modifier.fillMaxWidth())
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = false) {}
    ) {
        EnhancedBreathingBackground(color = animatedDominantColor)

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            if (dragAmount.y > 30) onClose()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(0.3f))
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.background(Color.White.copy(0.1f), CircleShape)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White)
                    }

                    LiveVisualizer(isPlaying = viewModel.isPlaying, color = Color.White.copy(0.8f))

                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.background(Color.White.copy(0.1f), CircleShape)
                    ) {
                        Icon(Icons.Outlined.Info, null, tint = Color.White.copy(0.8f))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount -> if (dragAmount.y > 60) onClose() }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Космический эффект позади пластинки
                if (viewModel.isVinylModeEnabled) {
                    SpaceVisualizer(
                        isPlaying = viewModel.isPlaying,
                        dominantColor = animatedDominantColor,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                    )
                }
                
                if (viewModel.isVinylModeEnabled) {
                    VinylDisk(
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .aspectRatio(1f)
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .size(320.dp)
                            .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.DarkGray
                    ) {
                        if (viewModel.currentTrackUri != null) {
                            AsyncImage(
                                model = viewModel.currentCoverUrl ?: viewModel.currentTrackUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(0.2f), modifier = Modifier.size(100.dp))
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(32.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.1f))
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = viewModel.currentTrackTitle,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                            )
                            Text(viewModel.currentTrackArtist ?: "Неизвестный исполнитель", fontSize = 14.sp, color = Color.White.copy(0.6f))
                        }

                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                if (viewModel.isCurrentTrackFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                null,
                                tint = if (viewModel.isCurrentTrackFav) animatedDominantColor else Color.White.copy(0.6f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        
                        IconButton(onClick = { 
                            viewModel.loadLyrics()
                            viewModel.showLyricsDialog = true 
                        }) {
                            Icon(
                                Icons.Rounded.MusicNote,
                                contentDescription = "Текст песни",
                                tint = if (viewModel.currentLyrics != null) animatedDominantColor else Color.White.copy(0.6f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    TimeControls(
                        currentPosition = viewModel.currentPosition,
                        duration = viewModel.duration,
                        dominantColor = animatedDominantColor,
                        onSeek = { pos ->
                            viewModel.seekTo(pos)
                            if (viewModel.isVinylModeEnabled) viewModel.vinylRotationAngle += 5f
                        },
                        onSeekStart = { if (viewModel.isVinylModeEnabled) viewModel.startScratchLoop() },
                        onSeekEnd = { if (viewModel.isVinylModeEnabled) viewModel.stopScratchLoop() }
                    )

                    Spacer(Modifier.height(20.dp))

                    if (viewModel.isDspFeatureEnabled) {
                        Row(
                            Modifier.fillMaxWidth(),
                            Arrangement.SpaceBetween,
                            Alignment.CenterVertically
                        ) {
                            EffectsPresetMenu(viewModel)

                            if (viewModel.isReverseFeatureEnabled) {
                                GlassyControlBtn(onClick = { viewModel.toggleReverse(context) }) {
                                    if (viewModel.isGeneratingReverse) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            Icons.Rounded.History,
                                            "Rev",
                                            tint = if (viewModel.isReversing) animatedDominantColor
                                            else if (viewModel.isReverseReady) Color.White
                                            else Color.Gray
                                        )
                                    }
                                }
                            }

                            if (viewModel.isKaraokeFeatureEnabled) {
                                GlassyControlBtn(onClick = { viewModel.toggleVocalRemover(context) }) {
                                    if (viewModel.isVocalRemovalProcessing) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            Icons.Rounded.MicOff,
                                            "Karaoke",
                                            tint = if (viewModel.instrumentalTrackPath != null) animatedDominantColor
                                            else if (viewModel.isInstrumentalReady) Color.White
                                            else Color.Gray
                                        )
                                    }
                                }
                            }

                            if (viewModel.isSpeedFeatureEnabled) {
                                SpeedControlDialog(viewModel)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    } else {
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleShuffle() }) {
                            Icon(Icons.Rounded.Shuffle, null, tint = if (viewModel.shuffleMode) animatedDominantColor else Color.White.copy(0.3f))
                        }
                        IconButton(onClick = { viewModel.skipPrev() }, Modifier.size(50.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                        }
                        Surface(
                            modifier = Modifier.size(72.dp).clickable { viewModel.togglePlay() },
                            shape = CircleShape, color = Color.White, shadowElevation = 10.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (viewModel.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(36.dp))
                            }
                        }
                        IconButton(onClick = { viewModel.skipNext() }, Modifier.size(50.dp)) {
                            Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                        }
                        IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                            val (icon, tint) = when (viewModel.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to animatedDominantColor
                                Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to animatedDominantColor
                                else -> Icons.Default.Repeat to Color.White.copy(0.3f)
                            }
                            Icon(icon, null, tint = tint)
                        }
                    }
                }
            }
        }
    }

    // Lyrics Dialog
    if (viewModel.showLyricsDialog) {
        val currentTrack = viewModel.playlist.find { it.uri == viewModel.currentTrackUri }
        if (currentTrack != null) {
            LyricsDialog(
                title = currentTrack.title,
                artist = currentTrack.artist ?: "Unknown Artist",
                lyrics = viewModel.currentLyrics,
                isLoading = viewModel.isLoadingLyrics,
                error = viewModel.lyricsError,
                currentPosition = viewModel.currentPosition,
                duration = viewModel.duration,
                onDismiss = { viewModel.showLyricsDialog = false },
                onRetry = { viewModel.loadLyrics() }
            )
        }
    }
}

@Composable
private fun TimeControls(
    currentPosition: Long,
    duration: Long,
    dominantColor: Color,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    if (!isDragging) {
        sliderValue = if (duration > 0) currentPosition.toFloat() else 0f
    }

    Column {
        Slider(
            value = sliderValue,
            onValueChange = {
                if (!isDragging) { isDragging = true; onSeekStart() }
                sliderValue = it; onSeek(it.toLong())
            },
            onValueChangeFinished = { isDragging = false; onSeekEnd() },
            valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = dominantColor, inactiveTrackColor = Color.White.copy(0.2f))
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime(currentPosition), color = Color.White.copy(0.5f), fontSize = 12.sp)
            Text(formatTime(duration), color = Color.White.copy(0.5f), fontSize = 12.sp)
        }
    }
}

@Composable
fun GlassyControlBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(0.08f)).border(1.dp, Color.White.copy(0.1f), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

