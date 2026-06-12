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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class AppService : Service(), ShakeDetector.Listener {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isShowing = false
    private var shakeDetector: ShakeDetector? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isListening = false
    private var lastAudioPeakTime: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

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
        logToFile("=== СЕРВИС СОЗДАН ===")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        
        if (intent?.action == "FORCE_PEEK") {
            logToFile("Ручной триггер FORCE_PEEK.")
            triggerDeerPeek()
        }

        // Железобетонный запуск акселерометра в основном потоке сервиса
        if (shakeDetector == null) {
            try {
                val sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                shakeDetector = ShakeDetector(this).apply {
                    setSensitivity(ShakeDetector.SENSITIVITY_HARD)
                    start(sensorManager)
                }
                logToFile("Акселерометр успешно запущен.")
            } catch (e: Exception) {
                logToFile("Ошибка акселерометра: ${e.localizedMessage}")
            }
        }

        // Запуск микрофона полностью изолирован. Даже если он упадет, акселерометр выживет
        if (!isListening) {
            startSnapDetector()
        }

        return START_STICKY
    }

    override fun hearShake() {
        logToFile("Датчик: Тряска! (Сработал независимо от состояния микрофона)")
        triggerDeerPeek()
    }

    @SuppressLint("MissingPermission")
    private fun startSnapDetector() {
        Thread {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            if (bufferSize <= 0) {
                logToFile("Ошибка аудио: Неверный размер буфера.")
                return@Thread
            }

            try {
                // ИСПОЛЬЗУЕМ VOICE_RECOGNITION вместо MIC. Он обходит монополию Discord/Записи экрана во многих прошивках
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, 
                    sampleRate, 
                    channelConfig, 
                    audioFormat, 
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    logToFile("Микрофон занят другим приложением (Discord/Рекордер). Ждем освобождения...")
                    return@Thread
                }

                isListening = true
                audioRecord?.startRecording()
                logToFile("Поток микрофона запущен в режиме совместного аудио.")

                val buffer = ShortArray(bufferSize)
                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmp = 0
                        for (i in 0 until readSize) {
                            val v = abs(buffer[i].toInt())
                            if (v > maxAmp) maxAmp = v
                        }
                        // Если система глушит поток из-за Discord, read вернет 0 или нули.
                        // Но если звук пробивается — обрабатываем двойной щелчок
                        if (maxAmp > 3000) {
                            val now = System.currentTimeMillis()
                            val diff = now - lastAudioPeakTime
                            if (diff in 50..1000) {
                                lastAudioPeakTime = 0
                                mainHandler.post { triggerDeerPeek() }
                            } else {
                                lastAudioPeakTime = now
                            }
                        }
                    }
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                logToFile("Изолированный сбой микрофона: ${e.localizedMessage}. Акселерометр продолжает работу.")
                isListening = false
            }
        }.start()
    }

    private fun triggerDeerPeek() {
        mainHandler.post {
            if (isShowing) return@post
            isShowing = true

            try {
                val inflater = applicationContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                overlayView = inflater.inflate(R.layout.overlay_deer, null)
                
                // Делаем контейнер прозрачным на уровне разметки кода
                overlayView?.setBackgroundColor(0x00000000) 

                val density = resources.displayMetrics.density
                val params = WindowManager.LayoutParams(
                    (250 * density).toInt(), 
                    (300 * density).toInt(),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT // Гарантирует альфа-канал и прозрачность
                ).apply {
                    // ИСПРАВЛЕНО: Переносим в левый нижний угол (BOTTOM or START/LEFT)
                    gravity = Gravity.BOTTOM or Gravity.START
                    x = 0
                    y = 0
                }

                val deerImageView = overlayView?.findViewById<ImageView>(R.id.deerImageView)
                // Убираем возможный фон у самой картинки
                deerImageView?.setBackgroundColor(0x00000000)

                windowManager.addView(overlayView, params)
                logToFile("Оверлей выведен в ЛЕВЫЙ НИЖНИЙ угол.")

                // Загружаем напрямую чистый кадр вместо XML-анимации для проверки
                deerImageView?.setImageResource(R.drawable.deer_frame_1)               

                mainHandler.postDelayed({
                    removeOverlay()
                }, 3000)
            } catch (e: Exception) {
                logToFile("Ошибка WindowManager: ${e.localizedMessage}")
                isShowing = false
            }
        }
    }

    private fun removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {}
            overlayView = null
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
            .setContentText("Защита от конфликтов микрофона")
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
        removeOverlay()
        super.onDestroy()
    }
}
