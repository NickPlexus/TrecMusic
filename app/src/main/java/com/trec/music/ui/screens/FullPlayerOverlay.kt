// ui/screens/FullPlayerOverlay.kt
//
// ТИП: UI Screen (Overlay)

package com.trec.music.ui.screens

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.trec.music.ui.components.*
import com.trec.music.ui.theme.liquidAccent
import com.trec.music.utils.formatTime
import com.trec.music.viewmodel.KaraokeOutputMode
import com.trec.music.viewmodel.MusicViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullPlayerOverlay(viewModel: MusicViewModel, onClose: () -> Unit) {
    val animatedDominantColor by animateColorAsState(targetValue = viewModel.dominantColor, animationSpec = tween(1500), label = "color")
    val uiAccent = animatedDominantColor.liquidAccent()
    val context = LocalContext.current
    var showInfoDialog by remember { mutableStateOf(false) }
    var showTrackActions by remember { mutableStateOf(false) }
    var showPlayerArchiveConfirm by remember { mutableStateOf(false) }
    var showPlayerDeleteConfirm by remember { mutableStateOf(false) }
    var showKaraokeMenu by remember { mutableStateOf(false) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
    var controlsBounds by remember { mutableStateOf<Rect?>(null) }
    val activeTrack = viewModel.currentPlaybackTrack()

    if (showInfoDialog) {
        val trackUri = viewModel.currentTrackUri
        var info by remember(trackUri) { mutableStateOf(mapOf("Загрузка..." to "")) }

        LaunchedEffect(trackUri) {
            info = withContext(Dispatchers.IO) { viewModel.getTrackMetadata(context) }
        }

        GlassDialog(onDismiss = { showInfoDialog = false }) {
            Column {
                Text("Информация о треке", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                info.forEach { (k, v) ->
                    InfoRow(label = k, value = v)
                }
                Spacer(Modifier.height(24.dp))
                GlassButton("Закрыть", { showInfoDialog = false }, uiAccent, Modifier.fillMaxWidth())
            }
        }
    }

    if (showKaraokeMenu) {
        GlassDialog(onDismiss = { showKaraokeMenu = false }) {
            Column {
                Text("AI Караоке", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                Text("Режим", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val isInst = viewModel.karaokeOutputMode == KaraokeOutputMode.INSTRUMENTAL
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isInst) uiAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isInst) uiAccent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier.weight(1f).clickable { viewModel.updateKaraokeOutputMode(KaraokeOutputMode.INSTRUMENTAL) }
                    ) {
                        Text("Минус", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    val isAca = viewModel.karaokeOutputMode == KaraokeOutputMode.ACAPELLA
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isAca) uiAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isAca) uiAccent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier.weight(1f).clickable { viewModel.updateKaraokeOutputMode(KaraokeOutputMode.ACAPELLA) }
                    ) {
                        Text("Акапелла", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Сила вырезания: ${(viewModel.karaokeRemovalStrength * 100f).roundToInt()}%",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp
                )
                Slider(
                    value = viewModel.karaokeRemovalStrength,
                    onValueChange = { viewModel.updateKaraokeRemovalStrength(it) },
                    valueRange = 0f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = uiAccent,
                        activeTrackColor = uiAccent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    )
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Буст вокала: ${(viewModel.karaokeVocalBoost * 100f).roundToInt()}%",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp
                )
                Slider(
                    value = viewModel.karaokeVocalBoost,
                    onValueChange = { viewModel.updateKaraokeVocalBoost(it) },
                    valueRange = 1f..2f,
                    colors = SliderDefaults.colors(
                        thumbColor = uiAccent,
                        activeTrackColor = uiAccent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    )
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Удерживай кнопку микрофона, чтобы открыть это меню снова.",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                GlassButton("Готово", { showKaraokeMenu = false }, uiAccent, Modifier.fillMaxWidth())
            }
        }
    }

    if (showTrackActions) {
        GlassDialog(onDismiss = { showTrackActions = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = activeTrack?.title ?: viewModel.currentTrackTitle,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = activeTrack?.getDisplayArtist() ?: viewModel.getCurrentDisplayArtist(),
                    color = Color.Gray,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(22.dp))

                GlassButton("Информация", {
                    showTrackActions = false
                    showInfoDialog = true
                }, uiAccent, Modifier.fillMaxWidth())

                Spacer(Modifier.height(8.dp))
                GlassButton("Архивировать", {
                    showTrackActions = false
                    showPlayerArchiveConfirm = true
                }, Color.White.copy(alpha = 0.10f), Modifier.fillMaxWidth())

                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            showTrackActions = false
                            showPlayerDeleteConfirm = true
                        }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DeleteForever, null, tint = uiAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Удалить с устройства", color = uiAccent, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))
                GlassTextButton("Закрыть") { showTrackActions = false }
            }
        }
    }

    if (showPlayerArchiveConfirm && activeTrack != null) {
        GlassDialog(onDismiss = { showPlayerArchiveConfirm = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Архивировать трек?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Он исчезнет из обычной медиатеки и плейлистов, но останется в архиве настроек.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextButton("Отмена") { showPlayerArchiveConfirm = false }
                    GlassButton("В архив", {
                        viewModel.archiveTrack(context, activeTrack)
                        showPlayerArchiveConfirm = false
                    }, uiAccent, Modifier.weight(1f))
                }
            }
        }
    }

    if (showPlayerDeleteConfirm && activeTrack != null) {
        GlassDialog(onDismiss = { showPlayerDeleteConfirm = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Удалить файл?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Файл будет удалён с устройства навсегда.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextButton("Отмена") { showPlayerDeleteConfirm = false }
                    GlassButton("Удалить", {
                        viewModel.deleteFileFromDevice(context, activeTrack)
                        showPlayerDeleteConfirm = false
                    }, uiAccent, Modifier.weight(1f))
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { rootSizePx = it }
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
            // ВЕРХНЯЯ ПАНЕЛЬ СО СВАЙПОМ
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .pointerInput(Unit) {
                        var dragOffset = 0f
                        detectVerticalDragGestures(
                            onDragEnd = { dragOffset = 0f },
                            onDragCancel = { dragOffset = 0f }
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                            if (dragOffset > 150f) {
                                onClose()
                                dragOffset = 0f
                            }
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
                        modifier = Modifier.frostedCircleGlass(uiAccent)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White)
                    }

                    LiveVisualizer(isPlaying = viewModel.isPlaying, color = Color.White.copy(0.8f))

                    IconButton(
                        onClick = { showTrackActions = true },
                        modifier = Modifier.frostedCircleGlass(uiAccent)
                    ) {
                        Icon(Icons.Rounded.MoreVert, null, tint = Color.White.copy(0.8f))
                    }
                }
            }

            // ЦЕНТРАЛЬНАЯ ОБЛАСТЬ С ОБЛОЖКОЙ/ВИНИЛОМ (И СВАЙПОМ)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var dragOffset = 0f
                        detectVerticalDragGestures(
                            onDragEnd = { dragOffset = 0f },
                            onDragCancel = { dragOffset = 0f }
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                            if (dragOffset > 150f) {
                                onClose()
                                dragOffset = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (viewModel.isVinylModeEnabled) {
                    SpaceVisualizer(
                        isPlaying = viewModel.isPlaying,
                        dominantColor = animatedDominantColor,
                        audio = viewModel.audioAnalysis,
                        modifier = Modifier
                            .fillMaxSize()
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

            // ПАНЕЛЬ УПРАВЛЕНИЯ
            val controlsShape = RoundedCornerShape(32.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .onGloballyPositioned { controlsBounds = it.boundsInRoot() }
                    .playerControlsGlass(uiAccent, controlsShape),
                color = Color.Transparent,
                shape = controlsShape
            ) {
                Box(modifier = Modifier.clip(controlsShape)) {
                    ControlsBackdropBlur(
                        rootSizePx = rootSizePx,
                        controlsBounds = controlsBounds,
                        shape = controlsShape,
                        accent = uiAccent
                    ) {
                        FullPlayerGlassBackdropLayer(
                            viewModel = viewModel,
                            dominantColor = animatedDominantColor,
                            uiAccent = uiAccent
                        )
                    }
                    ControlsFrostLayer(accent = uiAccent)

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
                                Text(viewModel.getCurrentDisplayArtist(), fontSize = 14.sp, color = Color.White.copy(0.72f))
                            }

                            IconButton(onClick = { viewModel.toggleFavorite() }) {
                                Icon(
                                    if (viewModel.isCurrentTrackFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    null,
                                    tint = if (viewModel.isCurrentTrackFav) uiAccent else Color.White.copy(0.78f),
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
                                    tint = if (viewModel.currentLyrics != null) uiAccent else Color.White.copy(0.78f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        TimeControls(
                            viewModel = viewModel,
                            dominantColor = uiAccent,
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
                                                tint = if (viewModel.isReversing) uiAccent
                                                else if (viewModel.isReverseReady) Color.White
                                                else Color.Gray
                                            )
                                        }
                                    }
                                }

                                if (viewModel.isKaraokeFeatureEnabled) {
                                    GlassyControlBtn(
                                        onClick = { viewModel.toggleVocalRemover(context) },
                                        onLongClick = {
                                            viewModel.performHapticFeedback()
                                            showKaraokeMenu = true
                                        }
                                    ) {
                                        if (viewModel.isKaraokeProcessingForCurrentTrack()) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                Icons.Rounded.MicOff,
                                                "Karaoke",
                                                tint = if (viewModel.instrumentalTrackPath != null) uiAccent
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
                                Icon(Icons.Rounded.Shuffle, null, tint = if (viewModel.shuffleMode) uiAccent else Color.White.copy(0.42f))
                            }
                            IconButton(onClick = { viewModel.skipPrev() }, Modifier.size(50.dp)) {
                                Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                            }
                            Surface(
                                modifier = Modifier
                                    .size(72.dp)
                                    .softPlayButtonGlow(uiAccent)
                                    .clickable { viewModel.togglePlay() },
                                shape = CircleShape,
                                color = Color.White,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (viewModel.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.skipNext() }, Modifier.size(50.dp)) {
                                Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.fillMaxSize())
                            }
                            IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                                val (icon, tint) = when (viewModel.repeatMode) {
                                    Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to uiAccent
                                    Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to uiAccent
                                    else -> Icons.Default.Repeat to Color.White.copy(0.42f)
                                }
                                Icon(icon, null, tint = tint)
                            }
                        }
                    }
                }
            }
        }
    }

    // Lyrics Dialog
    if (viewModel.showLyricsDialog) {
        val currentTrack = viewModel.currentPlaybackTrack()
        if (currentTrack != null) {
            LyricsDialog(
                title = currentTrack.title,
                artist = currentTrack.getDisplayArtist(),
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
private fun FullPlayerGlassBackdropLayer(
    viewModel: MusicViewModel,
    dominantColor: Color,
    uiAccent: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        EnhancedBreathingBackground(color = dominantColor)

        if (viewModel.isVinylModeEnabled) {
            SpaceVisualizer(
                isPlaying = viewModel.isPlaying,
                dominantColor = dominantColor,
                audio = viewModel.audioAnalysis,
                modifier = Modifier.fillMaxSize()
            )
        } else if (viewModel.currentTrackUri != null) {
            AsyncImage(
                model = viewModel.currentCoverUrl ?: viewModel.currentTrackUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.42f)
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f))
            )
        }
    }
}

@Composable
private fun BoxScope.ControlsBackdropBlur(
    rootSizePx: IntSize,
    controlsBounds: Rect?,
    shape: RoundedCornerShape,
    accent: Color,
    backgroundLayer: @Composable () -> Unit
) {
    val bounds = controlsBounds ?: return
    if (rootSizePx.width <= 0 || rootSizePx.height <= 0 || bounds.width <= 0f || bounds.height <= 0f) return

    val density = LocalDensity.current
    val blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurRadiusPx = with(density) { 58.dp.toPx() }
    val liquid = accent.liquidAccent()

    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
    ) {
        if (blurEnabled) {
            Box(
                modifier = Modifier
                    .absoluteOffset {
                        IntOffset(-bounds.left.roundToInt(), -bounds.top.roundToInt())
                    }
                    .width(with(density) { rootSizePx.width.toDp() })
                    .height(with(density) { rootSizePx.height.toDp() })
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        scaleX = 1.08f
                        scaleY = 1.08f
                        renderEffect = AndroidRenderEffect
                            .createBlurEffect(
                                blurRadiusPx,
                                blurRadiusPx,
                                Shader.TileMode.DECAL
                            )
                            .asComposeRenderEffect()
                    }
                    .alpha(0.98f)
            ) {
                backgroundLayer()
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                liquid.copy(alpha = 0.16f),
                                Color(0xFF151920).copy(alpha = 0.30f),
                                Color(0xFF080C12).copy(alpha = 0.54f),
                                Color(0xFF020305).copy(alpha = 0.72f)
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                liquid.copy(alpha = 0.11f),
                                Color.White.copy(alpha = 0.035f),
                                Color.Transparent
                            ),
                            center = Offset(bounds.width * 0.18f, bounds.height * 0.04f),
                            radius = bounds.width * 0.92f
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                liquid.copy(alpha = 0.13f),
                                Color(0xFF11151C).copy(alpha = 0.38f),
                                Color(0xFF050607).copy(alpha = 0.84f)
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun BoxScope.ControlsFrostLayer(accent: Color) {
    val liquid = accent.liquidAccent()
    Canvas(
        modifier = Modifier
            .matchParentSize()
            .blur(26.dp)
            .alpha(0.92f)
    ) {
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    liquid.copy(alpha = 0.12f),
                    Color(0xFF0B1118).copy(alpha = 0.24f),
                    Color(0xFF050607).copy(alpha = 0.42f)
                )
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = 0.045f),
                    liquid.copy(alpha = 0.11f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.18f, size.height * 0.05f),
                radius = size.maxDimension * 0.72f
            ),
            center = Offset(size.width * 0.18f, size.height * 0.05f),
            radius = size.maxDimension * 0.72f
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    liquid.copy(alpha = 0.15f),
                    Color(0xFF050607).copy(alpha = 0.18f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.72f, size.height * 0.92f),
                radius = size.maxDimension * 0.62f
            ),
            center = Offset(size.width * 0.72f, size.height * 0.92f),
            radius = size.maxDimension * 0.62f
        )
    }

    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.026f),
                    liquid.copy(alpha = 0.055f),
                    Color(0xFF080A0D).copy(alpha = 0.38f),
                    Color(0xFF040405).copy(alpha = 0.56f)
                )
            )
    )
    )
}

private fun Modifier.playerControlsGlass(
    accent: Color,
    shape: RoundedCornerShape
): Modifier {
    val liquid = accent.liquidAccent()
    return clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.052f),
                    liquid.copy(alpha = 0.12f),
                    Color(0xFF10141A).copy(alpha = 0.36f),
                    Color(0xFF050608).copy(alpha = 0.44f)
                )
            )
        )
        .background(
            Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = 0.026f),
                    liquid.copy(alpha = 0.075f),
                    Color.Transparent
                )
            )
        )
        .background(
            Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = 0.040f),
                    liquid.copy(alpha = 0.070f),
                    Color.Transparent
                )
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.24f),
                    liquid.copy(alpha = 0.44f),
                    Color.White.copy(alpha = 0.08f)
                )
            ),
            shape = shape
        )
}

private fun Modifier.frostedCircleGlass(accent: Color): Modifier {
    val liquid = accent.liquidAccent()
    return clip(CircleShape)
        .background(
            Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = 0.070f),
                    liquid.copy(alpha = 0.16f),
                    Color(0xFF050607).copy(alpha = 0.38f)
                )
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.22f),
                    liquid.copy(alpha = 0.38f),
                    Color.White.copy(alpha = 0.07f)
                )
            ),
            shape = CircleShape
        )
}

private fun Modifier.softPlayButtonGlow(accent: Color): Modifier {
    val liquid = accent.liquidAccent()
    return background(
        brush = Brush.radialGradient(
            listOf(
                liquid.copy(alpha = 0.22f),
                liquid.copy(alpha = 0.08f),
                Color.Transparent
            )
        ),
        shape = CircleShape
    )
}

@Composable
private fun TimeControls(
    viewModel: MusicViewModel,
    dominantColor: Color,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit
) {
    val currentPosition = viewModel.currentPosition
    val duration = viewModel.duration

    var isDragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    if (!isDragging) {
        sliderValue = if (duration > 0) currentPosition.toFloat() else 0f
    }

    Column {
        NeonPlaybackBar(
            value = sliderValue,
            durationMs = duration.coerceAtLeast(1L),
            color = dominantColor,
            onSeekStart = {
                if (!isDragging) {
                    isDragging = true
                    onSeekStart()
                }
            },
            onSeek = {
                sliderValue = it
                onSeek(it.toLong())
            },
            onSeekEnd = {
                isDragging = false
                onSeekEnd()
            }
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatTime(currentPosition), color = Color.White.copy(0.5f), fontSize = 12.sp)
            Text(formatTime(duration), color = Color.White.copy(0.5f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun NeonPlaybackBar(
    value: Float,
    durationMs: Long,
    color: Color,
    onSeekStart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekEnd: () -> Unit
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val safeDuration = durationMs.coerceAtLeast(1L).toFloat()
    val progress = (value / safeDuration).coerceIn(0f, 1f)

    fun positionFor(x: Float): Float {
        return ((x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f) * safeDuration)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    onSeekStart()
                    onSeek(positionFor(offset.x))
                    onSeekEnd()
                }
            }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onSeekStart()
                        onSeek(positionFor(offset.x))
                    },
                    onDragEnd = onSeekEnd,
                    onDragCancel = onSeekEnd,
                    onDrag = { change, _ ->
                        change.consume()
                        onSeek(positionFor(change.position.x))
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            val activeX = size.width * progress

            drawLine(
                color = Color.White.copy(alpha = 0.12f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )

            if (activeX > 0f) {
                drawLine(
                    color = color.copy(alpha = 0.22f),
                    start = Offset(0f, centerY),
                    end = Offset(activeX, centerY),
                    strokeWidth = 22f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color.copy(alpha = 0.38f),
                    start = Offset(0f, centerY),
                    end = Offset(activeX, centerY),
                    strokeWidth = 12f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.White.copy(alpha = 0.95f), color, color.copy(alpha = 0.78f))
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(activeX, centerY),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = color.copy(alpha = 0.28f),
                    radius = 18f,
                    center = Offset(activeX, centerY)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White, color.copy(alpha = 0.88f), color.copy(alpha = 0.18f)),
                        center = Offset(activeX, centerY),
                        radius = 16f
                    ),
                    radius = 10f,
                    center = Offset(activeX, centerY)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun GlassyControlBtn(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary.liquidAccent()
    Box(
        modifier = Modifier
            .size(50.dp)
            .frostedCircleGlass(accent)
            .alpha(if (enabled) 1f else 0.55f)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = { onLongClick?.invoke() }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
