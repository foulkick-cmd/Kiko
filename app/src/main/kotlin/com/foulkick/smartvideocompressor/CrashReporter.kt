package com.foulkick.smartvideocompressor

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes any uncaught exception to a private file so the next launch can show the stack trace
 * in-app. This app is used on a phone without adb access, so this is the only practical way to
 * get a real stack trace out of a crash instead of guessing at the cause.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readAndClear(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("Smart Video Compressor crash report")
            appendLine("Time: $timestamp")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(Log.getStackTraceString(throwable))
        }
        File(context.filesDir, FILE_NAME).writeText(report)
    }
}
