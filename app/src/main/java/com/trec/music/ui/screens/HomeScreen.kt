package com.trec.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.trec.music.ui.LocalBottomOverlayPadding
import com.trec.music.viewmodel.MusicViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun HomeScreen(viewModel: MusicViewModel, navController: NavController) {
    val accent by animateColorAsState(
        targetValue = viewModel.dominantColor,
        animationSpec = tween(1000),
        label = "homeAccent"
    )
    val bottomOverlay = LocalBottomOverlayPadding.current

    val tracks = viewModel.playlist
    val favoritesCount = viewModel.favoriteTracks.size
    val totalMinutes = remember(tracks.size, viewModel.playlistUpdateTrigger) {
        (tracks.sumOf { it.durationMs } / 60000L).toInt()
    }
    val recentTracks = remember(tracks.size, viewModel.playlistUpdateTrigger) {
        tracks.sortedByDescending { it.dateAdded }.take(8)
    }

    var todayListenMs by remember { mutableLongStateOf(viewModel.prefs.getTodayListeningMs()) }
    var totalListenMs by remember { mutableLongStateOf(viewModel.prefs.getTotalListeningMs()) }
    var todaySessions by remember { mutableIntStateOf(viewModel.prefs.getTodayListenSessions()) }
    var totalSessions by remember { mutableIntStateOf(viewModel.prefs.getTotalListenSessions()) }
    var todayTracksStarted by remember { mutableIntStateOf(viewModel.prefs.getTodayTracksStarted()) }
    var totalTracksStarted by remember { mutableIntStateOf(viewModel.prefs.getTotalTracksStarted()) }
    var topPlayed by remember { mutableStateOf(viewModel.prefs.getTopPlayedUris(8)) }

    val topPlayedTracks = remember(topPlayed, tracks, viewModel.playlistUpdateTrigger) {
        topPlayed.mapNotNull { (uri, count) ->
            val track = tracks.firstOrNull { it.uri.toString() == uri }
            track?.let { it to count }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            todayListenMs = viewModel.prefs.getTodayListeningMs()
            totalListenMs = viewModel.prefs.getTotalListeningMs()
            todaySessions = viewModel.prefs.getTodayListenSessions()
            totalSessions = viewModel.prefs.getTotalListenSessions()
            todayTracksStarted = viewModel.prefs.getTodayTracksStarted()
            totalTracksStarted = viewModel.prefs.getTotalTracksStarted()
            topPlayed = viewModel.prefs.getTopPlayedUris(8)
            delay(if (viewModel.isPlaying) 1000L else 10_000L)
        }
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
            contentPadding = PaddingValues(top = 56.dp, bottom = bottomOverlay + 24.dp),
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
                QuickActions(
                    accent = accent,
                    onLibrary = { navigateToTab("library") },
                    onFavorites = { navigateToTab("favorites") },
                    onRecorder = { navigateToTab("recorder") },
                    onRadio = { navigateToTab("radio") }
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
                UsageStatsCard(
                    accent = accent,
                    todayListenMs = todayListenMs,
                    totalListenMs = totalListenMs,
                    todaySessions = todaySessions,
                    totalSessions = totalSessions,
                    todayTracksStarted = todayTracksStarted,
                    totalTracksStarted = totalTracksStarted
                )
            }

            item { MusicFactsCard(accent = accent) }

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
                    RecentTrackRow(track = track, viewModel = viewModel, accent = accent, onClick = { playTrack(track) })
                }
            }

            item { SectionTitle("Чаще всего слушаете") }

            if (topPlayedTracks.isEmpty()) {
                item {
                    GlassCard {
                        Text(
                            "Пока недостаточно данных. Послушай несколько треков — и тут появятся лидеры.",
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(topPlayedTracks, key = { it.first.uri.toString() }) { (track, count) ->
                    MostPlayedTrackRow(
                        track = track,
                        playCount = count,
                        viewModel = viewModel,
                        accent = accent,
                        onClick = { playTrack(track) }
                    )
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
private fun RecentTrackRow(
    track: TrecTrackEnhanced,
    viewModel: MusicViewModel,
    accent: Color,
    onClick: () -> Unit
) {
    LaunchedEffect(track.uri) {
        viewModel.ensureCoverForTrack(track)
    }
    val coverUrl = viewModel.getCoverUrlForTrack(track)
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
                    model = coverUrl ?: track.uri,
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
private fun UsageStatsCard(
    accent: Color,
    todayListenMs: Long,
    totalListenMs: Long,
    todaySessions: Int,
    totalSessions: Int,
    todayTracksStarted: Int,
    totalTracksStarted: Int
) {
    GlassCard {
        Column(modifier = Modifier.padding(12.dp)) {
            SectionTitle("Статистика")
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                UsageColumn(
                    title = "Сегодня",
                    listen = formatListenTime(todayListenMs),
                    sessions = todaySessions,
                    tracks = todayTracksStarted,
                    accent = accent,
                    modifier = Modifier.weight(1f)
                )
                UsageColumn(
                    title = "Всего",
                    listen = formatListenTime(totalListenMs),
                    sessions = totalSessions,
                    tracks = totalTracksStarted,
                    accent = accent,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MostPlayedTrackRow(
    track: TrecTrackEnhanced,
    playCount: Int,
    viewModel: MusicViewModel,
    accent: Color,
    onClick: () -> Unit
) {
    LaunchedEffect(track.uri) {
        viewModel.ensureCoverForTrack(track)
    }
    val coverUrl = viewModel.getCoverUrlForTrack(track)

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
                    model = coverUrl ?: track.uri,
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
                Text(
                    track.artist ?: "Unknown Artist",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            Surface(
                color = accent.copy(alpha = 0.2f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(50))
            ) {
                Text(
                    "${playCount}×",
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun UsageColumn(
    title: String,
    listen: String,
    sessions: Int,
    tracks: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.06f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            StatLine("Слушали", listen, accent)
            StatLine("Сессий", sessions.toString(), accent)
            StatLine("Треков", tracks.toString(), accent)
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
        Surface(
            color = accent.copy(alpha = 0.16f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(50))
        ) {
            Text(
                value,
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MusicFactsCard(accent: Color) {
    val allFacts = remember {
        listOf(
            "Автотюн изначально разработали для поиска нефти методом сейсморазведки",
            "Первые сабвуферы в машинах делали не ради качества, а чтобы звук чувствовался телом",
            "MP3 убивает часть звука, но мозг сам «достраивает» недостающее",
            "Люди чаще слушают музыку громче, чем нужно, и не замечают, как теряют слух",
            "Чем проще бит, тем быстрее он застревает в голове",
            "Многие хиты построены на одних и тех же 4 аккордах",
            "В тишине мозг сам начинает «додумывать» звуки",
            "Старые колонки часто звучат «теплее» из-за специфических искажений звука",
            "Винил трещит не из-за «магии», а из-за пыли и износа дорожек",
            "Самые навязчивые песни обычно самые простые",
            "Чем дольше слушаешь трек на повторе, тем быстрее по ощущениям он заканчивается",
            "Если слушать один трек на повторе, мозг начинает игнорировать его детали",
            "Чем громче бас, тем сильнее ощущение «энергии» трека",
            "В машине музыка ощущается мощнее из-за замкнутого пространства",
            "Некоторые треки специально делают тише, чтобы они казались «чище»",
            "Чем больше компрессии в треке, тем он «плотнее», но быстрее утомляет",
            "На дешёвых наушниках бас часто завышают, чтобы казалось «круче»",
            "Автозвук стал отдельной культурой — иногда важнее не музыка, а чтобы стёкла дрожали",
            "В России огромное количество треков записываются дома, а не в студиях",
            "Дешёвого микрофона и ноутбука уже достаточно, чтобы сделать хит. Лучшее — враг хорошего",
            "Многие биты покупаются на зарубежных площадках за копейки",
            "Один и тот же бит могут использовать десятки артистов",
            "Уличный шум часто вдохновляет на создание битов и ритмов",
            "Самые залетающие треки часто раздражают с первого раза",
            "У группы Тату образ был частью продуманной провокации для привлечения внимания",
            "Некоторые артисты специально делают странные моменты, чтобы их обсуждали",
            "Скандал вокруг трека иногда важнее самого трека",
            "Иногда «живой звук» на концертах частично заменяют фонограммой",
            "Чем больше хейта у трека, тем выше шанс, что он станет популярным",
            "Люди чаще дослушивают трек, если он короткий",
            "Кино разлетались по стране через магнитофонные копии — как офлайн-стриминг до интернета",
            "Виктор Цой писал почти все тексты и музыку своей группы",
            "Название «Кино» выбрали коротким, чтобы его легко запомнили",
            "ДДТ начинали в Уфе и стали одной из главных рок-групп страны",
            "Мумий Тролль стартовали во Владивостоке и задали свой стиль",
            "Король и Шут сделали фишкой страшные сказки и чёрный юмор",
            "Little Big строят свой жанр, смешивая рейв, панк и поп",
            "Тату заняли третье место на Евровидении и стали мировым проектом",
            "Земфира до музыки серьёзно занималась баскетболом",
            "MACAN пишет треки про тачки, жизнь и отношения, которые отлично заходят людям",
            "Big Baby Tape учил английский по играм и переводил западный рэп",
            "Instasamka сначала стала известной в интернете, а потом в музыке",
            "Dead Blonde выстрелила благодаря тиктоку и вирусным трекам",
            "Morgenshtern сначала хайпанул на YouTube, а уже потом на музыке",
            "Pharaoh стал одним из ключевых артистов новой рэп-волны",
            "GSPD запустил волну рейва, которая снова стала популярной",
            "В СССР музыку переписывали на кассеты друг у друга — это был «пиратский стриминг»",
            "После революции гимном страны на время была французская песня",
            "Многие старые песни не имеют автора — их просто передавали устно",
            "Фолк-музыку сейчас смешивают с электроникой, чтобы она звучала современно",
            "Музыка в России часто связана с протестом и настроением общества",
            "Старые треки могут снова стать популярными спустя десятки лет",
            "Популярность песни часто зависит от одного короткого фрагмента",
            "Иногда трек становится популярным именно потому, что он бесит",
            "Скандал может дать больше прослушиваний, чем сама музыка",
            "Образ артиста иногда важнее его треков",
            "Некоторые хиты записываются за пару часов, а живут годами",
            "Коровы дают больше молока, если слушают спокойную музыку",
            "Цветы растут быстрее, если рядом играет классика",
            "Частота 432 Гц считается некоторыми более «естественной», чем 440 Гц",
            "Ритм сердца может подстраиваться под темп музыки",
            "Термиты едят дерево быстрее под тяжёлый металл",
            "В тибетской музыке используют трубы из человеческих костей",
            "Оззи Осборн откусил голову летучей мыши на сцене, думая, что она резиновая",
            "Бетховен «слушал» музыку через вибрации, зажимая палочку зубами",
            "Самый длинный музыкальный трек в мире «Organ²/ASLSP» длится 639 лет",
            "На Луне оставили две гармошки и камертон",
            "Мик Джаггер застраховал голосовые связки на несколько миллионов долларов",
            "В 1989 году ВМС США использовали песни AC/DC для психологического давления",
            "Скорость звука в воде почти в 4.5 раза выше, чем в воздухе",
            "Винил физически передаёт звук через микроскопические неровности дорожки",
            "Первая записанная песня в истории — французская «Au Clair de la Lune» (1860)",
            "Скрежет мела по доске неприятен, так как совпадает с частотой крика ребёнка",
            "Страдивари использовал древесину, росшую в период «Малого ледникового периода»",
            "Терменвокс — единственный инструмент, на котором играют, не касаясь его",
            "Битлз не знали нотной грамоты и сочиняли музыку на слух",
            "Компрессор выравнивает громкость: тихое делает громче, а громкое тише",
            "Короткий ревербератор создаёт ощущение пространства даже в наушниках",
            "Лео Фендер, создатель гитар Stratocaster, сам не умел играть на гитаре",
            "Мозг реагирует на музыку так же, как на еду или другие удовольствия",
            "Альбом «Dark Side of the Moon» Pink Floyd продержался в чартах 741 неделю",
            "Первый музыкальный клип на MTV — «Video Killed the Radio Star»",
            "Барабанщик Def Leppard Рик Аллен потерял руку, но продолжил играть",
            "Японское слово «караоке» означает «пустой оркестр»",
            "Самый дорогой музыкальный инструмент — скрипка Страдивари «Макдональд»",
            "Абсолютный слух встречается примерно у 1 из 10 000 человек",
            "В Древней Греции музыка считалась разделом математики",
            "Дисторшн изначально считался браком оборудования, а не художественным приёмом",
            "Элвис Пресли никогда не писал своих песен самостоятельно",
            "Звук грозы в кино часто создают с помощью огромного листа металла",
            "Pink Floyd записывали звуки монет и кассовых аппаратов для трека «Money»",
            "Самый быстрый рэпер в мире может произносить более 10 слов в секунду",
            "Студийные мониторы делают звук «честным», а не «красивым»",
            "Джими Хендрикс был левшой, но играл на праворукой гитаре, перетянув струны",
            "Первый синтезатор Moog занимал целую комнату",
            "Рояль состоит из более чем 12 000 отдельных деталей",
            "Слово «рояль» в переводе с французского означает «королевский»",
            "Мелодия Nokia Tune — это отрывок из произведения «Gran Vals» 1902 года",
            "Группа Metallica — единственная, кто выступил на всех 7 континентах",
            "Скрипичный ключ раньше назывался «ключом соль»",
            "В 18 веке кастраты-певцы были суперзвездами оперы",
            "Самый тихий звук, который слышит человек — 0 дБ",
            "Эминем написал «Lose Yourself» за один присест на съёмочной площадке",
            "Существует «эффект Моцарта», якобы повышающий IQ",
            "Термин «рок-н-ролл» изначально был сленгом для обозначения секса в лодке",
            "Гитаристу Queen Брайану Мэю гитару помогал делать отец из каминной полки",
            "В 2016 году Боб Дилан получил Нобелевскую премию по литературе",
            "Звук падающей монеты — это чистая синусоида высокой частоты",
            "Мурашки от музыки называются фриссоном",
            "Группа Wu-Tang Clan выпустила альбом в единственном экземпляре",
            "Курт Кобейн часто покупал гитары в ломбардах и специально их ломал",
            "Первый плеер Walkman стоил около 200 долларов в 1979 году",
            "Многие кошки предпочитают музыку, которая имитирует мурлыканье",
            "Бах однажды провёл месяц в тюрьме, потому что хотел уволиться",
            "Кит Ричардс придумал рифф «Satisfaction» во сне",
            "Элвис Пресли был натуральным блондином, но красил волосы в чёрный",
            "Один из самых низких звуков во Вселенной издаёт чёрная дыра",
            "Группа ABBA носила безумные костюмы, чтобы списывать их из налогов",
            "Волынка пришла из Азии, а не из Шотландии",
            "Группа Slipknot носит маски, чтобы люди фокусировались на музыке",
            "Сэмплирование появилось ещё в 40-х годах",
            "В 1952 году Джон Кейдж написал пьесу «4′33″», где музыкант просто молчит",
            "Фрэнк Синатра ненавидел песню «Strangers in the Night»"
        )
    }

    var shuffledFacts by remember { mutableStateOf(allFacts.shuffled()) }
    var factIndex by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(shuffledFacts) {
        while (true) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 10_000, easing = LinearEasing)
            )

            if (factIndex >= shuffledFacts.size - 1) {
                shuffledFacts = allFacts.shuffled()
                factIndex = 0
            } else {
                factIndex++
            }
        }
    }

    GlassCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Музыкальный факт", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress.value },
                color = accent,
                trackColor = Color.White.copy(alpha = 0.10f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
            )

            Spacer(Modifier.height(10.dp))

            // Исправленный блок AnimatedContent
            AnimatedContent(
                targetState = shuffledFacts[factIndex],
                transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(320)) },
                label = "facts"
            ) { factText ->
                Text(
                    text = factText,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
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

private fun formatListenTime(ms: Long): String {
    val minutes = (ms / 60000.0).roundToInt().coerceAtLeast(0)
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}ч ${mins}м" else "${mins}м"
}