// ui/screens/RecorderScreen.kt
package com.trec.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.trec.music.ui.components.GlassButton
import com.trec.music.ui.components.GlassDialog
import com.trec.music.ui.components.GlassTextButton
import com.trec.music.ui.components.RippleVinylVisualizer
import com.trec.music.ui.theme.TrecBlack
import com.trec.music.ui.theme.TrecGray
import com.trec.music.ui.theme.TrecRed
import com.trec.music.utils.formatTime
import com.trec.music.viewmodel.MusicViewModel
import com.trec.music.viewmodel.RecorderViewModel
import com.trec.music.viewmodel.RecordingItem
import kotlinx.coroutines.delay

@Composable
fun RecorderScreen(
    viewModel: RecorderViewModel,
    musicViewModel: MusicViewModel
) {
    val context = LocalContext.current

    // --- ЛОГИКА ЗАПРОСА ПРАВ ---
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (musicViewModel.isPlaying) musicViewModel.pausePlayback()
            viewModel.startRecording()
        } else {
            Toast.makeText(context, "Для записи нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
        }
    }

    // --- ЛОГИКА ВОССТАНОВЛЕНИЯ ТРЕКА ---
    val restorationUri = remember { musicViewModel.currentTrackUri }
    val restorationPos = remember { musicViewModel.currentPosition }

    DisposableEffect(Unit) {
        onDispose {
            if (restorationUri != null && musicViewModel.currentTrackUri != restorationUri) {
                val tracks = musicViewModel.playlist
                val index = tracks.indexOfFirst { it.uri == restorationUri }
                if (index != -1) {
                    musicViewModel.playTrackAtIndex(index)
                    musicViewModel.seekTo(restorationPos)
                    musicViewModel.player?.pause()
                }
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.initialize() }

    var itemToDelete by remember { mutableStateOf<RecordingItem?>(null) }
    var itemToRename by remember { mutableStateOf<RecordingItem?>(null) }

    // --- ДИАЛОГ УДАЛЕНИЯ ---
    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Удалить запись?") },
            text = { Text("Файл ${itemToDelete?.file?.name} будет удален безвозвратно.") },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(containerColor = TrecRed), onClick = {
                    itemToDelete?.let { viewModel.deleteRecording(it) }
                    itemToDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) { Text("Отмена", color = Color.White) }
            },
            containerColor = TrecGray, titleContentColor = Color.White, textContentColor = Color.LightGray
        )
    }

    // --- ДИАЛОГ ПЕРЕИМЕНОВАНИЯ ---
    if (itemToRename != null) {
        var newName by remember(itemToRename) { mutableStateOf(itemToRename!!.file.nameWithoutExtension) }
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(Unit) {
            delay(100)
            focusRequester.requestFocus()
            keyboardController?.show()
        }

        GlassDialog(onDismiss = { itemToRename = null }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Переименовать", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = TrecRed, focusedBorderColor = TrecRed, unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        viewModel.stopPlayback()
                        viewModel.renameRecording(itemToRename!!, newName)
                        itemToRename = null
                    })
                )

                Spacer(Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextButton("Отмена") { itemToRename = null }
                    GlassButton("Сохранить", {
                        viewModel.stopPlayback()
                        viewModel.renameRecording(itemToRename!!, newName)
                        itemToRename = null
                    }, TrecRed, Modifier.weight(1f))
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrecBlack)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(TrecRed.copy(alpha = 0.18f), Color.Transparent, TrecBlack)
                    )
                )
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("REC STUDIO", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("Запись голоса и заметок", fontSize = 14.sp, color = Color.White.copy(0.6f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsPill(
                        isActive = viewModel.skipSilence,
                        text = "Smart Skip",
                        icon = Icons.Rounded.GraphicEq,
                        enabled = !viewModel.isRecording,
                        onClick = { viewModel.skipSilence = !viewModel.skipSilence }
                    )
                    SettingsPill(
                        isActive = viewModel.isNoiseSuppressionEnabled,
                        text = "Clean Voice",
                        icon = Icons.Rounded.Mic,
                        enabled = !viewModel.isRecording,
                        onClick = { viewModel.isNoiseSuppressionEnabled = !viewModel.isNoiseSuppressionEnabled }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Surface(
                color = Color.White.copy(0.05f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (viewModel.isRecording) "Запись" else "Готово к записи",
                            color = if (viewModel.isRecording) TrecRed else Color.White.copy(0.7f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        if (viewModel.isPaused) {
                            Text("PAUSED", color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        RippleVinylVisualizer(
                            amplitude = if (viewModel.isRecording) viewModel.currentAmplitude else 0,
                            isRecording = viewModel.isRecording && !viewModel.isPaused,
                            modifier = Modifier.fillMaxSize()
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatTime(viewModel.recordingDuration),
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Text(
                                text = if (viewModel.isRecording) "REC" else "READY",
                                color = if (viewModel.isRecording) TrecRed else Color.White.copy(0.5f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (viewModel.isRecording) {
                            IconButton(
                                onClick = { if (viewModel.isPaused) viewModel.resumeRecording() else viewModel.pauseRecording() },
                                modifier = Modifier.size(56.dp).background(Color.White.copy(0.08f), CircleShape)
                            ) {
                                Icon(if (viewModel.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, tint = Color.White)
                            }
                            Spacer(Modifier.width(24.dp))
                            Box(
                                modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable { viewModel.stopRecording() },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(TrecRed))
                            }
                        } else {
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val scale by infiniteTransition.animateFloat(
                                initialValue = 1f, targetValue = 1.06f,
                                animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "scale"
                            )
                            Box(
                                modifier = Modifier.size(76.dp).scale(scale).clip(CircleShape).background(TrecRed).clickable {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        if (musicViewModel.isPlaying) musicViewModel.pausePlayback()
                                        viewModel.startRecording()
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Записи", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${viewModel.recordings.size}", color = Color.White.copy(0.5f), fontSize = 13.sp)
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(viewModel.recordings, key = { it.file.absolutePath }) { item ->
                    val isPlaying = viewModel.currentPlayback?.file?.absolutePath == item.file.absolutePath && viewModel.isPlaybackPlaying
                    val progress = if (isPlaying && viewModel.playbackDuration > 0)
                        viewModel.playbackPosition.toFloat() / viewModel.playbackDuration.toFloat()
                    else 0f

                    RecordingListItem(
                        item = item,
                        isPlaying = isPlaying,
                        progress = progress,
                        onPlayClick = { viewModel.playRecording(item) },
                        onDeleteClick = { itemToDelete = item },
                        onShareClick = { viewModel.shareRecording(context, item) },
                        onRenameClick = { itemToRename = item },
                        onItemClick = { viewModel.playRecording(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsPill(isActive: Boolean, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (isActive) TrecRed.copy(0.2f) else Color.White.copy(0.05f),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isActive) TrecRed else Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (isActive) TrecRed else Color.Gray, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (isActive) TrecRed else Color.Gray)
        }
    }
}

@Composable
fun RecordingListItem(
    item: RecordingItem,
    isPlaying: Boolean,
    progress: Float,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    onRenameClick: () -> Unit,
    onItemClick: () -> Unit
) {
    Surface(
        color = Color.White.copy(0.04f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.08f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onItemClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(44.dp).background(if (isPlaying) TrecRed else Color.White.copy(0.1f), CircleShape)
                ) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White)
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.file.nameWithoutExtension,
                        color = if (isPlaying) TrecRed else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(item.dateAdded).toString(),
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Text(
                    text = formatTime(item.durationMs),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (progress > 0f) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = TrecRed,
                    trackColor = Color.White.copy(0.08f),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onRenameClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onShareClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Share, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Delete, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
