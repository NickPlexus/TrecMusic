package com.trec.music.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.trec.music.utils.AudioAnalysisFrame
import kotlin.math.*
import kotlin.random.Random

// ─── Константы ────────────────────────────────────────────────────────────────

private const val TWO_PI        = 6.2831855f
private const val BG_STAR_COUNT = 820   // фоновое поле: мерцают, никуда не летят
private const val FG_STAR_COUNT = 880   // передний план: 3D-гиперпрыжок
private const val FOCAL         = 0.58f // «зум» перспективы
private const val Z_NEAR        = 0.007f
private const val Z_FAR         = 1.00f
private const val ATMOSPHERE_MODE_SECONDS = 18f
private const val ATMOSPHERE_BLEND_SECONDS = 5f

private val ATMOSPHERE_SEQUENCE = intArrayOf(0, 2, 4, 1, 3, 2, 0, 4, 3, 1)

// Единая палитра — космос без неона
private val STAR_TINTS = arrayOf(
    Color.White,          // 0: чисто белый      (65 %)
    Color(0xFFCEEAFF),    // 1: холодный голубой  (18 %)
    Color(0xFF9CC5FF),    // 2: стальной голубой  (10 %)
    Color(0xFFFFF2E8)     // 3: тёплый белый       (7 %)
)

// ─── Типы данных ──────────────────────────────────────────────────────────────

/**
 * Фоновая звезда — фиксированная точка на экране.
 * Никуда не двигается, только мерцает. Именно это поле нравится пользователю в паузе.
 * [fx, fy] — позиция в долях экрана [0..1].
 */
private data class BgStar(
    val fx:         Float,
    val fy:         Float,
    val radius:     Float,    // физический радиус точки в px
    val brightness: Float,
    val twinkleFreq: Float,
    val twinklePh:  Float,
    val tintIdx:    Int,
    val hasGlow:    Boolean   // только у крупных звёзд
)

/**
 * Передняя звезда — луч в 3D-пространстве.
 * [baseX, baseY] — направление луча (нормализованное, неизменяемое).
 * Глубина [z] хранится отдельно в zArr и обновляется каждый кадр.
 *
 * Проекция на экран:
 *   screenPos = center + (baseX, baseY) * FOCAL / z * projScale
 *
 * Когда z уменьшается (звезда летит к тебе) → screenPos удаляется от центра.
 * Хвост полосы — та же формула, но с бо́льшим z.
 */
private data class StarDef(
    val baseX:       Float,
    val baseY:       Float,
    val brightness:  Float,
    val twinkleFreq: Float,
    val twinklePh:   Float,
    val tintIdx:     Int
)

private data class CosmicPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val warm: Color,
    val deep: Color
)

// ─── Composable ───────────────────────────────────────────────────────────────

@Composable
fun SpaceVisualizer(
    isPlaying: Boolean,
    dominantColor: Color,
    audio: AudioAnalysisFrame = AudioAnalysisFrame.Silent,
    modifier: Modifier = Modifier
) {
    // ── Фоновые звёзды (seed отдельный, создаются один раз) ──────────────────
    val bgStars = remember {
        val r = Random(8888)
        Array(BG_STAR_COUNT) {
            val szRoll = r.nextFloat()
            // Три группы размеров: мелкие (60%), средние (28%), крупные с ореолом (12%)
            val rad = when {
                szRoll < 0.60f -> 0.35f + r.nextFloat() * 0.55f
                szRoll < 0.88f -> 0.90f + r.nextFloat() * 0.75f
                else           -> 1.65f + r.nextFloat() * 1.05f
            }
            val tRoll = r.nextFloat()
            BgStar(
                fx          = r.nextFloat(),
                fy          = r.nextFloat(),
                radius      = rad,
                brightness  = 0.14f + r.nextFloat() * 0.72f,
                twinkleFreq = 0.45f + r.nextFloat() * 2.60f,
                twinklePh   = r.nextFloat() * TWO_PI,
                tintIdx     = when {
                    tRoll < 0.65f -> 0
                    tRoll < 0.83f -> 1
                    tRoll < 0.93f -> 2
                    else          -> 3
                },
                hasGlow = rad > 1.60f
            )
        }
    }

    // ── Передние звёзды: фиксированные направления ────────────────────────────
    val fgStars = remember {
        val r = Random(2401)
        Array(FG_STAR_COUNT) {
            val angle  = r.nextFloat() * TWO_PI
            // sqrt даёт равномерное распределение по площади (не в центре)
            val radius = 0.07f + sqrt(r.nextFloat()) * 1.48f
            val tRoll  = r.nextFloat()
            StarDef(
                baseX       = cos(angle) * radius,
                baseY       = sin(angle) * radius,
                brightness  = 0.22f + r.nextFloat() * 0.78f,
                twinkleFreq = 0.60f + r.nextFloat() * 2.80f,
                twinklePh   = r.nextFloat() * TWO_PI,
                tintIdx     = when {
                    tRoll < 0.65f -> 0
                    tRoll < 0.83f -> 1
                    tRoll < 0.93f -> 2
                    else          -> 3
                }
            )
        }
    }

    // Глубина каждой передней звезды — единственное мутируемое состояние
    val zArr = remember {
        FloatArray(FG_STAR_COUNT) { Random.nextFloat() * (Z_FAR - Z_NEAR) + Z_NEAR }
    }

    // ── Анимации ──────────────────────────────────────────────────────────────

    // Плавный разгон 2.2 с при play, торможение при pause
    val warp by animateFloatAsState(
        targetValue   = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
        label         = "hyperWarp"
    )
    val volume by animateFloatAsState(audio.volume, tween(220), label = "vol")
    val bass   by animateFloatAsState(audio.bass,   tween(200), label = "bass")
    val beat   by animateFloatAsState(audio.beat,   tween(190), label = "beat")
    val treble by animateFloatAsState(audio.treble, tween(220), label = "treble")

    // time — State<Float>, его изменение заставляет Canvas перерисовываться
    var time by remember { mutableFloatStateOf(0f) }

    // ── Кадровый цикл ────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val dt = ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.008f, 0.034f)
                    time   = (time + dt) % 10_000f

                    // Передние звёзды летят к зрителю (z уменьшается)
                    val speed = warp * dt * (0.28f + volume * 0.22f + bass * 0.17f + beat * 0.14f)
                    if (speed > 0.00005f) {
                        for (i in 0 until FG_STAR_COUNT) {
                            zArr[i] -= speed
                            if (zArr[i] < Z_NEAR) {
                                // Звезда вылетела за экран — рециклинг назад вдаль
                                zArr[i] = Z_FAR * (0.75f + Random.nextFloat() * 0.25f)
                            }
                        }
                    }
                }
                lastNanos = nanos
            }
        }
    }

    // ── Отрисовка ─────────────────────────────────────────────────────────────
    Canvas(modifier = modifier.fillMaxSize().padding(4.dp)) {
        val center    = Offset(size.width * 0.5f, size.height * 0.5f)
        val projScale = minOf(size.width, size.height) * 0.5f

        // VinylDisk использует fillMaxWidth(0.8f) → радиус пластинки = 40 % ширины канваса
        val diskR = size.width * 0.40f

        drawDeepSpace(center, dominantColor, warp, time)
        drawAdaptiveAlbumAura(center, diskR, dominantColor, warp, volume, bass, beat, time)
        drawDynamicAtmosphere(center, diskR, dominantColor, warp, volume, bass, beat, time)
        drawBackgroundStarField(bgStars, center, diskR, warp, treble, time)
        drawHyperspace(fgStars, zArr, center, projScale, diskR, warp, bass, beat, treble, time)
        drawWarpRings(center, diskR, dominantColor, warp, bass, beat, time)
        drawVinylHalo(center, diskR, dominantColor, bass, beat, warp, time)
    }
}

// ─── Фон: тёмный космос с лёгкой синеватой дымкой ────────────────────────────

private fun DrawScope.drawDeepSpace(center: Offset, accent: Color, warp: Float, time: Float) {
    val r = maxOf(size.width, size.height) * 0.82f
    val palette = accent.cosmicPalette()
    val pulse = 0.5f + 0.5f * sin(time * 0.18f)

    drawRect(Color.Black)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                lerp(Color(0xFF080B16), palette.deep, 0.74f),
                lerp(Color(0xFF030610), palette.primary, 0.13f + pulse * 0.03f),
                Color(0xFF020307),
                Color.Black
            ),
            center = center, radius = r
        ),
        center = center, radius = r
    )

    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                palette.tertiary.copy(alpha = 0.030f + warp * 0.012f),
                palette.secondary.copy(alpha = 0.022f),
                Color.Transparent
            ),
            start = Offset(size.width * (0.12f + pulse * 0.08f), 0f),
            end = Offset(size.width * (0.90f - pulse * 0.06f), size.height)
        )
    )

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.primary.copy(alpha = 0.040f + warp * 0.040f),
                palette.deep.copy(alpha = 0.030f),
                Color.Transparent
            ),
            center = Offset(center.x, center.y * 0.88f),
            radius = r * (0.55f + pulse * 0.04f)
        ),
        center = Offset(center.x, center.y * 0.88f),
        radius = r * (0.55f + pulse * 0.04f)
    )
}

private fun DrawScope.drawAdaptiveAlbumAura(
    center: Offset,
    diskR: Float,
    accent: Color,
    warp: Float,
    volume: Float,
    bass: Float,
    beat: Float,
    time: Float
) {
    val palette = accent.cosmicPalette()
    val pausePresence = (1f - warp * 0.48f).coerceIn(0.42f, 1f)
    val audioLift = (0.82f + volume * 0.18f + bass * 0.16f + beat * 0.12f).coerceIn(0.78f, 1.18f)
    val breath = 0.5f + 0.5f * sin(time * 0.23f)
    val radius = diskR * (1.52f + breath * 0.18f + bass * 0.08f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.primary.copy(alpha = 0.170f * pausePresence * audioLift),
                palette.secondary.copy(alpha = 0.074f * pausePresence),
                palette.deep.copy(alpha = 0.038f * pausePresence),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        center = center,
        radius = radius
    )

    val driftA = Offset(
        center.x + cos(time * 0.17f) * diskR * 0.42f,
        center.y + sin(time * 0.13f) * diskR * 0.30f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.tertiary.copy(alpha = 0.095f * pausePresence * audioLift),
                Color.Transparent
            ),
            center = driftA,
            radius = diskR * (1.18f + bass * 0.18f)
        ),
        center = driftA,
        radius = diskR * (1.18f + bass * 0.18f)
    )

    val driftB = Offset(
        center.x - sin(time * 0.11f) * diskR * 0.50f,
        center.y + cos(time * 0.19f) * diskR * 0.38f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.warm.copy(alpha = 0.052f * pausePresence * (0.75f + beat * 0.35f)),
                Color.Transparent
            ),
            center = driftB,
            radius = diskR * 1.05f
        ),
        center = driftB,
        radius = diskR * 1.05f
    )

    repeat(3) { i ->
        val phase = ((time * (0.055f + i * 0.012f) + i * 0.31f) % 1f + 1f) % 1f
        val t = smooth01(phase)
        val ringAlpha = (1f - t) * pausePresence * (0.018f + bass * 0.015f + beat * 0.020f)
        drawCircle(
            color = listOf(palette.primary, palette.secondary, palette.tertiary)[i]
                .copy(alpha = ringAlpha),
            center = center,
            radius = diskR * (0.96f + t * 0.64f),
            style = Stroke(width = 1.0f + (1f - t) * 2.4f + bass * 1.1f)
        )
    }
}

// ─── Слой 0.5: Живая цветная атмосфера ───────────────────────────────────────
//
// Это лёгкие Canvas-градиенты: космические облака, волны, мягкие силуэты.
// Режимы меняются сами и плавно перетекают друг в друга.

private fun DrawScope.drawDynamicAtmosphere(
    center: Offset,
    diskR: Float,
    accent: Color,
    warp: Float,
    volume: Float,
    bass: Float,
    beat: Float,
    time: Float
) {
    val segment = floor(time / ATMOSPHERE_MODE_SECONDS).toInt()
    val local = time - segment * ATMOSPHERE_MODE_SECONDS
    val blendRaw = ((local - (ATMOSPHERE_MODE_SECONDS - ATMOSPHERE_BLEND_SECONDS)) / ATMOSPHERE_BLEND_SECONDS)
        .coerceIn(0f, 1f)
    val blend = smooth01(blendRaw)

    val modeA = ATMOSPHERE_SEQUENCE[positiveMod(segment, ATMOSPHERE_SEQUENCE.size)]
    val modeB = ATMOSPHERE_SEQUENCE[positiveMod(segment + 1, ATMOSPHERE_SEQUENCE.size)]
    val intensity = (0.68f + volume * 0.16f + bass * 0.16f + beat * 0.10f + warp * 0.10f)
        .coerceIn(0.55f, 1.08f)

    drawAtmosphereMode(modeA, 1f - blend, center, diskR, accent, warp, intensity, bass, beat, time)
    drawAtmosphereMode(modeB, blend, center, diskR, accent, warp, intensity, bass, beat, time)
}

private fun DrawScope.drawAtmosphereMode(
    mode: Int,
    blendAlpha: Float,
    center: Offset,
    diskR: Float,
    accent: Color,
    warp: Float,
    intensity: Float,
    bass: Float,
    beat: Float,
    time: Float
) {
    if (blendAlpha <= 0.01f) return

    val palette = accent.cosmicPalette()
    val primary = palette.primary
    val cyan = palette.secondary
    val violet = palette.tertiary
    val magenta = lerp(palette.primary, palette.tertiary, 0.62f)
    val amber = palette.warm
    val alpha = blendAlpha * intensity

    when (mode) {
        0 -> drawNebulaClouds(center, primary, cyan, magenta, alpha, bass, beat, time)
        1 -> drawLiquidHaze(center, primary, violet, cyan, alpha, warp, time)
        2 -> drawAuroraWaves(center, cyan, magenta, violet, alpha, bass, beat, time)
        3 -> drawSoftSilhouettes(center, diskR, primary, violet, amber, alpha, bass, time)
        else -> drawCosmicVeil(center, primary, cyan, violet, alpha, warp, bass, time)
    }
}

private fun DrawScope.drawNebulaClouds(
    center: Offset,
    primary: Color,
    cyan: Color,
    magenta: Color,
    alpha: Float,
    bass: Float,
    beat: Float,
    time: Float
) {
    val maxR = maxOf(size.width, size.height)
    repeat(6) { i ->
        val phase = time * (0.035f + i * 0.009f) + i * 1.73f
        val driftX = cos(phase) * size.width * (0.18f + i * 0.018f)
        val driftY = sin(phase * 0.73f) * size.height * (0.12f + i * 0.012f)
        val pos = Offset(
            center.x + driftX + sin(phase * 0.37f) * size.width * 0.08f,
            center.y + driftY
        )
        val color = when (i % 3) {
            0 -> primary
            1 -> cyan
            else -> magenta
        }
        val radius = maxR * (0.28f + i * 0.035f + bass * 0.05f + beat * 0.035f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = alpha * (0.078f + beat * 0.035f)),
                    color.copy(alpha = alpha * 0.030f),
                    Color.Transparent
                ),
                center = pos,
                radius = radius
            ),
            center = pos,
            radius = radius
        )
    }
}

private fun DrawScope.drawLiquidHaze(
    center: Offset,
    primary: Color,
    violet: Color,
    cyan: Color,
    alpha: Float,
    warp: Float,
    time: Float
) {
    val angle = time * 0.055f
    val sweep = Offset(cos(angle) * size.width, sin(angle) * size.height)
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                primary.copy(alpha = alpha * 0.025f),
                violet.copy(alpha = alpha * (0.070f + warp * 0.016f)),
                cyan.copy(alpha = alpha * 0.038f),
                Color.Transparent
            ),
            start = center - sweep,
            end = center + sweep
        )
    )

    val pulse = 0.5f + 0.5f * sin(time * 0.31f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                cyan.copy(alpha = alpha * (0.052f + pulse * 0.020f)),
                primary.copy(alpha = alpha * 0.020f),
                Color.Transparent
            ),
            center = center,
            radius = maxOf(size.width, size.height) * (0.42f + pulse * 0.10f)
        ),
        center = center,
        radius = maxOf(size.width, size.height) * (0.42f + pulse * 0.10f)
    )
}

private fun DrawScope.drawAuroraWaves(
    center: Offset,
    cyan: Color,
    magenta: Color,
    violet: Color,
    alpha: Float,
    bass: Float,
    beat: Float,
    time: Float
) {
    val width = size.width
    repeat(4) { i ->
        val yBase = size.height * (0.24f + i * 0.15f)
        val amp = size.height * (0.035f + bass * 0.030f + i * 0.006f)
        val path = Path()
        path.moveTo(-width * 0.12f, yBase)
        repeat(6) { step ->
            val x1 = width * (step / 6f + 0.08f)
            val x2 = width * ((step + 1) / 6f + 0.08f)
            val wave = sin(time * (0.34f + i * 0.04f) + step * 1.17f + i)
            path.quadraticTo(
                x1,
                yBase + wave * amp,
                x2,
                yBase + sin(time * 0.28f + step * 1.41f + i * 0.6f) * amp * 0.75f
            )
        }
        val color = when (i % 3) {
            0 -> cyan
            1 -> magenta
            else -> violet
        }
        drawPath(
            path = path,
            color = color.copy(alpha = alpha * (0.045f + beat * 0.030f)),
            style = Stroke(width = 18f + i * 7f + bass * 18f, cap = StrokeCap.Round)
        )
        drawPath(
            path = path,
            color = Color.White.copy(alpha = alpha * (0.010f + beat * 0.014f)),
            style = Stroke(width = 2.2f + beat * 2.4f, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawSoftSilhouettes(
    center: Offset,
    diskR: Float,
    primary: Color,
    violet: Color,
    amber: Color,
    alpha: Float,
    bass: Float,
    time: Float
) {
    val maxR = maxOf(size.width, size.height)
    repeat(5) { i ->
        val phase = time * (0.022f + i * 0.006f) + i * 2.03f
        val pos = Offset(
            center.x + cos(phase) * size.width * (0.34f + i * 0.018f),
            center.y + sin(phase * 0.64f) * size.height * (0.24f + i * 0.014f)
        )
        val color = when (i % 3) {
            0 -> primary
            1 -> violet
            else -> amber
        }
        val radius = maxR * (0.24f + i * 0.040f + bass * 0.035f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = alpha * 0.060f),
                    color.copy(alpha = alpha * 0.022f),
                    Color.Transparent
                ),
                center = pos,
                radius = radius
            ),
            center = pos,
            radius = radius
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Black.copy(alpha = alpha * 0.12f),
                Color.Transparent
            ),
            center = center,
            radius = diskR * 1.18f
        ),
        center = center,
        radius = diskR * 1.18f
    )
}

private fun DrawScope.drawCosmicVeil(
    center: Offset,
    primary: Color,
    cyan: Color,
    violet: Color,
    alpha: Float,
    warp: Float,
    bass: Float,
    time: Float
) {
    val maxR = maxOf(size.width, size.height)
    repeat(5) { i ->
        val phase = ((time * (0.052f + bass * 0.030f) + i / 5f) % 1f + 1f) % 1f
        val t = smooth01(phase)
        val radius = maxR * (0.18f + t * 0.68f)
        val color = when (i % 3) {
            0 -> primary
            1 -> cyan
            else -> violet
        }
        drawCircle(
            color = color.copy(alpha = alpha * (1f - t) * (0.024f + warp * 0.020f)),
            center = center,
            radius = radius,
            style = Stroke(width = 1.4f + (1f - t) * (5.0f + bass * 4.0f))
        )
    }
}

private fun Color.cosmicAccent(): Color {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val saturation = max - min
    val luminance = red * 0.299f + green * 0.587f + blue * 0.114f

    var color = copy(alpha = 1f)
    if (saturation < 0.18f) {
        color = lerp(color, Color(0xFF57D7FF), 0.42f)
    }
    if (luminance < 0.24f) {
        color = lerp(color, Color(0xFFFF4FC3), 0.30f)
    }
    if (luminance > 0.74f) {
        color = lerp(color, Color(0xFF496DFF), 0.34f)
    }
    return color
}

private fun Color.cosmicPalette(): CosmicPalette {
    val primary = cosmicAccent()
    val secondary = primary.shiftCosmicHue(42f, saturationBoost = 1.08f, valueBoost = 1.12f)
    val tertiary = primary.shiftCosmicHue(-58f, saturationBoost = 1.12f, valueBoost = 1.06f)
    val warm = primary.shiftCosmicHue(118f, saturationBoost = 0.82f, valueBoost = 1.16f)
    val deep = lerp(Color(0xFF050712), primary, 0.28f)
    return CosmicPalette(
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        warm = warm,
        deep = deep
    )
}

private fun Color.shiftCosmicHue(
    hueShift: Float,
    saturationBoost: Float,
    valueBoost: Float
): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = (hsv[0] + hueShift + 360f) % 360f
    hsv[1] = (hsv[1] * saturationBoost).coerceIn(0.38f, 0.92f)
    hsv[2] = (hsv[2] * valueBoost).coerceIn(0.42f, 0.92f)
    return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = 1f)
}

private fun smooth01(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun positiveMod(value: Int, mod: Int): Int {
    return ((value % mod) + mod) % mod
}

// ─── Слой 1: Фоновое звёздное поле ───────────────────────────────────────────
//
// Эти звёзды НИКОГДА не двигаются — это спокойный космос для паузы.
// Когда начинается warp, слой растворяется полностью, чтобы статичные точки
// не спорили с кинематографичным полётом.

private fun DrawScope.drawBackgroundStarField(
    bgStars: Array<BgStar>,
    center:  Offset,
    diskR:   Float,
    warp:    Float,
    treble:  Float,
    time:    Float
) {
    val pauseVisibility = (1f - warp).coerceIn(0f, 1f)
    val globalVis = pauseVisibility * pauseVisibility * (3f - 2f * pauseVisibility)
    if (globalVis < 0.01f) return

    for (star in bgStars) {
        val sx = star.fx * size.width
        val sy = star.fy * size.height
        val dx = sx - center.x
        val dy = sy - center.y
        if (dx * dx + dy * dy < diskR * diskR * 0.64f) continue

        // Мерцание — мягкое, всегда активно
        val twinkle = 0.52f + 0.48f * sin(
            (time * star.twinkleFreq + star.twinklePh).toDouble()
        ).toFloat()
        val slowBlink = 0.72f + 0.28f * sin(
            (time * (0.11f + star.twinkleFreq * 0.07f) + star.twinklePh * 1.7f).toDouble()
        ).toFloat()

        // Небольшой отклик на treble — высокие частоты делают звёзды чуть ярче
        val alpha = (star.brightness * twinkle * slowBlink * globalVis * (0.72f + treble * 0.24f))
            .coerceIn(0f, 0.92f)
        if (alpha < 0.008f) continue

        val col = STAR_TINTS[star.tintIdx]
        val pos = Offset(sx, sy)

        // Крупные звёзды: мягкий ореол + точка
        if (star.hasGlow) {
            drawCircle(color = col.copy(alpha = alpha * 0.16f), center = pos, radius = star.radius * 4.2f)
            drawCircle(color = col.copy(alpha = alpha * 0.34f), center = pos, radius = star.radius * 2.0f)
        }
        drawCircle(color = col.copy(alpha = alpha), center = pos, radius = star.radius)
    }
}

// ─── Слой 2: Гиперпрыжок — 3D перспективные полосы ──────────────────────────
//
// ПРИНЦИП:
//   1. Каждая звезда — луч (baseX, baseY) в 3D-пространстве.
//   2. Проекция: screenX = cx + baseX * FOCAL / z * projScale
//   3. Чем меньше z → тем дальше от центра на экране → тем длиннее полоса.
//   4. Хвост = та же звезда, но с z_tail > z → проецируется ближе к центру.
//   5. Линия от хвоста к голове: gradient transparent → bright.
//   6. Все полосы сходятся к центральной точке схода (center).

private fun DrawScope.drawHyperspace(
    fgStars:   Array<StarDef>,
    zArr:      FloatArray,
    center:    Offset,
    projScale: Float,
    diskR:     Float,
    warp:      Float,
    bass:      Float,
    beat:      Float,
    treble:    Float,
    time:      Float
) {
    // streakFactor [0..0.90]: как далеко хвост от головы (в пространстве z).
    // При pause → 0 (точки), при play+audio → растёт → длинные полосы.
    val streakFactor = (warp * (0.72f + bass * 0.12f + beat * 0.09f)).coerceIn(0f, 0.90f)

    for (i in fgStars.indices) {
        val s = fgStars[i]
        val z = zArr[i].coerceIn(Z_NEAR, Z_FAR)

        // ── Проекция головы (текущая позиция звезды) ─────────────────────
        val invZ = 1f / z
        val hx   = center.x + s.baseX * FOCAL * invZ * projScale
        val hy   = center.y + s.baseY * FOCAL * invZ * projScale
        val dx   = hx - center.x
        val dy   = hy - center.y

        // Отсечение: под пластинкой и за краями экрана
        if (dx * dx + dy * dy < diskR * diskR * 0.46f) continue
        if (hx < -size.width  * 0.06f || hx > size.width  * 1.06f) continue
        if (hy < -size.height * 0.06f || hy > size.height * 1.06f) continue

        val nearness = 1f - z       // 0 = далеко, 1 = у зрителя
        val near2    = nearness * nearness

        // ── Яркость: дальние тусклые, близкие яркие ──────────────────────
        // В паузе — мерцание, в движении — без него (полосы не мерцают)
        val twinkle = if (warp < 0.12f)
            0.55f + 0.45f * sin((time * s.twinkleFreq + s.twinklePh).toDouble()).toFloat()
        else 1f

        val alpha = ((0.05f + near2 * 0.95f) * s.brightness * (0.10f + warp * 0.90f) * twinkle)
            .coerceIn(0f, 1f)
        if (alpha < 0.009f) continue

        // ── Физическая толщина: близкие — толще ──────────────────────────
        val coreW = (0.28f + near2 * 4.4f + bass * nearness * 1.0f).coerceAtLeast(0.26f)
        val col   = STAR_TINTS[s.tintIdx]
        val head  = Offset(hx, hy)

        // ══════════════════════════════════════════════════════════════════
        // ПАУЗА: только точки с мерцанием
        // ══════════════════════════════════════════════════════════════════
        if (streakFactor < 0.03f) {
            if (near2 > 0.22f && s.brightness > 0.52f) {
                drawCircle(color = col.copy(alpha = alpha * 0.13f), center = head, radius = coreW * 3.4f)
            }
            drawCircle(color = col.copy(alpha = alpha), center = head, radius = coreW)

            // ══════════════════════════════════════════════════════════════════
            // ВОСПРОИЗВЕДЕНИЕ: хвост к центру — гиперпрыжок
            // ══════════════════════════════════════════════════════════════════
        } else {
            // tailZ > z → на экране хвост ближе к центру (точке схода)
            // Формула: tail_screen / head_screen = z / tailZ
            // При streakFactor → 0.90: tailZ = z / (1 - 0.792) = z * 4.8 → хвост у 21% расстояния
            val tailZ    = (z / (1f - streakFactor * 0.88f)).coerceAtMost(Z_FAR * 3.0f)
            val invTailZ = 1f / tailZ
            val tail = Offset(
                center.x + s.baseX * FOCAL * invTailZ * projScale,
                center.y + s.baseY * FOCAL * invTailZ * projScale
            )

            // Мягкое свечение вокруг полосы
            if (near2 > 0.05f && alpha > 0.06f) {
                drawLine(
                    color       = col.copy(alpha = alpha * 0.08f),
                    start       = tail,
                    end         = head,
                    strokeWidth = coreW * 6.2f,
                    cap         = StrokeCap.Round
                )
            }

            // Основная полоса: прозрачная у хвоста (у центра) → яркая у головы
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        col.copy(alpha = alpha * 0.36f),
                        col.copy(alpha = alpha)
                    ),
                    start = tail,
                    end   = head
                ),
                start       = tail,
                end         = head,
                strokeWidth = coreW.coerceAtLeast(0.40f),
                cap         = StrokeCap.Round
            )

            // Яркая «голова» — ведущая точка каждой звезды
            drawCircle(
                color  = col.copy(alpha = alpha * (0.92f + beat * 0.08f)),
                center = head,
                radius = coreW * 0.68f
            )
        }
    }
}

// ─── Слой 3: Концентрические кольца ──────────────────────────────────────────
// Расходятся от центра, пульсируют на bassе и beat.
// Усиливают ощущение туннеля при гиперпрыжке.

private fun DrawScope.drawWarpRings(
    center: Offset,
    diskR:  Float,
    accent: Color,
    warp:   Float,
    bass:   Float,
    beat:   Float,
    time:   Float
) {
    if (warp < 0.04f) return
    val palette = accent.cosmicPalette()
    val maxR = maxOf(size.width, size.height) * 0.78f
    val span = maxR - diskR * 0.95f

    repeat(5) { i ->
        // Каждое кольцо сдвинуто по фазе на i/5 периода — непрерывный поток
        val phase = ((time * (0.112f + bass * 0.068f) + i.toFloat() / 5f) % 1f + 1f) % 1f
        // Smoothstep: плавный старт, быстрое расширение
        val t     = phase * phase * (3f - 2f * phase)
        val r     = diskR * 0.95f + span * t
        // Кольцо гаснет по мере удаления от центра
        val alpha = (1f - t) * (0.042f + bass * 0.050f + beat * 0.044f) * warp
        val sw    = 0.55f + (1f - t) * (1.9f + bass * 1.7f)
        drawCircle(
            color  = lerp(palette.secondary, palette.primary, i / 5f).copy(alpha = alpha),
            radius = r,
            center = center,
            style  = Stroke(width = sw)
        )
    }
}

// ─── Слой 4: Гало вокруг пластинки ───────────────────────────────────────────

private fun DrawScope.drawVinylHalo(
    center: Offset,
    diskR:  Float,
    accent: Color,
    bass:   Float,
    beat:   Float,
    warp:   Float,
    time:   Float
) {
    val palette = accent.cosmicPalette()
    val pulse = (sin((time * (2.1f + bass * 1.8f)).toDouble()).toFloat() + 1f) * 0.5f
    val r     = diskR * (1.04f + bass * 0.06f + beat * 0.07f)

    // Рассеянный ореол
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.primary.copy(alpha = (0.07f + bass * 0.10f + beat * 0.13f).coerceIn(0f, 0.40f)),
                palette.secondary.copy(alpha = (0.020f + warp * 0.030f)),
                Color.Transparent
            ),
            center = center,
            radius = r * (1.22f + pulse * 0.07f)
        ),
        center = center,
        radius = r * (1.22f + pulse * 0.07f)
    )

    // Кольцо по контуру диска
    if (warp > 0.04f) {
        drawCircle(
            color  = palette.primary.copy(alpha = ((0.06f + beat * 0.12f) * warp).coerceIn(0f, 0.40f)),
            center = center,
            radius = r,
            style  = Stroke(width = 0.8f + bass * 1.8f)
        )
    }
}
