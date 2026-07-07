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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

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

    private var sampleRate: Int = 44100
    private var bassState = 0f
    private var midState = 0f
    private var bassEnvelope = 0.0001f

    // Формат: ExoPlayer работает с 16-bit PCM (обычно)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Мы работаем только с 16-битным стерео звуком.
        // Если придет что-то другое (например, 5.1 звук или Float), мы это пропустим без изменений.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
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

        val bassCoeff = lowPassCoefficient(cutoffHz = 150f)
        val midCoeff = lowPassCoefficient(cutoffHz = 2200f)
        var frameCount = 0
        var sumSquares = 0f
        var peak = 0f
        var bassSquares = 0f
        var midSquares = 0f
        var trebleSquares = 0f

        // Обработка сэмплов
        // 16 bit = 2 байта. Стерео = 2 канала (L, R).
        // Итого 4 байта на один фрейм (сэмпл L + сэмпл R).
        while (inputBuffer.remaining() >= 4) {
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

            val monoSample = (((outL + outR) * 0.5f) / Short.MAX_VALUE.toFloat()).coerceIn(-1f, 1f)
            bassState += (monoSample - bassState) * bassCoeff
            midState += (monoSample - midState) * midCoeff

            val bassSample = bassState
            val midSample = midState - bassState
            val trebleSample = monoSample - midState

            sumSquares += monoSample * monoSample
            peak = max(peak, abs(monoSample))
            bassSquares += bassSample * bassSample
            midSquares += midSample * midSample
            trebleSquares += trebleSample * trebleSample
            frameCount++

            // Запись в output (Little Endian)
            buffer.put((outL and 0xFF).toByte())
            buffer.put(((outL shr 8) and 0xFF).toByte())
            buffer.put((outR and 0xFF).toByte())
            buffer.put(((outR shr 8) and 0xFF).toByte())
        }

        while (inputBuffer.hasRemaining()) {
            buffer.put(inputBuffer.get())
        }

        if (frameCount > 0) {
            publishAnalysis(
                rms = sqrt(sumSquares / frameCount.toFloat()),
                peak = peak,
                bassRms = sqrt(bassSquares / frameCount.toFloat()),
                midRms = sqrt(midSquares / frameCount.toFloat()),
                trebleRms = sqrt(trebleSquares / frameCount.toFloat())
            )
        }

        buffer.flip()
    }

    private fun lowPassCoefficient(cutoffHz: Float): Float {
        val x = (2.0 * PI * cutoffHz / sampleRate.toDouble()).toFloat()
        return (x / (1f + x)).coerceIn(0.001f, 0.95f)
    }

    private fun publishAnalysis(
        rms: Float,
        peak: Float,
        bassRms: Float,
        midRms: Float,
        trebleRms: Float
    ) {
        bassEnvelope = bassEnvelope * 0.985f + bassRms * 0.015f
        val beat = ((bassRms - bassEnvelope * 1.42f) * 10.5f).coerceIn(0f, 1f)

        AudioAnalysisBus.publish(
            volume = (rms * 3.4f).coerceIn(0f, 1f),
            peak = (peak * 1.35f).coerceIn(0f, 1f),
            bass = (bassRms * 7.2f).coerceIn(0f, 1f),
            mid = (midRms * 5.0f).coerceIn(0f, 1f),
            treble = (trebleRms * 4.4f).coerceIn(0f, 1f),
            beat = beat
        )
    }
}
