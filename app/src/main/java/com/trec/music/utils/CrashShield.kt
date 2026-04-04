package com.trec.music.utils

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Best-effort crash shield:
 * - Writes last crash stacktrace to internal storage.
 * - Schedules a fast restart, so the app doesn't look like it "just closed".
 *
 * Note: This can't prevent OS kills (OOM/SIGKILL), but it helps for uncaught exceptions.
 */
object CrashShield {
    private const val LAST_CRASH_FILE = "last_crash.txt"

    fun install(app: Application, restartIntent: Intent) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    pw.println("At: $ts")
                    try {
                        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
                        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toString() else {
                            @Suppress("DEPRECATION")
                            pi.versionCode.toString()
                        }
                        pw.println("App: ${app.packageName} ${pi.versionName} ($code)")
                    } catch (_: Exception) {
                        pw.println("App: ${app.packageName}")
                    }
                    pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
                    pw.println("Thread: ${t.name}")
                    e.printStackTrace(pw)
                }
                app.openFileOutput(LAST_CRASH_FILE, Context.MODE_PRIVATE).use { out ->
                    out.write(sw.toString().toByteArray())
                }
            } catch (_: Exception) {
                // ignore
            }

            // Try to restart quickly (some devices may ignore).
            try {
                val pi = PendingIntent.getActivity(
                    app,
                    9911,
                    restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setExact(AlarmManager.RTC, System.currentTimeMillis() + 350L, pi)
            } catch (_: Exception) {
                // ignore
            }

            // Delegate to default handler if present.
            previous?.uncaughtException(t, e)

            // Fallback termination.
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    fun consumeLastCrash(app: Context): String? {
        return try {
            val file = app.getFileStreamPath(LAST_CRASH_FILE)
            if (!file.exists()) return null
            val text = file.readText()
            // consume
            runCatching { file.delete() }
            text.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
