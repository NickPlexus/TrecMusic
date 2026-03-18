package com.trec.music.data.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CoverArtService {

    companion object {
        private const val TAG = "CoverArtService"
        private const val ITUNES_URL = "https://itunes.apple.com/search"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun fetchCoverUrl(artist: String?, title: String?, album: String?): String? {
        val safeArtist = artist?.trim().orEmpty()
        val safeTitle = title?.trim().orEmpty()
        if (safeTitle.isBlank()) return null

        val term = listOf(safeArtist, safeTitle, album ?: "")
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        if (term.isBlank()) return null

        return try {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val url = "$ITUNES_URL?term=$encoded&entity=song&limit=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TrecMusic/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return null
                if (results.length() == 0) return null
                val item = results.optJSONObject(0) ?: return null
                val art = item.optString("artworkUrl100", "")
                if (art.isBlank()) return null
                art.replace("100x100bb", "600x600bb")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cover fetch failed: ${e.message}")
            null
        }
    }
}
