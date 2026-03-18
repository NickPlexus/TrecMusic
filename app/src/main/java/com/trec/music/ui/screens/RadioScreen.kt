package com.trec.music.ui.screens

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.trec.music.ui.components.GlassButton
import com.trec.music.ui.components.GlassDialog
import com.trec.music.ui.components.GlassTextButton
import com.trec.music.ui.theme.TrecBlack
import com.trec.music.ui.theme.TrecRed
import com.trec.music.viewmodel.RadioStation
import com.trec.music.viewmodel.RadioViewModel
import kotlin.random.Random

// --- ГЕНЕРАТОР БЕЛОГО ШУМА ---
@Suppress("DEPRECATION")
class WhiteNoisePlayer {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var thread: Thread? = null

    fun play() {
        if (isPlaying) return
        isPlaying = true
        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM)
        audioTrack?.play()

        thread = Thread {
            val buffer = ShortArray(bufferSize)
            while (isPlaying) {
                for (i in buffer.indices) {
                    buffer[i] = (Random.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())).toShort()
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }
        }
        thread?.start()
    }

    fun stop() {
        isPlaying = false
        thread?.interrupt()
        thread = null
        audioTrack?.run {
            try {
                pause()
                flush()
                stop()
            } catch (_: Exception) {
            } finally {
                release()
            }
        }
        audioTrack = null
    }
}

// --- СОСТОЯНИЯ СТАНЦИИ ---
enum class StationState { IDLE, TUNING, PLAYING, ERROR }

// --- МОДИФИКАТОР ДЛЯ ЭФФЕКТА СТЕКЛА (КРАСНЫЙ) ---
fun Modifier.glassEffect(
    shape: Shape = RoundedCornerShape(16.dp),
    alpha: Float = 0.12f,
    borderAlpha: Float = 0.2f
) = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            colors = listOf(
                TrecRed.copy(alpha = alpha),
                TrecRed.copy(alpha = alpha * 0.3f)
            )
        )
    )
    .border(1.dp, TrecRed.copy(alpha = borderAlpha), shape)

@Composable
fun RadioScreen() {
    val viewModel: RadioViewModel = viewModel()
    val context = LocalContext.current

    var isEditMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    val searchQuery = viewModel.searchQuery
    val filteredStations = viewModel.getFilteredStations()
    val customIds = remember(viewModel.customStations) { viewModel.customStations.map { it.id }.toSet() }
    val customStations = filteredStations.filter { customIds.contains(it.id) }
    val defaultStations = filteredStations.filter { !customIds.contains(it.id) }

    val currentStation = viewModel.currentStation
    val isNoise = viewModel.isSimulatingNoise && currentStation != null
    val isLoading = viewModel.isLoading && currentStation != null
    val isUnavailable = currentStation?.let { viewModel.isStationUnavailable(it.id) } == true

    // Проигрыватель шипения
    val whiteNoisePlayer = remember { WhiteNoisePlayer() }
    DisposableEffect(isNoise) {
        if (isNoise) whiteNoisePlayer.play() else whiteNoisePlayer.stop()
        onDispose { whiteNoisePlayer.stop() }
    }

    if (showAddDialog) {
        AddStationDialog(
            onDismiss = { showAddDialog = false },
            onSave = { draft ->
                if (!draft.url.startsWith("http")) {
                    Toast.makeText(context, "Нужна ссылка http/https", Toast.LENGTH_SHORT).show()
                    return@AddStationDialog
                }
                viewModel.addCustomStation(
                    name = draft.name,
                    url = draft.url,
                    genre = draft.genre,
                    iconUrl = draft.iconUrl,
                    bitrate = draft.bitrate,
                    description = draft.description,
                    country = draft.country,
                    language = draft.language
                )
                showAddDialog = false
            }
        )
    }
    if (showInfoDialog) {
        RadioInfoDialog(onDismiss = { showInfoDialog = false })
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
                    Brush.radialGradient(
                        colors = listOf(TrecRed.copy(alpha = 0.20f), Color.Transparent),
                        radius = 900f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(16.dp))

            HeaderRow(
                isEditMode = isEditMode,
                onToggleEdit = { isEditMode = !isEditMode },
                onAddStation = { showAddDialog = true },
                onInfo = { showInfoDialog = true }
            )

            Spacer(Modifier.height(16.dp))

            NowPlayingGlassCard(
                station = currentStation,
                isPlaying = viewModel.isPlaying,
                isLoading = isLoading,
                isNoise = isNoise,
                isUnavailable = isUnavailable,
                onPlayPause = { viewModel.togglePlayPause() },
                onRetry = { currentStation?.let { viewModel.playStation(it) } }
            )

            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.search(it) },
                placeholder = { Text("Поиск станций...", color = Color.White.copy(0.6f)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color.White.copy(0.6f)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
                            Icon(Icons.Rounded.Close, null, tint = Color.White.copy(0.8f))
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = TrecRed,
                    focusedContainerColor = TrecRed.copy(alpha = 0.12f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                singleLine = true
            )

            Spacer(Modifier.height(18.dp))

            Spacer(Modifier.height(6.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 200.dp)
            ) {
                if (customStations.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Мои станции", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("${customStations.size}", color = Color.White.copy(0.5f), fontSize = 14.sp)
                        }
                    }
                    items(customStations, key = { it.id }) { station ->
                        val isCurrent = viewModel.currentStation?.id == station.id
                        val state = when {
                            isCurrent && viewModel.isStationUnavailable(station.id) -> StationState.ERROR
                            isCurrent && (viewModel.isSimulatingNoise || viewModel.isLoading) -> StationState.TUNING
                            isCurrent && viewModel.isPlaying -> StationState.PLAYING
                            else -> StationState.IDLE
                        }

                        GlassRadioStationRow(
                            station = station,
                            state = state,
                            isSelected = isCurrent,
                            showDelete = isEditMode,
                            onDelete = { viewModel.deleteStation(station) },
                            onClick = {
                                if (isCurrent && !viewModel.isStationUnavailable(station.id)) viewModel.togglePlayPause()
                                else viewModel.playStation(station)
                            }
                        )
                    }
                    item { Spacer(Modifier.height(6.dp)) }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Все станции", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (viewModel.hiddenStationsCount > 0) {
                            TextButton(onClick = { viewModel.restoreHiddenStations() }) {
                                Text("Показать скрытые (${viewModel.hiddenStationsCount})", color = TrecRed, fontSize = 12.sp)
                            }
                        } else {
                            Text("${defaultStations.size}", color = Color.White.copy(0.5f), fontSize = 14.sp)
                        }
                    }
                }

                items(defaultStations, key = { it.id }) { station ->
                    val isCurrent = viewModel.currentStation?.id == station.id
                    val state = when {
                        isCurrent && viewModel.isStationUnavailable(station.id) -> StationState.ERROR
                        isCurrent && (viewModel.isSimulatingNoise || viewModel.isLoading) -> StationState.TUNING
                        isCurrent && viewModel.isPlaying -> StationState.PLAYING
                        else -> StationState.IDLE
                    }

                    GlassRadioStationRow(
                        station = station,
                        state = state,
                        isSelected = isCurrent,
                        showDelete = isEditMode,
                        onDelete = { viewModel.deleteStation(station) },
                        onClick = {
                            if (isCurrent && !viewModel.isStationUnavailable(station.id)) viewModel.togglePlayPause()
                            else viewModel.playStation(station)
                        }
                    )
                }
            }
        }
    }

    viewModel.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Радио", color = Color.White) },
            text = { Text(error, color = Color.White.copy(0.8f)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("Ок", color = TrecRed)
                }
            },
            containerColor = TrecRed.copy(alpha = 0.2f),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(0.8f),
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun HeaderRow(
    isEditMode: Boolean,
    onToggleEdit: () -> Unit,
    onAddStation: () -> Unit,
    onInfo: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "TREC RADIO",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TrecRed   // красный заголовок
            )
            Text("Интернет‑эфир", fontSize = 16.sp, color = Color.White.copy(0.6f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onToggleEdit,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isEditMode) TrecRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
            ) {
                Icon(Icons.Rounded.Edit, null, tint = if (isEditMode) TrecRed else Color.White.copy(0.7f))
            }
            IconButton(
                onClick = onAddStation,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(TrecRed.copy(alpha = 0.2f))
            ) {
                Icon(Icons.Rounded.Add, null, tint = Color.White)
            }
            IconButton(
                onClick = onInfo,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                Icon(Icons.Rounded.Info, null, tint = Color.White.copy(0.8f))
            }
        }
    }
}

// --- КРАСНЫЙ ИНДИКАТОР ---
@Composable
private fun LedIndicator(state: StationState, modifier: Modifier = Modifier) {
    val color = when (state) {
        StationState.PLAYING -> Color(0xFF00E676)
        StationState.TUNING -> Color(0xFFFF5722)  // оранжево-красный
        StationState.ERROR -> Color(0xFFB71C1C)   // тёмно-красный
        StationState.IDLE -> Color.White.copy(0.3f)
    }

    val animatedColor by animateColorAsState(targetValue = color, animationSpec = tween(500), label = "ledColor")

    Box(
        modifier = modifier
            .size(10.dp)
            .shadow(
                elevation = if (state != StationState.IDLE) 8.dp else 0.dp,
                shape = CircleShape,
                ambientColor = animatedColor,
                spotColor = animatedColor
            )
            .clip(CircleShape)
            .background(animatedColor)
            .border(1.dp, Color.White.copy(0.4f), CircleShape)
    )
}

@Composable
private fun NowPlayingGlassCard(
    station: RadioStation?,
    isPlaying: Boolean,
    isLoading: Boolean,
    isNoise: Boolean,
    isUnavailable: Boolean,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassEffect(RoundedCornerShape(24.dp), alpha = 0.15f)
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Сейчас в эфире",
                    color = TrecRed.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (station != null) {
                    val state = when {
                        isUnavailable -> StationState.ERROR
                        isNoise || isLoading -> StationState.TUNING
                        isPlaying -> StationState.PLAYING
                        else -> StationState.IDLE
                    }
                    LedIndicator(state = state)
                }
            }

            if (station == null) {
                Text("Выберите станцию", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Станции ниже", color = Color.White.copy(0.5f), fontSize = 14.sp)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StationArt(station = station, size = 70.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            station.name,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            station.genre,
                            color = Color.White.copy(0.7f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${station.bitrate} · ${station.country}",
                            color = Color.White.copy(0.4f),
                            fontSize = 12.sp
                        )
                    }

                    val isActive = isPlaying || isLoading
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isActive) TrecRed else TrecRed.copy(alpha = 0.2f))
                            .clickable { if (isUnavailable) onRetry() else onPlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isActive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = isNoise) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        NoiseBars(color = Color(0xFFFF5722))
                        Text("Настройка частоты...", color = Color(0xFFFF5722), fontSize = 14.sp)
                    }
                }

                if (isUnavailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.SignalCellularConnectedNoInternet0Bar,
                            null,
                            tint = Color(0xFFB71C1C),
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Станция недоступна", color = Color(0xFFB71C1C), fontSize = 14.sp)
                    }
                }

                if (!isNoise && !isUnavailable) {
                    Text(
                        station.description,
                        color = Color.White.copy(0.6f),
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassRadioStationCard(
    station: RadioStation,
    state: StationState,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(160.dp)
            .glassEffect(RoundedCornerShape(20.dp), alpha = 0.08f)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                StationArt(station = station, size = 60.dp)
                LedIndicator(state = state, modifier = Modifier.padding(top = 4.dp))
            }

            Column {
                Text(
                    station.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    station.genre,
                    color = Color.White.copy(0.6f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when (state) {
                StationState.TUNING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NoiseBars(color = Color(0xFFFF5722), heightBase = 10f)
                        Text("Шипение...", color = Color(0xFFFF5722), fontSize = 11.sp)
                    }
                }

                StationState.ERROR -> {
                    Text("Недоступно", color = Color(0xFFB71C1C), fontSize = 11.sp)
                }

                StationState.PLAYING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.VolumeUp,
                            null,
                            tint = TrecRed,
                            modifier = Modifier.size(14.dp)
                        )
                        Text("В эфире", color = TrecRed, fontSize = 11.sp)
                    }
                }

                StationState.IDLE -> {
                    Text(station.bitrate, color = Color.White.copy(0.4f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun GlassRadioStationRow(
    station: RadioStation,
    state: StationState,
    isSelected: Boolean,
    showDelete: Boolean = false,
    onDelete: () -> Unit = {},
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassEffect(
                shape = RoundedCornerShape(18.dp),
                alpha = if (isSelected) 0.2f else 0.05f,
                borderAlpha = if (isSelected) 0.4f else 0.1f
            )
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StationArt(station = station, size = 56.dp)

            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = station.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LedIndicator(state = state)
                }

                Text(
                    text = station.genre,
                    fontSize = 13.sp,
                    color = Color.White.copy(0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${station.bitrate} · ${station.country}",
                    fontSize = 11.sp,
                    color = Color.White.copy(0.4f)
                )
            }

            if (showDelete) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                ) {
                    Icon(Icons.Rounded.Delete, null, tint = Color.White.copy(0.8f))
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected && state == StationState.PLAYING) TrecRed
                            else TrecRed.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (state) {
                        StationState.TUNING -> NoiseBars(color = Color(0xFFFF5722))
                        StationState.ERROR -> Icon(
                            Icons.Rounded.SignalCellularConnectedNoInternet0Bar,
                            null,
                            tint = Color(0xFFB71C1C)
                        )

                        StationState.PLAYING -> Icon(
                            Icons.Rounded.Pause,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )

                        StationState.IDLE -> Icon(
                            Icons.Rounded.PlayArrow,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StationArt(station: RadioStation, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(TrecRed.copy(alpha = 0.1f))
            .border(1.dp, TrecRed.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (station.iconUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(station.iconUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = station.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Rounded.Radio,
                null,
                tint = TrecRed.copy(alpha = 0.5f),
                modifier = Modifier.size(size / 2)
            )
        }
    }
}

@Composable
private fun NoiseBars(color: Color, heightBase: Float = 14f) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(4) { index ->
            val transition = rememberInfiniteTransition(label = "noise$index")
            val height by transition.animateFloat(
                initialValue = 4f,
                targetValue = heightBase + (index % 2 * 4f),
                animationSpec = infiniteRepeatable(
                    animation = tween(200 + index * 50, easing = androidx.compose.animation.core.LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "noiseHeight$index"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

private data class StationDraft(
    val name: String,
    val url: String,
    val genre: String,
    val iconUrl: String?,
    val bitrate: String,
    val description: String,
    val country: String,
    val language: String
)

@Composable
private fun AddStationDialog(
    onDismiss: () -> Unit,
    onSave: (StationDraft) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("Custom") }
    var iconUrl by remember { mutableStateOf("") }
    var bitrate by remember { mutableStateOf("Stream") }
    var description by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("Custom") }
    var language by remember { mutableStateOf("—") }

    val scrollState = rememberScrollState()

    GlassDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Text("Новая радиостанция", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название *") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = TrecRed,
                    focusedBorderColor = TrecRed,
                    unfocusedBorderColor = Color.White.copy(0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Ссылка потока (http/https) *") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = TrecRed,
                    focusedBorderColor = TrecRed,
                    unfocusedBorderColor = Color.White.copy(0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = genre,
                onValueChange = { genre = it },
                label = { Text("Жанр") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = TrecRed,
                    focusedBorderColor = TrecRed,
                    unfocusedBorderColor = Color.White.copy(0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = iconUrl,
                onValueChange = { iconUrl = it },
                label = { Text("URL иконки (опционально)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = TrecRed,
                    focusedBorderColor = TrecRed,
                    unfocusedBorderColor = Color.White.copy(0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = bitrate,
                onValueChange = { bitrate = it },
                label = { Text("Битрейт") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = TrecRed,
                    focusedBorderColor = TrecRed,
                    unfocusedBorderColor = Color.White.copy(0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Описание") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = TrecRed,
                    focusedBorderColor = TrecRed,
                    unfocusedBorderColor = Color.White.copy(0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = country,
                onValueChange = { country = it },
                label = { Text("Страна") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = TrecRed,
                    focusedBorderColor = TrecRed,
                    unfocusedBorderColor = Color.White.copy(0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text("Язык") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = TrecRed,
                    focusedBorderColor = TrecRed,
                    unfocusedBorderColor = Color.White.copy(0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTextButton("Отмена") { onDismiss() }
                GlassButton(
                    "Сохранить",
                    {
                        onSave(
                            StationDraft(
                                name = name,
                                url = url,
                                genre = genre,
                                iconUrl = iconUrl.takeIf { it.isNotBlank() },
                                bitrate = bitrate,
                                description = description,
                                country = country,
                                language = language
                            )
                        )
                    },
                    TrecRed,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RadioInfoDialog(onDismiss: () -> Unit) {
    GlassDialog(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("О радио", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Радио находится в бета‑режиме — возможны краткие обрывы или недоступность отдельных потоков.",
                color = Color.White.copy(0.7f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Text(
                "TREC Radio — это интернет‑радио. Станции воспроизводятся прямо из потоковых URL.",
                color = Color.White.copy(0.8f),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Text(
                "Как добавить свою станцию:",
                color = TrecRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "1) Найдите потоковый URL станции (обычно .mp3, .aac, .m3u, .pls) на официальном сайте радио.\n" +
                        "2) Нажмите кнопку “+” сверху справа.\n" +
                        "3) Вставьте ссылку и заполните название.",
                color = Color.White.copy(0.8f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Text(
                "Совет: если станция не запускается, попробуйте другой поток или другой формат.",
                color = Color.White.copy(0.6f),
                fontSize = 12.sp
            )
            GlassButton("Понятно", onDismiss, TrecRed, Modifier.fillMaxWidth())
        }
    }
}
