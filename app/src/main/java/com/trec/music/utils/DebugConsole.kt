// utils/DebugConsole.kt
//
// ТИП: Debugging Utility
//
// НАЗНАЧЕНИЕ:
// Глобальный логгер, который отображает сообщения в оверлей-окне поверх UI.
// Позволяет отлаживать приложение на телефоне без подключения к компьютеру.
//
// ЧТО СДЕЛАНО (FIXES):
// 1. Thread Safety: Запись в лог теперь обернута в Handler(Looper.getMainLooper()).
//    Это предотвращает краши при вызове DebugLogger.log() из фоновых потоков (DSP).
// 2. Performance: Ограничен размер списка (1000 элементов), чтобы не забивать память.
// 3. UI Optimization: В списке логов используется key (log.id), чтобы Compose
//    не перерисовывал весь список при добавлении новой строки.
// 4. UX: Добавлен авто-скролл к новому сообщению (опционально, если список перевернут).
//
// ЧТО ПРЕДСТОИТ СДЕЛАТЬ (FUTURE):
// 1. Фильтры: Добавить поле поиска/фильтрации по тегам.

package com.trec.music.utils

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Глобальный объект для логирования
object DebugLogger {
    // Список логов (UI State)
    val logs = mutableStateListOf<LogEntry>()

    // Управление видимостью окна
    var isVisible by mutableStateOf(false)

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun log(tag: String, message: String) {
        pushLog(tag, message, LogType.INFO)
    }

    fun error(tag: String, message: String, e: Throwable? = null) {
        val msg = if (e != null) "$message\n${e.stackTraceToString()}" else message
        pushLog(tag, msg, LogType.ERROR)
    }

    private fun pushLog(tag: String, message: String, type: LogType) {
        // Формируем запись сразу, чтобы время было точным
        val time = dateFormat.format(Date())
        val entry = LogEntry(UUID.randomUUID().toString(), time, tag, message, type)

        // UI обновления только в главном потоке!
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addEntrySafe(entry)
        } else {
            mainHandler.post { addEntrySafe(entry) }
        }
    }

    private fun addEntrySafe(entry: LogEntry) {
        logs.add(0, entry)
        if (logs.size > 1000) {
            logs.removeLast()
        }
    }

    fun clear() {
        mainHandler.post { logs.clear() }
    }

    fun getAllText(): String {
        return logs.joinToString("\n") { "[${it.time}] ${it.tag}: ${it.message}" }
    }
}

enum class LogType { INFO, ERROR }

// Добавил ID для оптимизации LazyColumn
data class LogEntry(
    val id: String,
    val time: String,
    val tag: String,
    val message: String,
    val type: LogType
)

@Composable
fun DebugConsoleWindow() {
    if (!DebugLogger.isVisible) return

    Dialog(
        onDismissRequest = { DebugLogger.isVisible = false },
        properties = DialogProperties(usePlatformDefaultWidth = false) // Fullscreen overlay
    ) {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val listState = rememberLazyListState()

        // Автоскролл к началу при добавлении новых логов (так как reverseLayout=true, начало это низ)
        // Но так как мы добавляем в index 0, а reverseLayout=true, то index 0 внизу.
        LaunchedEffect(DebugLogger.logs.size) {
            if (DebugLogger.logs.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .border(1.dp, Color.Green.copy(0.5f), RoundedCornerShape(8.dp)),
            color = Color(0xFF0D0D0D), // Hacker Black
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(Modifier.padding(8.dp)) {
                // Header Controls
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "SYSTEM LOG",
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )

                    Row {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(DebugLogger.getAllText()))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, "Copy", tint = Color.Gray)
                        }

                        IconButton(onClick = { DebugLogger.clear() }) {
                            Icon(Icons.Default.Delete, "Clear", tint = Color.Gray)
                        }

                        IconButton(onClick = { DebugLogger.isVisible = false }) {
                            Icon(Icons.Default.Close, "Close", tint = Color.Red)
                        }
                    }
                }

                HorizontalDivider(color = Color.Green.copy(0.3f))

                // Logs List
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true // Новые снизу (как в терминале)
                ) {
                    // Используем key для производительности
                    items(
                        items = DebugLogger.logs,
                        key = { it.id }
                    ) { log ->
                        LogItem(log)
                    }
                }
            }
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    val color = when (log.type) {
        LogType.INFO -> Color(0xFF00FF00)
        LogType.ERROR -> Color(0xFFFF3333)
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(
                text = "${log.time} ",
                color = Color.Gray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "[${log.tag}]",
                color = Color.Yellow.copy(0.8f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = log.message,
            color = color,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        HorizontalDivider(color = Color.White.copy(0.1f))
    }
}