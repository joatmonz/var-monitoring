package com.example.varcamera.usbtest

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One log file per app start, written line by line so nothing is lost if the phone or
 * the app dies mid-test. [onLine] mirrors every line to the screen.
 */
class TestLog(dir: File, private val onLine: (String) -> Unit) {

    val file: File
    private val writer: BufferedWriter
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        file = File(dir, "var_usb_test_$stamp.txt")
        writer = file.bufferedWriter()
    }

    /** [screen] false keeps bulky lines (hex, picture data) in the file only. */
    @Synchronized
    fun log(message: String, screen: Boolean = true) {
        val line = "${time.format(Date())}  $message"
        Log.i("VarUsbTest", message)
        runCatching {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
        if (screen) onLine(line)
    }

    fun section(title: String) = log("===== $title =====")

    /** Raw bytes, 32 per line, so the descriptors can be re-read later by any tool. */
    fun hex(data: ByteArray, length: Int = data.size) {
        var offset = 0
        while (offset < length) {
            val end = minOf(offset + 32, length)
            val bytes = (offset until end).joinToString(" ") { "%02x".format(data[it].toInt() and 0xff) }
            log("%04x: %s".format(offset, bytes), screen = false)
            offset = end
        }
    }
}
