package com.arkpets.mobile

import android.app.Application
import java.io.File

class ArkPetsApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Set up crash handler FIRST
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val msg = buildString {
                appendLine("=== CRASH ===")
                appendLine("Thread: ${t.name}")
                appendLine("Exception: ${e.javaClass.name}: ${e.message}")
                for (line in e.stackTrace.take(40)) appendLine(line.toString())
                var cause = e.cause
                while (cause != null) {
                    appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                    for (line in cause.stackTrace.take(20)) appendLine(line.toString())
                    cause = cause.cause
                }
            }
            try { File(filesDir, "arkpets_crash.log").appendText("$msg\n\n") } catch (_: Exception) {}
            android.util.Log.e("ArkPets", msg)
            prev?.uncaughtException(t, e)
        }

        // Log and load native library
        try {
            File(filesDir, "arkpets_startup.log").writeText("App.onCreate start\n")
            System.loadLibrary("gdx")
            File(filesDir, "arkpets_startup.log").appendText("libgdx.so loaded OK\n")
        } catch (e: Throwable) {
            try {
                File(filesDir, "arkpets_startup.log").appendText("loadLibrary FAIL: ${e.message}\n")
                File(filesDir, "arkpets_startup.log").appendText(
                    android.util.Log.getStackTraceString(e) + "\n"
                )
            } catch (_: Exception) {}
        }
    }
}
