package com.arkpets.mobile

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArkPetsApp : Application() {

    companion object {
        private const val TAG = "ArkPets"
        private const val CRASH_LOG = "arkpets_crash.log"
        private const val STARTUP_LOG = "arkpets_startup.log"
        private const val DATE_FMT = "yyyy-MM-dd HH:mm:ss.SSS"
    }

    override fun onCreate() {
        super.onCreate()

        // ---- 1. Crash handler — FIRST, before anything else ----
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val timestamp = SimpleDateFormat(DATE_FMT, Locale.US).format(Date())
            val crashMsg = buildString {
                appendLine("=== CRASH @ $timestamp ===")
                appendLine("Thread: ${thread.name} (id=${thread.id})")
                appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                appendLine("Stack:")
                throwable.stackTrace.take(48).forEach { appendLine("  $it") }

                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 8) {
                    appendLine("Caused by (depth=$depth): ${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace.take(16).forEach { appendLine("  $it") }
                    cause = cause.cause
                    depth++
                }
            }
            Log.e(TAG, crashMsg)

            try {
                File(filesDir, CRASH_LOG).appendText("$crashMsg\n\n")
            } catch (_: Exception) {
                // Last-resort: can't even write crash log
            }

            prevHandler?.uncaughtException(thread, throwable)
        }

        // ---- 2. Preload native library ----
        val startupLog = File(filesDir, STARTUP_LOG)
        try {
            startupLog.writeText("${SimpleDateFormat(DATE_FMT, Locale.US).format(Date())} App.onCreate start\n")
            val t0 = System.nanoTime()
            System.loadLibrary("gdx")
            val elapsed = (System.nanoTime() - t0) / 1_000_000f
            startupLog.appendText("libgdx.so loaded OK in ${"%.1f".format(elapsed)}ms\n")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load libgdx.so", e)
            try {
                startupLog.appendText("loadLibrary FAIL: ${e.message}\n")
                startupLog.appendText(Log.getStackTraceString(e) + "\n")
            } catch (_: Exception) {
                // Can't log
            }
        }
    }
}
