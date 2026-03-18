// viewmodel/DspHandler.kt
//
// ТИП: Logic Handler (Audio Processing)
//
// НАЗНАЧЕНИЕ:
//
// ИЗМЕНЕНИЯ (NON-BLOCKING UX):

package com.trec.music.viewmodel

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import com.trec.music.data.AudioPresets
import com.trec.music.utils.AudioReverser
import com.trec.music.utils.VocalRemoverEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "DspHandler"

class DspHandler(private val vm: MusicViewModel) {

    private val processingMutex = Mutex()

    fun setupEqualizer(sessionId: Int) {
        try {
            if (vm.equalizer != null && vm.audioSessionId == sessionId) return

            vm.equalizer?.release()
            vm.audioSessionId = sessionId
            vm.equalizer = Equalizer(0, sessionId)
            vm.equalizer?.enabled = true

            applyPreset(vm.currentPresetName)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun applyPreset(name: String) {
        vm.currentPresetName = name
        val params = AudioPresets.getPlaybackParameters(name)

        vm.player?.playbackParameters = params
        vm.playbackSpeed = params.speed
        vm.playbackPitch = params.pitch

        if (vm.equalizer == null) setupEqualizer(0) // comment normalized
        AudioPresets.applyEqualizerSettings(vm.equalizer, name)
    }

    fun setSpeed(speed: Float) {
        vm.playbackSpeed = speed
        vm.player?.playbackParameters = PlaybackParameters(speed, vm.playbackPitch)
    }

    fun setPitch(pitch: Float) {
        vm.playbackPitch = pitch
        vm.player?.playbackParameters = PlaybackParameters(vm.playbackSpeed, pitch)
    }

    // --- VOCAL REMOVER (KARAOKE) ---

    fun toggleVocalRemover(context: Context) {
        val uriStr = vm.currentTrackUri?.toString() ?: return
        if (vm.brokenTracks.contains(uriStr)) return

        if (vm.instrumentalTrackPath != null) {
            vm.normalTrackUri?.let { restoreTrack(it, vm.player?.currentPosition ?: 0L) }
            vm.instrumentalTrackPath = null
            applyPreset(vm.currentPresetName) // comment normalized
            return
        }

        if (vm.isReversing) {
            vm.isReversing = false
            val pos = vm.player?.currentPosition ?: 0L
            val target = (vm.duration - pos).coerceAtLeast(0)
            vm.normalTrackUri?.let { restoreTrack(it, target) }
        }

        val uri = vm.normalTrackUri ?: vm.currentTrackUri ?: return

        // Защита от двойного клика
        if (vm.isVocalRemovalProcessing) return

        vm.backgroundGenJob?.cancel()

        vm.viewModelScope.launch {
            vm.isVocalRemovalProcessing = true


            val instFile = File(context.cacheDir, "inst_${uri.toString().hashCode()}.wav")

            val processingTrackUri = uri

            val resultPath: String? = processingMutex.withLock {
                try {
                    if (instFile.exists() && instFile.length() > 1000) {
                        // Файл уже есть в кэше
                        instFile.absolutePath
                    } else {
                        val result = withContext(Dispatchers.IO) {
                            VocalRemoverEngine.generateInstrumental(context, uri, instFile)
                        }
                        if (result.success) {
                            calculateCacheSize(context)
                            instFile.absolutePath
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Vocal Remover: ${result.methodUsed}", Toast.LENGTH_SHORT).show()
                                Toast.makeText(context, "Ошибка: ${result.error}", Toast.LENGTH_LONG).show()
                            }
                            null
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            vm.isVocalRemovalProcessing = false

            if (resultPath != null) {
                val currentPlaying = vm.normalTrackUri ?: vm.currentTrackUri
                if (currentPlaying == processingTrackUri) {
                    val resultFile = File(resultPath)
                    if (resultFile.exists() && resultFile.length() > 1000) {
                        vm.isInstrumentalReady = true
                        switchToInstrumentalTrack(File(resultPath))
                    } else {
                        Log.w(TAG, "Instrumental file not ready or too small: $resultPath")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Инструментал еще не готов", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // --- REVERSE ---

    fun toggleReverse(context: Context) {
        val uriStr = vm.currentTrackUri?.toString() ?: return
        if (vm.brokenTracks.contains(uriStr)) return

        if (vm.instrumentalTrackPath != null) {
            vm.normalTrackUri?.let { restoreTrack(it, vm.player?.currentPosition ?: 0L) }
            vm.instrumentalTrackPath = null
        }

        if (vm.isReversing) {
            vm.isReversing = false
            val pos = vm.player?.currentPosition ?: 0L
            val target = (vm.duration - pos).coerceAtLeast(0)
            vm.normalTrackUri?.let { restoreTrack(it, target) }
            return
        }

        val uri = vm.normalTrackUri ?: vm.currentTrackUri ?: return
        if (vm.duration > 15 * 60 * 1000) {
            Toast.makeText(context, "Трек слишком длинный для реверса", Toast.LENGTH_SHORT).show()
            return
        }

        if (vm.isGeneratingReverse) return

        vm.backgroundGenJob?.cancel()

        vm.viewModelScope.launch {
            vm.isGeneratingReverse = true

            val revFile = File(context.cacheDir, "rev_${uri.toString().hashCode()}.wav")
            val processingTrackUri = uri

            val resultPath: String? = processingMutex.withLock {
                try {
                    if (revFile.exists() && revFile.length() > 1000) {
                        revFile.absolutePath
                    } else {
                        val err = withContext(Dispatchers.IO) {
                            AudioReverser.reverseAudio(context, uri, revFile)
                        }
                        if (err == null) {
                            calculateCacheSize(context)
                            revFile.absolutePath
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Ошибка реверса: $err", Toast.LENGTH_SHORT).show()
                            }
                            null
                        }
                    }
                } catch (e: Exception) { e.printStackTrace(); null }
            }

            vm.isGeneratingReverse = false

            if (resultPath != null) {
                val currentPlaying = vm.normalTrackUri ?: vm.currentTrackUri
                if (currentPlaying == processingTrackUri) {
                    vm.isReverseReady = true
                    switchToReverseTrack(File(resultPath))
                }
            }
        }
    }

    private fun switchToReverseTrack(file: File) {
        val currentPos = vm.player?.currentPosition ?: 0L
        val duration = vm.duration.coerceAtLeast(1)

        // Новая позиция = Конец - Текущая
        val revPos = (duration - currentPos).coerceIn(0, duration)

        vm.reverseTrackPath = file.absolutePath
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(vm.reverseTrackPath!!)
            .build()

        vm.player?.setMediaItem(item)
        vm.player?.seekTo(revPos) // comment normalized
        vm.player?.prepare()
        vm.player?.play() // comment normalized

        vm.isReversing = true
    }

    private fun switchToInstrumentalTrack(file: File) {
        val pos = vm.player?.currentPosition ?: 0L

        vm.instrumentalTrackPath = Uri.fromFile(file).toString()
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(vm.instrumentalTrackPath ?: Uri.fromFile(file).toString()) // Null safety
            .build()

        vm.player?.setMediaItem(item)
        vm.player?.seekTo(pos)
        vm.player?.prepare()
        vm.player?.play()

        applyPreset("Normal") // comment normalized
    }

    private fun restoreTrack(uri: Uri, position: Long) {
        val tracksToUse = if (vm.currentPlaylistFilter != null)
            vm.libraryHandler.getPlaylistTracks(vm.currentPlaylistFilter!!)
        else
            vm.playlist.toList()

        val index = tracksToUse.indexOfFirst { it.uri == uri }

        if (index != -1) {
            val mediaItems = tracksToUse.map {
                MediaItem.Builder()
                    .setMediaId(it.uri.toString())
                    .setUri(it.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build())
                    .build()
            }
            vm.player?.setMediaItems(mediaItems)
            vm.player?.seekTo(index, position)
        } else {
            vm.player?.setMediaItem(MediaItem.fromUri(uri))
            vm.player?.seekTo(position)
        }
        vm.player?.prepare()
        vm.player?.play()
    }

    fun calculateCacheSize(context: Context) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            val files = context.cacheDir.listFiles { _, name -> (name.startsWith("rev_") || name.startsWith("inst_")) && name.endsWith(".wav") }
            val size = files?.sumOf { it.length() } ?: 0L
            withContext(Dispatchers.Main) { vm.reverseCacheSize = "${size / (1024*1024)} MB" }
        }
    }

    fun clearReverseCache(context: Context) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            context.cacheDir.listFiles { _, name -> (name.startsWith("rev_") || name.startsWith("inst_")) && name.endsWith(".wav") }?.forEach { it.delete() }
            withContext(Dispatchers.Main) {
                calculateCacheSize(context)
                vm.isReverseReady = false
                vm.isInstrumentalReady = false
                Toast.makeText(context, "Кэш очищен", Toast.LENGTH_SHORT).show()
            }
        }
    }
}



















