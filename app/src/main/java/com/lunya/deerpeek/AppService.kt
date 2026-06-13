package com.lunya.deerpeek

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AppService : Service() {
    private lateinit var aiEngine: AiEngine
    private lateinit var overlayRenderer: OverlayRenderer
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        
        aiEngine = AiEngine(this)
        overlayRenderer = OverlayRenderer(this)
        
        startForegroundNotification()
        overlayRenderer.attachOverlay()
        
        processEvent("Инициализация завершена. Анализ статуса готовности.")
    }

    private fun processEvent(eventData: String) {
        overlayRenderer.updateText("Обработка массива данных...")
        
        serviceScope.launch {
            val response = aiEngine.generateResponse(eventData)
            val isError = response.startsWith("ОШИБКА") || response.startsWith("СИСТЕМНЫЙ СБОЙ")
            
            withContext(Dispatchers.Main) {
                overlayRenderer.updateText(response)
                overlayRenderer.updateStateIcon(isError)
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "deer_core_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Deer Core", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Процесс Lunya")
            .setContentText("Модули AI и UI активны")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .build()
        startForeground(777, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        overlayRenderer.detachOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
