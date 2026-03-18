// utils/AudioReverser.kt
//
// ТИП: Utility / DSP Engine
//
// ЗАВИСИМОСТИ (LINKS TO):
// - Используется в: viewmodel/DspHandler.kt (метод toggleReverse)
// - Ссылается на: utils/WavUtils.kt (для записи заголовка WAV файла)
//
// НАЗНАЧЕНИЕ:
// Создает обратную (реверсивную) копию аудиофайла.
// Работает в два этапа:
// 1. Декодирование исходного файла (MP3/AAC/etc) в сырой PCM (временный .raw файл).
// 2. Чтение временного файла с конца и запись в итоговый .wav файл.
//
// ОСОБЕННОСТИ:
// - Disk-Based: Не грузит весь трек в RAM, поэтому не вызывает OOM на длинных песнях.
// - MediaCodec: Использует аппаратные декодеры Android.

package com.trec.music.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log // Заменили DebugLogger на стандартный Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object AudioReverser {

    private const val TAG = "AudioReverser"

    fun reverseAudio(context: Context, sourceUri: Uri, outputFile: File): String? {
        Log.d(TAG, "Start Disk-Based Reverse: $sourceUri")

        // 1. Подготовка временного файла для сырых PCM данных
        // Используем cacheDir, чтобы система могла почистить мусор, если мы забудем
        val tempRawFile = File(context.cacheDir, "temp_decode_${System.currentTimeMillis()}.raw")

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var outputStream: BufferedOutputStream? = null

        var sampleRate = 44100
        var channelCount = 2

        // --- ЭТАП 1: ДЕКОДИРОВАНИЕ ---
        try {
            extractor = MediaExtractor()
            // Безопасная установка источника данных
            if (sourceUri.scheme == "file" && sourceUri.path != null) {
                extractor.setDataSource(sourceUri.path!!)
            } else {
                extractor.setDataSource(context, sourceUri, null)
            }

            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackIndex = i
                    extractor.selectTrack(i)
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    break
                }
            }

            if (trackIndex == -1) return "Аудиодорожка не найдена"

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return "Формат не определен"

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            outputStream = BufferedOutputStream(FileOutputStream(tempRawFile))
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            var inputDone = false
            val kTimeOutUs = 5000L

            Log.d(TAG, "Decoding to temp file...")

            while (!isEOS) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(kTimeOutUs)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx)
                        val size = extractor.readSampleData(buffer!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, kTimeOutUs)
                if (outIdx >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIdx)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        outputStream.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode Error", e)
            return "Ошибка декодирования: ${e.message}"
        } finally {
            try { outputStream?.flush(); outputStream?.close() } catch (_: Exception) {}
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }

        if (tempRawFile.length() == 0L) return "Файл пуст после декодирования"

        Log.d(TAG, "Decoding done. Size: ${tempRawFile.length()} bytes. Reversing...")

        // --- ЭТАП 2: РЕВЕРС (DISK BASED) ---
        // Читаем temp файл с конца и пишем в целевой WAV

        var raf: RandomAccessFile? = null
        var fos: FileOutputStream? = null

        try {
            raf = RandomAccessFile(tempRawFile, "r")
            fos = FileOutputStream(outputFile)

            // Пишем заголовок (Требуется WavUtils!)
            WavUtils.writeWavHeader(fos, sampleRate, channelCount, tempRawFile.length())

            val frameSize = 2 * channelCount // 16 bit (2 bytes) * channels
            val bufferSize = 64 * 1024 // 64 KB chunk
            // Выравниваем буфер по размеру фрейма, чтобы не разрывать сэмплы
            val alignedBufferSize = (bufferSize / frameSize) * frameSize

            val buffer = ByteArray(alignedBufferSize)
            var fileLength = raf.length()
            var currentPos = fileLength

            while (currentPos > 0) {
                // Определяем сколько читать (не больше чем осталось, не больше чем буфер)
                val bytesToRead = if (currentPos >= alignedBufferSize) alignedBufferSize.toLong() else currentPos
                val seekPos = currentPos - bytesToRead

                raf.seek(seekPos)
                raf.readFully(buffer, 0, bytesToRead.toInt())

                // Реверс внутри буфера (пофреймово)
                reverseFramesInBuffer(buffer, bytesToRead.toInt(), frameSize)

                // Пишем реверсированный чанк
                fos.write(buffer, 0, bytesToRead.toInt())

                currentPos -= bytesToRead

                // Проверка отмены (важно для UX)
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
            }

            Log.d(TAG, "Success!")

        } catch (e: Exception) {
            Log.e(TAG, "Reverse Error", e)
            if (outputFile.exists()) outputFile.delete()
            return "Ошибка реверса: ${e.message}"
        } finally {
            try { raf?.close() } catch (_: Exception) {}
            try { fos?.close() } catch (_: Exception) {}
            // Удаляем временный файл
            if (tempRawFile.exists()) tempRawFile.delete()
        }

        return null // Успех (null means no error)
    }

    /**
     * Инвертирует порядок фреймов внутри массива байт.
     * Важно: байты ВНУТРИ фрейма (сэмпла) не меняются местами (Little Endian сохраняется),
     * меняется только порядок следования фреймов.
     */
    private fun reverseFramesInBuffer(buffer: ByteArray, validLength: Int, frameSize: Int) {
        var i = 0
        var j = validLength - frameSize
        val temp = ByteArray(frameSize)

        while (i < j) {
            // Swap frame at i with frame at j
            System.arraycopy(buffer, i, temp, 0, frameSize)
            System.arraycopy(buffer, j, buffer, i, frameSize)
            System.arraycopy(temp, 0, buffer, j, frameSize)

            i += frameSize
            j -= frameSize
        }
    }
}