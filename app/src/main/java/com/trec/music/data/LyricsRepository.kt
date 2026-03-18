// data/LyricsRepository.kt
//
// ТИП: Repository (Lyrics Cache & Management)
//
// НАЗНАЧЕНИЕ:
// Кэширование текстов песен в памяти и SharedPreferences.
// Управление загрузкой и сохранением lyrics.
//
// ИЗМЕНЕНИЯ:
// 1. Full Implementation: Репозиторий для текстов песен.

package com.trec.music.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.trec.music.data.api.LyricsApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LyricsRepository(context: Context) {

    private val apiService = LyricsApiService()
    private val prefs: SharedPreferences = context.getSharedPreferences("lyrics_cache", Context.MODE_PRIVATE)
    
    // In-memory cache
    private val memoryCache = mutableMapOf<String, String>()
    
    companion object {
        private const val CACHE_PREFIX = "lyrics_"
        private const val CACHE_MAX_AGE_DAYS = 30 // Кэш на 30 дней
    }

    data class LyricsData(
        val lyrics: String,
        val source: String // "cache" или "api"
    )

    /**
     * Получить текст песни (с кэшем)
     */
    suspend fun getLyrics(artist: String, title: String): Result<LyricsData> = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(artist, title)
        
        // 1. Проверяем memory cache
        memoryCache[cacheKey]?.let {
            return@withContext Result.success(LyricsData(it, "cache"))
        }
        
        // 2. Проверяем SharedPreferences cache
        val cachedLyrics = getCachedLyrics(cacheKey)
        if (cachedLyrics != null) {
            memoryCache[cacheKey] = cachedLyrics
            return@withContext Result.success(LyricsData(cachedLyrics, "cache"))
        }
        
        // 3. Загружаем из API
        val result = apiService.getLyricsWithFallback(artist, title)
        
        if (result.success && result.lyrics != null) {
            // Сохраняем в кэш
            saveLyricsToCache(cacheKey, result.lyrics)
            memoryCache[cacheKey] = result.lyrics
            Result.success(LyricsData(result.lyrics, "api"))
        } else {
            Result.failure(Exception(result.error ?: "Не удалось загрузить текст"))
        }
    }

    /**
     * Очистить кэш текстов
     */
    fun clearCache() {
        memoryCache.clear()
        prefs.edit {
            prefs.all.keys
                .filter { it.startsWith(CACHE_PREFIX) }
                .forEach { remove(it) }
        }
    }

    /**
     * Получить размер кэша
     */
    fun getCacheSize(): Int {
        return memoryCache.size + prefs.all.keys.count { it.startsWith(CACHE_PREFIX) }
    }

    private fun generateCacheKey(artist: String, title: String): String {
        val cleanArtist = artist.lowercase().trim().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        val cleanTitle = title.lowercase().trim().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        return "${CACHE_PREFIX}${cleanArtist}_${cleanTitle}"
    }

    private fun getCachedLyrics(key: String): String? {
        val lyrics = prefs.getString(key, null) ?: return null
        val timestamp = prefs.getLong("${key}_time", 0L)
        
        // Проверяем не устарел ли кэш
        val ageDays = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60 * 24)
        if (ageDays > CACHE_MAX_AGE_DAYS) {
            prefs.edit { 
                remove(key)
                remove("${key}_time")
            }
            return null
        }
        
        return lyrics
    }

    private fun saveLyricsToCache(key: String, lyrics: String) {
        prefs.edit {
            putString(key, lyrics)
            putLong("${key}_time", System.currentTimeMillis())
        }
    }
}

