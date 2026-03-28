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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Buffer

class AudioCoverFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    companion object {
        // 🚨 ФИКС ФАТАЛЬНОГО КРАША 🚨
        // Ограничиваем количество одновременных тяжелых парсеров до 3-х.
        // Это защищает нативную (C++) память от переполнения при быстром скролле плейлиста.
        private val retrieverLimiter = Semaphore(3)
    }

    override suspend fun fetch(): FetchResult? {
        // --- 1. FAST PATH (Android 10 / API 29+) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val width = when (val w = options.size.width) {
                    is Dimension.Pixels -> w.px
                    else -> 512
                }
                val height = when (val h = options.size.height) {
                    is Dimension.Pixels -> h.px
                    else -> 512
                }

                val thumbnail: Bitmap? = try {
                    context.contentResolver.loadThumbnail(uri, Size(width, height), null)
                } catch (e: Exception) {
                    null
                }

                if (thumbnail != null) {
                    return DrawableResult(
                        drawable = BitmapDrawable(context.resources, thumbnail),
                        isSampled = true,
                        dataSource = DataSource.DISK
                    )
                }
            } catch (e: CancellationException) {
                // ФИКС ЗОМБИ-КОРУТИН: Разрешаем Coil отменять загрузку, если картинка ушла за экран
                throw e
            } catch (_: Exception) {
                // Падаем в Slow Path
            }
        }

        // --- 2. SLOW PATH (Legacy / Private Files / Non-indexed) ---
        return try {
            // Встаем в очередь! Запускаем не более 3-х одновременно
            retrieverLimiter.withPermit {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == ContentResolver.SCHEME_FILE && uri.path != null) {
                        retriever.setDataSource(uri.path)
                    } else {
                        retriever.setDataSource(context, uri)
                    }

                    val artBytes = retriever.embeddedPicture

                    if (artBytes != null) {
                        val buffer = Buffer().write(artBytes)
                        SourceResult(
                            source = ImageSource(source = buffer, context = context),
                            mimeType = "image/jpeg",
                            dataSource = DataSource.DISK
                        )
                    } else {
                        null
                    }
                } finally {
                    // Гарантированно освобождаем тяжелые нативные ресурсы
                    try { retriever.release() } catch (_: Exception) {}
                }
            }
        } catch (e: CancellationException) {
            // ФИКС ЗОМБИ-КОРУТИН
            throw e
        } catch (e: Exception) {
            null
        }
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            val isContent = scheme == ContentResolver.SCHEME_CONTENT
            val isFile = scheme == ContentResolver.SCHEME_FILE

            if (!isContent && !isFile) return null

            val uriString = data.toString().lowercase()

            val hasAudioExtension = uriString.endsWith(".mp3") || uriString.endsWith(".m4a") ||
                    uriString.endsWith(".flac") || uriString.endsWith(".aac") ||
                    uriString.endsWith(".ogg") || uriString.endsWith(".wav")

            val isAudioContent = uriString.contains("/audio/") || uriString.contains("audio%3a")

            return if (hasAudioExtension || isAudioContent) {
                AudioCoverFetcher(options.context, data, options)
            } else {
                null
            }
        }
    }
}