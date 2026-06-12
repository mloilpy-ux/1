package com.lunya.deerpeek

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
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
    private var audioRecord: NullableAudioRecord? = null
    private var audioRecordInstance: AudioRecord? = null
    @Volatile private var isListening = false
    private var lastAudioPeakTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastTriggerTime: Long = 0
    private val cooldownMillis = 3500L
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
        logToFile("=== ЗАПУСК СЛУЖБЫ С АНИМАЦИЕЙ ВЫЕЗДА ===")
        registerSystemTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        
        when (intent?.action) {
            "FORCE_PEEK" -> triggerDeerPeek(peekType = PeekType.FULL_WAVING, force = true)
            "UPDATE_SETTINGS" -> applyLiveSettings()
        }

        if (shakeDetector == null) {
            try {
                val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                shakeDetector = ShakeDetector(this).apply {
                    setSensitivity(ShakeDetector.SENSITIVITY_HARD)
                    start(sensorManager)
                }
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
        logToFile("Триггер: Тряска корпуса. Полный выезд.")
        triggerDeerPeek(peekType = PeekType.FULL_WAVING)
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
                    Intent.ACTION_POWER_CONNECTED -> triggerDeerPeek(peekType = PeekType.SHORT_LOOK)
                    Intent.ACTION_HEADSET_PLUG -> {
                        if (intent.getIntExtra("state", -1) == 1) {
                            triggerDeerPeek(peekType = PeekType.SHORT_LOOK)
                        }
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (level * 100 / scale.toFloat() <= 15f) {
                            triggerDeerPeek(peekType = PeekType.SHORT_LOOK)
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
                audioRecordInstance = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, 
                    sampleRate, 
                    channelConfig, 
                    audioFormat, 
                    bufferSize
                )

                if (audioRecordInstance?.state != AudioRecord.STATE_INITIALIZED) {
                    logToFile("Микрофон монополизирован сторонним софтом.")
                    return@Thread
                }

                isListening = true
                audioRecordInstance?.startRecording()

                val buffer = ShortArray(bufferSize)
                while (isListening) {
                    val prefs = getSharedPreferences("deer_prefs", Context.MODE_PRIVATE)
                    val threshold = prefs.getInt("mic_threshold", 3000)

                    val readSize = audioRecordInstance?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmp = 0
                        for (i in 0 until readSize) {
                            val v = abs(buffer[i].toInt())
                            if (v > maxAmp) maxAmp = v
                        }
                        
                        if (maxAmp > threshold) {
                            val now = System.currentTimeMillis()
                            val diff = now - lastAudioPeakTime
                            
                            if (lastAudioPeakTime == 0L) {
                                // Первый щелчок / одиночный звук: реагируем мгновенно легким выглядыванием
                                lastAudioPeakTime = now
                                mainHandler.post { triggerDeerPeek(peekType = PeekType.SHORT_LOOK) }
                            } else if (diff in 50..1000) {
                                // Успешный двойной щелчок: олень понимает команду и машет лапой
                                lastAudioPeakTime = 0
                                mainHandler.post { triggerDeerPeek(peekType = PeekType.FULL_WAVING, force = true) }
                            } else if (diff > 1000) {
                                lastAudioPeakTime = now
                                mainHandler.post { triggerDeerPeek(peekType = PeekType.SHORT_LOOK) }
                            }
                        }
                    }
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                isListening = false
            }
        }.start()
    }

    enum class PeekType {
        SHORT_LOOK,   // Слегка приподнимается (реакция на шум/одиночный клик)
        FULL_WAVING   // Выезжает полностью и машет (двойной щелчок/тряска)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun triggerDeerPeek(peekType: PeekType, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastTriggerTime < cooldownMillis) && peekType == PeekType.SHORT_LOOK) {
            return 
        }

        mainHandler.post {
            // Если олень уже на экране и регистрируется повторный двойной щелчок — переключаем текстуру на анимацию махания
            if (isShowing) {
                if (peekType == PeekType.FULL_WAVING) {
                    try {
                        deerImageView?.setImageResource(R.drawable.deer_waving)
                        (deerImageView?.drawable as? AnimationDrawable)?.start()
                    } catch (e: Exception) {}
                }
                return@post
            }

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

                val viewSizePx = (size * density).toInt()

                deerImageView = ImageView(applicationContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = alphaPercent / 100f
                }

                val params = WindowManager.LayoutParams(
                    viewSizePx, viewSizePx,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = gravityFlag
                    x = if (hasSavedPos) savedX else 0
                    y = if (hasSavedPos) savedY else 0
                }

                // Интерактив таскания
                deerImageView?.setOnTouchListener(object : View.OnTouchListener {
                    private var initialX = 0
                    private var initialY = 0
                    private var initialTouchX = 0f
                    private var initialTouchY = 0f
                    private var isMove = false

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = params.x
                                initialY = params.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                isMove = false
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (abs(event.rawX - initialTouchX) > 10 || abs(event.rawY - initialTouchY) > 10) {
                                    isMove = true
                                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                                    if ((gravityFlag and Gravity.BOTTOM) == Gravity.BOTTOM) {
                                        params.y = initialY - (event.rawY - initialTouchY).toInt()
                                    } else {
                                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                                    }
                                    try { windowManager.updateViewLayout(deerImageView, params) } catch (e: Exception) {}
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (isMove) {
                                    prefs.edit().putInt("saved_x", params.x).putInt("saved_y", params.y).putBoolean("has_saved_pos", true).apply()
                                } else {
                                    triggerDeerPeek(peekType = PeekType.FULL_WAVING, force = true)
                                }
                                return true
                            }
                        }
                        return false
                    }
                })

                // Выбор стартового кадра/анимации в зависимости от типа шума
                try {
                    if (peekType == PeekType.SHORT_LOOK) {
                        deerImageView?.setImageResource(R.drawable.deer_frame_1) // Одиночный шум — просто настороженный первый кадр
                    } else {
                        deerImageView?.setImageResource(R.drawable.deer_waving)
                        (deerImageView?.drawable as? AnimationDrawable)?.start()
                    }
                } catch (e: Exception) {
                    deerImageView?.setImageResource(android.R.drawable.ic_menu_compass)
                }

                windowManager.addView(deerImageView, params)

                // Рассчитываем высоту подъема анимации
                val targetYDelta = if (peekType == PeekType.SHORT_LOOK) viewSizePx * 0.35f else viewSizePx * 1.0f
                
                // Инициализируем аниматор перевода координат (выезд из самого низа экрана)
                deerImageView?.translationY = targetYDelta
                
                val appearanceAnimator = ValueAnimator.ofFloat(targetYDelta, 0f).apply {
                    duration = 550
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animator ->
                        val value = animator.animatedValue as Float
                        deerImageView?.translationY = value
                    }
                }
                appearanceAnimator.start()

                // Таймер ухода обратно под экран
                mainHandler.postDelayed({
                    val disappearanceAnimator = ValueAnimator.ofFloat(0f, targetYDelta).apply {
                        duration = 450
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { animator ->
                            val value = animator.animatedValue as Float
                            deerImageView?.translationY = value
                        }
                    }
                    disappearanceAnimator.start()

                    mainHandler.postDelayed({
                        removeOverlay()
                    }, 460)
                }, 2500)

            } catch (e: Exception) {
                isShowing = false
            }
        }
    }

    private fun removeOverlay() {
        if (deerImageView != null) {
            try { windowManager.removeView(deerImageView) } catch (e: Exception) {}
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
            .setContentText("Кинематическая отрисовка включена")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        isListening = false
        audioRecordInstance?.apply { try { stop(); release() } catch (e: Exception) {} }
        try { shakeDetector?.stop() } catch (e: Exception) {}
        systemReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        removeOverlay()
        super.onDestroy()
    }
}
