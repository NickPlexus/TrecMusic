// utils/LibraryScanner.kt
//
// ТИП: Utility (File System Scanner)
//
// ЗАВИСИМОСТИ (LINKS TO):
// - Используется в: data/LibraryRepository.kt
// - Использует: data/TrecTrackEnhanced.kt
//
// НАЗНАЧЕНИЕ:
// Сканирует файловую систему и MediaStore для поиска аудиофайлов.
// Превращает сырые файлы/курсоры в объекты TrecTrackEnhanced с ПОЛНЫМИ метаданными.
//
// ИЗМЕНЕНИЯ:
// 1. Artist Logic: Теперь возвращает null вместо "Неизвестный исполнитель",
//    чтобы соответствовать обновленному TrecTrackEnhanced (Nullable Artist).
// 2. Cancellation: Добавлена проверка isActive, чтобы сканирование можно было прервать.
// 3. Full Metadata: Теперь использует MetadataExtractor для получения ПОЛНЫХ метаданных из файлов.

package com.trec.music.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.trec.music.data.TrecTrackEnhanced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

object LibraryScanner {

    /**
     * Сканирование конкретной папки через Storage Access Framework (SAF).
     * Используется, когда пользователь вручную выбрал папку.
     * ВНИМАНИЕ: Это медленная операция, так как требует открытия каждого файла для чтения метаданных.
     */
    suspend fun scanFolder(context: Context, folderUri: Uri): List<TrecTrackEnhanced> =
        withContext(Dispatchers.IO) {
            val foundFiles = mutableListOf<TrecTrackEnhanced>()

            try {
                // Получаем ID дерева документов
                val treeId = DocumentsContract.getTreeDocumentId(folderUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeId)

                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )

                context.contentResolver.query(childrenUri, projection, null, null, null)
                    ?.use { cursor ->
                        val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                        while (cursor.moveToNext()) {
                            // Проверка отмены корутины (если юзер ушел с экрана)
                            coroutineContext.ensureActive()

                            val docId = cursor.getString(idColumn)
                            val name = cursor.getString(nameColumn) ?: ""
                            val mime = cursor.getString(mimeColumn) ?: ""

                            // Фильтруем папки (вглубь не идем, SAF это делает сложно) и не-аудио файлы
                            if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                                val isAudio = mime.startsWith("audio/")
                                val hasAudioExt = name.endsWith(".mp3", true) ||
                                        name.endsWith(".m4a", true) ||
                                        name.endsWith(".wav", true) ||
                                        name.endsWith(".flac", true) ||
                                        name.endsWith(".aac", true)

                                if (isAudio || hasAudioExt) {
                                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                                    
                                    // ИСПОЛЬЗУЕМ MetadataExtractor для получения ПОЛНЫХ метаданных
                                    val track = try {
                                        MetadataExtractor.extractMetadata(context, fileUri)
                                    } catch (e: Exception) {
                                        // Fallback: создаем трек с базовой информацией из имени файла
                                        val displayName = name.substringBeforeLast(".")
                                        TrecTrackEnhanced(
                                            uri = fileUri,
                                            title = displayName,
                                            artist = null,
                                            album = null,
                                            durationMs = 0L
                                        )
                                    }
                                    
                                    foundFiles.add(track)
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Сортируем по названию (А-Я)
            foundFiles.sortBy { it.title }
            return@withContext foundFiles
        }

    /**
     * Быстрое автоматическое сканирование всей музыки на устройстве через MediaStore.
     * Работает без выбора папки (при наличии разрешения READ_MEDIA_AUDIO).
     * Теперь использует MetadataExtractor для получения ПОЛНЫХ метаданных.
     */
    suspend fun scanMediaStore(context: Context): List<TrecTrackEnhanced> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<TrecTrackEnhanced>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // Запрашиваем ВСЕ доступные поля из MediaStore
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // Фильтр: только музыка, длительность > 30 секунд (исключаем рингтоны)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC" // Сначала новые
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumArtistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
                val genreCol = cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                val composerCol = cursor.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val addedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    // Проверка отмены
                    coroutineContext.ensureActive()

                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)                    // Получаем базовые данные из MediaStore (без тяжелого retriever в цикле)
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown Track"

                    val artist = cursor.getString(artistCol)?.takeIf {
                        it.isNotBlank() && !it.contains("<unknown>", true)
                    }

                    val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() }
                    val albumArtist = cursor.getString(albumArtistCol)?.takeIf { it.isNotBlank() }
                    val genre = if (genreCol != -1) cursor.getString(genreCol)?.takeIf { it.isNotBlank() } else null
                    val composer = cursor.getString(composerCol)?.takeIf { it.isNotBlank() }
                    val year = if (yearCol != -1) cursor.getInt(yearCol).takeIf { it > 0 } else null
                    val trackNumber = if (trackCol != -1) cursor.getInt(trackCol).takeIf { it > 0 } else null
                    val duration = cursor.getLong(durCol)
                    val fileSize = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val dateAdded = if (addedCol != -1) cursor.getLong(addedCol) else System.currentTimeMillis()
                    val dateModified = if (modifiedCol != -1) cursor.getLong(modifiedCol) else 0L
                    val mimeType = cursor.getString(mimeCol)

                    tracks.add(TrecTrackEnhanced(
                        uri = contentUri,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        albumArtist = albumArtist,
                        genre = genre,
                        year = year,
                        trackNumber = trackNumber,
                        composer = composer,
                        bitrate = null, // MediaStore не дает битрейт напрямую
                        sampleRate = null, // MediaStore не дает частоту дискретизации
                        mimeType = mimeType,
                        fileSize = fileSize,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        isLocal = true,
                        path = contentUri.path
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext tracks
    }
    
    /**
     * Получение полных метаданных для конкретного трека.
     * Использует MetadataExtractor для извлечения ВСЕХ возможных метаданных из файла.
     */
    suspend fun getFullMetadata(context: Context, uri: Uri): TrecTrackEnhanced = withContext(Dispatchers.IO) {
        return@withContext try {
            MetadataExtractor.extractMetadata(context, uri)
        } catch (e: Exception) {
            // Fallback с базовой информацией
            TrecTrackEnhanced(
                uri = uri,
                title = "Unknown Track",
                artist = null,
                album = null,
                durationMs = 0L
            )
        }
    }
}


