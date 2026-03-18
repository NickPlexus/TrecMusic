// AudioPresets.kt
//
// ТИП: Data / Logic (Audio DSP)
//
// НАЗНАЧЕНИЕ:
// Содержит "рецепты" для обработки звука.
// Использует нативные возможности Android (Equalizer + PlaybackParams).
//
// ИЗМЕНЕНИЯ:
// 1. Полностью переписаны алгоритмы эквалайзера.
// 2. Реализован "умный" маппинг частот (так как на разных телефонах разные полосы EQ).
// 3. Добавлены пресеты: Ethereal, Phonk, Hyperpop, Underwater, Demon.
// 4. Исправлен Mega Bass (теперь он не бубнит, а долбит).

package com.trec.music.data

import android.media.audiofx.Equalizer
import androidx.media3.common.PlaybackParameters
import kotlin.math.abs

/**
 * Описание одного пресета.
 */
data class AudioPreset(
    val name: String,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f
)

/**
 * Объект-синглтон, хранящий "Магию" звука.
 */
object AudioPresets {

    // --- СПИСОК ПРЕСЕТОВ ---
    // Порядок важен для отображения в UI
    val presets = listOf(
        AudioPreset("Normal"),
        AudioPreset("Mega Bass"),       // FIX: Реальный Bass Boost
        AudioPreset("Ethereal"),        // NEW: "Вознесение на небеса" (Atmospheric)
        AudioPreset("Phonk"),           // NEW: Агрессивный Drift фонк
        AudioPreset("Slowed + Reverb"), // FIX: Имитация (Muted highs + Slow)
        AudioPreset("Nightcore", speed = 1.25f, pitch = 1.25f),
        AudioPreset("Vaporwave", speed = 0.85f, pitch = 0.70f),
        AudioPreset("Hyperpop", speed = 1.05f, pitch = 1.0f), // NEW: Имитация автотюна EQ
        AudioPreset("Chipmunk", speed = 1.05f, pitch = 1.6f), // FIX: Был скрыт или не настроен
        AudioPreset("Demon", speed = 0.9f, pitch = 0.6f),     // NEW: TikTok Low Voice
        AudioPreset("Underwater"),      // NEW: Low pass filter
        AudioPreset("Telephone"),       // FIX: Band pass filter (реалистичный)
        AudioPreset("Metal"),
        AudioPreset("Jazz"),
        AudioPreset("Rock")
    )

    /**
     * Получение параметров скорости и тона для ExoPlayer
     */
    fun getPlaybackParameters(presetName: String): PlaybackParameters {
        val preset = presets.find { it.name == presetName } ?: return PlaybackParameters.DEFAULT
        return PlaybackParameters(preset.speed, preset.pitch)
    }

    /**
     * Применение эквалайзера.
     * МАГИЯ ЗДЕСЬ: Мы не знаем, какие частоты поддерживает телефон (5 полос? 10 полос?).
     * Поэтому мы проходимся по всем доступным полосам и вычисляем усиление математически
     * на основе целевой кривой пресета.
     */
    fun applyEqualizerSettings(equalizer: Equalizer?, presetName: String) {
        if (equalizer == null || !equalizer.hasControl()) return

        try {
            val minLevel = equalizer.bandLevelRange[0] // Обычно -1500 mB
            val maxLevel = equalizer.bandLevelRange[1] // Обычно +1500 mB
            val bandsCount = equalizer.numberOfBands

            for (i in 0 until bandsCount) {
                val bandIndex = i.toShort()
                // Получаем центральную частоту полосы в Гц (getCenterFreq возвращает mHz)
                val freqHz = equalizer.getCenterFreq(bandIndex) / 1000

                // Получаем целевой процент усиления (-100% ... +100%)
                val targetPercent = getLevelForPreset(presetName, freqHz)

                // Конвертируем процент в реальные milliBels
                val targetLevel = if (targetPercent >= 0) {
                    (targetPercent / 100f * maxLevel).toInt()
                } else {
                    (targetPercent / 100f * abs(minLevel.toInt())).toInt() * -1 // Cut
                }

                // Ставим значение (с защитой от выхода за пределы)
                equalizer.setBandLevel(bandIndex, targetLevel.coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- ЛОГИКА ЧАСТОТ (DSP CURVES) ---

    private fun getLevelForPreset(name: String, freq: Int): Int {
        return when (name) {
            "Mega Bass" -> {
                // Стратегия: Максимальный подъем низов (<80Гц), небольшой провал в мид-басе (200-400), чтобы убрать гул
                when {
                    freq <= 60 -> 100   // SUB BASS: KILLER
                    freq in 61..150 -> 60
                    freq in 151..400 -> -20 // Cut mud
                    freq > 4000 -> 10   // Slight clarity
                    else -> 0
                }
            }

            "Ethereal" -> {
                // Стратегия: "Вознесение". Убираем "коробочный" звук (середину), задираем "воздух" (>10кГц)
                // + добавляем немного суб-баса для кинематографичности.
                when {
                    freq < 80 -> 30     // Cinematic rumble
                    freq in 300..2000 -> -30 // Remove boxiness (Clean sound)
                    freq in 2001..8000 -> 20
                    freq > 8000 -> 80   // AIR / HEAVEN
                    else -> 0
                }
            }

            "Phonk" -> {
                // Стратегия: Drift Phonk. Перегруженный бас и очень резкие высокие (Cowbell).
                when {
                    freq < 100 -> 80    // Distorted Bass
                    freq in 200..1000 -> -40 // Hard scoop
                    freq in 2000..5000 -> 30
                    freq > 5000 -> 90   // Cowbell crispness
                    else -> 0
                }
            }

            "Hyperpop" -> {
                // Стратегия: Имитация "Auto-tune" резонанса.
                // Острый пик на 2-4кГц создает "металлический" оттенок голоса.
                when {
                    freq < 200 -> -20   // Thin sound
                    freq in 2000..4000 -> 70 // ROBOTIC RESONANCE
                    freq > 8000 -> 40   // Sparkle
                    else -> 0
                }
            }

            "Slowed + Reverb" -> {
                // Стратегия: Имитация реверберации через EQ невозможна полноценно,
                // но мы можем создать "эффект дали", срезав самые верха и размыв бас.
                when {
                    freq < 100 -> 40    // Muddy bass
                    freq in 1000..5000 -> -20 // Distance effect
                    freq > 8000 -> -60  // Cut presence (Far away)
                    else -> 0
                }
            }

            "Underwater" -> {
                // Стратегия: Low Pass Filter. Срезаем всё, что выше 500-800 Гц.
                when {
                    freq < 300 -> 20    // Muffled boom
                    freq in 300..800 -> -10
                    freq > 800 -> -100  // Silence the highs
                    else -> 0
                }
            }

            "Demon" -> {
                // Стратегия: Голос монстра. Поднимаем Low-Mid (тело голоса).
                when {
                    freq in 100..400 -> 60 // Body
                    freq > 2000 -> -40  // Dark
                    else -> 0
                }
            }

            "Telephone" -> {
                // Стратегия: Band Pass. Оставляем только 500-3000 Гц.
                when {
                    freq < 400 -> -100  // No bass
                    freq in 1000..3000 -> 60 // Harsh mids
                    freq > 3500 -> -100 // No highs
                    else -> -50
                }
            }

            "Vaporwave" -> {
                // Теплый, винтажный звук.
                when {
                    freq < 200 -> 30    // Warmth
                    freq in 2000..10000 -> -30 // Tape degradation
                    else -> 0
                }
            }

            "Rock", "Metal" -> {
                // Классическая "U" кривая (Smiley Face EQ)
                when {
                    freq < 100 -> 50
                    freq in 500..2000 -> -20
                    freq > 4000 -> 40
                    else -> 0
                }
            }

            "Jazz" -> {
                // Акцент на верхах (тарелочки) и низах (контрабас), чистая середина.
                when {
                    freq < 150 -> 20
                    freq > 10000 -> 30
                    else -> 0
                }
            }

            "Nightcore", "Chipmunk" -> {
                // Для ускоренных треков бас обычно пропадает, нужно его чуть вернуть,
                // а верха чуть прибрать, чтобы не резало уши.
                if (freq < 200) 20 else if (freq > 4000) -10 else 0
            }

            else -> 0 // Normal (Flat)
        }
    }
}