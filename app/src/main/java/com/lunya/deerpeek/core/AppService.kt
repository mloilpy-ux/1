package com.lunya.deerpeek.core

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lunya.deerpeek.ai.GeminiCore
import com.lunya.deerpeek.ai.ImageGenClient
import com.lunya.deerpeek.data.MemoryManager
import com.lunya.deerpeek.data.SettingsManager
import com.lunya.deerpeek.ui.OverlayRenderer // Твой предыдущий файл перемещен в папку ui
import kotlinx.coroutines.*

class AppService : Service() {
    private lateinit var settings: SettingsManager
    private lateinit var memory: MemoryManager
    private lateinit var gemini: GeminiCore
    private lateinit var imageClient: ImageGenClient
    private lateinit var overlay: OverlayRenderer
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        
        settings = SettingsManager(this)
        memory = MemoryManager(this)
        gemini = GeminiCore(settings, memory)
        imageClient = ImageGenClient()
        overlay = OverlayRenderer(this)
        
        // Хардкод для тестов убран. Запись ключа напрямую в хранилище (временно, до создания UI настроек)
        if (settings.geminiApiKey.isEmpty()) {
            settings.geminiApiKey = "AQ.Ab8RN6LfK-1CnHzd5FBqfxANiH5uPyeYePb4pzu5-xvrGbQTNg" 
        }

        deployForeground()
        overlay.attachOverlay()
        
        dispatchPipeline("Модульная структура загружена. Проверка подсистем.")
    }

    private fun dispatchPipeline(data: String) {
        overlay.updateText("Анализ...")
        
        scope.launch {
            val textResult = gemini.executeInference(data)
            
            // Если ответ содержит маркер арт-генерации (например, [ART])
            if (textResult.contains("[ART]")) {
                imageClient.requestImageSynthesis(textResult)
            }

            withContext(Dispatchers.Main) {
                overlay.updateText(textResult)
                overlay.updateStateIcon(textResult.contains("ОШИБКА") || textResult.contains("ОТКАЗ"))
            }
        }
    }

    private fun deployForeground() {
        val channelId = "lunya_core"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System Core", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(777, NotificationCompat.Builder(this, channelId).setContentTitle("Core Active").build())
    }

    override fun onDestroy() {
        scope.cancel()
        overlay.detachOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
