package com.trec.music.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Высота нижнего "перекрытия" интерфейса (нижняя навигация + мини‑плееры),
 * которую нужно учитывать в scroll-контенте, чтобы ничего не обрезалось.
 *
 * Важно: это именно overlay-height, а не системные insets.
 */
val LocalBottomOverlayPadding = staticCompositionLocalOf<Dp> { 0.dp }

