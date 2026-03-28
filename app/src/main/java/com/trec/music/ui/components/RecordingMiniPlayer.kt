@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.trec.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trec.music.ui.theme.TrecBlack
import com.trec.music.viewmodel.RecorderViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun RecordingMiniPlayer(
    viewModel: RecorderViewModel,
    onOpen: () -> Unit
) {
    val accent by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.primary,
        animationSpec = androidx.compose.animation.core.tween(1200),
        label = "RecordingMiniPlayerColor"
    )

    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .clickable { onOpen() }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    val resistance = 0.6f
                    scope.launch {
                        offsetY.snapTo(offsetY.value + (delta * resistance))
                    }
                },
                onDragStopped = {
                    val y = offsetY.value
                    when {
                        y < -100f -> onOpen()
                        y > 100f -> viewModel.stopPlayback()
                    }
                    scope.launch {
                        offsetY.animateTo(
                            0f,
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                        )
                    }
                }
            )
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxSize()
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = accent,
                    ambientColor = Color.Transparent
                )
        )

        Surface(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp)),
            color = TrecBlack.copy(alpha = 0.95f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(0.08f), accent.copy(0.35f))
                )
            )
        ) {
            Column {
                RecordingMiniPlayerProgress(
                    progressProvider = {
                        val total = viewModel.playbackDuration
                        if (total > 0) viewModel.playbackPosition.toFloat() / total.toFloat() else 0f
                    },
                    color = accent
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(0.06f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Mic, null, tint = accent)
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = viewModel.currentPlayback?.file?.name ?: "Запись",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                        Text(
                            text = "Диктофон",
                            color = Color.White.copy(0.6f),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            val item = viewModel.currentPlayback
                            if (item != null) {
                                viewModel.playRecording(item)
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(if (viewModel.isPlaybackPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingMiniPlayerProgress(progressProvider: () -> Float, color: Color) {
    val progress = progressProvider()
    Box(
        modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(0.1f))
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(
                Brush.horizontalGradient(listOf(color.copy(0.5f), color))
            )
        )
    }
}
