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
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.squareup.seismic.ShakeDetector
import kotlin.math.abs

class AppService : Service(), ShakeDetector.Listener {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    
    private var audioRecord: AudioRecord? = null
    @Volatile private var isListening = false
    private var lastAudioPeakTime: Long = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sendLogToActivity(msg: String) {
        val intent = Intent("com.lunya.deerpeek.LOG_BROADCAST").apply {
            putExtra("log_msg", msg)
        }
        sendBroadcast(intent)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            mainHandler.postDelayed({ sendLogToActivity("Сервис: метод onCreate() отработал успешно.") }, 200)
        } catch (e: Exception) {
            mainHandler.postDelayed({ sendLogToActivity("Сервис ОШИБКА onCreate: ${e.localizedMessage}") }, 200)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        sendLogToActivity("Сервис: Метод onStartCommand запущен.")

        // Настройка детектора тряски
        try {
            if (shakeDetector != null) {
                shakeDetector?.stop()
                sendLogToActivity("Сервис: Предыдущий ShakeDetector остановлен.")
            }
            sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            shakeDetector = ShakeDetector(this)
            shakeDetector?.setSensitivity(ShakeDetector.SENSITIVITY_HARD)
            shakeDetector?.start(sensorManager!!)
            sendLogToActivity("Сервис: ShakeDetector успешно инициализирован.")
        } catch (e: Exception) {
            sendLogToActivity("Сервис СБОЙ АКСЕЛЕРОМЕТРА: ${e.localizedMessage}")
        }

        // Настройка микрофона
        if (!isListening) {
            sendLogToActivity("Сервис: Запуск детектора микрофона...")
            startSnapDetector()
        } else {
            sendLogToActivity("Сервис: Детектор микрофона уже работал.")
        }

        return START_STICKY
    }

    override fun hearShake() {
        sendLogToActivity("ДАТЧИК: Зафиксирована ТРЯСКА корпуса!")
        mainHandler.post { triggerDeerPeek() }
    }

    @SuppressLint("MissingPermission")
    private fun startSnapDetector() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        sendLogToActivity("МИКРОФОН: Системный размер буфера = $bufferSize")

        if (bufferSize <= 0) {
            sendLogToActivity("МИКРОФОН ОШИБКА: Некорректный размер буфера.")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                sendLogToActivity("МИКРОФОН ОШИБКА: AudioRecord не инициализирован (state != INITIALIZED).")
                return
            }

            isListening = true
            audioRecord?.startRecording()
            sendLogToActivity("МИКРОФОН: Запись успешно активирована.")

            Thread {
                val buffer = ShortArray(bufferSize)
                val peakThreshold = 3000 
                var reportCounter = 0

                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val value = abs(buffer[i].toInt())
                            if (value > maxAmplitude) maxAmplitude = value
                        }

                        // Периодически шлем статус, что поток жив и слушает
                        if (maxAmplitude > peakThreshold) {
                            val currentTime = System.currentTimeMillis()
                            val timeDiff = currentTime - lastAudioPeakTime
                            mainHandler.post { sendLogToActivity("ЗВУК: Пик! Амплитуда=$maxAmplitude, интервал=${timeDiff}мс") }

                            if (timeDiff in 50..1000) {
                                mainHandler.post { sendLogToActivity("ЗВУК УСПЕХ: Двойной щелчок распознан!") }
                                mainHandler.post { triggerDeerPeek() }
                                lastAudioPeakTime = 0
                            } else {
                                lastAudioPeakTime = currentTime
                            }
                        }
                    } else {
                        reportCounter++
                        if (reportCounter % 200 == 0) {
                            mainHandler.post { sendLogToActivity("МИКРОФОН ПРЕДУПРЕЖДЕНИЕ: Ошибка члния данных, код=$readSize") }
                        }
                    }
                    Thread.sleep(10)
                }
            }.start()
        } catch (e: Exception) {
            val err = e.localizedMessage ?: "Unknown Error"
            mainHandler.post { sendLogToActivity("МИКРОФОН КРИТИЧЕСКИЙ СБОЙ ПОТОКА: $err") }
        }
    }

    private fun triggerDeerPeek() {
        mainHandler.post {
            sendLogToActivity("ОТРИСОВКА: Запрос вывода оверлея. Статус отображения isShowing=$isShowing")
            if (isShowing) return@post
            isShowing = true

            try {
                val inflater = applicationContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                overlayView = inflater.inflate(R.layout.overlay_deer, null)

                val density = resources.displayMetrics.density
                val widthPx = (250 * density).toInt()
                val heightPx = (300 * density).toInt()

                val params = WindowManager.LayoutParams(
                    widthPx,
                    heightPx,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                    else 
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = 0
                    y = 0 
                }

                val deerImageView = overlayView?.findViewById<ImageView>(R.id.deerImageView)
                if (deerImageView == null) {
                    sendLogToActivity("ОТРИСОВКА ОШИБКА: deerImageView не найден в XML разметке!")
                }
                
                windowManager.addView(overlayView, params)
                sendLogToActivity("ОТРИСОВКА: Окно оверлея успешно добавлено в WindowManager системы.")
                
                deerImageView?.setImageResource(R.drawable.deer_waving)
                val wavingAnimation = deerImageView?.drawable as? AnimationDrawable
                if (wavingAnimation != null) {
                    wavingAnimation.start()
                    sendLogToActivity("ОТРИСОВКА: Анимация махания запущена.")
                } else {
                    sendLogToActivity("ОТРИСОВКА ПРЕДУПРЕЖДЕНИЕ: Не удалось запустить AnimationDrawable.")
                }

                mainHandler.postDelayed({
                    wavingAnimation?.stop()
                    removeOverlay()
                }, 3000)

            } catch (e: Exception) {
                sendLogToActivity("ОТРИСОВКА КРИТИЧЕСКИЙ СБОЙ WINDOW_MANAGER: ${e.localizedMessage}")
                isShowing = false
            }
        }
    }

    private fun removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView)
                sendLogToActivity("ОТРИСОВКА: Окно оверлея успешно удалено.")
            } catch (e: Exception) {
                sendLogToActivity("ОТРИСОВКА СБОЙ УДАЛЕНИЯ: ${e.localizedMessage}")
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
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Олень на страже")
            .setContentText("Интегрированный текстовый логгер активен...")
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
            try { stop(); release() } catch (e: Exception) {}
        }
        try { shakeDetector?.stop() } catch (e: Exception) {}
        removeOverlay()
        super.onDestroy()
    }
}
