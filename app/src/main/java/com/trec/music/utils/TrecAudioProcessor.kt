// utils/TrecAudioProcessor.kt
//
// ТИП: Native DSP (ExoPlayer AudioProcessor)
//
// НАЗНАЧЕНИЕ:
// Низкоуровневая обработка PCM-сэмплов.
// Реализует:
// 1. Stereo Balance: Управление громкостью левого/правого канала.
// 2. Mono Downmix: Смешивание каналов (L+R)/2.

package com.trec.music.utils

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

@OptIn(UnstableApi::class)
class TrecAudioProcessor : BaseAudioProcessor() {

    // Настройки (изменяются на лету из PlaybackService)
    var balance: Float = 0f // -1.0 (Left) ... 0.0 (Center) ... 1.0 (Right)
        set(value) {
            field = value.coerceIn(-1f, 1f)
        }

    var isMono: Boolean = false
        set(value) {
            field = value
        }

    // Формат: ExoPlayer работает с 16-bit PCM (обычно)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Мы работаем только с 16-битным стерео звуком.
        // Если придет что-то другое (например, 5.1 звук или Float), мы это пропустим без изменений.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        // Выходной формат такой же, как входной
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val buffer = replaceOutputBuffer(remaining)

        // Расчет множителей громкости
        // Balance: -1.0 (L) ... 0.0 (Center) ... 1.0 (R)
        var leftVol = 1.0f
        var rightVol = 1.0f

        if (balance < 0) {
            // Сдвиг влево -> глушим правый
            rightVol = 1.0f - abs(balance)
        } else if (balance > 0) {
            // Сдвиг вправо -> глушим левый
            leftVol = 1.0f - balance
        }

        // Обработка сэмплов
        // 16 bit = 2 байта. Стерео = 2 канала (L, R).
        // Итого 4 байта на один фрейм (сэмпл L + сэмпл R).
        while (inputBuffer.hasRemaining()) {
            // Читаем Left (16 bit Little Endian)
            val lLow = inputBuffer.get().toInt()
            val lHigh = inputBuffer.get().toInt()
            val lSample = ((lHigh shl 8) or (lLow and 0xFF)).toShort()

            // Читаем Right (16 bit Little Endian)
            val rLow = inputBuffer.get().toInt()
            val rHigh = inputBuffer.get().toInt()
            val rSample = ((rHigh shl 8) or (rLow and 0xFF)).toShort()

            var outL = lSample.toInt()
            var outR = rSample.toInt()

            // 1. MONO MIXING
            if (isMono) {
                // (L + R) / 2
                val mix = (outL + outR) / 2
                outL = mix
                outR = mix
            }

            // 2. BALANCE
            outL = (outL * leftVol).toInt()
            outR = (outR * rightVol).toInt()

            // Клиппинг (защита от перегрузки)
            outL = outL.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outR = outR.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            // Запись в output (Little Endian)
            buffer.put((outL and 0xFF).toByte())
            buffer.put(((outL shr 8) and 0xFF).toByte())
            buffer.put((outR and 0xFF).toByte())
            buffer.put(((outR shr 8) and 0xFF).toByte())
        }

        buffer.flip()
    }
}