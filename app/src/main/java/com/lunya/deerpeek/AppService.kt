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

    private lateinit var shakeDetector: ShakeDetector
    private var audioRecord: AudioRecord? = null
    @Volatile private var isListening = false
    private var lastAudioPeakTime: Long = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        startForegroundNotification()

        // Инициализация детектора тряски
        try {
            val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            shakeDetector = ShakeDetector(this)
            // Установим среднюю чувствительность (SENSITIVITY_LIGHT или SENSITIVITY_DEFAULT)
            shakeDetector.setSensitivity(ShakeDetector.SENSITIVITY_LIGHT)
            shakeDetector.start(sensorManager)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Запуск микрофона
        startSnapDetector()
    }

    override fun hearShake() {
        mainHandler.post { triggerDeerPeek() }
    }

    @SuppressLint("MissingPermission")
    private fun startSnapDetector() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (bufferSize <= 0) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

            isListening = true
            audioRecord?.startRecording()

            Thread {
                val buffer = ShortArray(bufferSize)
                // Снизили порог до 12000, чтобы реагировал даже на негромкие щелчки
                val peakThreshold = 12000 

                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val value = abs(buffer[i].toInt())
                            if (value > maxAmplitude) maxAmplitude = value
                        }

                        if (maxAmplitude > peakThreshold) {
                            val currentTime = System.currentTimeMillis()
                            val timeDiff = currentTime - lastAudioPeakTime

                            // Окно между щелчками: от 150 до 800 мс
                            if (timeDiff in 150..800) {
                                mainHandler.post { triggerDeerPeek() }
                                lastAudioPeakTime = 0
                            } else {
                                lastAudioPeakTime = currentTime
                            }
                        }
                    }
                    // Легкая разгрузка процессора
                    Thread.sleep(10)
                }
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerDeerPeek() {
        if (isShowing) return
        isShowing = true

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_deer, null)

        // КРИТИЧЕСКИЕ ФЛАГИ: Добавлен FLAG_NOT_TOUCHABLE, чтобы клики шли сквозь оверлей
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 0
            y = 150 
        }

        val deerImageView = overlayView?.findViewById<ImageView>(R.id.deerImageView)

        // Изначально прячем контейнер полностью за край экрана (на 600 пикселей вправо)
        overlayView?.translationX = 600f
        
        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            isShowing = false
            return
        }

        // Анимация выезда
        overlayView?.animate()
            ?.translationX(0f)
            ?.setDuration(500)
            ?.setInterpolator(OvershootInterpolator(1.0f))
            ?.withEndAction {
                deerImageView?.setImageResource(R.drawable.deer_waving)
                val wavingAnimation = deerImageView?.drawable as? AnimationDrawable
                wavingAnimation?.start()

                mainHandler.postDelayed({
                    wavingAnimation?.stop()
                    deerImageView?.setImageResource(R.drawable.deer_frame_1)

                    // Анимация уезда назад
                    overlayView?.animate()
                        ?.translationX(600f)
                        ?.setDuration(400)
                        ?.withEndAction {
                            removeOverlay()
                        }
                        ?.start()
                }, 2500)
            }
            ?.start()
    }

    private fun removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
        isShowing = false
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
            .setContentText("Жду двойной щелчок или встряску...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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
        try {
            shakeDetector.stop()
        } catch (e: Exception) { e.printStackTrace() }
        removeOverlay()
        super.onDestroy()
    }
}
