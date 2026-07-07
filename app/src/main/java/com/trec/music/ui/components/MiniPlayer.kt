// ui/components/MiniPlayer.kt
@file:OptIn(ExperimentalFoundationApi::class)

package com.trec.music.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.trec.music.ui.theme.liquidAccent
import com.trec.music.viewmodel.MusicViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(
    viewModel: MusicViewModel,
    onOpen: () -> Unit
) {
    val dominantColor by animateColorAsState(
        targetValue = viewModel.dominantColor.liquidAccent(),
        animationSpec = tween(1200),
        label = "MiniPlayerColor"
    )

    val playerShape = RoundedCornerShape(18.dp)
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp) // Высота плеера
            .offset { IntOffset(offset.value.x.roundToInt(), offset.value.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val x = offset.value.x
                        val y = offset.value.y

                        when {
                            y < -100f -> onOpen()
                            y > 100f -> viewModel.stopAndClear()
                            x > 150f -> viewModel.skipPrev()
                            x < -150f -> viewModel.skipNext()
                        }

                        scope.launch {
                            offset.animateTo(
                                Offset.Zero,
                                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                            )
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val resistance = 0.6f
                            offset.snapTo(offset.value + (dragAmount * resistance))
                        }
                    }
                )
            }
    ) {
        // Тень (исправленная, без черной грязи)
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxSize()
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = dominantColor,
                    ambientColor = Color.Transparent // КЛЮЧЕВОЙ ФИКС ГРЯЗИ
                )
        )

        // Само тело плеера
        Surface(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxSize()
                .clip(playerShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color(0xFF121216).copy(alpha = 0.94f),
                            Color(0xFF060608).copy(alpha = 0.96f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.30f),
                            dominantColor.copy(alpha = 0.26f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    ),
                    shape = playerShape
                )
                .clickable { onOpen() },
            color = Color.Transparent,
            shape = playerShape
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Обложка / Винил
                    Box(modifier = Modifier.size(48.dp)) {
                        if (viewModel.isVinylModeEnabled) {
                            Box(
                                modifier = Modifier.fillMaxSize().rotate(viewModel.vinylRotationAngle),
                                contentAlignment = Alignment.Center
                            ) {
                                VinylPlaceholder(color = dominantColor, modifier = Modifier.fillMaxSize())
                                if (viewModel.currentTrackUri != null) {
                                    AsyncImage(
                                        model = viewModel.currentCoverUrl ?: viewModel.currentTrackUri,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        } else {
                            VinylPlaceholder(color = dominantColor, modifier = Modifier.fillMaxSize())
                            if (viewModel.currentTrackUri != null) {
                                AsyncImage(
                                        model = viewModel.currentCoverUrl ?: viewModel.currentTrackUri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    // Инфо
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = viewModel.currentTrackTitle,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                        Text(
                            text = viewModel.getCurrentDisplayArtist(),
                            color = Color.White.copy(0.6f),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    // Кнопки
                    Row {
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                if (viewModel.isCurrentTrackFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                null,
                                tint = if (viewModel.isCurrentTrackFav) dominantColor else Color.White.copy(0.4f)
                            )
                        }
                        FilledIconButton(
                            onClick = { viewModel.togglePlay() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(if (viewModel.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                        }
                    }
                }

                MiniPlayerProgress(
                    progressProvider = {
                        if (viewModel.duration > 0) viewModel.currentPosition.toFloat() / viewModel.duration.toFloat() else 0f
                    },
                    color = dominantColor,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerProgress(
    progressProvider: () -> Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val progress = progressProvider().coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxWidth().height(6.dp)) {
        val y = size.height / 2f
        drawLine(
            color = Color.White.copy(alpha = 0.10f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2f,
            cap = StrokeCap.Round
        )
        val activeWidth = size.width * progress
        if (activeWidth > 0f) {
            drawLine(
                color = color.copy(alpha = 0.24f),
                start = Offset(0f, y),
                end = Offset(activeWidth, y),
                strokeWidth = 9f,
                cap = StrokeCap.Round
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0.92f), color, color.copy(alpha = 0.72f))
                ),
                start = Offset(0f, y),
                end = Offset(activeWidth, y),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }
    }
}
