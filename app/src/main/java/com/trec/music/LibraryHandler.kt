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
        return uris.mapNotNull { uriStr ->
            vm.playlist.find { it.uri.toString() == uriStr }
        }
    }

    fun loadTrackCache() {
        val cached = vm.repository.getTrackCache()
        if (cached.isNotEmpty() && vm.playlist.isEmpty()) {
            vm.playlist.addAll(cached)
            vm.playlistUpdateTrigger++
        }
    }

    fun refreshLibrary(context: Context) {
        val folder = vm.repository.getSavedFolderUri()
        if (folder != null) {
            vm.viewModelScope.launch { vm.loadFromFolder(context, folder.toUri(), isAutoLoad = true) }
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
                vm.repository.clearLibraryData()
                vm.repository.clearTrackCache()
            }
            if (tracks.isNotEmpty()) {
                updatePlayerPlaylist(tracks)
                // Кэшируем треки на фоне, чтобы не тормозить UI
                vm.viewModelScope.launch(Dispatchers.IO) {
                    vm.repository.saveTrackCache(tracks)
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
                updatePlayerPlaylist(tracks)

                // Сохранение кэша тоже уводим в фон
                withContext(Dispatchers.IO) {
                    vm.repository.saveTrackCache(tracks)
                }
            }
            vm.isLoading = false
        }
    }

    private fun updatePlayerPlaylist(tracks: List<TrecTrackEnhanced>) {
        // Мы обновляем ТОЛЬКО визуальный список для UI.
        vm.playlist.clear()
        vm.playlist.addAll(tracks)

        // 🚨 ФИКС ФАТАЛЬНОГО КРАША: 🚨
        // Блок p.setMediaItems(items) удален.
        // Загрузка тысяч элементов в MediaController при старте вызывала TransactionTooLargeException.
        // Плеер получит свои треки в момент, когда пользователь нажмет на песню (через playTrackFromPlaylist)
        // или когда восстановится последняя прослушанная песня (restoreLastTrack).
    }

    fun deleteFileFromDevice(context: Context, track: TrecTrackEnhanced) {
        // Удаление файла — это дисковая операция, убираем ее из Main Thread
        vm.viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
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

            // Обновляем UI и коллекции на главном потоке
            if (success) {
                vm.playlist.remove(track)

                withContext(Dispatchers.IO) {
                    vm.userPlaylists.forEach { plName ->
                        val tracks = vm.repository.getTracksInPlaylist(plName)
                        if (tracks.contains(track.uri.toString())) {
                            vm.repository.removeTrackFromPlaylist(plName, track.uri.toString())
                        }
                    }
                    vm.repository.saveTrackCache(vm.playlist)
                }

                vm.playlistUpdateTrigger++
                Toast.makeText(context, "Файл удалён", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Ошибка удаления: нужны права", Toast.LENGTH_LONG).show()
            }
        }
    }
}