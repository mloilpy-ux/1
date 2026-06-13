package com.lunya.deerpeek

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppService : Service() {
    private var windowManager: WindowManager? = null
    private var containerView: LinearLayout? = null
    private var deerView: ImageView? = null
    private var responseTv: TextView? = null

    // СЮДА СВОЙ РЕАЛЬНЫЙ API КЛЮЧ GEMINI
    private val API_KEY = "ТВОЙ_API_КЛЮЧ"

    private val characterSystemPrompt = """
        Ты — Луня, строго антропоморфный олень (не гибрид). У тебя синий мех, неоново-зеленые волосы, фиолетовые глаза, фиолетовый нос и фиолетовые когти. 
        Твой стиль речи: высокоаналитический, объективный, клинический, как у маркет-мейкера. Полное отсутствие chatbot-вежливости, филлеров и навязчивого дружелюбия. Пиши емко, лаконично, прямо по существу контекста. Если требуется арт-промпт, пиши структуру для Flux/Stable Diffusion с акцентом на cinematic noir.
    """.trimIndent()

    override fun onCreate() {
        super.onCreate()
        Log.d("DeerPeek", "=== СЕРВИС УСПЕШНО ОЗНАМЕНОВАН И ЗАПУЩЕН ===")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        startForegroundNotification()
        setupOverlay()
        
        executeGeminiRequest("Система активирована. Выдай первый аналитический отчет.")
    }

    private fun startForegroundNotification() {
        val channelId = "deer_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, 
                "DeerPeek Фоновая служба", 
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Луня в эфире")
            .setContentText("Мониторинг запущен")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        startForeground(777, notification)
        Log.d("DeerPeek", "Уведомление Foreground Service создано")
    }

    private fun setupOverlay() {
        containerView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC111111")) // Почти черный полупрозрачный фон
            setPadding(16, 16, 16, 16)
        }

        responseTv = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 13f
            text = "Инициализация ИИ..."
        }

        deerView = ImageView(this).apply {
            // Если закинул картинку lunya_base.png в res/drawable, напиши тут R.drawable.lunya_base
            setImageResource(android.R.drawable.sym_def_app_icon) 
            layoutParams = LinearLayout.LayoutParams(250, 250)
        }

        containerView?.addView(responseTv)
        containerView?.addView(deerView)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 50
            y = 150
        }

        try {
            windowManager?.addView(containerView, params)
            Log.d("DeerPeek", "Оверлей успешно добавлен в WindowManager")
        } catch (e: Exception) {
            Log.e("DeerPeek", "Крах при добавлении оверлея: ${e.message}")
        }
    }

    private fun executeGeminiRequest(reason: String) {
        Log.d("DeerPeek", "Запуск корутины для запроса к Gemini...")
        
        if (API_KEY == "ТВОЙ_API_КЛЮЧ" || API_KEY.isEmpty()) {
            Log.e("DeerPeek", "ОШИБКА: Забыли вставить реальный API-ключ!")
            responseTv?.text = "Ошибка: Нет ключа API"
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Используем стабильную модель gemini-1.5-flash
                val generativeModel = GenerativeModel(
                    modelName = "gemini-1.5-flash",
                    apiKey = API_KEY
                )
                val fullPrompt = "$characterSystemPrompt\n\nКонтекст: $reason"
                Log.d("DeerPeek", "Отправка запроса в сеть...")
                
                val response = generativeModel.generateContent(fullPrompt)
                
                withContext(Dispatchers.Main) {
                    response.text?.let { text ->
                        Log.d("DeerPeek", "Ответ от ИИ успешно получен: $text")
                        responseTv?.text = text
                    } ?: run {
                        Log.w("DeerPeek", "ИИ вернул пустой текст")
                        responseTv?.text = "[Пустой ответ]"
                    }
                }
            } catch (e: Exception) {
                Log.e("DeerPeek", "КРИТИЧЕСКАЯ ОШИБКА СЕТИ ИЛИ ИИ: ${e.message}")
                withContext(Dispatchers.Main) {
                    responseTv?.text = "Ошибка ИИ: ${e.localizedMessage}"
                }
            }
        }
    }

    override fun onDestroy() {
        containerView?.let { windowManager?.removeView(it) }
        Log.d("DeerPeek", "Сервис уничтожен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
