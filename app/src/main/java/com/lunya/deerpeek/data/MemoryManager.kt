package com.lunya.deerpeek.data

import android.content.Context
import android.util.Log
import java.io.File

class MemoryManager(context: Context) {
    private val logFile = File(context.filesDir, "lunya_transaction.log")

    init {
        if (!logFile.exists()) logFile.createNewFile()
    }

    fun record(entry: String) {
        try {
            logFile.appendText("${System.currentTimeMillis()} | $entry\n")
        } catch (e: Exception) {
            Log.e("MemoryManager", "I/O Error: ${e.message}")
        }
    }

    fun extractContext(depth: Int = 12): String {
        return try {
            logFile.readLines().takeLast(depth).joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }
}
