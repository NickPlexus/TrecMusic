// MetadataHandler.kt
//
// ТИП: Logic Handler (Metadata & Color Extraction)
//
// НАЗНАЧЕНИЕ:
// Извлечение ПОЛНЫХ метаданных треков и цветов из обложек.
// Использует MediaMetadataRetriever для получения всех возможных полей.
//
// ИЗМЕНЕНИЯ:
// 1. Color Logic Fix: Теперь при смене трека цвет не меняется, если Dynamic Color выключен.
// 2. Full Metadata: Добавлено извлечение ВСЕХ метаданных для диалога информации.

package com.trec.music.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.palette.graphics.Palette
import com.trec.music.utils.chooseTrecAccentColor
import com.trec.music.utils.TrackMetadataText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

class MetadataHandler(private val vm: MusicViewModel) {

    private var colorExtractionJob: Job? = null

    private fun decodeEmbeddedArt(bytes: ByteArray, maxSizePx: Int = 512): Bitmap? {
        // Встроенные обложки иногда бывают огромными и способны уронить приложение (OOM).
        // Декодируем с downscale по inSampleSize.
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null

            var sample = 1
            while (w / sample > maxSizePx || h / sample > maxSizePx) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun isNumericTitle(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.all { it.isDigit() }
    }

    private fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "${bytes} B"
        }
    }

    /**
     * Получение ПОЛНЫХ метаданных для конкретного URI.
     * Использует MediaMetadataRetriever для извлечения всех возможных полей.
     */
    fun getTrackMetadataForUri(context: Context, uri: Uri): Map<String, String> {
        val meta = mutableMapOf<String, String>()
        val ret = MediaMetadataRetriever()

        var displayName: String? = null
        var sizeBytes: Long? = null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) displayName = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
        }

        try {
            ret.setDataSource(context, uri)
            
            // === ОСНОВНАЯ ИНФОРМАЦИЯ ===
            val rawTitle = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: displayName?.substringBeforeLast(".")
                ?: uri.lastPathSegment?.substringBeforeLast(".")
                ?: "Unknown"
            val title = if (isNumericTitle(rawTitle) && !displayName.isNullOrBlank()) {
                displayName?.substringBeforeLast(".") ?: rawTitle
            } else {
                rawTitle
            }
            meta["Название"] = title
            
            val artist = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (!artist.isNullOrBlank()) {
                meta["Исполнитель"] = artist
            }
            
            val album = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            if (!album.isNullOrBlank()) {
                meta["Альбом"] = album
            }
            
            val albumArtist = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            if (!albumArtist.isNullOrBlank() && albumArtist != artist) {
                meta["Исполнитель альбома"] = albumArtist
            }
            
            // === ДЛИТЕЛЬНОСТЬ ===
            val dur = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            if (dur > 0) {
                val minutes = (dur / 1000) / 60
                val seconds = (dur / 1000) % 60
                val hours = minutes / 60
                meta["Длительность"] = if (hours > 0) {
                    String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60, seconds)
                } else {
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                }
            }
            
            // === ЖАНР И ГОД ===
            val genre = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            if (!genre.isNullOrBlank()) {
                meta["Жанр"] = genre
            }
            
            val year = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            if (!year.isNullOrBlank()) {
                meta["Год выпуска"] = year
            }
            
            val date = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!date.isNullOrBlank() && date != year) {
                meta["Дата"] = date
            }
            
            // === НОМЕР ТРЕКА ===
            val trackNumber = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            if (!trackNumber.isNullOrBlank()) {
                meta["Номер трека"] = trackNumber
            }
            
            val discNumber = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            if (!discNumber.isNullOrBlank()) {
                meta["Номер диска"] = discNumber
            }
            
            // === АВТОРСТВО ===
            val composer = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            if (!composer.isNullOrBlank()) {
                meta["Композитор"] = composer
            }
            
            val writer = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER)
            if (!writer.isNullOrBlank()) {
                meta["Автор текста"] = writer
            }
            
            // === ТЕХНИЧЕСКАЯ ИНФОРМАЦИЯ ===
            val bitrate = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (!bitrate.isNullOrBlank()) {
                meta["Битрейт"] = "${bitrate.toInt() / 1000} kbps"
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sampleRate = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                if (!sampleRate.isNullOrBlank()) {
                    meta["Частота дискретизации"] = "${sampleRate.toInt() / 1000} kHz"
                }
            }
            sizeBytes?.takeIf { it > 0 }?.let { size ->
                meta["Размер файла"] = formatFileSize(size)
            }
            
            val mimeType = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (!mimeType.isNullOrBlank()) {
                val format = when {
                    mimeType.contains("mpeg") || mimeType.contains("mp3") -> "MP3"
                    mimeType.contains("mp4") || mimeType.contains("m4a") -> "AAC/M4A"
                    mimeType.contains("flac") -> "FLAC"
                    mimeType.contains("wav") || mimeType.contains("wave") -> "WAV"
                    mimeType.contains("ogg") -> "OGG"
                    mimeType.contains("opus") -> "Opus"
                    mimeType.contains("wma") -> "WMA"
                    else -> mimeType.substringAfter("/", mimeType).uppercase()
                }
                meta["Формат"] = format
            }
            
            // === ЛОКАЦИЯ ===
            val location = ret.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            if (!location.isNullOrBlank()) {
                meta["Местоположение"] = location
            }
            
        } catch (e: Exception) {
            meta["Ошибка"] = "Не удалось прочитать метаданные: ${e.message}"
        } finally {
            try { ret.release() } catch (_: Exception) {}
        }
        
        return meta
    }

    /**
     * Получение метаданных для текущего трека.
     */
    fun getTrackMetadata(context: Context): Map<String, String> {
        val uri = vm.currentTrackUri ?: return mapOf("Ошибка" to "Нет активного трека")
        val meta = getTrackMetadataForUri(context, uri).toMutableMap()

        val title = vm.currentTrackTitle.takeIf { it.isNotBlank() && it != "TREC MUSIC" }
        if (title != null) meta["Название"] = title

        val artist = vm.getCurrentDisplayArtist().takeIf { it.isNotBlank() }
        if (artist != null) meta["Исполнитель"] = artist

        val album = vm.currentTrackAlbum?.takeIf { it.isNotBlank() }
        if (album != null) meta["Альбом"] = album

        val coverInfo = when {
            vm.hasEmbeddedArtwork -> "Встроенная"
            !vm.currentCoverUrl.isNullOrBlank() -> "Онлайн (iTunes)"
            else -> null
        }
        if (coverInfo != null) meta["Обложка"] = coverInfo

        return meta
    }

    fun updateCurrentTrackInfo(context: Context?, mediaItem: MediaItem?) {
        // Эта функция вызывается из Player.Listener и должна быть максимально crash-safe.
        try {
            // Пропускаем служебные пути (реверс/инструментал)
            if (mediaItem?.mediaId == vm.reverseTrackPath || mediaItem?.mediaId == vm.instrumentalTrackPath) return

            val rawTitle = mediaItem?.mediaMetadata?.title?.toString()
                ?: mediaItem?.mediaId?.split("/")?.last()?.substringBeforeLast(".")
                ?: "TREC MUSIC"
            val normalizedTitle = TrackMetadataText.normalizeValue(rawTitle) ?: "TREC MUSIC"
            val inferred = TrackMetadataText.inferArtistAndTitle(normalizedTitle)
            vm.currentTrackTitle = inferred.second
            vm.duration = vm.player?.duration?.coerceAtLeast(0) ?: 0L

            val artistFromItem = TrackMetadataText.normalizeValue(mediaItem?.mediaMetadata?.artist?.toString())
            val albumFromItem = TrackMetadataText.normalizeValue(mediaItem?.mediaMetadata?.albumTitle?.toString())
            vm.currentTrackArtist = TrackMetadataText.displayArtist(
                artist = artistFromItem ?: inferred.first,
                albumArtist = null,
                title = vm.currentTrackTitle
            )
            vm.currentTrackAlbum = albumFromItem
            vm.currentCoverUrl = null
            vm.hasEmbeddedArtwork = false

            val uriString = mediaItem?.mediaId
            if (uriString != null) {
                val uri = uriString.toUri()
                vm.markCurrentTrackAsPlaybackTarget(uri)
                vm.isCurrentTrackFav = vm.favoriteTracks.contains(uriString)
                vm.instrumentalTrackPath = null

                val track = vm.playlistSnapshot().find { it.uri.toString() == uriString }
                vm.currentTrackArtist = TrackMetadataText.displayArtist(
                    artist = artistFromItem ?: track?.artist ?: inferred.first,
                    albumArtist = track?.albumArtist,
                    title = vm.currentTrackTitle
                )
                vm.currentTrackAlbum = albumFromItem ?: track?.album

                // Start online cover lookup in parallel with embedded-art extraction.
                vm.refreshCoverArt(vm.currentTrackArtist, vm.currentTrackTitle, vm.currentTrackAlbum)

                if (!vm.brokenTracks.contains(uriString) && context != null) {
                    extractColors(context, uri)

                    // Проверяем наличие кэша
                    val revFile = File(context.cacheDir, "rev_${uri.toString().hashCode()}.wav")
                    vm.isReverseReady = revFile.exists() && revFile.length() > 1000
                    // Должно совпадать с DspHandler: путь учитывает режим/силу/буст.
                    val sepFile = vm.getKaraokeCacheFileFor(uri, context)
                    vm.isInstrumentalReady = sepFile.exists() && sepFile.length() > 1000

                    vm.backgroundGenJob?.cancel()
                } else {
                    // Трек битый или нет контекста
                    if (vm.isDynamicColorEnabled) {
                        vm.dominantColor = Color.DarkGray
                        vm.secondaryColor = Color.Black
                    } else {
                        vm.dominantColor = vm.staticColor
                        vm.secondaryColor = Color.Black
                    }
                }
            }
        } catch (_: Throwable) {
            // Если что-то пошло не так, не падаем: оставляем безопасные значения.
            if (!vm.isDynamicColorEnabled) {
                vm.dominantColor = vm.staticColor
                vm.secondaryColor = Color(0xFF050505)
            } else {
                vm.dominantColor = Color.DarkGray
                vm.secondaryColor = Color.Black
            }
        }
    }

    private fun extractColors(context: Context, uri: Uri) {
        colorExtractionJob?.cancel()
        colorExtractionJob = vm.viewModelScope.launch(Dispatchers.IO) {
            val ret = MediaMetadataRetriever()
            try {
                ret.setDataSource(context, uri)
                val bytes = ret.embeddedPicture

                if (bytes != null) {
                    val bitmap = decodeEmbeddedArt(bytes, maxSizePx = 640)
                    val palette = if (bitmap != null) {
                        try {
                            val maxPaletteSize = 192
                            val pb = if (bitmap.width > maxPaletteSize || bitmap.height > maxPaletteSize) {
                                val scale =
                                    minOf(maxPaletteSize.toFloat() / bitmap.width, maxPaletteSize.toFloat() / bitmap.height)
                                        .coerceAtMost(1f)
                                val nw = (bitmap.width * scale).toInt().coerceAtLeast(1)
                                val nh = (bitmap.height * scale).toInt().coerceAtLeast(1)
                                Bitmap.createScaledBitmap(bitmap, nw, nh, true)
                            } else {
                                bitmap
                            }
                            try {
                                Palette.from(pb).generate()
                            } finally {
                                if (pb !== bitmap) pb.recycle()
                            }
                        } catch (_: Throwable) {
                            null
                        }
                    } else null

                    withContext(Dispatchers.Main) {
                        vm.hasEmbeddedArtwork = true
                        vm.currentCoverUrl = null
                        if (bitmap != null) {
                            vm.setEmbeddedArtworkForCurrentTrack(bitmap, uri.toString())
                        }
                        if (vm.isDynamicColorEnabled && palette != null) {
                            vm.dominantColor = Color(palette.chooseTrecAccentColor())
                            vm.secondaryColor = Color(palette.getDarkMutedColor(0xFF050505.toInt()))
                        } else if (!vm.isDynamicColorEnabled) {
                            vm.dominantColor = vm.staticColor
                            vm.secondaryColor = Color(0xFF050505)
                        }
                    }
                } else {
                    // Нет встроенной обложки — просто обновляем флаг и генерируем цвет.
                    // refreshCoverArt уже был вызван в updateCurrentTrackInfo — не дублируем!
                    val generatedColor = generateColorForTrack(uri.toString())
                    withContext(Dispatchers.Main) {
                        vm.hasEmbeddedArtwork = false
                        if (vm.isDynamicColorEnabled) {
                            vm.dominantColor = generatedColor
                            vm.secondaryColor = Color(0xFF050505)
                        } else {
                            vm.dominantColor = vm.staticColor
                            vm.secondaryColor = Color(0xFF050505)
                        }
                    }
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    vm.hasEmbeddedArtwork = false
                    if (vm.isDynamicColorEnabled) {
                        vm.dominantColor = Color.DarkGray
                        vm.secondaryColor = Color.Black
                    } else {
                        vm.dominantColor = vm.staticColor
                        vm.secondaryColor = Color.Black
                    }
                }
            } finally {
                try { ret.release() } catch (_: Exception) {}
            }
        }
    }

    private fun generateColorForTrack(uri: String): Color {
        val colors = listOf(
            Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
            Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1),
            Color(0xFF00897B), Color(0xFF43A047), Color(0xFF7CB342), Color(0xFFC0CA33),
            Color(0xFFFDD835), Color(0xFFFFB300), Color(0xFFFB8C00), Color(0xFFF4511E),
            Color(0xFF6D4C41), Color(0xFF757575)
        )
        return colors[abs(uri.hashCode()) % colors.size]
    }
}







