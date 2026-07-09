package com.undead85.messagebot

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de actividad del bot en fichero, visible desde LogActivity sin
 * necesidad de logcat/PC. Con tope de tamaño: al superarlo se conserva solo
 * la mitad más reciente.
 */
object BotLog {

    private const val FILE_NAME = "bot-log.txt"
    private const val MAX_BYTES = 200_000L
    private const val TAG = "WaBotLog"
    private val timeFormat = SimpleDateFormat("dd-MM HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(context: Context, message: String) {
        Log.d(TAG, message)
        try {
            val file = logFile(context)
            file.appendText("${timeFormat.format(Date())}  $message\n")
            if (file.length() > MAX_BYTES) {
                val lines = file.readLines()
                file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo escribir el registro", e)
        }
    }

    @Synchronized
    fun read(context: Context): String =
        logFile(context).takeIf { it.exists() }?.readText().orEmpty()

    @Synchronized
    fun clear(context: Context) {
        logFile(context).delete()
    }

    private fun logFile(context: Context) = File(context.filesDir, FILE_NAME)
}
