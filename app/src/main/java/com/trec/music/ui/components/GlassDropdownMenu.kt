// ui/components/GlassDropdownMenu.kt
//
// ТИП: UI Component (Glassmorphism Dropdown Menu)
//
// НАЗНАЧЕНИЕ:
// Стилизованное выпадающее меню в стиле glassmorphism.
// Анимированное, с blur-эффектом и современным дизайном.
//
// ИЗМЕНЕНИЯ:
// 1. Full Implementation: Новый компонент для красивых выпадающих списков.

package com.trec.music.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.trec.music.ui.theme.TrecRed

/**
 * Стильный glassmorphism dropdown меню
 * 
 * @param expanded Показывать ли меню
 * @param onDismissRequest Callback при закрытии
 * @param modifier Modifier для позиционирования
 * @param content Содержимое меню
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(150)) + 
                slideInVertically(
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                    initialOffsetY = { with(density) { (-20).dp.roundToPx() } }
                ),
        exit = fadeOut(animationSpec = tween(100)) + 
               slideOutVertically(
                   animationSpec = tween(150),
                   targetOffsetY = { with(density) { (-10).dp.roundToPx() } }
               )
    ) {
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true)
        ) {
            Box(
                modifier = modifier
                    .width(200.dp)
                    .shadow(
                        elevation = 16.dp,
                        spotColor = Color.Black.copy(0.5f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        color = Color(0xFF1A1A1A).copy(0.95f)
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(0.1f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(8.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Элемент выпадающего меню
 * 
 * @param text Текст элемента
 * @param icon Иконка (опционально)
 * @param selected Выбран ли элемент
 * @param onClick Callback при нажатии
 */
@Composable
fun GlassDropdownMenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) TrecRed.copy(0.15f) else Color.Transparent,
        animationSpec = tween(150),
        label = "bg"
    )
    
    val textColor by animateColorAsState(
        targetValue = if (selected) TrecRed else Color.White,
        animationSpec = tween(150),
        label = "text"
    )
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
        }
        
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
        
        Spacer(Modifier.weight(1f))
        
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(animationSpec = tween(150)),
            exit = scaleOut(animationSpec = tween(100))
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = TrecRed,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Разделитель в меню
 */
@Composable
fun GlassDropdownDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(1.dp)
            .background(Color.White.copy(0.08f))
    )
}

/**
 * Заголовок в меню
 */
@Composable
fun GlassDropdownHeader(
    text: String
) {
    Text(
        text = text.uppercase(),
        color = Color.Gray,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
