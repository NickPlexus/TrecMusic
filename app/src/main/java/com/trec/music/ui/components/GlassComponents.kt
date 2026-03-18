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
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trec.music.ui.theme.TrecDarkGray

@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
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
                    .padding(24.dp)
                    .fillMaxWidth()
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
                    )
                    .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = TrecDarkGray.copy(alpha = 0.95f),
                tonalElevation = 16.dp,
                shadowElevation = 16.dp
            ) {
                Box(Modifier.padding(24.dp)) {
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
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.6f))
                )
            )
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material.ripple.rememberRipple(color = Color.White)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
fun GlassTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = Color.White.copy(0.7f),
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
            .padding(vertical = 4.dp),
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
            modifier = Modifier.padding(start = 16.dp).weight(1f, fill = false)
        )
    }
}