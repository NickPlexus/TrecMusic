// ui/screens/FavoritesScreen.kt
//
// ТИП: UI Screen (Premium)
//
// ИСПРАВЛЕНИЯ (HOTFIX):
// 1. Removed 'modifier' arg from TrackRow: Теперь TrackRow вызывается без модификатора,
//    чтобы соответствовать твоей текущей сигнатуре компонента.
// 2. Removed 'animateItem': Убрана экспериментальная анимация для совместимости.
// 3. Layout Fix: Анимация появления (AnimatedVisibility) теперь работает корректно.

package com.trec.music.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trec.music.ui.components.TrackRow
import com.trec.music.ui.LocalBottomOverlayPadding
import com.trec.music.viewmodel.MusicViewModel

@Composable
fun FavoritesScreen(viewModel: MusicViewModel) {
    val scrollState = rememberLazyListState()
    val bottomOverlay = LocalBottomOverlayPadding.current

    // Умная фильтрация
    val favTracks by remember(viewModel.playlist, viewModel.favoriteTracks) {
        derivedStateOf {
            viewModel.playlist.filter { viewModel.favoriteTracks.contains(it.uri.toString()) }
        }
    }

    // Анимированный фон
    val animatedColor by animateColorAsState(
        targetValue = viewModel.dominantColor,
        animationSpec = tween(1000),
        label = "HeaderColor"
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Фоновый градиент
        Box(
            Modifier
                .fillMaxWidth()
                .height(400.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(animatedColor.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
        )

        if (favTracks.isEmpty()) {
            EmptyFavoritesState()
        } else {
            LazyColumn(
                state = scrollState,
                contentPadding = PaddingValues(bottom = bottomOverlay + 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // --- HEADER ---
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp, bottom = 24.dp)
                            .padding(horizontal = 24.dp)
                            .graphicsLayer {
                                alpha = 1f - (scrollState.firstVisibleItemScrollOffset / 600f).coerceIn(0f, 1f)
                                translationY = scrollState.firstVisibleItemScrollOffset / 2f
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // "Обложка" плейлиста
                        Surface(
                            modifier = Modifier.size(180.dp),
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 20.dp,
                            color = Color.White.copy(0.1f)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(
                                    Brush.linearGradient(
                                        listOf(MaterialTheme.colorScheme.primary, animatedColor)
                                    )
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Favorite,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(80.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        Text(
                            text = "Любимые треки",
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )

                        Text(
                            text = "${favTracks.size} композиций",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.6f)
                        )

                        Spacer(Modifier.height(24.dp))

                        // Кнопка Shuffle
                        Button(
                            onClick = {
                                if (favTracks.isNotEmpty()) {
                                    val randomFav = favTracks.random()
                                    val indexInMain = viewModel.playlist.indexOf(randomFav)
                                    if (indexInMain != -1) {
                                        viewModel.toggleShuffle()
                                        viewModel.playTrackAtIndex(indexInMain)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                            modifier = Modifier.height(56.dp)
                        ) {
                            Icon(Icons.Filled.Shuffle, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Перемешать", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // --- LIST ---
                itemsIndexed(
                    items = favTracks,
                    key = { _, track -> track.uri.toString() }
                ) { index, track ->
                    // Анимация появления
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }
                    ) {
                        // ФИКС: Убрали modifier, так как TrackRow его не принимает
                        TrackRow(
                            track = track,
                            index = -1,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyFavoritesState() {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.FavoriteBorder,
                null,
                tint = Color.White.copy(0.3f),
                modifier = Modifier.size(120.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Здесь пока пусто",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "Отмечайте треки сердечком,\nчтобы они появились тут",
                fontSize = 16.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}
