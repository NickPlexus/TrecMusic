package com.trec.music.ui.theme

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.compositeOver

fun Color.liquidAccent(): Color {
    if (this == Color.Unspecified) return TrecRed

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)

    val originalAlpha = alpha
    val saturation = hsl[1].coerceIn(0f, 1f)
    val lightness = hsl[2].coerceIn(0f, 1f)

    if (saturation < 0.10f) {
        val base = Color(red, green, blue, 1f)
        val lift = when {
            lightness < 0.20f -> 0.74f
            lightness < 0.40f -> 0.58f
            lightness > 0.80f -> 0.20f
            else -> 0.38f
        }
        return Color.White
            .copy(alpha = lift)
            .compositeOver(base)
            .copy(alpha = originalAlpha)
    }

    hsl[1] = when {
        saturation < 0.24f -> 0.34f
        else -> saturation.coerceIn(0.28f, 0.86f)
    }
    hsl[2] = when {
        lightness < 0.30f -> 0.58f
        lightness < 0.46f -> 0.54f
        lightness > 0.78f -> 0.64f
        else -> lightness.coerceIn(0.50f, 0.68f)
    }

    return Color(ColorUtils.HSLToColor(hsl)).copy(alpha = originalAlpha)
}

fun Color.liquidOnAccent(): Color {
    return if (liquidAccent().luminance() > 0.58f) Color(0xFF070A10) else Color.White
}

fun Modifier.liquidGlassSurface(
    accent: Color,
    shape: Shape,
    fillAlpha: Float = 0.10f,
    borderWidth: Dp = 1.dp
): Modifier {
    val liquid = accent.liquidAccent()
    val glassTint = liquid.copy(alpha = (fillAlpha * 0.92f).coerceIn(0.03f, 0.18f))
    return clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = (fillAlpha * 0.32f).coerceIn(0.02f, 0.08f)),
                    glassTint,
                    Color(0xFF080A0E).copy(alpha = 0.34f),
                    Color.Black.copy(alpha = 0.30f)
                )
            )
        )
        .background(
            Brush.radialGradient(
                listOf(
                    liquid.copy(alpha = (fillAlpha * 0.58f).coerceIn(0.02f, 0.10f)),
                    Color.Transparent
                )
            )
        )
        .border(
            width = borderWidth,
            brush = Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.20f),
                    liquid.copy(alpha = 0.32f),
                    Color.White.copy(alpha = 0.06f)
                )
            ),
            shape = shape
        )
}

@Composable
fun Modifier.experimentalLiquidRefraction(
    enabled: Boolean = true,
    intensity: Float = 0.24f
): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this

    val shader = remember { RuntimeShader(LiquidGlassAgsl) }
    var width by remember { mutableStateOf(0f) }
    var height by remember { mutableStateOf(0f) }
    val phase by rememberInfiniteTransition(label = "liquidPhase").animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "liquidPhaseValue"
    )

    SideEffect {
        shader.setFloatUniform("size", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        shader.setFloatUniform("phase", phase)
        shader.setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
    }

    return onSizeChanged {
        width = it.width.toFloat()
        height = it.height.toFloat()
    }.graphicsLayer {
        if (width > 0f && height > 0f) {
            compositingStrategy = CompositingStrategy.Offscreen
            renderEffect = AndroidRenderEffect
                .createRuntimeShaderEffect(shader, "composable")
                .asComposeRenderEffect()
        }
    }
}

private const val LiquidGlassAgsl = """
uniform shader composable;
uniform float2 size;
uniform float phase;
uniform float intensity;

half4 main(float2 p) {
    float2 uv = p / size;
    float2 center = uv - float2(0.5, 0.5);
    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    float rim = 1.0 - smoothstep(0.0, 0.18, edge);
    float lens = smoothstep(0.72, 0.0, length(center));
    float wave = sin((uv.x * 10.0 + uv.y * 6.0) + phase) * 0.5 + 0.5;

    float2 normal = normalize(center + float2(0.001, 0.001));
    float2 shimmer = float2(
        sin(uv.y * 18.0 + phase),
        cos(uv.x * 15.0 - phase)
    ) * intensity * (0.65 + rim);
    float2 offset = normal * (rim * 5.5 + lens * 1.2) * intensity + shimmer;

    half4 base = composable.eval(p + offset);
    half4 red = composable.eval(p + offset + float2(1.5, 0.0) * intensity);
    half4 blue = composable.eval(p + offset - float2(1.5, 0.0) * intensity);
    base.r = mix(base.r, red.r, 0.24 * intensity);
    base.b = mix(base.b, blue.b, 0.24 * intensity);

    float topGlow = (1.0 - smoothstep(0.0, 0.52, uv.y)) * 0.08 * intensity;
    float edgeGlow = rim * (0.12 + wave * 0.08) * intensity;
    float2 specPoint = uv - float2(0.24 + sin(phase) * 0.04, 0.12);
    float specular = pow(max(0.0, 1.0 - length(specPoint * float2(2.2, 7.0))), 5.0) * 0.46 * intensity;
    float bottomShade = smoothstep(0.52, 1.0, uv.y) * 0.08 * intensity;

    base.rgb += half3(topGlow + edgeGlow + specular) * base.a;
    base.rgb -= half3(bottomShade) * base.a;
    return base;
}
"""
