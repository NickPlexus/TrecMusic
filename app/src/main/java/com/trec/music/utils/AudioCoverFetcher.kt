// utils/AudioCoverFetcher.kt
//
// ТИП: Coil Fetcher (Image Loading Utility)
//
// ЗАВИСИМОСТИ (LINKS TO):
// - Используется в TrecApplication.kt (через .components { add(...) })
// - Использует TrecTrackEnhanced.uri (косвенно, через Coil request)
//
// НАЗНАЧЕНИЕ:
// Загружает обложки аудиофайлов.
// 1. Android 10+ (Q): Использует системный loadThumbnail (супер-быстро, без аллокации лишней памяти).
// 2. Legacy / Files: Использует MediaMetadataRetriever для вытаскивания картинки из ID3 тегов.
//
// ИЗМЕНЕНИЯ:
// - Factory: Добавлена поддержка MediaStore URI (которые не заканчиваются на .mp3),
//   чтобы обложки грузились не только с папок, но и из общей библиотеки.

package com.trec.music.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import okio.Buffer
import java.io.File

class AudioCoverFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // --- 1. FAST PATH (Android 10 / API 29+) ---
        // Используем системный кэш миниатюр. Это в 10 раз быстрее и экономичнее по памяти,
        // чем парсить файл вручную.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Вычисляем требуемый размер. Если Coil просит Original - берем 512px (разумный максимум для списка)
                val width = when (val w = options.size.width) {
                    is Dimension.Pixels -> w.px
                    else -> 512
                }
                val height = when (val h = options.size.height) {
                    is Dimension.Pixels -> h.px
                    else -> 512
                }

                // Пытаемся загрузить через системный ContentResolver
                val thumbnail: Bitmap? = try {
                    context.contentResolver.loadThumbnail(
                        uri,
                        Size(width, height),
                        null
                    )
                } catch (e: Exception) {
                    null
                }

                if (thumbnail != null) {
                    // ВАЖНО: Возвращаем DrawableResult.
                    // isSampled = true говорит Coil, что это уменьшенная копия, а не оригинал.
                    return DrawableResult(
                        drawable = BitmapDrawable(context.resources, thumbnail),
                        isSampled = true,
                        dataSource = DataSource.DISK
                    )
                }
            } catch (_: Exception) {
                // Если Fast Path не сработал (например, файл приватный или битый индекс),
                // молча падаем в Slow Path.
            }
        }

        // --- 2. SLOW PATH (Legacy / Private Files / Non-indexed) ---
        // Ручное извлечение картинки из метаданных файла.
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == ContentResolver.SCHEME_FILE && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }

            val artBytes = retriever.embeddedPicture

            if (artBytes != null) {
                // Оборачиваем байты в Buffer для Coil
                val buffer = Buffer().write(artBytes)

                SourceResult(
                    source = ImageSource(source = buffer, context = context),
                    mimeType = "image/jpeg", // Обычно это jpeg, Coil сам разберется если png
                    dataSource = DataSource.DISK
                )
            } else {
                null // Обложки нет
            }
        } catch (e: Exception) {
            null // Ошибка чтения файла
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            val isContent = scheme == ContentResolver.SCHEME_CONTENT
            val isFile = scheme == ContentResolver.SCHEME_FILE

            // Работаем только с локальным контентом
            if (!isContent && !isFile) return null

            val uriString = data.toString().lowercase()

            // ФИКС: Улучшенная проверка на аудио.
            // 1. Проверяем расширения (для файлов)
            val hasAudioExtension = uriString.endsWith(".mp3") || uriString.endsWith(".m4a") ||
                    uriString.endsWith(".flac") || uriString.endsWith(".aac") ||
                    uriString.endsWith(".ogg") || uriString.endsWith(".wav")

            // 2. Проверяем наличие маркера audio в URI (для ContentProvider/MediaStore)
            // Пример URI: content://media/external/audio/media/135
            val isAudioContent = uriString.contains("/audio/") || uriString.contains("audio%3a")

            return if (hasAudioExtension || isAudioContent) {
                AudioCoverFetcher(options.context, data, options)
            } else {
                null
            }
        }
    }
}