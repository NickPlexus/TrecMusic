package com.trec.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import coil.compose.AsyncImage
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.ui.components.EnhancedBreathingBackground
import com.trec.music.viewmodel.MusicViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(viewModel: MusicViewModel, navController: NavController) {
    val accent by animateColorAsState(
        targetValue = viewModel.dominantColor,
        animationSpec = tween(1000),
        label = "homeAccent"
    )

    val tracks = viewModel.playlist
    val favoritesCount = viewModel.favoriteTracks.size
    val totalMinutes = remember(tracks.size, viewModel.playlistUpdateTrigger) {
        (tracks.sumOf { it.durationMs } / 60000L).toInt()
    }
    val recentTracks = remember(tracks.size, viewModel.playlistUpdateTrigger) {
        tracks.sortedByDescending { it.dateAdded }.take(8)
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun playTrack(track: TrecTrackEnhanced) {
        val index = viewModel.playlist.indexOfFirst { it.uri == track.uri }
        if (index >= 0) viewModel.playTrackAtIndex(index)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        EnhancedBreathingBackground(color = accent)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 56.dp, bottom = 200.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HeaderBlock(
                    title = getGreetingMessage(),
                    subtitle = getNowText(),
                    onSettings = { navigateToTab("settings") }
                )
            }

            item {
                NowPlayingCard(
                    viewModel = viewModel,
                    accent = accent,
                    onOpenLibrary = { navigateToTab("library") }
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    StatCard("Треков", tracks.size.toString(), Icons.Rounded.GraphicEq, accent, Modifier.weight(1f))
                    StatCard("Избранных", favoritesCount.toString(), Icons.Rounded.Favorite, accent, Modifier.weight(1f))
                    StatCard("Минут", totalMinutes.toString(), Icons.Rounded.Equalizer, accent, Modifier.weight(1f))
                }
            }

            item {
                QuickActions(
                    accent = accent,
                    onLibrary = { navigateToTab("library") },
                    onFavorites = { navigateToTab("favorites") },
                    onRecorder = { navigateToTab("recorder") },
                    onRadio = { navigateToTab("radio") }
                )
            }

            item {
                SmartMixes(
                    accent = accent,
                    onRandom = {
                        if (!viewModel.shuffleMode) viewModel.toggleShuffle()
                        viewModel.skipNextRandom()
                    },
                    onChill = {
                        viewModel.enableVaporwave()
                        if (!viewModel.isPlaying) viewModel.togglePlay()
                    }
                )
            }

            item { SectionTitle("Недавно добавленные") }

            if (recentTracks.isEmpty()) {
                item {
                    GlassCard {
                        Text(
                            "Библиотека пока пуста. Открой медиатеку и обнови сканирование.",
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(recentTracks, key = { it.uri.toString() }) { track ->
                    RecentTrackRow(track = track, accent = accent, onClick = { playTrack(track) })
                }
            }
        }
    }
}

@Composable
private fun HeaderBlock(title: String, subtitle: String, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        IconButton(onClick = onSettings, modifier = Modifier.background(Color.White.copy(alpha = 0.10f), CircleShape)) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun NowPlayingCard(viewModel: MusicViewModel, accent: Color, onOpenLibrary: () -> Unit) {
    val currentTrack = viewModel.playlist.find { it.uri == viewModel.currentTrackUri }

    GlassCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (viewModel.currentTrackUri != null) {
                        AsyncImage(
                            model = viewModel.currentCoverUrl ?: viewModel.currentTrackUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(alpha = 0.5f))
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = viewModel.currentTrackTitle,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = viewModel.currentTrackArtist ?: currentTrack?.artist ?: "Unknown Artist",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                IconButton(onClick = { viewModel.togglePlay() }) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = accent
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartButton("Открыть библиотеку", accent, Modifier.weight(1f), onOpenLibrary)
                SmartButton("Случайный", Color.White.copy(alpha = 0.12f), Modifier.weight(1f)) {
                    if (!viewModel.shuffleMode) viewModel.toggleShuffle()
                    viewModel.skipNextRandom()
                }
            }
        }
    }
}

@Composable
private fun QuickActions(
    accent: Color,
    onLibrary: () -> Unit,
    onFavorites: () -> Unit,
    onRecorder: () -> Unit,
    onRadio: () -> Unit
) {
    GlassCard {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("Быстрые действия")
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ActionPill("Библиотека", Icons.Rounded.FolderOpen, accent, onLibrary) }
                item { ActionPill("Избранное", Icons.Rounded.Favorite, accent, onFavorites) }
                item { ActionPill("Рекордер", Icons.Outlined.Mic, accent, onRecorder) }
                item { ActionPill("Радио", Icons.Rounded.Radio, accent, onRadio) }
            }
        }
    }
}

@Composable
private fun SmartMixes(accent: Color, onRandom: () -> Unit, onChill: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        GradientTile(
            title = "Smart Shuffle",
            subtitle = "Авто-перемешивание",
            c1 = accent.copy(alpha = 0.85f),
            c2 = Color(0xFF0EA5E9),
            onClick = onRandom,
            modifier = Modifier.weight(1f)
        )
        GradientTile(
            title = "Chill Mode",
            subtitle = "Vaporwave preset",
            c1 = Color(0xFFEF4444),
            c2 = Color(0xFF8B5CF6),
            onClick = onChill,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RecentTrackRow(track: TrecTrackEnhanced, accent: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = track.uri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist ?: "Unknown Artist", color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp, maxLines = 1)
            }
            Surface(
                color = accent.copy(alpha = 0.2f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(50))
            ) {
                Text(track.getFormattedDuration(), color = accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White.copy(alpha = 0.66f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun GradientTile(
    title: String,
    subtitle: String,
    c1: Color,
    c2: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(106.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(c1, c2)))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.Bottom, modifier = Modifier.fillMaxSize()) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ActionPill(text: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.08f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SmartButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = color
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 10.dp)) {
            Text(text = text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
    ) {
        Column(content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

private fun getGreetingMessage(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..16 -> "Добрый день"
        in 17..23 -> "Добрый вечер"
        else -> "Ночной вайб"
    }
}

private fun getNowText(): String {
    val df = SimpleDateFormat("d MMMM, EEEE • HH:mm", Locale("ru"))
    return df.format(Date())
}
