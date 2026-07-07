package com.trec.music.utils

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object CrashShield {
    private const val LAST_CRASH_FILE = "last_crash.txt"

    // Резервные 512 КБ памяти — при OOM освобождаем их первыми,
    // чтобы хватило места записать отчёт о крашe
    private var memoryReserve: ByteArray? = ByteArray(512 * 1024)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // OOM — сразу освобождаем резерв и просим GC
                // Без этого запись в файл тоже упадёт с OOM
                if (error is OutOfMemoryError) {
                    memoryReserve = null
                    System.gc()
                }

                val report = buildReport(app, thread, error)

                app.openFileOutput(LAST_CRASH_FILE, Context.MODE_PRIVATE).use { out ->
                    out.write(report.toByteArray())
                }
            } catch (_: Throwable) {
                // Если даже запись упала — не падаем повторно
            }

            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Throwable) {
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun buildReport(app: Application, thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        pw.println("════════════════════════════════════════")
        pw.println("       TREC MUSIC — ОТЧЁТ О КРАШE")
        pw.println("════════════════════════════════════════")
        pw.println()

        // --- Время ---
        pw.println("Время: $timestamp")

        // --- Версия приложения ---
        try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.longVersionCode.toString()
            else
                @Suppress("DEPRECATION") pi.versionCode.toString()
            pw.println("Версия приложения: ${pi.versionName} (build $code)")
        } catch (_: Throwable) {
            pw.println("Версия приложения: неизвестна")
        }

        // --- Устройство ---
        pw.println()
        pw.println("── УСТРОЙСТВО ──────────────────────────")
        pw.println("Производитель: ${Build.MANUFACTURER}")
        pw.println("Модель: ${Build.MODEL} (${Build.DEVICE})")
        pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        pw.println("Архитектура: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")

        // --- Память JVM ---
        pw.println()
        pw.println("── ПАМЯТЬ ──────────────────────────────")
        try {
            val rt = Runtime.getRuntime()
            val maxMb = rt.maxMemory() / 1024 / 1024
            val totalMb = rt.totalMemory() / 1024 / 1024
            val freeMb = rt.freeMemory() / 1024 / 1024
            val usedMb = totalMb - freeMb
            pw.println("JVM: использовано ${usedMb}MB / выделено ${totalMb}MB / лимит ${maxMb}MB")
        } catch (_: Throwable) {
            pw.println("JVM память: не удалось получить")
        }

        // --- Системная RAM ---
        try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / 1024 / 1024
            val totalMb = memInfo.totalMem / 1024 / 1024
            val lowMemWarning = if (memInfo.lowMemory) " ⚠️ КРИТИЧЕСКИ МАЛО ПАМЯТИ" else ""
            pw.println("RAM: доступно ${availMb}MB / всего ${totalMb}MB$lowMemWarning")
        } catch (_: Throwable) {
            pw.println("RAM: не удалось получить")
        }

        // --- Тип краша ---
        pw.println()
        pw.println("── КРАШ ────────────────────────────────")
        val crashType = when (error) {
            is OutOfMemoryError    -> "💀 OUT OF MEMORY — кончилась память"
            is StackOverflowError  -> "💀 STACK OVERFLOW — бесконечная рекурсия"
            is NullPointerException -> "NULL POINTER — обращение к пустому объекту"
            is IllegalStateException -> "ILLEGAL STATE — неверное состояние"
            is IllegalArgumentException -> "ILLEGAL ARGUMENT — неверный аргумент"
            is SecurityException   -> "SECURITY — нет нужного разрешения"
            is RuntimeException    -> "RUNTIME — ${error.javaClass.simpleName}"
            else -> error.javaClass.simpleName
        }
        pw.println("Тип: $crashType")
        pw.println("Поток: ${thread.name} (id=${thread.id})")
        pw.println("Главный поток: ${thread == Thread.currentThread()}")

        // --- Стектрейс ---
        pw.println()
        pw.println("── СТЕКТРЕЙС ───────────────────────────")
        error.printStackTrace(pw)

        // --- Цепочка причин ---
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 5) {
            pw.println()
            pw.println("── ПРИЧИНА ${depth + 1} ─────────────────────────")
            cause.printStackTrace(pw)
            cause = cause.cause
            depth++
        }

        // --- Все активные потоки ---
        // Полезно при дедлоках и зависаниях
        try {
            pw.println()
            pw.println("── АКТИВНЫЕ ПОТОКИ ─────────────────────")
            Thread.getAllStackTraces()
                .filter { (_, stack) -> stack.isNotEmpty() }
                .forEach { (t, stack) ->
                    pw.println()
                    pw.println("  ${t.name} [${t.state}]${if (t.isDaemon) " (daemon)" else ""}")
                    // Первые 6 строк стека — достаточно чтобы понять что делал поток
                    stack.take(6).forEach { frame ->
                        pw.println("    at $frame")
                    }
                }
        } catch (_: Throwable) {
            pw.println("(не удалось получить список потоков)")
        }

        pw.println()
        pw.println("════════════════════════════════════════")

        pw.flush()
        return sw.toString()
    }

    fun consumeLastCrash(app: Context): String? {
        return try {
            val file = app.getFileStreamPath(LAST_CRASH_FILE)
            if (!file.exists()) return null
            val text = file.readText()
            runCatching { file.delete() }
            text.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
