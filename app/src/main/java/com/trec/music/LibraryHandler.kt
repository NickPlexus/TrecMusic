package com.trec.music.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.trec.music.data.TrecTrackEnhanced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryHandler(private val vm: MusicViewModel) {

    fun refreshPlaylists() {
        val loadedSet = vm.repository.getPlaylistNames()
        val loaded = loadedSet.toList().sortedBy { it.lowercase() }
        val storedOrder = vm.repository.getPlaylistOrder()

        val merged = ArrayList<String>(loaded.size)
        storedOrder.forEach { if (loadedSet.contains(it)) merged.add(it) }
        loaded.forEach { if (!merged.contains(it)) merged.add(it) }

        vm.userPlaylists.clear()
        vm.userPlaylists.addAll(merged)
        vm.repository.savePlaylistOrder(merged)
        vm.playlistUpdateTrigger++
    }

    fun createPlaylist(name: String) { vm.repository.createPlaylist(name); refreshPlaylists() }
    fun deletePlaylist(name: String) { vm.repository.deletePlaylist(name); refreshPlaylists() }
    fun renamePlaylist(old: String, new: String) { vm.repository.renamePlaylist(old, new); refreshPlaylists() }

    fun addTrackToPlaylist(name: String, uri: String) {
        vm.repository.addTrackToPlaylist(name, uri)
        vm.playlistUpdateTrigger++
    }

    fun removeTrackFromPlaylist(name: String, uri: String) {
        vm.repository.removeTrackFromPlaylist(name, uri)
        vm.playlistUpdateTrigger++
    }

    fun moveTrackInPlaylist(playlistName: String, fromIndex: Int, toIndex: Int) {
        try {
            val visibleTracks = getPlaylistTracks(playlistName)
            if (fromIndex !in visibleTracks.indices || toIndex !in visibleTracks.indices) return

            val fromUri = visibleTracks[fromIndex].uri.toString()
            val toUri = visibleTracks[toIndex].uri.toString()

            val storedUris = vm.repository.getTracksInPlaylist(playlistName).toMutableList()
            val realFrom = storedUris.indexOf(fromUri)
            val realTo = storedUris.indexOf(toUri)
            if (realFrom == -1 || realTo == -1) return

            val moved = storedUris.removeAt(realFrom)
            storedUris.add(realTo.coerceIn(0, storedUris.size), moved)

            vm.repository.replaceTracksInPlaylist(playlistName, storedUris)
            vm.playlistUpdateTrigger++
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun movePlaylist(fromIndex: Int, toIndex: Int) {
        if (fromIndex in vm.userPlaylists.indices && toIndex in vm.userPlaylists.indices) {
            val item = vm.userPlaylists.removeAt(fromIndex)
            vm.userPlaylists.add(toIndex, item)
        }
    }

    fun persistPlaylistOrder() {
        vm.repository.savePlaylistOrder(vm.userPlaylists.toList())
    }

    fun getPlaylistTracks(name: String): List<TrecTrackEnhanced> {
        val uris = vm.repository.getTracksInPlaylist(name)
        val tracksSnapshot = vm.playlistSnapshot()
        val archivedUris = vm.archivedTrackUrisSnapshot()
        return uris.mapNotNull { uriStr ->
            tracksSnapshot.find { it.uri.toString() == uriStr && !archivedUris.contains(uriStr) }
        }
    }

    fun loadTrackCache() {
        vm.viewModelScope.launch {
            val cached = vm.repository.getTrackCache()
            if (cached.isNotEmpty() && vm.playlist.isEmpty()) {
                // Иногда MediaStore/кэш могут содержать дубли одного и того же Uri → это ломает Lazy списки (key collision).
                val archivedUris = vm.archivedTrackUrisSnapshot()
                val unique = cached
                    .distinctBy { it.uri.toString() }
                    .filterNot { archivedUris.contains(it.uri.toString()) }
                vm.playlist.addAll(unique)
                vm.playlistUpdateTrigger++
            }
        }
    }

    fun refreshLibrary(context: Context) {
        val folder = vm.repository.getSavedFolderUri()
        if (folder != null) {
            // Убрали viewModelScope.launch
            vm.loadFromFolder(context, folder.toUri(), isAutoLoad = true)
        } else {
            loadFromMediaStore(context)
        }
    }

    suspend fun loadFromFolder(context: Context, folderUri: Uri, isAutoLoad: Boolean = false) {
        vm.isLoading = true

        // Переносим тяжелое чтение файлов в фоновый поток (IO)
        val tracks = withContext(Dispatchers.IO) {
            if (!isAutoLoad) vm.repository.saveFolderUri(folderUri)
            vm.repository.scanTracks(folderUri)
        }

        // Обновляем UI состояния строго в главном потоке
        withContext(Dispatchers.Main) {
            if (tracks.isEmpty() && isAutoLoad) {
                withContext(Dispatchers.IO) {
                    vm.repository.clearLibraryData()
                    vm.repository.clearTrackCache()
                }
            }
            if (tracks.isNotEmpty()) {
                val used = updatePlayerPlaylist(tracks)
                // Кэшируем треки на фоне, чтобы не тормозить UI
                vm.viewModelScope.launch(Dispatchers.IO) {
                    vm.repository.saveTrackCache(used)
                }
            }
            vm.isLoading = false
        }
    }

    fun loadFromMediaStore(context: Context) {
        vm.viewModelScope.launch {
            vm.isLoading = true

            // Читаем базу данных MediaStore в фоне
            val tracks = withContext(Dispatchers.IO) {
                vm.repository.scanDeviceLibrary()
            }

            if (tracks.isNotEmpty()) {
                val used = updatePlayerPlaylist(tracks)

                // Сохранение кэша тоже уводим в фон
                withContext(Dispatchers.IO) {
                    vm.repository.saveTrackCache(used)
                }
            }
            vm.isLoading = false
        }
    }

    private fun updatePlayerPlaylist(tracks: List<TrecTrackEnhanced>): List<TrecTrackEnhanced> {
        // Мы обновляем ТОЛЬКО визуальный список для UI.
        // Дедуп по Uri важен: одинаковые ключи в LazyColumn/LazyRow приводят к крэшу Compose.
        val archivedUris = vm.archivedTrackUrisSnapshot()
        val unique = tracks
            .distinctBy { it.uri.toString() }
            .filterNot { archivedUris.contains(it.uri.toString()) }
        vm.playlist.clear()
        vm.playlist.addAll(unique)
        vm.playlistUpdateTrigger++

        // 🚨 ВАЖНО: НЕ загружаем обложки для всех треков!
        // Обложки будут загружаться ТОЛЬКО для текущего трека при его воспроизведении
        // и для треков, которые отображаются на экране (если нужно, то в UI-компонентах)

        // Убираем вызов ensureCoverForTrack для всех треков
        return unique
    }

    fun deleteFileFromDevice(context: Context, track: TrecTrackEnhanced) {
        deleteFilesFromDevice(context, listOf(track))
    }

    fun deleteFilesFromDevice(context: Context, tracks: List<TrecTrackEnhanced>) {
        val uniqueTracks = tracks.distinctBy { it.uri.toString() }
        if (uniqueTracks.isEmpty()) return

        vm.viewModelScope.launch {
            val successfulTracks = withContext(Dispatchers.IO) {
                uniqueTracks.filter { track ->
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.contentResolver.delete(track.uri, null, null)
                            true
                        } else {
                            val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, track.uri)
                            docFile?.delete() == true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
            }

            // Обновляем UI и коллекции на главном потоке
            if (successfulTracks.isNotEmpty()) {
                val deletedUris = successfulTracks.map { it.uri.toString() }.toSet()
                vm.playlist.removeAll { deletedUris.contains(it.uri.toString()) }
                vm.archivedTracks.removeAll { deletedUris.contains(it.uri.toString()) }
                vm.favoriteTracks.removeAll(deletedUris)
                if (vm.currentTrackUri?.toString() in deletedUris) vm.isCurrentTrackFav = false
                vm.repository.saveFavorites(vm.favoriteTracksSnapshot())

                // Снимаем снепшот ДО перехода в IO, иначе ConcurrentModificationException
                val playlistsSnapshot = vm.userPlaylists.toList()
                val trackCacheSnapshot = vm.playlistSnapshot()

                withContext(Dispatchers.IO) {
                    deletedUris.forEach { uri -> vm.repository.removeArchivedTrack(uri) }
                    playlistsSnapshot.forEach { plName ->
                        val tracks = vm.repository.getTracksInPlaylist(plName)
                        deletedUris.forEach { uri ->
                            if (tracks.contains(uri)) {
                                vm.repository.removeTrackFromPlaylist(plName, uri)
                            }
                        }
                    }
                    vm.repository.saveTrackCache(trackCacheSnapshot)
                }

                deletedUris.forEach { uri -> vm.removeTrackFromActiveQueue(uri) }
                vm.playlistUpdateTrigger++
                val message = if (successfulTracks.size == 1) {
                    "Файл удалён"
                } else {
                    "Удалено файлов: ${successfulTracks.size}"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Ошибка удаления: нужны права", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun archiveTrack(context: Context, track: TrecTrackEnhanced) {
        archiveTracks(context, listOf(track))
    }

    fun archiveTracks(context: Context, tracks: List<TrecTrackEnhanced>) {
        val uniqueTracks = tracks
            .distinctBy { it.uri.toString() }
            .filterNot { vm.archivedTrackUrisSnapshot().contains(it.uri.toString()) }
        if (uniqueTracks.isEmpty()) {
            Toast.makeText(context, "Треки уже в архиве", Toast.LENGTH_SHORT).show()
            return
        }

        vm.viewModelScope.launch {
            val playlistsSnapshot = vm.userPlaylists.toList()
            val archivedUris = uniqueTracks.map { it.uri.toString() }.toSet()

            withContext(Dispatchers.IO) {
                uniqueTracks.forEach { track -> vm.repository.archiveTrack(track) }
                playlistsSnapshot.forEach { plName ->
                    val tracksInPlaylist = vm.repository.getTracksInPlaylist(plName)
                    archivedUris.forEach { uri ->
                        if (tracksInPlaylist.contains(uri)) {
                            vm.repository.removeTrackFromPlaylist(plName, uri)
                        }
                    }
                }
            }

            vm.playlist.removeAll { archivedUris.contains(it.uri.toString()) }
            vm.archivedTracks.removeAll { archivedUris.contains(it.uri.toString()) }
            vm.archivedTracks.addAll(0, uniqueTracks)

            if (vm.favoriteTracks.removeAll(archivedUris)) {
                if (vm.currentTrackUri?.toString() in archivedUris) vm.isCurrentTrackFav = false
                vm.repository.saveFavorites(vm.favoriteTracksSnapshot())
            }

            val trackCacheSnapshot = vm.playlistSnapshot()
            withContext(Dispatchers.IO) {
                vm.repository.saveTrackCache(trackCacheSnapshot)
            }

            archivedUris.forEach { uri -> vm.removeTrackFromActiveQueue(uri) }
            vm.playlistUpdateTrigger++
            val message = if (uniqueTracks.size == 1) {
                "Трек отправлен в архив"
            } else {
                "Отправлено в архив: ${uniqueTracks.size}"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun restoreArchivedTrack(context: Context, track: TrecTrackEnhanced) {
        val trackUriStr = track.uri.toString()
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) {
                vm.repository.unarchiveTrack(trackUriStr)
            }

            vm.archivedTracks.removeAll { it.uri.toString() == trackUriStr }
            if (vm.playlist.none { it.uri.toString() == trackUriStr }) {
                vm.playlist.add(track)
            }

            val trackCacheSnapshot = vm.playlistSnapshot()
            withContext(Dispatchers.IO) {
                vm.repository.saveTrackCache(trackCacheSnapshot)
            }

            vm.playlistUpdateTrigger++
            Toast.makeText(context, "Трек возвращён из архива", Toast.LENGTH_SHORT).show()
        }
    }
}
