// ui/theme/Theme.kt
//
// ТИП: Theme Definition (Jetpack Compose)
//
// НАЗНАЧЕНИЕ:
// Настраивает глобальную тему приложения (цвета, шрифты) и системные бары.
// Реализует Edge-to-Edge (прозрачный статус-бар и навигация).
//
// ЧТО СДЕЛАНО (FIXES):
// 1. Safe Activity Lookup: Добавлена функция findActivity().
//    Это предотвращает краш (ClassCastException), если view.context обернут в ContextWrapper.
// 2. Edge-to-Edge: Настройка прозрачных баров и цвета иконок (белые иконки на темном фоне).
// 3. Dark Mode enforcement: Принудительная темная тема (приложение музыкальное, ему идет).
//
// ЧТО ПРЕДСТОИТ СДЕЛАТЬ (FUTURE):
// 1. Dynamic Colors: Можно добавить поддержку Material You (динамические цвета из обоев) для Android 12+,
//    но для брендированного плеера (Red/Black) это может быть лишним.

package com.trec.music.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// --- ГЛОБАЛЬНЫЕ ЦВЕТА ---
val TrecRed = Color(0xFFD50000)
val TrecBlack = Color(0xFF050505)
val TrecGray = Color(0xFF282828)
val TrecDarkGray = Color(0xFF1E1E1E)
// ------------------------

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    secondary = accent,
    background = TrecBlack,
    surface = TrecDarkGray,
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun TrecMusicTheme(
    accentColor: Color = TrecRed,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // БЕЗОПАСНЫЙ ПОИСК ACTIVITY
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window

            // Прозрачные бары для Edge-to-Edge
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()

            // Настройка иконок (false = белые иконки, так как фон темный)
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    val scheme = darkScheme(accentColor)
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}

// Extension для рекурсивного поиска Activity (защита от крашей)
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
