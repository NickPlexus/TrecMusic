package com.trec.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trec.music.ui.theme.TrecBlack
import com.trec.music.ui.LocalBottomOverlayPadding

enum class LegalType { PRIVACY, TERMS }

@Composable
fun LegalScreen(type: LegalType, onBack: () -> Unit) {
    val updatedAt = "17 марта 2026"
    val title = if (type == LegalType.PRIVACY) "Политика конфиденциальности" else "Условия использования"

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(TrecBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), Color.Transparent, TrecBlack)
                    )
                )
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
                Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Обновлено: $updatedAt", color = Color.White.copy(0.6f), fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
            }

            val scrollState = rememberScrollState()
            val bottomOverlay = LocalBottomOverlayPadding.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = bottomOverlay + 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (type == LegalType.PRIVACY) {
                    LegalSection("1. Общие положения") {
                        LegalText("TREC Music — офлайн‑плеер с дополнительными функциями (радио, диктофон, тексты песен). Мы уважаем приватность и не собираем лишние данные.")
                    }
                    LegalSection("2. Какие данные обрабатываются") {
                        LegalText("Локальные файлы музыки и записи микрофона обрабатываются на устройстве. Приложение не загружает вашу медиатеку на сервер.")
                        LegalText("Сетевые функции (радио/тексты) обращаются к внешним сервисам. Эти сервисы могут получать технические данные соединения (IP‑адрес, тип устройства, User‑Agent).")
                    }
                    LegalSection("3. Разрешения") {
                        LegalText("Микрофон — для записи. Память/медиатека — для доступа к локальной музыке. Уведомления — для фонового воспроизведения (если включены).")
                    }
                    LegalSection("4. Хранение данных") {
                        LegalText("Плейлисты, настройки и записи хранятся локально на устройстве в системном хранилище приложения.")
                    }
                    LegalSection("5. Передача третьим лицам") {
                        LegalText("Мы не продаём и не передаём ваши персональные данные. Внешние радиопотоки и сервисы текстов работают по своим правилам.")
                    }
                    LegalSection("6. Контакты") {
                        LegalText("По вопросам конфиденциальности: nickplexus@gmail.com")
                    }
                } else {
                    LegalSection("1. Лицензия") {
                        LegalText("TREC Music предоставляется для личного использования. Запрещено использовать приложение для нарушения прав третьих лиц.")
                    }
                    LegalSection("2. Контент") {
                        LegalText("Вы несёте ответственность за законность используемых аудиофайлов и источников радио‑потоков.")
                    }
                    LegalSection("3. Сетевые функции") {
                        LegalText("Радио и тексты песен предоставляются сторонними сервисами. Мы не гарантируем их доступность и качество.")
                    }
                    LegalSection("4. Ограничение ответственности") {
                        LegalText("Приложение предоставляется «как есть». Мы не отвечаем за потерю данных, вызванную сбоями устройства или ОС.")
                    }
                    LegalSection("5. Изменения") {
                        LegalText("Мы можем обновлять эти условия. Дата последнего обновления указана вверху экрана.")
                    }
                    LegalSection("6. Контакты") {
                        LegalText("Вопросы по условиям использования: nickplexus@gmail.com")
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LegalSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        content()
    }
}

@Composable
private fun LegalText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(0.8f),
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
}
