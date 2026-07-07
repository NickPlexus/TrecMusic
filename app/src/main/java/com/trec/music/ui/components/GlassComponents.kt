// ui/components/GlassComponents.kt
//
// ТИП: UI Kit (Reusable Components)
//
// ИСПРАВЛЕНИЕ (CRITICAL INPUT FIX):
// Исправлена ошибка, из-за которой текстовые поля не получали фокус.
// Surface диалога теперь использует активный clickable (без действия),
// чтобы гарантированно перехватывать нажатия и не давать им уходить в фон.

package com.trec.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trec.music.ui.theme.TrecDarkGray
import com.trec.music.ui.theme.liquidAccent
import com.trec.music.ui.theme.liquidGlassSurface
import com.trec.music.ui.theme.liquidOnAccent
import com.trec.music.ui.theme.TrecRadius
import com.trec.music.ui.theme.TrecSpacing
import com.trec.music.ui.theme.TrecTouchTarget

@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val dialogShape = RoundedCornerShape(TrecRadius.Dialog)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Scrim (Затемнение фона)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .imePadding() // Сдвиг при появлении клавиатуры
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss // Закрытие по клику на фон
                ),
            contentAlignment = Alignment.Center
        ) {
            // Само окно диалога
            Surface(
                modifier = Modifier
                    .padding(TrecSpacing.Xl)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 26.dp,
                        shape = dialogShape,
                        ambientColor = Color.Transparent,
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                    )
                    .liquidGlassSurface(
                        accent = MaterialTheme.colorScheme.primary,
                        shape = dialogShape,
                        fillAlpha = 0.13f
                    )
                    // !!! ГЛАВНЫЙ ФИКС !!!
                    // enabled = true (было false).
                    // Теперь Surface "съедает" клик, не пуская его в Box.
                    // onClick пустой, чтобы ничего не происходило.
                    // interactionSource/indication нужны, чтобы убрать анимацию клика (ripple) на самом окне.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = true,
                        onClick = {}
                    ),
                shape = dialogShape,
                color = TrecDarkGray.copy(alpha = 0.30f),
                tonalElevation = 16.dp,
                shadowElevation = 16.dp
            ) {
                Box(Modifier.padding(TrecSpacing.Xl)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    val liquid = color.liquidAccent()
    val shape = RoundedCornerShape(TrecRadius.Control)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = TrecTouchTarget.Min)
            .height(50.dp)
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = Color.Transparent,
                spotColor = liquid.copy(alpha = 0.34f)
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.56f),
                        liquid.copy(alpha = 0.92f),
                        liquid.copy(alpha = 0.62f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.58f),
                        liquid.copy(alpha = 0.62f),
                        Color.White.copy(alpha = 0.18f)
                    )
                ),
                shape = shape
            )
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = liquid.copy(alpha = 0.65f)),
                role = Role.Button
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = liquid.liquidOnAccent(),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = TrecSpacing.Md)
        )
    }
}

@Composable
fun GlassTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary.liquidAccent(),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TrecSpacing.Xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White.copy(0.5f),
            fontSize = 14.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = TrecSpacing.Lg).weight(1f, fill = false)
        )
    }
}
