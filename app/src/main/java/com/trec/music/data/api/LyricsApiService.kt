// data/api/LyricsApiService.kt
//
// ТИП: API Service (Lyrics Integration)
//
// НАЗНАЧЕНИЕ:
// Клиент для API текстов песен с резервным источником.
//
// ИЗМЕНЕНИЯ:
// 1. Основной источник: lrclib.net.
// 2. Резервный источник: lyrics.ovh.
// 3. Корректная обработка временных ошибок 5xx.

package com.trec.music.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LyricsApiService {

    companion object {
        private const val TAG = "LyricsApiService"
        private const val LYRICS_OVH_URL = "https://api.lyrics.ovh/v1"
        private const val LRCLIB_GET_URL = "https://lrclib.net/api/get"
        private const val LRCLIB_SEARCH_URL = "https://lrclib.net/api/search"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class LyricsResult(
        val success: Boolean,
        val lyrics: String? = null,
        val error: String? = null
    )

    /**
     * Получить текст песни по исполнителю и названию
     */
    suspend fun getLyrics(artist: String, title: String): LyricsResult = withContext(Dispatchers.IO) {
        try {
            val cleanArtist = sanitizeArtist(artist)
            val cleanTitle = sanitizeTitle(title)

            val lrcLibResult = requestFromLrcLib(cleanArtist, cleanTitle)
            if (lrcLibResult.success) return@withContext lrcLibResult

            val ovhResult = requestFromLyricsOvh(cleanArtist, cleanTitle)
            if (ovhResult.success) return@withContext ovhResult

            if (ovhResult.error?.startsWith("SERVICE_UNAVAILABLE") == true) {
                return@withContext LyricsResult(
                    success = false,
                    error = "Сервис текста временно недоступен. Попробуйте позже."
                )
            }

            lrcLibResult
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}", e)
            LyricsResult(success = false, error = "Ошибка сети: ${e.message}")
        }
    }

    /**
     * Попытка найти текст с альтернативными вариантами названия
     */
    suspend fun getLyricsWithFallback(artist: String, title: String): LyricsResult {
        var result = getLyrics(artist, title)
        if (result.success) return result

        val titleNoBrackets = title.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]\\s*"), "").trim()
        if (titleNoBrackets != title) {
            result = getLyrics(artist, titleNoBrackets)
            if (result.success) return result
        }

        val firstArtist = artist.split(",", "&", "and", "feat.", "ft.", "feat", "ft")
            .firstOrNull()
            ?.trim()
            ?: artist

        if (firstArtist != artist) {
            result = getLyrics(firstArtist, title)
            if (result.success) return result

            if (titleNoBrackets != title) {
                result = getLyrics(firstArtist, titleNoBrackets)
                if (result.success) return result
            }
        }

        return result
    }

    private fun sanitizeArtist(artist: String): String {
        return artist.trim()
            .replace(" ft. ", " ", ignoreCase = true)
            .replace(" feat. ", " ", ignoreCase = true)
            .replace(" ft ", " ", ignoreCase = true)
            .replace(" feat ", " ", ignoreCase = true)
            .replace(" & ", " ")
            .replace(" and ", " ", ignoreCase = true)
            .replace("/", " ")
            .trim()
    }

    private fun sanitizeTitle(title: String): String {
        return title.trim()
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .trim()
    }

    private fun normalizeLyrics(raw: String?): String {
        return raw.orEmpty()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
    }

    private fun requestFromLyricsOvh(artist: String, title: String): LyricsResult {
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = "$LYRICS_OVH_URL/$encodedArtist/$encodedTitle"

        Log.d(TAG, "lyrics.ovh request: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TrecMusic/2.1")
            .build()

        client.newCall(request).execute().use { response ->
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (code in 500..599) {
                Log.w(TAG, "lyrics.ovh temporary failure: HTTP $code")
                return LyricsResult(false, error = "SERVICE_UNAVAILABLE:$code")
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "lyrics.ovh failure: HTTP $code")
                return LyricsResult(false, error = "Текст не найден")
            }

            val json = JSONObject(body)
            val text = normalizeLyrics(json.optString("lyrics"))
            if (text.isNotBlank()) {
                return LyricsResult(true, lyrics = text)
            }

            return LyricsResult(false, error = "Текст не найден")
        }
    }

    private fun requestFromLrcLib(artist: String, title: String): LyricsResult {
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val encodedTitle = URLEncoder.encode(title, "UTF-8")

        val getUrl = "$LRCLIB_GET_URL?artist_name=$encodedArtist&track_name=$encodedTitle"
        Log.d(TAG, "lrclib get request: $getUrl")

        val direct = requestLrcLibObject(getUrl)
        if (direct.success) return direct

        val searchUrl = "$LRCLIB_SEARCH_URL?artist_name=$encodedArtist&track_name=$encodedTitle"
        Log.d(TAG, "lrclib search request: $searchUrl")

        val searchByArtistAndTitle = requestLrcLibArray(searchUrl)
        if (searchByArtistAndTitle.success) return searchByArtistAndTitle

        // Доп. fallback: ищем по одному названию, когда artist в тегах мусорный.
        val titleOnlyUrl = "$LRCLIB_SEARCH_URL?track_name=$encodedTitle"
        Log.d(TAG, "lrclib title-only search request: $titleOnlyUrl")
        return requestLrcLibArray(titleOnlyUrl)
    }

    private fun requestLrcLibObject(url: String): LyricsResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TrecMusic/2.1")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return LyricsResult(false, error = "Текст не найден")
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return LyricsResult(false, error = "Текст не найден")
            }

            val json = JSONObject(body)
            val text = normalizeLyrics(json.optString("plainLyrics"))
            if (text.isNotBlank()) {
                return LyricsResult(true, lyrics = text)
            }

            return LyricsResult(false, error = "Текст не найден")
        }
    }

    private fun requestLrcLibArray(url: String): LyricsResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TrecMusic/2.1")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return LyricsResult(false, error = "Текст не найден")
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return LyricsResult(false, error = "Текст не найден")
            }

            val arr = JSONArray(body)
            if (arr.length() == 0) {
                return LyricsResult(false, error = "Текст не найден")
            }

            val first = arr.optJSONObject(0) ?: return LyricsResult(false, error = "Текст не найден")
            val text = normalizeLyrics(first.optString("plainLyrics"))
            if (text.isNotBlank()) {
                return LyricsResult(true, lyrics = text)
            }

            return LyricsResult(false, error = "Текст не найден")
        }
    }
}


