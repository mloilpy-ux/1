package com.lunya.deerpeek

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.*

class AppService : Service() {
    private var windowManager: WindowManager? = null
    private var containerView: LinearLayout? = null
    private var deerView: ImageView? = null
    private var responseTv: TextView? = null
    
    // Хранилище контекста для "памяти" Луни
    private var conversationHistory = mutableListOf<String>()
    
    // Вставь свой ключ сюда
    private val API_KEY = "AQ.Ab8RN6IMMyLQCZLDN-YmKoTqF6m_7ZaAJxOeIEOp8boXgGuZ8w"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
        startForegroundNotification()
        
        // Первый запуск
        processInteraction("Привет, Луня. Система активирована. Введи отчет.")
    }

    private fun setupOverlay() {
        containerView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#80000000")) // Полупрозрачный фон
        }

        responseTv = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 12f
            setPadding(10, 10, 10, 10)
        }

        deerView = ImageView(this).apply {
            // СЮДА КЛАДИ СВОЮ КАРТИНКУ В drawable/lunya.png
            setImageResource(android.R.drawable.sym_def_app_icon) 
            layoutParams = LinearLayout.LayoutParams(200, 200)
        }

        containerView?.addView(responseTv)
        containerView?.addView(deerView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(containerView, params)
    }

    private fun processInteraction(input: String) {
        conversationHistory.add(input)
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val model = GenerativeModel("gemini-1.5-flash", API_KEY)
                val response = model.generateContent("Луня, ответь кратко и аналитично: $input")
                
                withContext(Dispatchers.Main) {
                    response.text?.let { 
                        responseTv?.text = it
                        conversationHistory.add(it) // Сохраняем в память
                    }
                }
            } catch (e: Exception) {
                Log.e("DeerPeek", "Gemini error: ${e.message}")
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "deer_service"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Deer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(1, NotificationCompat.Builder(this, channelId).setContentTitle("Луня активна").setSmallIcon(android.R.drawable.ic_menu_info_details).build())
    }

    override fun onDestroy() {
        windowManager?.removeView(containerView)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
