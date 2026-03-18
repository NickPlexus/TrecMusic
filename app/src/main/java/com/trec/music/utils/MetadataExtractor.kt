// utils/MetadataExtractor.kt
//
// ТИП: Utility
//
// НАЗНАЧЕНИЕ:
// Извлечение полных метаданных из аудиофайлов.
// Использует MediaMetadataRetriever для получения всей информации.
//
// ЧТО СДЕЛАНО:
// 1. Полное извлечение метаданных - все возможные поля
// 2. Обработка ошибок - безопасное извлечение
// 3. Конвертация старой модели в новую
// 4. Форматирование и вспомогательные функции
//
// ЧТО ПРЕДСТОИТ СДЕЛАТЬ:
// 1. Кэширование метаданных для производительности
// 2. Поддержка разных форматов (MP3, FLAC, M4A)
// 3. Извлечение обложек альбомов

package com.trec.music.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.trec.music.data.TrecTrackEnhanced
import java.io.File

object MetadataExtractor {
    private const val TAG = "MetadataExtractor"
    
    fun extractMetadata(context: Context, uri: Uri): TrecTrackEnhanced {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            
            // Извлекаем все возможные метаданные
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "Неизвестный трек"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.toIntOrNull()
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            
            // Размер файла
            val fileSize = if (uri.scheme == "file") {
                try {
                    File(uri.path ?: "").length()
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot get file size", e)
                    0L
                }
            } else {
                0L
            }
            
            // Даты
            val dateAdded = System.currentTimeMillis()
            val dateModified = if (uri.scheme == "file") {
                try {
                    File(uri.path ?: "").lastModified()
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot get file modified date", e)
                    0L
                }
            } else {
                0L
            }
            
            // Проверяем, локальный ли файл
            val isLocal = uri.scheme == "file"
            val path = if (isLocal) uri.path else null
            
            TrecTrackEnhanced(
                uri = uri,
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                albumArtist = albumArtist,
                genre = genre,
                year = year,
                trackNumber = trackNumber,
                composer = composer,
                bitrate = bitrate,
                sampleRate = sampleRate,
                mimeType = mimeType,
                fileSize = fileSize,
                dateAdded = dateAdded,
                dateModified = dateModified,
                isLocal = isLocal,
                path = path
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting metadata", e)
            // Возвращаем базовую информацию при ошибке
            TrecTrackEnhanced(
                uri = uri,
                title = File(uri.path ?: uri.toString()).nameWithoutExtension,
                artist = null,
                album = null,
                durationMs = 0L,
                albumArtist = null,
                genre = null,
                year = null,
                trackNumber = null,
                composer = null,
                bitrate = null,
                sampleRate = null,
                mimeType = null,
                fileSize = 0L,
                dateAdded = System.currentTimeMillis(),
                dateModified = 0L,
                isLocal = uri.scheme == "file",
                path = uri.path
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
    

}
