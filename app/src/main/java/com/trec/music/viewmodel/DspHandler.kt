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
import com.trec.music.utils.UmxlVocalSeparatorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "DspHandler"

class DspHandler(private val vm: MusicViewModel) {

    private val processingMutex = Mutex()
    private var vocalProcessingJob: Job? = null

    fun setupEqualizer(sessionId: Int) {
        try {
            if (vm.equalizer != null && vm.audioSessionId == sessionId) return

            vm.equalizer?.release()
            vm.audioSessionId = sessionId

            // ФИКС КРАША (StackOverflow):
            // Запрещаем создавать эквалайзер на сессии 0 (глобальный микс).
            // Это вызывает краш на современных Android.
            if (sessionId == 0) {
                vm.equalizer = null
                return
            }

            vm.equalizer = Equalizer(0, sessionId)
            vm.equalizer?.enabled = true

            // Если эквалайзер успешно создался, применяем к нему текущий пресет
            applyPreset(vm.currentPresetName)

        } catch (e: Exception) {
            vm.equalizer = null
            e.printStackTrace()
        }
    }

    fun applyPreset(name: String) {
        vm.currentPresetName = name
        val params = AudioPresets.getPlaybackParameters(name)

        vm.player?.playbackParameters = params
        vm.playbackSpeed = params.speed
        vm.playbackPitch = params.pitch

        // ФИКС КРАША: Убрали вызов setupEqualizer(0) отсюда.
        // Мы больше не уходим в бесконечный цикл.
        // Настройки применятся к эквалайзеру, только если он уже существует.
        vm.equalizer?.let { eq ->
            AudioPresets.applyEqualizerSettings(eq, name)
        }
    }

    fun setSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(0.1f, 4.0f)

        // Важно: "как кассета/винил" = тональность меняется вместе со скоростью.
        // Это то, что люди ожидают от "slowed" / "nightcore" и т.п.
        val targetPitch = if (vm.isPitchFollowsSpeed) {
            safeSpeed.coerceIn(0.1f, 4.0f)
        } else {
            // Pitch Lock: удерживаем тон на "нормальном" уровне независимо от темпа.
            1.0f
        }

        vm.playbackSpeed = safeSpeed
        vm.playbackPitch = targetPitch
        vm.player?.playbackParameters = PlaybackParameters(safeSpeed, targetPitch)
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

        val processingUri = vm.vocalRemovalProcessingUri
        if (vm.isVocalRemovalProcessing && processingUri == uri.toString()) return

        if (vocalProcessingJob?.isActive == true && processingUri != uri.toString()) {
            vocalProcessingJob?.cancel()
        }

        vm.backgroundGenJob?.cancel()

        vocalProcessingJob = vm.viewModelScope.launch {
            vm.isVocalRemovalProcessing = true
            vm.vocalRemovalProcessingUri = uri.toString()
            vm.isInstrumentalReady = false

            val sepFile = vm.getKaraokeCacheFileFor(uri, context)
            val processingTrackUri = uri.toString()
            val mode = if (vm.karaokeOutputMode == KaraokeOutputMode.ACAPELLA) {
                UmxlVocalSeparatorEngine.OutputMode.ACAPELLA
            } else {
                UmxlVocalSeparatorEngine.OutputMode.INSTRUMENTAL
            }

            try {
                val resultPath: String? = processingMutex.withLock {
                    try {
                        if (sepFile.exists() && sepFile.length() > 1000) {
                            // Файл уже есть в кэше
                            sepFile.absolutePath
                        } else {
                            val result = withContext(Dispatchers.IO) {
                                UmxlVocalSeparatorEngine.generateInstrumental(
                                    context = context,
                                    sourceUri = uri,
                                    outputFile = sepFile,
                                    outputMode = mode,
                                    removalStrength = vm.karaokeRemovalStrength,
                                    vocalBoost = vm.karaokeVocalBoost
                                )
                            }

                            if (result.success) {
                                calculateCacheSize(context)
                                sepFile.absolutePath
                            } else {
                                withContext(Dispatchers.Main) {
                                    val label = (result.methodUsed.ifBlank { "Karaoke" })
                                    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                                    Toast.makeText(context, "Ошибка: ${result.error}", Toast.LENGTH_LONG).show()
                                }
                                null
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Ошибка AI-сепарации: ${e.message ?: "неизвестная ошибка"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        null
                    }
                }

                if (resultPath != null) {
                    val currentPlaying = (vm.normalTrackUri ?: vm.currentTrackUri)?.toString()
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
            } finally {
                vm.isVocalRemovalProcessing = false
                vm.vocalRemovalProcessingUri = null
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

        // Копируем метаданные оригинального трека, чтобы UI не "моргал"
        val metadata = MediaMetadata.Builder()
            .setTitle(vm.currentTrackTitle)
            .setArtist(vm.currentTrackArtist)
            .setAlbumTitle(vm.currentTrackAlbum)
            .build()

        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(vm.reverseTrackPath!!)
            .setMediaMetadata(metadata)
            .build()

        vm.player?.setMediaItem(item)
        vm.player?.seekTo(revPos)
        vm.player?.prepare()
        vm.player?.play()

        vm.isReversing = true
    }

    private fun switchToInstrumentalTrack(file: File) {
        val pos = vm.player?.currentPosition ?: 0L

        vm.instrumentalTrackPath = Uri.fromFile(file).toString()

        // Копируем метаданные оригинального трека
        val metadata = MediaMetadata.Builder()
            .setTitle(vm.currentTrackTitle)
            .setArtist(vm.currentTrackArtist)
            .setAlbumTitle(vm.currentTrackAlbum)
            .build()

        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(vm.instrumentalTrackPath ?: Uri.fromFile(file).toString())
            .setMediaMetadata(metadata)
            .build()

        vm.player?.setMediaItem(item)
        vm.player?.seekTo(pos)
        vm.player?.prepare()
        vm.player?.play()

        applyPreset("Normal")
    }

    private fun restoreTrack(uri: Uri, position: Long) {
        val tracksToUse = if (vm.currentPlaylistFilter != null)
            vm.libraryHandler.getPlaylistTracks(vm.currentPlaylistFilter!!)
        else
            vm.playlistSnapshot()

        val index = tracksToUse.indexOfFirst { it.uri == uri }

        if (index != -1) {
            val mediaItems = tracksToUse.map { track ->
                // Восстанавливаем ПОЛНЫЕ метаданные, чтобы UI не терял обложки и названия
                val metadata = MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(vm.getTrackArtist(track))
                    .setAlbumTitle(track.album)
                    .build()

                MediaItem.Builder()
                    .setMediaId(track.uri.toString())
                    .setUri(track.uri)
                    .setMediaMetadata(metadata)
                    .build()
            }
            vm.player?.setMediaItems(mediaItems, index, position)
        } else {
            // Фолбэк, если трека нет в списке
            val metadata = MediaMetadata.Builder()
                .setTitle(vm.currentTrackTitle)
                .setArtist(vm.currentTrackArtist)
                .setAlbumTitle(vm.currentTrackAlbum)
                .build()
            val item = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(uri.toString())
                .setMediaMetadata(metadata)
                .build()
            vm.player?.setMediaItem(item)
            vm.player?.seekTo(position)
        }
        vm.player?.prepare()
        vm.player?.play()
    }

    fun calculateCacheSize(context: Context) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            val files = context.cacheDir.listFiles { _, name ->
                (name.startsWith("rev_") || name.startsWith("inst_") || name.startsWith("sep_umxl1_")) &&
                    name.endsWith(".wav")
            }
            val size = files?.sumOf { it.length() } ?: 0L
            withContext(Dispatchers.Main) { vm.reverseCacheSize = "${size / (1024*1024)} MB" }
        }
    }

    fun clearReverseCache(context: Context) {
        vm.viewModelScope.launch(Dispatchers.IO) {
            context.cacheDir.listFiles { _, name ->
                (name.startsWith("rev_") || name.startsWith("inst_") || name.startsWith("sep_umxl1_")) &&
                    name.endsWith(".wav")
            }?.forEach { it.delete() }
            withContext(Dispatchers.Main) {
                calculateCacheSize(context)
                vm.isReverseReady = false
                vm.isInstrumentalReady = false
                Toast.makeText(context, "Кэш очищен", Toast.LENGTH_SHORT).show()
            }
        }
    }
}



















