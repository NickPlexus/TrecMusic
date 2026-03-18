// Файл: MainActivity.kt
//
// ИЗМЕНЕНИЯ:
// 1. Updated Nav: BottomNavigationBar теперь принимает musicViewModel.
// 2. Radio Route: Добавлен экран "radio" в NavHost (пока заглушка, чтобы не крашилось).

package com.trec.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trec.music.ui.components.MiniPlayer
import com.trec.music.ui.components.RecordingMiniPlayer
import com.trec.music.ui.navigation.BottomNavigationBar
import com.trec.music.ui.screens.*
import com.trec.music.ui.theme.TrecBlack
import com.trec.music.ui.theme.TrecMusicTheme
import com.trec.music.ui.theme.TrecRed
import com.trec.music.utils.DebugConsoleWindow
import com.trec.music.viewmodel.MusicViewModel
import com.trec.music.viewmodel.RecorderViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // FLAG_KEEP_SCREEN_ON - будет обновляться при изменении настройки
        val viewModel = androidx.lifecycle.ViewModelProvider(this).get(MusicViewModel::class.java)
        if (viewModel.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        setContent {
            TrecMusicTheme { MainAppStructure() }
        }
    }
}

@Composable
fun MainAppStructure() {
    val navController = rememberNavController()
    val musicViewModel: MusicViewModel = viewModel()
    val recorderViewModel: RecorderViewModel = viewModel()
    val context = LocalContext.current
    
    // Обновляем FLAG_KEEP_SCREEN_ON при изменении настройки
    SideEffect {
        val activity = context as? ComponentActivity
        if (musicViewModel.keepScreenOn) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var hasPermissions by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
        }
    }

    val optionalPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
    }

    val allPermissionsToRequest = remember(requiredPermissions, optionalPermissions) {
        requiredPermissions + optionalPermissions
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        hasPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPermissions) musicViewModel.initialize()
    }

    LaunchedEffect(Unit) {
        hasPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermissions) {
            launcher.launch(allPermissionsToRequest)
        } else {
            musicViewModel.initialize()
        }
    }

    var showFullPlayer by remember { mutableStateOf(false) }

    // Высоты для расчетов позиционирования (не для обрезки!)
    val navBarHeight = 80.dp
    val systemNavHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomAreaHeight = navBarHeight + systemNavHeight

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrecBlack)
    ) {
        // СЛОЙ 1: КОНТЕНТ (NavHost)
        Box(modifier = Modifier.fillMaxSize()) {
            if (hasPermissions) {
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(400)) },
                    exitTransition = { fadeOut(tween(400)) }
                ) {
                    composable("home") { HomeScreen(musicViewModel, navController) }
                    composable("library") { LibraryScreen(musicViewModel) }

                    composable("radio") { RadioScreen() }

                    composable("recorder") {
                        RecorderScreen(viewModel = recorderViewModel, musicViewModel = musicViewModel)
                    }
                    composable("favorites") { FavoritesScreen(musicViewModel) }
                    composable("settings") { SettingsScreen(musicViewModel, navController) }
                    composable("privacy") { LegalScreen(LegalType.PRIVACY) { navController.popBackStack() } }
                    composable("terms") { LegalScreen(LegalType.TERMS) { navController.popBackStack() } }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Требуются разрешения", color = Color.White)
                }
            }
        }

        // СЛОЙ 2: UI (Навигация и Плеер)
        if (!showFullPlayer) {
            // Навигация (внизу)
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                // ФИКС: Передаем ViewModel целиком, так как сигнатура изменилась
                BottomNavigationBar(navController, musicViewModel)
            }

            val miniPlayerHeight = 86.dp
            val miniPlayerGap = 8.dp

            val hasTrackMini = musicViewModel.currentTrackUri != null
            val hasRecordingMini = recorderViewModel.currentPlayback != null

            if (hasRecordingMini) {
                val offset = bottomAreaHeight + if (hasTrackMini) (miniPlayerHeight + miniPlayerGap) else 0.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = offset)
                        .padding(horizontal = 8.dp)
                ) {
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        RecordingMiniPlayer(recorderViewModel) {
                            navController.navigate("recorder")
                        }
                    }
                }
            }

            if (hasTrackMini) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomAreaHeight)
                        .padding(horizontal = 8.dp)
                ) {
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        MiniPlayer(musicViewModel) { showFullPlayer = true }
                    }
                }
            }
        }

        // СЛОЙ 3: FULL PLAYER OVERLAY
        AnimatedVisibility(
            visible = showFullPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            BackHandler(enabled = showFullPlayer) { showFullPlayer = false }
            FullPlayerOverlay(viewModel = musicViewModel, onClose = { showFullPlayer = false })
        }

        DebugConsoleWindow()
    }
}
