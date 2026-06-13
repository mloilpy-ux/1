package com.lunya.deerpeek.core

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lunya.deerpeek.ai.GeminiCore
import com.lunya.deerpeek.ai.GeminiImagenClient
import com.lunya.deerpeek.data.MemoryManager
import com.lunya.deerpeek.data.SettingsManager
import com.lunya.deerpeek.ui.OverlayRenderer
import kotlinx.coroutines.*
import org.json.JSONObject

class AppService : Service(), LunyaBrain.BrainCallback {
    private lateinit var settings: SettingsManager
    private lateinit var memory: MemoryManager
    private lateinit var geminiCore: GeminiCore
    private lateinit var imagenClient: GeminiImagenClient
    private lateinit var brain: LunyaBrain
    private lateinit var overlay: OverlayRenderer
    private lateinit var telemetry: TelemetryCollector
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var tickerJob: Job? = null
    private var lastCachedClipboard = ""

    override fun onCreate() {
        super.onCreate()
        
        settings = SettingsManager(this)
        memory = MemoryManager(this)
        geminiCore = GeminiCore(settings, memory)
        imagenClient = GeminiImagenClient(settings)
        brain = LunyaBrain(geminiCore, imagenClient)
        overlay = OverlayRenderer(this)
        telemetry = TelemetryCollector(this)
        
        deployForeground()
        
        if (Settings.canDrawOverlays(this)) {
            overlay.attachOverlay()
        } else {
            Log.e("AppService", "SYSTEM_ALERT_WINDOW permission missing.")
        }
        
        startCognitiveTicker()
        startClipboardListener()
    }

    private fun startCognitiveTicker() {
        tickerJob = serviceScope.launch {
            while (isActive) {
                val snapshot = telemetry.getSystemSnapshot()
                // Передача строго по позиционному индексу аргументов
                brain.executePipeline(snapshot, "Плановый аудит дельт среды.", this@AppService)
                delay(60000)
            }
        }
    }

    private fun startClipboardListener() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            serviceScope.launch {
                val snapshot = telemetry.getSystemSnapshot()
                val currentClip = snapshot.optString("clipboard_content", "")
                
                if (currentClip.isNotEmpty() && currentClip != lastCachedClipboard) {
                    lastCachedClipboard = currentClip
                    brain.executePipeline(snapshot, "Событие изменения системного буфера.", this@AppService)
                }
            }
        }
    }

    override fun onExecuteAction(clipboardFix: String) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                lastCachedClipboard = clipboardFix
                val clip = ClipData.newPlainText("Lunya Dynamic Patch", clipboardFix)
                clipboard.setPrimaryClip(clip)
                if (Settings.canDrawOverlays(this@AppService)) {
                    overlay.updateText("[WORKSPACE_FIX] Изменения зафиксированы в буфере.")
                }
            } catch (e: Exception) {
                Log.e("AppService", "Clipboard injection failed")
            }
        }
    }

    override fun onTextReady(text: String) {
        serviceScope.launch(Dispatchers.Main) {
            if (Settings.canDrawOverlays(this@AppService)) {
                overlay.updateText(text)
            }
        }
    }

    override fun onStickerReady(bitmap: Bitmap) {
        serviceScope.launch(Dispatchers.Main) {
            if (Settings.canDrawOverlays(this@AppService)) {
                overlay.applyNewSticker(bitmap)
            }
        }
    }

    override fun onStatusChanged(isThinking: Boolean, isError: Boolean) {
        serviceScope.launch(Dispatchers.Main) {
            if (Settings.canDrawOverlays(this@AppService)) {
                overlay.setVisualState(isThinking, isError)
            }
        }
    }

    private fun deployForeground() {
        val channelId = "lunya_isolated_core"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Lunya Core Execution", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DeerPeek Engine Active")
            .setContentText("Контур отслеживания буфера и дельт среды запущен.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(778, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(778, notification)
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceScope.cancel()
        if (Settings.canDrawOverlays(this)) {
            overlay.detachOverlay()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
