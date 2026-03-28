// LibraryRepository.kt
//
// ТИП: Repository (Data Layer)
//
// НАЗНАЧЕНИЕ:
// Единая точка доступа к данным приложения. Изолирует ViewModel от прямой работы
// с SharedPreferences (PrefsManager), файловой системой (LibraryScanner) и ContentResolver.
// Выполняет роль посредника (Proxy).

package com.trec.music.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.trec.music.PrefsManager
import com.trec.music.utils.LibraryScanner // ИМПОРТ ОБНОВЛЕН (теперь utils)
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryRepository(private val context: Context) {

    private val prefs = PrefsManager(context)

    fun getSavedFolderUri(): String? = prefs.getFolderUri()

    fun saveFolderUri(uri: Uri) {
        try {
            // Важно: сохраняем пермиссии на будущее (чтобы после перезагрузки был доступ к папке)
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            prefs.saveFolderUri(uri.toString())
        } catch (e: SecurityException) {
            e.printStackTrace()
            // Если не удалось взять персистентные права, все равно сохраняем строку
            prefs.saveFolderUri(uri.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Сканирование выбранной папки (SAF) - для флешек и конкретных папок
    suspend fun scanTracks(uri: Uri): List<TrecTrackEnhanced> = withContext(Dispatchers.IO) {
        return@withContext LibraryScanner.scanFolder(context, uri)
    }

    // Автоматическое сканирование всей музыки на устройстве (MediaStore) - дефолтное поведение
    suspend fun scanDeviceLibrary(): List<TrecTrackEnhanced> = withContext(Dispatchers.IO) {
        return@withContext LibraryScanner.scanMediaStore(context)
    }

    // --- Playlist Proxy Methods ---

    fun getPlaylistNames(): Set<String> = prefs.getPlaylistNames()

    fun getPlaylistOrder(): List<String> = prefs.getPlaylistOrder()

    fun savePlaylistOrder(order: List<String>) = prefs.savePlaylistOrder(order)

    fun createPlaylist(name: String) = prefs.createPlaylist(name)

    fun deletePlaylist(name: String) = prefs.deletePlaylist(name)

    fun renamePlaylist(old: String, new: String) = prefs.renamePlaylist(old, new)

    fun addTrackToPlaylist(name: String, uri: String) = prefs.addTrackToPlaylist(name, uri)

    fun removeTrackFromPlaylist(name: String, uri: String) = prefs.removeTrackFromPlaylist(name, uri)

    fun replaceTracksInPlaylist(name: String, tracks: List<String>) = prefs.replaceTracksInPlaylist(name, tracks)

    // ВАЖНО: PrefsManager теперь возвращает List<String> (JSON), поэтому порядок сохраняется.
    fun getTracksInPlaylist(name: String): List<String> = prefs.getTracksInPlaylist(name)

    fun getFavorites(): Set<String> = prefs.getFavorites()

    fun saveFavorites(favs: Set<String>) = prefs.saveFavorites(favs)

    fun getBlacklist(): Set<String> = prefs.getBlacklist()

    // --- State Saving ---

    fun saveLastState(uri: String, pos: Long) = prefs.saveLastState(uri, pos)

    fun getLastTrackUri(): String? = prefs.getLastTrackUri()

    fun getLastTrackPos(): Long = prefs.getLastTrackPos()

    fun clearLibraryData() = prefs.clearFolder()
    fun saveTrackCache(tracks: List<TrecTrackEnhanced>) = prefs.saveTrackCache(tracks)

    fun getTrackCache(): List<TrecTrackEnhanced> = prefs.getTrackCache()

    fun clearTrackCache() = prefs.clearTrackCache()
}

