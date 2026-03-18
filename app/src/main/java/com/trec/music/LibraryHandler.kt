package com.trec.music.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.trec.music.data.TrecTrackEnhanced
import kotlinx.coroutines.launch

class LibraryHandler(private val vm: MusicViewModel) {

    fun refreshPlaylists() {
        val loaded = vm.repository.getPlaylistNames().toList().sorted()
        if (vm.userPlaylists.isEmpty()) {
            vm.userPlaylists.addAll(loaded)
        } else {
            loaded.forEach { if (!vm.userPlaylists.contains(it)) vm.userPlaylists.add(it) }
            vm.userPlaylists.removeAll { !loaded.contains(it) }
        }
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
        if (folder != null) vm.loadFromFolder(context, folder.toUri(), isAutoLoad = true)
        else loadFromMediaStore(context)
    }

    suspend fun loadFromFolder(context: Context, folderUri: Uri, isAutoLoad: Boolean = false) {
        vm.isLoading = true
        if (!isAutoLoad) vm.repository.saveFolderUri(folderUri)
        val tracks = vm.repository.scanTracks(folderUri)
        if (tracks.isEmpty() && isAutoLoad) {
            vm.repository.clearLibraryData()
            vm.repository.clearTrackCache()
        }
        if (tracks.isNotEmpty()) updatePlayerPlaylist(tracks)
        vm.isLoading = false
    }

    fun loadFromMediaStore(context: Context) {
        vm.viewModelScope.launch {
            vm.isLoading = true
            val tracks = vm.repository.scanDeviceLibrary()
            if (tracks.isNotEmpty()) updatePlayerPlaylist(tracks)
            vm.isLoading = false
        }
    }

    private fun updatePlayerPlaylist(tracks: List<TrecTrackEnhanced>) {
        vm.playlist.clear()
        vm.playlist.addAll(tracks)
        vm.repository.saveTrackCache(tracks)

        vm.player?.let { p ->
            if (p.mediaItemCount == 0) {
                val items = tracks.map {
                    MediaItem.Builder().setMediaId(it.uri.toString()).setUri(it.uri)
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(it.title).build()).build()
                }
                p.setMediaItems(items)
                p.prepare()
            }
        }
    }

    fun deleteFileFromDevice(context: Context, track: TrecTrackEnhanced) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.delete(track.uri, null, null)
            } else {
                val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, track.uri)
                docFile?.delete()
            }
            vm.playlist.remove(track)
            vm.userPlaylists.forEach { plName ->
                val tracks = vm.repository.getTracksInPlaylist(plName)
                if (tracks.contains(track.uri.toString())) {
                    vm.repository.removeTrackFromPlaylist(plName, track.uri.toString())
                }
            }
            vm.playlistUpdateTrigger++
            vm.repository.saveTrackCache(vm.playlist)
            Toast.makeText(context, "Файл удален", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Ошибка удаления: нужны права", Toast.LENGTH_LONG).show()
        }
    }
}




