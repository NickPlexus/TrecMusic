// data/TrecTrackEnhanced.kt
//
// ТИП: Enhanced Data Model (DTO)
//
// НАЗНАЧЕНИЕ:
// Расширенная модель данных для музыкального трека с полной информацией о метаданных.
// Используется для корректного отображения всей информации о треке.
//
// ЧТО ДОБАВЛЕНО:
// 1. Поля метаданных: album, genre, year, trackNumber, composer
// 2. Техническая информация: bitrate, sampleRate, mimeType, fileSize
// 3. Временные метки: dateAdded, dateModified
// 4. Флаги: isLocal, path
// 5. Parcelable: Для передачи между компонентами
//
package com.trec.music.data

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TrecTrackEnhanced(
    val uri: Uri,
    val title: String,
    val artist: String? = null, // Исполнитель
    val album: String? = null, // Название альбома
    val durationMs: Long = 0L, // Длительность в миллисекундах
    val albumArtist: String? = null, // Исполнитель альбома
    val genre: String? = null, // Жанр
    val year: Int? = null, // Год выпуска
    val trackNumber: Int? = null, // Номер трека в альбоме
    val composer: String? = null, // Композитор
    val bitrate: Int? = null, // Битрейт в kbps
    val sampleRate: Int? = null, // Частота дискретизации в Hz
    val mimeType: String? = null, // MIME тип файла
    val fileSize: Long = 0L, // Размер файла в байтах
    val dateAdded: Long = 0L, // Дата добавления (timestamp)
    val dateModified: Long = 0L, // Дата модификации (timestamp)
    val isLocal: Boolean = true, // Локальный файл или нет
    val path: String? = null // Путь к файлу
) : Parcelable {
    
    // Вспомогательные функции для форматирования
    fun getFormattedDuration(): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }
    
    fun getFormattedFileSize(): String {
        val kb = fileSize / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$fileSize B"
        }
    }
    
    fun getDisplayArtist(): String {
        return artist ?: "Неизвестный исполнитель"
    }
    
    fun getDisplayAlbum(): String {
        return album ?: "Неизвестный альбом"
    }
    
    fun getDisplayGenre(): String {
        return genre ?: "Неизвестный жанр"
    }
    
    fun getDisplayYear(): String {
        return year?.toString() ?: "Неизвестный год"
    }
}
