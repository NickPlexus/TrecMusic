// ui/screens/LibraryScreen.kt
package com.trec.music.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.trec.music.ui.components.GlassButton
import com.trec.music.ui.components.GlassDialog
import com.trec.music.ui.components.GlassDropdownHeader
import com.trec.music.ui.components.GlassDropdownMenu
import com.trec.music.ui.components.GlassDropdownMenuItem
import com.trec.music.ui.components.GlassTextButton
import com.trec.music.ui.components.TrackRow
import com.trec.music.ui.components.VinylPlaceholder
import com.trec.music.ui.LocalBottomOverlayPadding
import com.trec.music.ui.theme.TrecBlack
import com.trec.music.viewmodel.MusicViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// --- ENUMS FOR SORTING ---
enum class SortOption(val title: String) {
    CUSTOM("Свой порядок"),
    TITLE_ASC("По названию (А-Я)"),
    TITLE_DESC("По названию (Я-А)"),
    DURATION_ASC("По длительности (Мин)"),
    DURATION_DESC("По длительности (Макс)"),
    DATE_NEWEST("Сначала новые"),
    DATE_OLDEST("Сначала старые")
}

private data class GridDragState(
    val index: Int,
    val name: String,
    val itemOffset: IntOffset,
    val itemSize: IntSize
)

private data class ListDragState(
    val index: Int,
    val key: String,
    val itemOffset: IntOffset,
    val itemSize: IntSize
)

// --- ГЛАВНЫЙ ЭКРАН БИБЛИОТЕКИ ---
@Composable
fun LibraryScreen(viewModel: MusicViewModel) {
    var openedPlaylistName by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = openedPlaylistName != null) {
        openedPlaylistName = null
        viewModel.currentPlaylistFilter = null
    }

    AnimatedContent(
        targetState = openedPlaylistName,
        label = "LibraryNav",
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it / 2 } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it / 2 } + fadeOut()
            }
        }
    ) { targetName ->
        if (targetName == null) {
            PlaylistsOverview(viewModel, onOpenPlaylist = { openedPlaylistName = it })
        } else {
            PlaylistEditorScreen(
                viewModel = viewModel,
                playlistName = targetName,
                onBack = {
                    openedPlaylistName = null
                    viewModel.currentPlaylistFilter = null
                }
            )
        }
    }
}

// --- ЭКРАН 1: СЕТКА ПЛЕЙЛИСТОВ ---
@Composable
fun PlaylistsOverview(viewModel: MusicViewModel, onOpenPlaylist: (String) -> Unit) {
    val bottomOverlay = LocalBottomOverlayPadding.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<String?>(null) }
    var playlistToDelete by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shouldAnimateWiggle = isEditMode && viewModel.userPlaylists.size <= 40
    val rotation by if (shouldAnimateWiggle) {
        val infiniteTransition = rememberInfiniteTransition(label = "Wiggle")
        infiniteTransition.animateFloat(
            initialValue = -1.5f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(150, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "Rot"
        )
    } else remember { mutableFloatStateOf(0f) }

    val context = LocalContext.current
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

    var draggingGrid by remember { mutableStateOf<GridDragState?>(null) }
    var gridDragOffset by remember { mutableStateOf(Offset.Zero) }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }

    // --- DIALOGS ---
    if (showCreateDialog) {
        TextInputDialog("Новый плейлист", "", "Название", "Создать", { showCreateDialog = false }) {
            if (it.isNotBlank()) { viewModel.createPlaylist(it); showCreateDialog = false }
        }
    }
    playlistToRename?.let { oldName ->
        TextInputDialog("Переименовать", oldName, "Новое название", "Сохранить", { playlistToRename = null }) {
            if (it.isNotBlank() && it != oldName) viewModel.renamePlaylist(oldName, it)
            playlistToRename = null
        }
    }
    playlistToDelete?.let { name ->
        GlassDialog(onDismiss = { playlistToDelete = null }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Удалить плейлист?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(name, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextButton("Отмена") { playlistToDelete = null }
                    GlassButton("Удалить", { viewModel.deletePlaylist(name); playlistToDelete = null }, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                }
            }
        }
    }

    // Корневой контейнер с отключенной обрезкой
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { clip = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .graphicsLayer { clip = false }
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(16.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Медиатека", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row {
                    IconButton(
                        onClick = { isEditMode = !isEditMode },
                        modifier = Modifier.background(if (isEditMode) MaterialTheme.colorScheme.primary else Color.White.copy(0.1f), CircleShape)
                    ) {
                        Icon(Icons.Filled.Edit, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.background(Color.White.copy(0.1f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // Grid
            val gridState = rememberLazyGridState()
            val playlists = viewModel.userPlaylists.toList()

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                userScrollEnabled = draggingGrid == null,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = bottomOverlay + 24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { clip = false } // отключаем обрезку на сетке
                    .onGloballyPositioned { coordinates ->
                        gridOrigin = coordinates.positionInRoot()
                    }
                    .pointerInput(isEditMode, viewModel.userPlaylists.size) {
                        if (!isEditMode) return@pointerInput

                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val item = gridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { item ->
                                        offset.x.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                                offset.y.toInt() in item.offset.y..(item.offset.y + item.size.height)
                                    }
                                item?.let {
                                    if (it.index > 0) { // Не перетаскиваем "Все треки"
                                        val name = viewModel.userPlaylists.getOrNull(it.index - 1) ?: return@let
                                        draggingGrid = GridDragState(
                                            index = it.index,
                                            name = name,
                                            itemOffset = it.offset,
                                            itemSize = it.size
                                        )
                                        gridDragOffset = Offset.Zero
                                        vibrate()
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingGrid = null
                                gridDragOffset = Offset.Zero
                                viewModel.persistPlaylistOrder()
                            },
                            onDragCancel = {
                                draggingGrid = null
                                gridDragOffset = Offset.Zero
                                viewModel.persistPlaylistOrder()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dragState = draggingGrid ?: return@detectDragGesturesAfterLongPress
                                gridDragOffset += dragAmount

                                val dragCenter = Offset(
                                    dragState.itemOffset.x + gridDragOffset.x + dragState.itemSize.width / 2f,
                                    dragState.itemOffset.y + gridDragOffset.y + dragState.itemSize.height / 2f
                                )

                                val targetItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    dragCenter.x.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                            dragCenter.y.toInt() in item.offset.y..(item.offset.y + item.size.height)
                                }

                                if (targetItem != null && targetItem.index != dragState.index && targetItem.index > 0) {
                                    viewModel.movePlaylist(dragState.index - 1, targetItem.index - 1)
                                    draggingGrid = dragState.copy(
                                        index = targetItem.index,
                                        itemOffset = targetItem.offset,
                                        itemSize = targetItem.size
                                    )
                                    gridDragOffset = Offset.Zero
                                    vibrate()
                                }
                            }
                        )
                    }
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(0f)
                            .graphicsLayer {
                                clip = false
                            }
                    ) {
                        PlaylistCard(
                            name = "Все треки",
                            count = viewModel.playlist.size,
                            color = MaterialTheme.colorScheme.primary,
                            isSystem = true,
                            onClick = { if (!isEditMode) onOpenPlaylist("All Tracks") },
                            clickEnabled = !isEditMode
                        )
                    }
                }

                itemsIndexed(items = playlists) { idx, name ->
                    val gridIndex = idx + 1
                    val isDragging = draggingGrid?.index == gridIndex
                    val wiggleModifier = if (isEditMode && !isDragging) Modifier.rotate(rotation) else Modifier

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 0f else 0f)
                            .graphicsLayer {
                                alpha = if (isDragging) 0f else 1f
                                clip = false
                            }
                            .then(wiggleModifier)
                    ) {
                        val color = remember(name) { generateDeterministicColor(name) }
                        val count = viewModel.getPlaylistTracks(name).size
                        PlaylistCard(
                            name = name,
                            count = count,
                            color = color,
                            isSystem = false,
                            onClick = { if (!isEditMode) onOpenPlaylist(name) },
                            onEdit = { playlistToRename = name },
                            onDelete = { playlistToDelete = name },
                            clickEnabled = !isEditMode
                        )
                    }
                }
            }
        }

        // Нижний градиент (не перекрывает перетаскиваемые элементы)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, TrecBlack.copy(alpha = 0.6f), TrecBlack.copy(alpha = 0.95f))
                    )
                )
                .zIndex(0f)
        )

        // Оверлей для перетаскиваемого плейлиста (поверх всего, без обрезки)
        draggingGrid?.let { drag ->
            val widthDp = with(density) { drag.itemSize.width.toDp() }
            val heightDp = with(density) { drag.itemSize.height.toDp() }
            val offsetX = gridOrigin.x + drag.itemOffset.x + gridDragOffset.x
            val offsetY = gridOrigin.y + drag.itemOffset.y + gridDragOffset.y
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(widthDp, heightDp)
                    .zIndex(200f)
                    .graphicsLayer {
                        scaleX = 1.08f
                        scaleY = 1.08f
                        shadowElevation = 18.dp.toPx()
                        clip = false
                    }
            ) {
                val color = remember(drag.name) { generateDeterministicColor(drag.name) }
                val count = viewModel.getPlaylistTracks(drag.name).size
                PlaylistCard(
                    name = drag.name,
                    count = count,
                    color = color,
                    isSystem = false,
                    onClick = {},
                    clickEnabled = false
                )
            }
        }
    }
}

// --- ЭКРАН 2: ДЕТАЛИ И РЕДАКТИРОВАНИЕ ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistEditorScreen(
    viewModel: MusicViewModel,
    playlistName: String,
    onBack: () -> Unit
) {
    val bottomOverlay = LocalBottomOverlayPadding.current
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dragScope = rememberCoroutineScope()
    val isSystem = playlistName == "All Tracks"

    // --- State ---
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.CUSTOM) }
    var showSortDialog by remember { mutableStateOf(false) }

    var editClickCounter by remember { mutableIntStateOf(0) }
    var showSwitchToCustomDialog by remember { mutableStateOf(false) }

    // Данные треков
    val rawTracks = remember(viewModel.playlist, viewModel.userPlaylists, playlistName, viewModel.playlistUpdateTrigger) {
        if (isSystem) viewModel.playlist else viewModel.getPlaylistTracks(playlistName)
    }

    // Локальный список для перетаскивания
    var localTracks by remember { mutableStateOf(rawTracks) }

    // Применяем фильтрацию и сортировку
    val displayTracks = remember(localTracks, searchQuery, sortOption) {
        var list = localTracks
        if (searchQuery.isNotEmpty()) {
            list = list.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        (it.artist?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
        when(sortOption) {
            SortOption.CUSTOM -> list
            SortOption.TITLE_ASC -> list.sortedBy { it.title }
            SortOption.TITLE_DESC -> list.sortedByDescending { it.title }
            SortOption.DURATION_ASC -> list.sortedBy { it.durationMs }
            SortOption.DURATION_DESC -> list.sortedByDescending { it.durationMs }
            SortOption.DATE_NEWEST -> list
            SortOption.DATE_OLDEST -> list.reversed()
        }
    }

    // Синхронизируем локальный список при изменении rawTracks
    LaunchedEffect(rawTracks) {
        localTracks = rawTracks
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.loadFromFolder(context, uri)
    }

    var isEditMode by remember { mutableStateOf(false) }
    var draggingTrack by remember { mutableStateOf<ListDragState?>(null) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var dragTouchOffsetY by remember { mutableFloatStateOf(0f) }
    var autoScrollDir by remember { mutableIntStateOf(0) } // -1 up, 1 down
    var listOrigin by remember { mutableStateOf(Offset.Zero) }
    var listSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var showAddSheet by remember { mutableStateOf(false) }

    val shouldAnimateTrackWiggle = isEditMode && !isSystem && sortOption == SortOption.CUSTOM && displayTracks.size <= 120
    val rotation by if (shouldAnimateTrackWiggle) {
        val infiniteTransition = rememberInfiniteTransition(label = "WiggleTrack")
        infiniteTransition.animateFloat(
            initialValue = -0.7f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse),
            label = "RotTrack"
        )
    } else remember { mutableFloatStateOf(0f) }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    fun vibrateDrag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(30)
        }
    }

    val canDrag = !isSystem && isEditMode && sortOption == SortOption.CUSTOM && searchQuery.isEmpty()

    // --- DIALOGS ---
    if (showAddSheet) TrackPickerSheet(viewModel, playlistName) { showAddSheet = false }

    if (showSwitchToCustomDialog) {
        GlassDialog(onDismiss = { showSwitchToCustomDialog = false }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Изменить порядок?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Сейчас включена авто-сортировка. Переключитесь на «Свой порядок», чтобы двигать треки.", color = Color.Gray, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                GlassButton("Переключить", {
                    sortOption = SortOption.CUSTOM
                    isEditMode = true
                    showSwitchToCustomDialog = false
                    editClickCounter = 0
                }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
            }
        }
    }

    if (showSortDialog) {
        GlassDropdownMenu(
            expanded = showSortDialog,
            onDismissRequest = { showSortDialog = false }
        ) {
            GlassDropdownHeader("Сортировка")

            SortOption.values().forEach { option ->
                if (isSystem && option == SortOption.CUSTOM) return@forEach

                GlassDropdownMenuItem(
                    text = option.title,
                    selected = sortOption == option,
                    onClick = {
                        sortOption = option
                        showSortDialog = false
                        if (option != SortOption.CUSTOM) isEditMode = false
                    }
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { clip = false } // корневой контейнер без обрезки
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                PlaylistHeader(
                    title = if (isSystem) "Все треки" else playlistName,
                    count = displayTracks.size,
                    color = viewModel.dominantColor,
                    isSystem = isSystem,
                    isEditMode = isEditMode,
                    canEdit = sortOption == SortOption.CUSTOM,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onBack = onBack,
                    onPlay = {
                        if (displayTracks.isNotEmpty()) {
                            val track = displayTracks.first()
                            val realIndex = rawTracks.indexOf(track)
                            if (realIndex != -1) viewModel.playTrackFromPlaylist(playlistName, realIndex)
                        }
                    },
                    onShuffle = {
                        if (displayTracks.isNotEmpty()) {
                            viewModel.playShuffledFromPlaylist(playlistName)
                        }
                    },
                    onAdd = if (!isSystem) { { showAddSheet = true } } else null,
                    onToggleEdit = {
                        if (sortOption == SortOption.CUSTOM) {
                            isEditMode = !isEditMode
                            if (!isEditMode) {
                                draggingTrack = null
                                dragPointerY = 0f
                                dragTouchOffsetY = 0f
                                autoScrollDir = 0
                                dragStartIndex = -1
                            }
                        } else {
                            editClickCounter++
                            if (editClickCounter >= 2) showSwitchToCustomDialog = true
                        }
                    },
                    onSelectFolder = if (isSystem) { { folderLauncher.launch(null) } } else null,
                    onSortClick = { showSortDialog = true }
                )
            }
        ) { padding ->
            if (displayTracks.isEmpty()) {
                EmptyPlaylistView(
                    padding = padding,
                    onAdd = {
                        if (!isSystem) showAddSheet = true
                        else folderLauncher.launch(null)
                    },
                    isSystem = isSystem,
                    onRefresh = if (isSystem) { { viewModel.refreshLibrary(context) } } else null
                )
            } else {
                val listState = rememberLazyListState()
                val autoScrollThresholdPx = with(density) { 96.dp.toPx() }
                val autoScrollSpeedPx = with(density) { 20.dp.toPx() }

                fun maybeReorder() {
                    val dragState = draggingTrack ?: return
                    val dragCenterY =
                        (dragPointerY - dragTouchOffsetY) + (dragState.itemSize.height / 2f)
                    val targetItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        dragCenterY.toInt() in item.offset..(item.offset + item.size)
                    }

                    if (targetItem != null &&
                        targetItem.index != dragState.index &&
                        targetItem.index < localTracks.size
                    ) {
                        val newList = localTracks.toMutableList()
                        val moved = newList.removeAt(dragState.index)
                        newList.add(targetItem.index, moved)
                        localTracks = newList

                        draggingTrack = dragState.copy(
                            index = targetItem.index,
                            itemOffset = IntOffset(0, targetItem.offset),
                            itemSize = IntSize(listSize.width, targetItem.size)
                        )
                        vibrateDrag()
                    }
                }

                LaunchedEffect(autoScrollDir, draggingTrack?.key) {
                    if (autoScrollDir == 0 || draggingTrack == null) return@LaunchedEffect

                    while (true) {
                        val delta = autoScrollDir.toFloat() * autoScrollSpeedPx
                        val canScroll = (delta < 0 && listState.canScrollBackward) ||
                                (delta > 0 && listState.canScrollForward)
                        if (canScroll) {
                            listState.scrollBy(delta)
                            maybeReorder()
                        }
                        delay(16L)
                    }
                }

                LazyColumn(
                    state = listState,
                    userScrollEnabled = draggingTrack == null,
                    contentPadding = PaddingValues(bottom = bottomOverlay + 24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                        .graphicsLayer { clip = false } // отключаем обрезку на списке
                        .onGloballyPositioned { coordinates ->
                            listOrigin = coordinates.positionInRoot()
                            listSize = coordinates.size
                        }
                        .pointerInput(canDrag) {
                            if (!canDrag) return@pointerInput

                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val item = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            offset.y.toInt() in it.offset..(it.offset + it.size)
                                        }
                                    if (item != null && item.index < localTracks.size) {
                                        val track = localTracks[item.index]
                                        draggingTrack = ListDragState(
                                            index = item.index,
                                            key = track.uri.toString(),
                                            itemOffset = IntOffset(0, item.offset),
                                            itemSize = IntSize(listSize.width, item.size)
                                        )
                                        dragStartIndex = item.index
                                        dragPointerY = offset.y
                                        dragTouchOffsetY = offset.y - item.offset
                                        autoScrollDir = 0
                                        vibrateDrag()
                                    }
                                },
                                onDragEnd = {
                                    val endIndex = draggingTrack?.index ?: -1
                                    if (dragStartIndex != -1 && endIndex != -1 && dragStartIndex != endIndex) {
                                        viewModel.moveTrackInPlaylist(playlistName, dragStartIndex, endIndex)
                                    }
                                    draggingTrack = null
                                    dragPointerY = 0f
                                    dragTouchOffsetY = 0f
                                    autoScrollDir = 0
                                    dragStartIndex = -1
                                },
                                onDragCancel = {
                                    draggingTrack = null
                                    dragPointerY = 0f
                                    dragTouchOffsetY = 0f
                                    autoScrollDir = 0
                                    dragStartIndex = -1
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragPointerY = change.position.y
                                    autoScrollDir = when {
                                        dragPointerY < autoScrollThresholdPx -> -1
                                        dragPointerY > (listSize.height - autoScrollThresholdPx) -> 1
                                        else -> 0
                                    }
                                    maybeReorder()
                                }
                            )
                        }
                ) {
                    itemsIndexed(
                        items = displayTracks,
                        key = { _, track -> track.uri.toString() }
                    ) { index, track ->
                        val isDragging = draggingTrack?.key == track.uri.toString()
                        val wiggleDirection = if (index % 2 == 0) 1f else -1f
                        val wiggle = if (isEditMode && !isDragging && !isSystem && sortOption == SortOption.CUSTOM) {
                            Modifier.rotate(rotation * wiggleDirection)
                        } else Modifier

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItemPlacement(
                                    animationSpec = tween(300)
                                )
                                .zIndex(0f)
                                .graphicsLayer {
                                    alpha = if (isDragging) 0f else 1f
                                    clip = false
                                }
                                .then(wiggle)
                        ) {
                            TrackRow(
                                track = track,
                                index = index,
                                viewModel = viewModel,
                                isSelectionMode = !isSystem,
                                isEditMode = isEditMode && !isDragging,
                                onClick = {
                                    if (!isEditMode) {
                                        val realIndex = rawTracks.indexOf(track)
                                        if (realIndex != -1) viewModel.playTrackFromPlaylist(playlistName, realIndex)
                                    }
                                },
                                onRemoveClick = { viewModel.removeTrackFromPlaylist(playlistName, track.uri.toString()) }
                            )
                        }
                    }
                }
            }
        }

        // Bottom Gradient – низкий zIndex, не перекрывает перетаскиваемые элементы
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, TrecBlack.copy(alpha = 0.6f), TrecBlack.copy(alpha = 0.95f))
                    )
                )
                .zIndex(0f)
        )

        // Оверлей для перетаскиваемого трека (поверх всего, без обрезки)
        draggingTrack?.let { drag ->
            val track = localTracks.firstOrNull { it.uri.toString() == drag.key } ?: return@let
            val widthDp = with(density) { listSize.width.toDp() }
            val heightDp = with(density) { drag.itemSize.height.toDp() }
            val offsetX = listOrigin.x
            val offsetY = listOrigin.y + (dragPointerY - dragTouchOffsetY)

            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(widthDp, heightDp)
                    .zIndex(200f)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                viewModel.dominantColor.copy(alpha = 0.22f),
                                Color.White.copy(alpha = 0.06f)
                            )
                        ),
                        RoundedCornerShape(12.dp)
                    )
                    .border(1.dp, viewModel.dominantColor.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .graphicsLayer {
                        scaleX = 1.02f
                        scaleY = 1.02f
                        shadowElevation = 12.dp.toPx()
                        clip = false
                    }
            ) {
                TrackRow(
                    track = track,
                    index = drag.index,
                    viewModel = viewModel,
                    isSelectionMode = !isSystem,
                    isEditMode = false,
                    onClick = {},
                    onRemoveClick = {}
                )
            }
        }
    }
}

@Composable
fun PlaylistHeader(
    title: String,
    count: Int,
    color: Color,
    isSystem: Boolean = false,
    isEditMode: Boolean = false,
    canEdit: Boolean = true,
    searchQuery: String = "",
    onSearchChange: (String) -> Unit = {},
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAdd: (() -> Unit)?,
    onToggleEdit: (() -> Unit)? = null,
    onSelectFolder: (() -> Unit)? = null,
    onSortClick: () -> Unit
) {
    var isSearchExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            onSearchChange("")
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent)))
            .graphicsLayer { clip = false }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.statusBars)
                .graphicsLayer { clip = false }
        ) {
            Row(
                Modifier.fillMaxWidth().height(50.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isSearchExpanded) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                }

                AnimatedContent(
                    targetState = isSearchExpanded,
                    transitionSpec = {
                        fadeIn(tween(300)) + expandHorizontally(tween(300)) togetherWith
                                fadeOut(tween(300)) + shrinkHorizontally(tween(300))
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = if(isSearchExpanded) 0.dp else 8.dp)
                ) { expanded ->
                    if (expanded) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(0.15f))
                                .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(50))
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchChange,
                                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(text = "Поиск треков...", color = Color.White.copy(0.5f))
                                    }
                                    innerTextField()
                                }
                            )
                            IconButton(
                                onClick = { isSearchExpanded = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, null, tint = Color.White.copy(0.7f))
                            }
                        }
                    } else {
                        Spacer(Modifier.fillMaxWidth())
                    }
                }

                if (!isSearchExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isSearchExpanded = true }) {
                            Icon(Icons.Default.Search, null, tint = Color.White)
                        }
                        IconButton(onClick = onSortClick) {
                            Icon(Icons.AutoMirrored.Filled.Sort, null, tint = Color.White)
                        }

                        if (onSelectFolder != null) {
                            IconButton(onClick = onSelectFolder) {
                                Icon(Icons.Rounded.FolderOpen, null, tint = Color.White)
                            }
                        }
                        if (!isSystem && onToggleEdit != null) {
                            IconButton(
                                onClick = onToggleEdit,
                                modifier = Modifier
                                    .alpha(if (canEdit || isEditMode) 1f else 0.4f)
                                    .background(if(isEditMode) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            ) {
                                Icon(Icons.Filled.Edit, null, tint = Color.White)
                            }
                        }
                        if (onAdd != null) {
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = onAdd) {
                                Icon(Icons.Rounded.Add, null, tint = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$count треков",
                        color = Color.White.copy(0.7f),
                        fontSize = 14.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onShuffle,
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.White.copy(0.1f), CircleShape)
                    ) {
                        Icon(Icons.Rounded.Shuffle, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    FilledIconButton(
                        onClick = onPlay,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TrackPickerSheet(viewModel: MusicViewModel, currentPlaylist: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val allTracks = viewModel.getAllTracks()
    var searchQuery by remember { mutableStateOf("") }

    val tracksToShow = remember(searchQuery, allTracks, viewModel.playlistUpdateTrigger) {
        val existing = viewModel.getPlaylistTracks(currentPlaylist).map { it.uri.toString() }.toSet()
        allTracks.filter {
            !existing.contains(it.uri.toString()) &&
                    (it.title.contains(searchQuery, true) || it.artist?.contains(searchQuery, true) == true)
        }
    }

    GlassDialog(onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 600.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Добавить треки", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White.copy(0.7f))
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.White.copy(0.1f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(0.03f))
            ) {
                if (tracksToShow.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isEmpty()) "Все треки уже добавлены" else "Ничего не найдено",
                                color = Color.Gray
                            )
                        }
                    }
                }
                items(items = tracksToShow) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addTrackToPlaylist(currentPlaylist, track.uri.toString())
                                Toast.makeText(context, "Добавлено", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val color = remember(track.uri) { generateDeterministicColor(track.uri.toString()) }
                        VinylPlaceholder(color = color, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist ?: "Unknown",
                                color = Color.White.copy(0.6f),
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                        Icon(
                            Icons.Rounded.Add,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    HorizontalDivider(
                        color = Color.White.copy(0.05f),
                        modifier = Modifier.padding(start = 70.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            GlassButton("Готово", onDismiss, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun PlaylistCard(
    name: String,
    count: Int,
    color: Color,
    isSystem: Boolean = false,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    clickEnabled: Boolean = true
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(160.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.6f), color.copy(alpha = 0.2f))))
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(24.dp))
            .clickable(enabled = clickEnabled, onClick = onClick)
            .graphicsLayer { clip = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = if (isSystem) Icons.Rounded.FolderOpen else Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                if (!isSystem) {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Rounded.MoreVert, null, tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(Color(0xFF222222))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Переименовать", color = Color.White) },
                                onClick = {
                                    showMenu = false
                                    onEdit?.invoke()
                                },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = Color.White) }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить", color = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showMenu = false
                                    onDelete?.invoke()
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.primary) }
                            )
                        }
                    }
                }
            }
            Column {
                Text(
                    text = name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$count треков",
                    color = Color.White.copy(0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initialValue: String,
    hint: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    GlassDialog(onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(hint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onConfirm(text)
                    keyboardController?.hide()
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = Color.Gray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTextButton("Отмена", onDismiss)
                GlassButton(
                    confirmText,
                    {
                        onConfirm(text)
                        keyboardController?.hide()
                    },
                    MaterialTheme.colorScheme.primary,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun EmptyPlaylistView(
    padding: PaddingValues,
    onAdd: () -> Unit,
    isSystem: Boolean,
    onRefresh: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                null,
                tint = Color.White.copy(0.3f),
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isSystem) "Музыка не найдена" else "Здесь пока пусто",
                color = Color.Gray,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(24.dp))
            if (!isSystem) {
                GlassButton(
                    text = "Добавить треки",
                    onClick = onAdd,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(200.dp)
                )
            } else {
                GlassButton(
                    text = "Выбрать папку",
                    onClick = onAdd,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(200.dp)
                )
                if (onRefresh != null) {
                    Spacer(Modifier.height(16.dp))
                    GlassTextButton("Обновить медиатеку", onRefresh)
                }
            }
        }
    }
}

// --- Хелпер для генерации цветов ---
fun generateDeterministicColor(key: String): Color {
    val colors = listOf(
        Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA),
        Color(0xFF5E35B1), Color(0xFF3949AB), Color(0xFF1E88E5),
        Color(0xFF039BE5), Color(0xFF00ACC1), Color(0xFF00897B),
        Color(0xFF43A047), Color(0xFF7CB342), Color(0xFFC0CA33),
        Color(0xFFFDD835), Color(0xFFFFB300), Color(0xFFFB8C00),
        Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF757575)
    )
    return colors[abs(key.hashCode()) % colors.size]
}
