// utils/TimeUtils.kt
//
// ТИП: Utility (Formatting)
//
// ЗАВИСИМОСТИ (LINKS TO):
// - Будет использоваться в UI (PlayerScreen.kt, PlaylistScreen.kt) для отображения
//   текущей позиции и длительности трека.
//
// НАЗНАЧЕНИЕ:
// Конвертирует миллисекунды (Long) в человекочитаемую строку "MM:SS" или "H:MM:SS".

package com.trec.music.utils

import java.util.Locale

/**
 * Утилита для форматирования времени (миллисекунды -> строка).
 * Реализована как функция верхнего уровня.
 */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"

    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return if (minutes >= 60) {
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        // Формат H:MM:SS для длинных аудиокниг или миксов
        String.format(Locale.US, "%d:%02d:%02d", hours, remainingMinutes, seconds)
    } else {
        // Стандартный формат MM:SS для песен
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}