package com.abeswastaken.trades.util

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Small rotating file log (two files of 256 KB), viewable and shareable from Settings. */
object AppLog {
    private const val MAX = 256 * 1024
    private lateinit var file: File
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    fun init(context: Context) {
        file = File(context.filesDir, "logs/trades.log").apply { parentFile?.mkdirs() }
    }

    @Synchronized
    fun i(msg: String) = write("I", msg, null)

    @Synchronized
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    private fun write(level: String, msg: String, t: Throwable?) {
        Log.println(if (level == "E") Log.ERROR else Log.INFO, "Trades", msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        if (!::file.isInitialized) return
        runCatching {
            if (file.length() > MAX) file.renameTo(File(file.parentFile, "trades.1.log"))
            file.appendText("${LocalDateTime.now().format(fmt)} $level $msg${t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""}\n")
        }
    }

    fun tail(lines: Int = 300): String =
        if (!::file.isInitialized || !file.exists()) "Log is empty."
        else file.readLines().takeLast(lines).joinToString("\n")

    fun files(): List<File> = listOfNotNull(File(file.parentFile, "trades.1.log").takeIf { it.exists() }, file.takeIf { it.exists() })
}
