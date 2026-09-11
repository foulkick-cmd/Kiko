package com.foulkick.smartvideocompressor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Surfaces why the app last died so it can be copied out of the app without adb access.
 *
 * Two sources, because they cover different kinds of death:
 *  - A default uncaught-exception handler writes Java/Kotlin crashes (stack trace) to a file.
 *  - ActivityManager's historical process-exit records cover everything the handler cannot see:
 *    native crashes (SIGSEGV/SIGABRT inside codec/GL code, which Media3 exercises), ANRs, low
 *    memory kills, and system kills -- with the tombstone/ANR trace where the OS kept one.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val MAX_TRACE_CHARS = 12_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeJavaCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns a report for the previous run if it died abnormally, consuming it so it shows once. */
    fun collectReport(context: Context): String? {
        val javaCrash = readAndClearJavaCrash(context)
        val exitInfo = runCatching { readNewExitInfo(context) }.getOrNull()
        if (javaCrash == null && exitInfo == null) return null
        return buildString {
            appendLine("Smart Video Compressor crash report")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            if (javaCrash != null) {
                appendLine("=== Uncaught exception ===")
                appendLine(javaCrash)
                appendLine()
            }
            if (exitInfo != null) {
                appendLine("=== Last process exit (from Android) ===")
                appendLine(exitInfo)
            }
        }
    }

    private fun readAndClearJavaCrash(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text
    }

    private fun writeJavaCrash(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            append(Log.getStackTraceString(throwable))
        }
        File(context.filesDir, FILE_NAME).writeText(report)
    }

    private fun readNewExitInfo(context: Context): String? {
        val am = context.getSystemService(ActivityManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        val latest = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            ?: return null
        if (latest.timestamp <= lastSeen) return null
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, latest.timestamp).apply()

        // Normal exits are not worth showing.
        if (latest.reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
            latest.reason == ApplicationExitInfo.REASON_EXIT_SELF ||
            latest.reason == ApplicationExitInfo.REASON_USER_STOPPED ||
            latest.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED ||
            latest.reason == ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE
        ) return null

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(latest.timestamp))
        return buildString {
            appendLine("Time: $timestamp")
            appendLine("Reason: ${reasonName(latest.reason)} (${latest.reason}), status ${latest.status}")
            appendLine("Importance: ${latest.importance}, pss ${latest.pss} kB, rss ${latest.rss} kB")
            appendLine("Description: ${latest.description}")
            val trace = runCatching {
                latest.traceInputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!trace.isNullOrBlank()) {
                appendLine()
                appendLine("--- trace ---")
                append(trace.take(MAX_TRACE_CHARS))
                if (trace.length > MAX_TRACE_CHARS) appendLine("\n[... truncated ...]")
            }
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "REASON_$reason"
    }
}
