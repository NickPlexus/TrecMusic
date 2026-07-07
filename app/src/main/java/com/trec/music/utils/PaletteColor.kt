package com.trec.music.utils

import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.ln

fun Palette.chooseTrecAccentColor(fallbackArgb: Int = 0xFFD50000.toInt()): Int {
    val bestSwatch = swatches
        .asSequence()
        .filter { it.population > 0 }
        .maxByOrNull { swatch ->
            val hsl = swatch.hsl
            val saturation = hsl[1].coerceIn(0f, 1f)
            val lightness = hsl[2].coerceIn(0f, 1f)
            val lightScore = 1f - abs(lightness - 0.54f)
            val populationScore = ln(swatch.population.toFloat() + 1f) * 0.08f
            val neutralPenalty = if (saturation < 0.14f) 0.65f else 0f
            val extremeLightPenalty = if (lightness < 0.12f || lightness > 0.88f) 0.45f else 0f

            saturation * 2.5f + lightScore * 0.75f + populationScore - neutralPenalty - extremeLightPenalty
        }

    return bestSwatch?.rgb
        ?: getVibrantColor(getMutedColor(getDominantColor(fallbackArgb)))
}
