package com.trec.music.data.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RemoteTrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null
)

class TrackMetadataService {

    companion object {
        private const val TAG = "TrackMetadataService"
        private const val ITUNES_URL = "https://itunes.apple.com/search"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun fetchMetadata(artist: String?, title: String?, album: String?): RemoteTrackMetadata? {
        val safeTitle = title?.trim().orEmpty()
        if (safeTitle.isBlank()) return null

        val term = listOf(artist?.trim().orEmpty(), safeTitle, album?.trim().orEmpty())
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

                val releaseDate = item.optString("releaseDate", "")
                val year = releaseDate.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()

                RemoteTrackMetadata(
                    title = item.optString("trackName", "").ifBlank { null },
                    artist = item.optString("artistName", "").ifBlank { null },
                    album = item.optString("collectionName", "").ifBlank { null },
                    genre = item.optString("primaryGenreName", "").ifBlank { null },
                    year = year
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "metadata fetch failed: ${e.message}")
            null
        }
    }
}

