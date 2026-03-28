// PrefsManager.kt
//
// ТИП: Data Persistence (SharedPreferences Wrapper)
//
// НАЗНАЧЕНИЕ:
// Управляет сохранением настроек, состояния плейлистов и избранного.
//
// ВЕРСИЯ: ULTIMATE
// Включает настройки для:
// - DSP (Global + Granular: Reverse, Karaoke, Speed, Effects)
// - Audio Expert (Crossfade, Mono, Balance, Skip Silence, Focus)
// - UI (Dynamic Colors, Screen On, Filename)
// - Modules (Radio, Recorder)

package com.trec.music

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.trec.music.data.TrecTrackEnhanced
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("trec_prefs", Context.MODE_PRIVATE)

    // ==========================================
    // GENERAL SETTINGS
    // ==========================================

    fun saveFolderUri(uri: String) {
        prefs.edit { putString("default_folder", uri) }
    }

    fun getFolderUri(): String? = prefs.getString("default_folder", null)

    fun saveFavorites(favorites: Set<String>) {
        prefs.edit { putStringSet("fav_tracks", favorites) }
    }

    fun getFavorites(): MutableSet<String> {
        return prefs.getStringSet("fav_tracks", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    // ==========================================
    // COVER CACHE (URL + Color)
    // ==========================================

    private fun shortSha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append(String.format(Locale.US, "%02x", bytes[i]))
        return sb.toString()
    }

    fun getCachedCoverUrl(cacheKey: String): String? {
        val h = shortSha256Hex(cacheKey)
        return prefs.getString("cover_url_$h", null)
    }

    fun saveCachedCoverUrl(cacheKey: String, url: String) {
        val h = shortSha256Hex(cacheKey)
        val keySet = prefs.getStringSet("cover_cache_keys", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!keySet.contains(h)) keySet.add(h)
        prefs.edit {
            putStringSet("cover_cache_keys", keySet)
            putString("cover_url_$h", url)
        }
    }

    fun getCachedCoverColorArgb(cacheKey: String): Int? {
        val h = shortSha256Hex(cacheKey)
        val k = "cover_color_$h"
        return if (prefs.contains(k)) prefs.getInt(k, 0) else null
    }

    fun saveCachedCoverColorArgb(cacheKey: String, argb: Int) {
        val h = shortSha256Hex(cacheKey)
        val keySet = prefs.getStringSet("cover_cache_keys", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!keySet.contains(h)) keySet.add(h)
        prefs.edit {
            putStringSet("cover_cache_keys", keySet)
            putInt("cover_color_$h", argb)
        }
    }

    fun clearCoverCache() {
        val keySet = prefs.getStringSet("cover_cache_keys", emptySet())?.toSet() ?: emptySet()
        prefs.edit {
            keySet.forEach { h ->
                remove("cover_url_$h")
                remove("cover_color_$h")
            }
            remove("cover_cache_keys")
        }
    }

    // ==========================================
    // MOST PLAYED (Per-track counter)
    // ==========================================

    fun incrementTrackPlayCount(uri: String) {
        if (uri.isBlank()) return
        val h = shortSha256Hex(uri)
        val keys = prefs.getStringSet("playcount_keys", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!keys.contains(h)) keys.add(h)

        val cntKey = "playcount_cnt_$h"
        val next = prefs.getInt(cntKey, 0) + 1
        prefs.edit {
            putStringSet("playcount_keys", keys)
            putString("playcount_uri_$h", uri)
            putInt(cntKey, next)
        }
    }

    fun getTopPlayedUris(limit: Int): List<Pair<String, Int>> {
        val keys = prefs.getStringSet("playcount_keys", emptySet())?.toSet() ?: emptySet()
        if (keys.isEmpty() || limit <= 0) return emptyList()

        val list = ArrayList<Pair<String, Int>>(keys.size)
        keys.forEach { h ->
            val uri = prefs.getString("playcount_uri_$h", null) ?: return@forEach
            val cnt = prefs.getInt("playcount_cnt_$h", 0)
            if (cnt > 0) list.add(uri to cnt)
        }

        list.sortByDescending { it.second }
        return if (list.size > limit) list.subList(0, limit) else list
    }

    // ==========================================
    // USAGE STATS (Listening)
    // ==========================================

    private fun listenDayKey(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    private fun ensureListenDay() {
        val today = listenDayKey()
        val stored = prefs.getString("listen_day_key", null)
        if (stored == today) return
        prefs.edit {
            putString("listen_day_key", today)
            putLong("listen_today_ms", 0L)
            putInt("listen_today_sessions", 0)
            putInt("listen_today_tracks", 0)
        }
    }

    fun addListeningTime(ms: Long) {
        if (ms <= 0) return
        ensureListenDay()
        val total = prefs.getLong("listen_total_ms", 0L) + ms
        val today = prefs.getLong("listen_today_ms", 0L) + ms
        prefs.edit {
            putLong("listen_total_ms", total)
            putLong("listen_today_ms", today)
        }
    }

    fun incrementListenSession() {
        ensureListenDay()
        val total = prefs.getInt("listen_total_sessions", 0) + 1
        val today = prefs.getInt("listen_today_sessions", 0) + 1
        prefs.edit {
            putInt("listen_total_sessions", total)
            putInt("listen_today_sessions", today)
        }
    }

    fun incrementTracksStarted() {
        ensureListenDay()
        val total = prefs.getInt("listen_total_tracks", 0) + 1
        val today = prefs.getInt("listen_today_tracks", 0) + 1
        prefs.edit {
            putInt("listen_total_tracks", total)
            putInt("listen_today_tracks", today)
        }
    }

    fun getTotalListeningMs(): Long = prefs.getLong("listen_total_ms", 0L)

    fun getTodayListeningMs(): Long {
        ensureListenDay()
        return prefs.getLong("listen_today_ms", 0L)
    }

    fun getTotalListenSessions(): Int = prefs.getInt("listen_total_sessions", 0)

    fun getTodayListenSessions(): Int {
        ensureListenDay()
        return prefs.getInt("listen_today_sessions", 0)
    }

    fun getTotalTracksStarted(): Int = prefs.getInt("listen_total_tracks", 0)

    fun getTodayTracksStarted(): Int {
        ensureListenDay()
        return prefs.getInt("listen_today_tracks", 0)
    }

    // ==========================================
    // VISUALS & BEHAVIOR
    // ==========================================

    fun saveNeedleEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("vinyl_needle", enabled) }
    }

    fun getNeedleEnabled(): Boolean = prefs.getBoolean("vinyl_needle", true)

    fun saveScratchEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("scratch_sound", enabled) }
    }

    fun getScratchEnabled(): Boolean = prefs.getBoolean("scratch_sound", true)

    fun saveShakeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("shake_enabled", enabled) }
    }

    fun getShakeEnabled(): Boolean = prefs.getBoolean("shake_enabled", true)

    fun saveDynamicColorEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("dynamic_color", enabled) }
    }

    fun getDynamicColorEnabled(): Boolean = prefs.getBoolean("dynamic_color", true)

    fun saveStaticColor(colorArgb: Int) {
        prefs.edit { putInt("static_color", colorArgb) }
    }

    // Default: TrecRed (0xFFD50000 = -2818048)
    fun getStaticColor(): Int = prefs.getInt("static_color", -2818048)

    fun saveVinylModeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("vinyl_mode", enabled) }
    }

    fun getVinylModeEnabled(): Boolean = prefs.getBoolean("vinyl_mode", true)

    // ==========================================
    // MODULES (FEATURE FLAGS)
    // ==========================================

    // 1. Recorder
    fun saveRecorderFeatureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("feat_recorder", enabled) }
    }

    fun getRecorderFeatureEnabled(): Boolean = prefs.getBoolean("feat_recorder", false)

    // 2. Radio
    fun saveRadioFeatureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("feat_radio", enabled) }
    }

    fun getRadioFeatureEnabled(): Boolean = prefs.getBoolean("feat_radio", false)

    // Радио: пользовательские станции и скрытые дефолтные
    fun saveCustomRadioStations(json: String) {
        prefs.edit { putString("radio_custom_stations", json) }
    }

    fun getCustomRadioStationsJson(): String? = prefs.getString("radio_custom_stations", null)

    fun saveHiddenRadioStations(ids: Set<String>) {
        prefs.edit { putStringSet("radio_hidden_ids", ids) }
    }

    fun getHiddenRadioStations(): MutableSet<String> {
        return prefs.getStringSet("radio_hidden_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    // 3. DSP Global
    fun saveDspFeatureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("feat_dsp", enabled) }
    }

    fun getDspFeatureEnabled(): Boolean = prefs.getBoolean("feat_dsp", true)

    // ==========================================
    // GRANULAR DSP SETTINGS (Раздельное управление)
    // ==========================================

    fun saveReverseFeatureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("feat_dsp_reverse", enabled) }
    }

    fun getReverseFeatureEnabled(): Boolean = prefs.getBoolean("feat_dsp_reverse", true)

    fun saveKaraokeFeatureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("feat_dsp_karaoke", enabled) }
    }

    fun getKaraokeFeatureEnabled(): Boolean = prefs.getBoolean("feat_dsp_karaoke", true)

    fun saveSpeedFeatureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("feat_dsp_speed", enabled) }
    }

    fun getSpeedFeatureEnabled(): Boolean = prefs.getBoolean("feat_dsp_speed", true)

    // NEW: Кнопка "Эффекты" (Пресеты)
    fun saveEffectsFeatureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("feat_dsp_effects", enabled) }
    }

    fun getEffectsFeatureEnabled(): Boolean = prefs.getBoolean("feat_dsp_effects", true)

    // ==========================================
    // AUDIO EXPERT SETTINGS (ULTIMATE)
    // ==========================================

    // 1. Skip Silence
    fun saveSkipSilence(enabled: Boolean) {
        prefs.edit { putBoolean("audio_skip_silence", enabled) }
    }

    fun getSkipSilence(): Boolean = prefs.getBoolean("audio_skip_silence", false)

    // 2. Crossfade (ms, 0 = off)
    fun saveCrossfade(ms: Int) {
        prefs.edit { putInt("audio_crossfade", ms) }
    }

    fun getCrossfade(): Int = prefs.getInt("audio_crossfade", 0)

    // 3. Mono Audio
    fun saveMonoAudio(enabled: Boolean) {
        prefs.edit { putBoolean("audio_mono", enabled) }
    }

    fun getMonoAudio(): Boolean = prefs.getBoolean("audio_mono", false)

    // 4. Balance (-1.0 Left ... 1.0 Right)
    fun saveAudioBalance(balance: Float) {
        prefs.edit { putFloat("audio_balance", balance) }
    }

    fun getAudioBalance(): Float = prefs.getFloat("audio_balance", 0f)

    // 5. Audio Focus Behavior (true = ignore others)
    fun saveAudioFocusIgnore(enabled: Boolean) {
        prefs.edit { putBoolean("audio_focus_ignore", enabled) }
    }

    fun getAudioFocusIgnore(): Boolean = prefs.getBoolean("audio_focus_ignore", false)

    // ==========================================
    // UI & BEHAVIOR SETTINGS
    // ==========================================

    // 1. Keep Screen On
    fun saveKeepScreenOn(enabled: Boolean) {
        prefs.edit { putBoolean("ui_keep_screen", enabled) }
    }

    fun getKeepScreenOn(): Boolean = prefs.getBoolean("ui_keep_screen", false)

    // 2. Show File Extensions (.mp3)
    fun saveShowFilename(enabled: Boolean) {
        prefs.edit { putBoolean("ui_show_filename", enabled) }
    }

    fun getShowFilename(): Boolean = prefs.getBoolean("ui_show_filename", false)

    // 3. Lock Screen Art
    fun saveLockScreenArt(enabled: Boolean) {
        prefs.edit { putBoolean("ui_lock_screen", enabled) }
    }

    fun getLockScreenArt(): Boolean = prefs.getBoolean("ui_lock_screen", true)


    // ==========================================
    // STATE RESTORATION
    // ==========================================

    fun saveLastState(uri: String, position: Long) {
        prefs.edit {
            putString("last_track_uri", uri)
            putLong("last_track_pos", position)
        }
    }

    fun getLastTrackUri(): String? = prefs.getString("last_track_uri", null)
    fun getLastTrackPos(): Long = prefs.getLong("last_track_pos", 0L)

    // ==========================================
    // BLACKLIST
    // ==========================================

    fun addToBlacklist(uri: String) {
        val currentList = getBlacklist()
        currentList.add(uri)
        prefs.edit { putStringSet("broken_tracks", currentList) }
    }

    fun getBlacklist(): MutableSet<String> {
        return prefs.getStringSet("broken_tracks", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    // ==========================================
    // PLAYLISTS SYSTEM (JSON ORDERED)
    // ==========================================

    fun getPlaylistNames(): MutableSet<String> {
        return prefs.getStringSet("playlist_names", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun readPlaylistOrder(): MutableList<String> {
        val raw = prefs.getString("playlist_order_json", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "").trim()
                if (name.isNotBlank()) result.add(name)
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun getPlaylistOrder(): List<String> {
        val order = readPlaylistOrder()
        if (order.isNotEmpty()) return order
        return getPlaylistNames().toList().sortedBy { it.lowercase() }
    }

    fun savePlaylistOrder(order: List<String>) {
        val arr = JSONArray()
        order.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { arr.put(it) }
        prefs.edit { putString("playlist_order_json", arr.toString()) }
    }

    fun createPlaylist(name: String) {
        val names = getPlaylistNames()
        if (!names.contains(name)) {
            names.add(name)
            prefs.edit { putStringSet("playlist_names", names) }
        }

        val order = readPlaylistOrder()
        if (order.isNotEmpty()) {
            if (!order.contains(name)) {
                order.add(name)
                savePlaylistOrder(order)
            }
        } else {
            // If order wasn't stored yet, initialize it in stable (alphabetical) order.
            savePlaylistOrder(names.toList().sortedBy { it.lowercase() })
        }
    }

    fun deletePlaylist(name: String) {
        val names = getPlaylistNames()
        names.remove(name)
        prefs.edit {
            putStringSet("playlist_names", names)
            remove("pl_json_$name")
            remove("pl_$name")
        }

        val order = readPlaylistOrder()
        if (order.remove(name)) savePlaylistOrder(order)
    }

    fun renamePlaylist(oldName: String, newName: String) {
        if (oldName == newName) return
        val names = getPlaylistNames()
        if (!names.contains(oldName)) {
            createPlaylist(newName)
            return
        }

        val tracks = getTracksInPlaylist(oldName)
        names.remove(oldName)
        names.add(newName)
        prefs.edit {
            putStringSet("playlist_names", names)
            remove("pl_json_$oldName")
            remove("pl_$oldName")
        }
        saveTracksToPlaylist(newName, tracks)

        val order = readPlaylistOrder()
        if (order.isNotEmpty()) {
            val idx = order.indexOf(oldName)
            if (idx >= 0) order[idx] = newName
            else order.add(newName)
            savePlaylistOrder(order)
        } else {
            savePlaylistOrder(names.toList().sortedBy { it.lowercase() })
        }
    }

    fun addTrackToPlaylist(playlistName: String, uri: String) {
        val currentTracks = getTracksInPlaylist(playlistName).toMutableList()
        if (!currentTracks.contains(uri)) {
            currentTracks.add(uri)
            saveTracksToPlaylist(playlistName, currentTracks)
        }
    }

    fun removeTrackFromPlaylist(playlistName: String, uri: String) {
        val currentTracks = getTracksInPlaylist(playlistName).toMutableList()
        currentTracks.remove(uri)
        saveTracksToPlaylist(playlistName, currentTracks)
    }

    fun replaceTracksInPlaylist(playlistName: String, tracks: List<String>) {
        saveTracksToPlaylist(playlistName, tracks)
    }

    fun getTracksInPlaylist(playlistName: String): List<String> {
        val jsonString = prefs.getString("pl_json_$playlistName", null)
        if (jsonString != null) {
            val list = mutableListOf<String>()
            try {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    list.add(jsonArray.getString(i))
                }
                return list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback for migration
        val oldSet = prefs.getStringSet("pl_$playlistName", null)
        if (oldSet != null) {
            val list = oldSet.toList()
            saveTracksToPlaylist(playlistName, list)
            return list
        }

        return emptyList()
    }

    private fun saveTracksToPlaylist(playlistName: String, tracks: List<String>) {
        val jsonArray = JSONArray()
        tracks.forEach { jsonArray.put(it) }
        prefs.edit {
            putString("pl_json_$playlistName", jsonArray.toString())
        }
    }

    
    // ==========================================
    // TRACK CACHE (FAST BOOTSTRAP)
    // ==========================================

    fun saveTrackCache(tracks: List<TrecTrackEnhanced>) {
        val root = JSONArray()
        tracks.forEach { t ->
            val o = JSONObject()
            o.put("uri", t.uri.toString())
            o.put("title", t.title)
            o.put("artist", t.artist ?: JSONObject.NULL)
            o.put("album", t.album ?: JSONObject.NULL)
            o.put("durationMs", t.durationMs)
            o.put("dateAdded", t.dateAdded)
            o.put("mimeType", t.mimeType ?: JSONObject.NULL)
            root.put(o)
        }
        prefs.edit { putString("track_cache_v1", root.toString()) }
    }

    fun getTrackCache(): List<TrecTrackEnhanced> {
        val raw = prefs.getString("track_cache_v1", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<TrecTrackEnhanced>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uriString = o.optString("uri", "")
                if (uriString.isBlank()) continue

                result.add(
                    TrecTrackEnhanced(
                        uri = Uri.parse(uriString),
                        title = o.optString("title", "Unknown Track"),
                        artist = o.optString("artist", "").takeIf { it.isNotBlank() },
                        album = o.optString("album", "").takeIf { it.isNotBlank() },
                        durationMs = o.optLong("durationMs", 0L),
                        dateAdded = o.optLong("dateAdded", 0L),
                        mimeType = o.optString("mimeType", "").takeIf { it.isNotBlank() }
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearTrackCache() {
        prefs.edit { remove("track_cache_v1") }
    }
    fun clearFolder() {
        prefs.edit {
            remove("default_folder")
            remove("last_track_uri")
            remove("last_track_pos")
            remove("broken_tracks")
        }
    }
}




