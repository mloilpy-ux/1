package com.lunya.deerpeek.core

import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class TelemetryCollector(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun getSystemSnapshot(): JSONObject {
        val json = JSONObject()
        
        // 1. Метрики оперативной памяти
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availableRamGb = memoryInfo.availMem / (1024 * 1024 * 1024.0)
        
        // 2. Энергосистема
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (scale > 0) (level / scale.toFloat()) * 100 else -1.0

        // 3. Сетевой статус и задержка (RTT) до шлюза Google API
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val netType = when {
            capabilities == null -> "OFFLINE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "UNKNOWN"
        }
        val apiLatency = measureApiLatency()

        // 4. Термальный троттлинг ядра (Исправлено: привязка к API 29 / Q)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            0
        }

        // 5. Захват данных буфера обмена
        val clipboardText = try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/*") == true) {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            } else ""
        } catch (e: Exception) { "" }

        // 6. Сбор контекста локальной рабочей директории (Workspace)
        val workspaceData = readWorkspaceContext()

        json.put("available_ram_gb", String.format("%.2f", availableRamGb))
        json.put("battery_percent", batteryPct.toInt())
        json.put("network_type", netType)
        json.put("api_latency_ms", apiLatency)
        json.put("thermal_throttling_level", thermalStatus)
        json.put("clipboard_content", clipboardText.take(1000))
        json.put("workspace_context", workspaceData)
        
        return json
    }

    private fun measureApiLatency(): Int {
        return try {
            val startTime = System.currentTimeMillis()
            val url = URL("https://generativelanguage.googleapis.com")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 700
            connection.readTimeout = 700
            connection.requestMethod = "HEAD"
            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()
            if (responseCode in 200..404) {
                (System.currentTimeMillis() - startTime).toInt()
            } else -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun readWorkspaceContext(): JSONObject {
        val workspaceJson = JSONObject()
        try {
            val targetDir = context.getExternalFilesDir("workspace")
            if (targetDir != null && targetDir.exists()) {
                val logFile = File(targetDir, "latest_error.log")
                val sourceFile = File(targetDir, "source_context.py")
                
                if (logFile.exists()) workspaceJson.put("tracked_log", logFile.readText().take(500))
                if (sourceFile.exists()) workspaceJson.put("source_code", sourceFile.readText().take(1000))
            }
        } catch (e: Exception) {
            workspaceJson.put("error", e.message ?: "Read failed")
        }
        return workspaceJson
    }
}
