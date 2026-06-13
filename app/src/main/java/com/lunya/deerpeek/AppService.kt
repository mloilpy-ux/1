package com.lunya.deerpeek

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.ai.client.generativeai.GenerativeModel
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppService : Service() {
    private var windowManager: WindowManager? = null
    private var containerView: LinearLayout? = null
    private var deerView: ImageView? = null
    private var speechBubbleTv: TextView? = null

    private val characterSystemPrompt = """
        Ты — Луня, строго антропоморфный олень (не гибрид). У тебя синий мех, неоново-зеленые волосы, фиолетовые глаза, фиолетовый нос и фиолетовые когти. 
        Твой стиль речи: высокоаналитический, объективный, клинический, как у маркет-мейкера. Полное отсутствие chatbot-вежливости, филлеров и навязчивого дружелюбия. Пиши емко, лаконично, прямо по существу контекста. Если требуется арт-промпт, пиши структуру для Flux/Stable Diffusion с акцентом на cinematic noir.
    """.trimIndent()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        showOverlay()
        
        executeGeminiRequest("Система инициализирована. Запущен базовый мониторинг.")
        
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "deer_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Deer Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Луня")
            .setContentText("Модуль оверлея и ИИ активен")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
    }

    private fun showOverlay() {
        if (containerView != null) return

        containerView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
        }

        speechBubbleTv = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#DD111111"))
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
            textSize = 14f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }

        deerView = ImageView(this).apply {
            setImageResource(android.R.drawable.star_big_on)
            layoutParams = LinearLayout.LayoutParams(300, 300)
        }

        containerView?.addView(speechBubbleTv)
        containerView?.addView(deerView)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
        
        try {
            windowManager?.addView(containerView, params)
        } catch (e: Exception) {
            Log.e("DeerPeek", "Ошибка отрисовки оверлея: ${e.message}")
        }
    }

    private fun executeGeminiRequest(reason: String) {
        val apiKey = "AQ.Ab8RN6IMMyLQCZLDN-YmKoTqF6m_7ZaAJxOeIEOp8boXgGuZ8w" // Требуется ввод валидного ключа
        
        if (apiKey.isEmpty() || apiKey == "ТВОЙ_API_КЛЮЧ") {
            Log.e("DeerPeek", "API ключ не задан. Блокировка запроса.")
            return
        }

        // Изолированный контекст корутин для I/O операций
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val generativeModel = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey
                )
                val fullPrompt = "$characterSystemPrompt\n\nКонтекстное событие: $reason"
                
                // Легальный вызов suspend-функции
                val response = generativeModel.generateContent(fullPrompt)
                
                // Возврат в UI-поток для отрисовки
                withContext(Dispatchers.Main) {
                    response.text?.let { text ->
                        speechBubbleTv?.text = text
                        speechBubbleTv?.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e("DeerPeek", "Ошибка генерации Gemini: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        containerView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
