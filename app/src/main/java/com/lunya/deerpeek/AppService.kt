package com.lunya.deerpeek

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.AnimationDrawable
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.squareup.seismic.ShakeDetector
import kotlin.math.abs

class AppService : Service(), ShakeDetector.Listener {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    // Для детектора тряски (Seismic)
    private lateinit var shakeDetector: ShakeDetector

    // Для детектора щелчков
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var lastAudioPeakTime: Long = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // 1. Запуск Foreground уведомления
        startForegroundNotification()

        // 2. Инициализация детектора тряски
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        shakeDetector = ShakeDetector(this)
        shakeDetector.start(sensorManager)

        // 3. Запуск детектора щелчков пальцев
        startSnapDetector()
    }

    // Триггер 1: Сработал детектор тряски от Square Seismic
    override fun hearShake() {
        triggerDeerPeek()
    }

    // Триггер 2: Логика обработки звука (Двойной щелчок)
    @SuppressLint("MissingPermission")
    private fun startSnapDetector() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            isListening = true
            audioRecord?.startRecording()

            Thread {
                val buffer = ShortArray(bufferSize)
                val peakThreshold = 18000 // Чувствительность к резкому звуку (щелчку)

                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val value = abs(buffer[i].toInt())
                            if (value > maxAmplitude) maxAmplitude = value
                        }

                        // Если зафиксирован резкий всплеск звука
                        if (maxAmplitude > peakThreshold) {
                            val currentTime = System.currentTimeMillis()
                            val timeDiff = currentTime - lastAudioPeakTime

                            // Проверяем интервал между первым и вторым щелчком (от 200 до 650 миллисекунд)
                            if (timeDiff in 200..650) {
                                mainHandler.post { triggerDeerPeek() }
                                lastAudioPeakTime = 0 // Сброс
                            } else {
                                lastAudioPeakTime = currentTime
                            }
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Рендеринг и анимация оверлея поверх экрана
    private fun triggerDeerPeek() {
        if (isShowing) return
        isShowing = true

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_deer, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 0
            y = 100 // Высота над нижним краем экрана
        }

        val deerImageView = overlayView?.findViewById<ImageView>(R.id.deerImageView)

        // Изначально смещаем оленя вправо за границы видимости экрана
        overlayView?.translationX = 500f
        windowManager.addView(overlayView, params)

        // Анимация 1: Плавный выезд оленя слева из-за экрана
        overlayView?.animate()
            ?.translationX(0f)
            ?.setDuration(400)
            ?.setInterpolator(OvershootInterpolator(1.2f))
            ?.withEndAction {
                // Как только олень выехал, меняем статичный кадр на анимацию махания лапкой
                deerImageView?.setImageResource(R.drawable.deer_waving)
                val wavingAnimation = deerImageView?.drawable as? AnimationDrawable
                wavingAnimation?.start()

                // Олень машет лапкой ровно 2.5 секунды, затем прячется обратно
                mainHandler.postDelayed({
                    wavingAnimation?.stop()
                    // Возвращаем первый кадр перед уходом
                    deerImageView?.setImageResource(R.drawable.deer_frame_1)

                    // Анимация 2: Уезд обратно за экран
                    overlayView?.animate()
                        ?.translationX(500f)
                        ?.setDuration(350)
                        ?.withEndAction {
                            removeOverlay()
                        }
                        ?.start()
                }, 2500)
            }
            ?.start()
    }

    private fun removeOverlay() {
        if (overlayView != null && isShowing) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
            isShowing = false
        }
    }

    private fun startForegroundNotification() {
        val channelId = "deer_peek_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Deer Peek Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Олень на страже")
            .setContentText("Слушаю двойной щелчок или тряску...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ ДЛЯ ANDROID 14+: Указываем тип сервиса явно
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        isListening = false
        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) { e.printStackTrace() }
        }
        shakeDetector.stop()
        removeOverlay()
        super.onDestroy()
    }
}
