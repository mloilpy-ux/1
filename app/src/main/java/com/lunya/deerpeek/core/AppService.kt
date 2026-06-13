package com.lunya.deerpeek.core

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lunya.deerpeek.ai.GeminiCore
import com.lunya.deerpeek.ai.GeminiImagenClient
import com.lunya.deerpeek.data.MemoryManager
import com.lunya.deerpeek.data.SettingsManager
import com.lunya.deerpeek.ui.OverlayRenderer
import kotlinx.coroutines.*

class AppService : Service(), LunyaBrain.BrainCallback {
    private lateinit var settings: SettingsManager
    private lateinit var memory: MemoryManager
    private lateinit var geminiCore: GeminiCore
    private lateinit var imagenClient: GeminiImagenClient
    private lateinit var brain: LunyaBrain
    private lateinit var overlay: OverlayRenderer
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        
        settings = SettingsManager(this)
        memory = MemoryManager(this)
        geminiCore = GeminiCore(settings, memory)
        imagenClient = GeminiImagenClient(settings)
        brain = LunyaBrain(geminiCore, imagenClient)
        overlay = OverlayRenderer(this)
        
        deployForeground()
        overlay.attachOverlay()
        
        // Подача стартового импульса
        triggerBrainPipeline("Запуск системы. Выполни сканирование среды.")
    }

    private fun triggerBrainPipeline(input: String) {
        serviceScope.launch {
            brain.executePipeline(input, this@AppService)
        }
    }

    override fun onTextReady(text: String) {
        launch(Dispatchers.Main) { overlay.updateText(text) }
    }

    override fun onStickerReady(bitmap: Bitmap) {
        launch(Dispatchers.Main) { overlay.applyNewSticker(bitmap) }
    }

    override fun onStatusChanged(isThinking: Boolean, isError: Boolean) {
        launch(Dispatchers.Main) { overlay.setVisualState(isThinking, isError) }
    }

    private fun deployForeground() {
        val channelId = "lunya_advanced_core"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Lunya Operations", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(777, NotificationCompat.Builder(this, channelId).setContentTitle("Lunya Core Online").build())
    }

    override fun onDestroy() {
        serviceScope.cancel()
        overlay.detachOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
