package com.lunya.deerpeek

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.AnimationDrawable
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.squareup.seismic.ShakeDetector
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class AppService : Service(), ShakeDetector.Listener {

    private lateinit var windowManager: WindowManager
    private var deerImageView: ImageView? = null
    private var isShowing = false
    private var shakeDetector: ShakeDetector? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isListening = false
    private var lastAudioPeakTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // Таймер защиты от спама датчиков
    private var lastTriggerTime: Long = 0
    private val cooldownMillis = 4000L

    // Приемник системных событий (зарядка, наушники, батарея)
    private var systemReceiver: BroadcastReceiver? = null

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
        logToFile("=== СЕРВИС РАСШИРЕН И ЗАПУЩЕН ===")
        
        registerSystemTriggers()
        
        mainHandler.postDelayed({
            triggerDeerPeek(isSystemEvent = true)
        }, 500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        
        when (intent?.action) {
            "FORCE_PEEK" -> {
                logToFile("Триггер: Ручной запуск из UI.")
                triggerDeerPeek(isSystemEvent = false, force = true)
            }
            "UPDATE_SETTINGS" -> {
                logToFile("Настройки изменены пользователем. Применение на лету.")
                applyLiveSettings()
            }
        }

        if (shakeDetector == null) {
            try {
                val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                shakeDetector = ShakeDetector(this).apply {
                    setSensitivity(ShakeDetector.SENSITIVITY_HARD)
                    start(sensorManager)
                }
                logToFile("Датчик акселерометра инициализирован.")
            } catch (e: Exception) {
                logToFile("Ошибка акселерометра: ${e.localizedMessage}")
            }
        }

        if (!isListening) {
            startSnapDetector()
        }

        return START_STICKY
    }

    override fun hearShake() {
        logToFile("Триггер датчика: Встряска корпуса телефона.")
        triggerDeerPeek(isSystemEvent = false)
    }

    private fun applyLiveSettings() {
        val prefs = getSharedPreferences("deer_prefs", Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val size = prefs.getInt("deer_size", 250)
        val alphaPercent = prefs.getInt("deer_alpha", 100)

        mainHandler.post {
            deerImageView?.let { view ->
                try {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.width = (size * density).toInt()
                    params.height = (size * density).toInt()
                    windowManager.updateViewLayout(view, params)
                    view.alpha = alphaPercent / 100f
                } catch (e: Exception) {}
            }
        }
    }

    private fun registerSystemTriggers() {
        systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        logToFile("Системное событие: Подключено зарядное устройство.")
                        triggerDeerPeek(isSystemEvent = true)
                    }
                    Intent.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", -1)
                        if (state == 1) {
                            logToFile("Системное событие: Наушники подключены.")
                            triggerDeerPeek(isSystemEvent = true)
                        }
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val batteryPct = level * 100 / scale.toFloat()
                        if (batteryPct <= 15f && batteryPct > 14f) { // Сработает один раз при падении до 15%
                            logToFile("Системное событие: Низкий уровень заряда батареи ($level%).")
                            triggerDeerPeek(isSystemEvent = true)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(systemReceiver, filter)
    }

    @SuppressLint("MissingPermission")
    private fun startSnapDetector() {
        Thread {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            if (bufferSize <= 0) return@Thread

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, 
                    sampleRate, 
                    channelConfig, 
                    audioFormat, 
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    logToFile("Микрофон занят. Изолированное ожидание аудиопотока.")
                    return@Thread
                }

                isListening = true
                audioRecord?.startRecording()
                logToFile("Аудиомониторинг успешно запущен.")

                val buffer = ShortArray(bufferSize)
                while (isListening) {
                    val prefs = getSharedPreferences("deer_prefs", Context.MODE_PRIVATE)
                    val threshold = prefs.getInt("mic_threshold", 3000)

                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmp = 0
                        for (i in 0 until readSize) {
                            val v = abs(buffer[i].toInt())
                            if (v > maxAmp) maxAmp = v
                        }
                        if (maxAmp > threshold) {
                            val now = System.currentTimeMillis()
                            val diff = now - lastAudioPeakTime
                            if (diff in 50..1000) {
                                lastAudioPeakTime = 0
                                mainHandler.post { triggerDeerPeek(isSystemEvent = false) }
                            } else {
                                lastAudioPeakTime = now
                            }
                        }
                    }
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                logToFile("Исключение аудиомониторинга: ${e.localizedMessage}")
                isListening = false
            }
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun triggerDeerPeek(isSystemEvent: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastTriggerTime < cooldownMillis)) {
            return // Защита от спама и дребезга датчиков активна
        }

        mainHandler.post {
            if (isShowing) return@post
            isShowing = true
            lastTriggerTime = now

            try {
                val prefs = getSharedPreferences("deer_prefs", Context.MODE_PRIVATE)
                val density = resources.displayMetrics.density
                
                val size = prefs.getInt("deer_size", 250)
                val alphaPercent = prefs.getInt("deer_alpha", 100)
                val savedX = prefs.getInt("saved_x", 0)
                val savedY = prefs.getInt("saved_y", 0)
                val hasSavedPos = prefs.getBoolean("has_saved_pos", false)
                val gravityFlag = prefs.getInt("start_gravity_flag", Gravity.BOTTOM or Gravity.START)

                deerImageView = ImageView(applicationContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = alphaPercent / 100f
                }

                val params = WindowManager.LayoutParams(
                    (size * density).toInt(), 
                    (size * density).toInt(),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = gravityFlag
                    if (hasSavedPos) {
                        x = savedX
                        y = savedY
                    } else {
                        x = 0
                        y = 0
                    }
                }

                // Интерактивное перемещение + Тап-реакция
                deerImageView?.setOnTouchListener(object : View.OnTouchListener {
                    private var initialX = 0
                    private var initialY = 0
                    private var initialTouchX = 0f
                    private var initialTouchY = 0f
                    private var isMoveAction = false

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = params.x
                                initialY = params.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                isMoveAction = false
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = abs(event.rawX - initialTouchX)
                                val dy = abs(event.rawY - initialTouchY)
                                if (dx > 10 || dy > 10) {
                                    isMoveAction = true
                                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                                    // Корректировка направления Y в зависимости от верхнего/нижнего Gravity
                                    if ((gravityFlag and Gravity.BOTTOM) == Gravity.BOTTOM) {
                                        params.y = initialY - (event.rawY - initialTouchY).toInt()
                                    } else {
                                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                                    }
                                    try {
                                        windowManager.updateViewLayout(deerImageView, params)
                                    } catch (e: Exception) {}
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (isMoveAction) {
                                    // Сохраняем финальные координаты после перемещения
                                    prefs.edit()
                                        .putInt("saved_x", params.x)
                                        .putInt("saved_y", params.y)
                                        .putBoolean("has_saved_pos", true)
                                        .apply()
                                    logToFile("Координаты оверлея сохранены: X=${params.x}, Y=${params.y}")
                                } else {
                                    // ТАП-РЕАКЦИЯ: Пользователь просто кликнул на оленя
                                    logToFile("Интерактив: Одиночный клик пользователя по оверлею.")
                                    executeTapReaction()
                                }
                                return true
                            }
                        }
                        return false
                    }
                })

                windowManager.addView(deerImageView, params)

                // Разделение анимаций по типам событий
                try {
                    if (isSystemEvent) {
                        // Если сработало системное событие, можно ставить альтернативный кадр/анимацию
                        deerImageView?.setImageResource(R.drawable.deer_frame_1) 
                    } else {
                        deerImageView?.setImageResource(R.drawable.deer_waving)
                        (deerImageView?.drawable as? AnimationDrawable)?.start()
                    }
                } catch (resException: Exception) {
                    deerImageView?.setImageResource(android.R.drawable.ic_menu_compass)
                }

                mainHandler.postDelayed({
                    removeOverlay()
                }, 3000)
            } catch (e: Exception) {
                logToFile("Ошибка WindowManager addView: ${e.localizedMessage}")
                isShowing = false
            }
        }
    }

    private fun executeTapReaction() {
        // Короткий визуальный отклик при тапе: например, подмигивание или быстрая смена кадров
        try {
            deerImageView?.setImageResource(R.drawable.deer_frame_1)
        } catch (e: Exception) {}
    }

    private fun removeOverlay() {
        if (deerImageView != null) {
            try {
                windowManager.removeView(deerImageView)
            } catch (e: Exception) {}
            deerImageView = null
        }
        isShowing = false
    }

    private fun startForegroundNotification() {
        val channelId = "deer_peek_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Deer Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Олень")
            .setContentText("Режим максимальной производительности")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        isListening = false
        audioRecord?.apply { try { stop(); release() } catch (e: Exception) {} }
        try { shakeDetector?.stop() } catch (e: Exception) {}
        systemReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        removeOverlay()
        super.onDestroy()
    }
}
