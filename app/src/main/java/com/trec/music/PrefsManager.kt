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

    fun getRecorderFeatureEnabled(): Boolean = prefs.getBoolean("feat_recorder", true)

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

    fun createPlaylist(name: String) {
        val names = getPlaylistNames()
        if (!names.contains(name)) {
            names.add(name)
            prefs.edit { putStringSet("playlist_names", names) }
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
    }

    fun renamePlaylist(oldName: String, newName: String) {
        val tracks = getTracksInPlaylist(oldName)
        deletePlaylist(oldName)
        createPlaylist(newName)
        saveTracksToPlaylist(newName, tracks)
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




