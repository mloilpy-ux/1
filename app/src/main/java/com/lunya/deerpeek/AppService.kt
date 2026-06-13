package com.lunya.deerpeek

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.ai.client.generativeai.GenerativeModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class AppService : Service(), SensorEventListener {

    // Укажи свой рабочий API-ключ непосредственно здесь
    private val directApiKey = "AQ.Ab8RN6IMMyLQCZLDN-YmKoTqF6m_7ZaAJxOeIEOp8boXgGuZ8w"

    private lateinit var windowManager: WindowManager
    private var containerView: LinearLayout? = null
    private var deerImageView: ImageView? = null
    private var speechBubbleTv: TextView? = null

    private var isShowing = false
    private var sensorManager: SensorManager? = null
    private var audioRecordInstance: AudioRecord? = null
    @Volatile private var isListening = false
    private var lastAudioPeakTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastAcceleration = 0f
    private var currentAcceleration = 0f
    private var shakeAcceleration = 0f

    private val characterSystemPrompt = """
        Ты — Луня, персонаж-антропоморфный олень. У тебя синий мех, неоново-зеленые волосы, фиолетовые глаза, фиолетовый нос и фиолетовые когти. 
        Твой стиль речи: строго аналитический, объективный, клинический. Полное отсутствие chatbot-вежливости, филлеров и навязчивого дружелюбия. Пиши емко, лаконично, прямо по существу контекста ситуации. Если требуется сгенерировать арт-промпт, пиши структуру для Image Generator (Flux/Stable Diffusion) с акцентом на cinematic noir и tactile realism.
    """.trimIndent()

    override fun onBind(intent: Intent?): IBinder? = null

    private fun logToFile(msg: String) {
        try {
            val file = File(getExternalFilesDir(null), "deer_log.txt")
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            FileOutputStream(file, true).use { fos ->
                fos.write("[$time] $msg\n".toByteArray())
            }
        } catch (e: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        logToFile("Инициализация службы. Проверка ключа: ${directApiKey.take(7)}...")
        startForegroundNotification()
        initHardwareDetectors()
        showOverlay("Служба запущена в фоновом режиме. Система активна.")
    }

    private fun initHardwareDetectors() {
        // Нативный акселерометр
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
            lastAcceleration = SensorManager.GRAVITY_EARTH
            currentAcceleration = SensorManager.GRAVITY_EARTH
            logToFile("Нативный датчик ускорения подключен.")
        } catch (e: Exception) {
            logToFile("Сбой инициализации акселерометра: ${e.localizedMessage}")
        }

        // Акустический поток
        startAudioMonitoring()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            lastAcceleration = currentAcceleration
            currentAcceleration = sqrt(x * x + y * y + z * z)
            val delta = currentAcceleration - lastAcceleration
            shakeAcceleration = shakeAcceleration * 0.9f + delta

            if (shakeAcceleration > 12f) {
                shakeAcceleration = 0f
                logToFile("Детектор: Зафиксировано резкое смещение корпуса устройства.")
                mainHandler.post {
                    showOverlay("Зафиксировано физическое ускорение устройства. Требуется анализ стабильности положения.")
                }
            }
        }
    }

    override fun on
