package com.example.varcamera.diag

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A plain-text log of what happened with the capture card, kept on the tablet.
 *
 * At a match the tablet's only USB port holds the capture card, so logcat is out of
 * reach exactly when things go wrong. Everything worth knowing afterwards (plugging,
 * permissions, stream start and stop, signal, battery and heat) goes here, one file
 * per day in the app's external files, where adb can read it and the LOG button can
 * share it.
 */
object DiagnosticLog {

    private const val TAG = "VarDiag"
    private const val KEEP_DAYS = 14

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "diag-log").apply { isDaemon = true } }
    private val lineTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileDay = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        val logs = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        dir = logs
        prune(logs)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written synchronously: the process is about to die.
            append("CRASH", "in ${thread.name}: ${Log.getStackTraceString(error)}")
            previous?.uncaughtException(thread, error)
        }

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        log("App", "started v$version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    fun log(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
        val now = Date()
        writer.execute { append(tag, message, now) }
    }

    /** Today's and yesterday's files, newest last: enough to cover a match night. */
    fun recentFiles(): List<File> =
        dir?.listFiles { f -> f.name.endsWith(".txt") }?.sortedBy { it.name }?.takeLast(2).orEmpty()

    @Synchronized
    private fun append(tag: String, message: String, time: Date = Date()) {
        val logs = dir ?: return
        runCatching {
            FileWriter(File(logs, "var_${fileDay.format(time)}.txt"), true).use {
                it.write("${lineTime.format(time)}  [$tag] $message\n")
            }
        }
    }

    private fun prune(logs: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        logs.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
