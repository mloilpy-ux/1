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
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.squareup.seismic.ShakeDetector
import kotlin.math.abs

class AppService : Service(), ShakeDetector.Listener {

    private val TAG = "DEER_DEBUG"

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    private var sensorManager: SensorManager? = null
    private var shakeDetector: ShakeDetector? = null
    
    private var audioRecord: AudioRecord? = null
    @Volatile private var isListening = false
    private var lastAudioPeakTime: Long = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind вызван")
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Инициализация сервиса")
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            Log.d(TAG, "onCreate: WindowManager успешно получен")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Ошибка получения WindowManager", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: Сервис запускается, flags=$flags, startId=$startId")
        
        startForegroundNotification()

        // Настройка акселерометра
        if (shakeDetector == null) {
            try {
                Log.d(TAG, "onStartCommand: Настройка детектора тряски...")
                sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
                shakeDetector = ShakeDetector(this)
                shakeDetector?.setSensitivity(ShakeDetector.SENSITIVITY_HARD)
                shakeDetector?.start(sensorManager!!)
                Log.d(TAG, "onStartCommand: ShakeDetector успешно запущен с максимальной чувствительностью")
            } catch (e: Exception) {
                Log.e(TAG, "onStartCommand: Сбой при запуске ShakeDetector", e)
            }
        } else {
            Log.d(TAG, "onStartCommand: ShakeDetector уже существует, повторный запуск пропущен")
        }

        // Настройка микрофона
        if (!isListening) {
            Log.d(TAG, "onStartCommand: Запуск потока детектора щелчков...")
            startSnapDetector()
        } else {
            Log.d(TAG, "onStartCommand: Поток детектора щелчков уже активен")
        }

        return START_STICKY
    }

    override fun hearShake() {
        Log.d(TAG, "hearShake: Зафиксировано событие тряски от Seismic!")
        mainHandler.post { triggerDeerPeek() }
    }

    @SuppressLint("MissingPermission")
    private fun startSnapDetector() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        Log.d(TAG, "startSnapDetector: Минимальный размер буфера аудио = $bufferSize")

        if (bufferSize <= 0) {
            Log.e(TAG, "startSnapDetector: Некорректный размер буфера, остановка инициализации")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "startSnapDetector: AudioRecord не смог инициализироваться. Проверь разрешения!")
                return
            }

            isListening = true
            audioRecord?.startRecording()
            Log.d(TAG, "startSnapDetector: Запись с микрофона успешно запущена, вход в цикл обработки")

            Thread {
                val buffer = ShortArray(bufferSize)
                val peakThreshold = 4000 
                Log.d(TAG, "AudioThread: Фоновый поток аудио запущен. Порог пика = $peakThreshold")

                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var maxAmplitude = 0
                        for (i in 0 until readSize) {
                            val value = abs(buffer[i].toInt())
                            if (value > maxAmplitude) maxAmplitude = value
                        }

                        // Логируем только значительные всплески звука, чтобы не спамить логгер шумом
                        if (maxAmplitude > 1500) {
                            Log.v(TAG, "AudioThread: Текущая амплитуда = $maxAmplitude")
                        }

                        if (maxAmplitude > peakThreshold) {
                            val currentTime = System.currentTimeMillis()
                            val timeDiff = currentTime - lastAudioPeakTime
                            Log.d(TAG, "AudioThread: Превышен порог! Амплитуда=$maxAmplitude, Интервал с прошлого пика=${timeDiff}мс")

                            if (timeDiff in 50..1000) {
                                Log.i(TAG, "AudioThread: УСПЕХ! Зафиксирован двойной щелчок. Вызов отрисовки.")
                                mainHandler.post { triggerDeerPeek() }
                                lastAudioPeakTime = 0
                            } else {
                                Log.d(TAG, "AudioThread: Первый одиночный пик зафиксирован. Ждем второй.")
                                lastAudioPeakTime = currentTime
                            }
                        }
                    } else {
                        Log.w(TAG, "AudioThread: Ошибка чтения аудио данных, readSize=$readSize")
                    }
                    Thread.sleep(10)
                }
                Log.d(TAG, "AudioThread: Выход из фонового потока аудио")
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "startSnapDetector: Исключение в аудио-треде", e)
        }
    }

    private fun triggerDeerPeek() {
        Log.d(TAG, "triggerDeerPeek: Попытка вызвать оверлей. Текущий статус видимости isShowing=$isShowing")
        if (isShowing) return
        isShowing = true

        try {
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            overlayView = inflater.inflate(R.layout.overlay_deer, null)
            Log.d(TAG, "triggerDeerPeek: Разметка overlay_deer успешно инфлейтнута")

            val density = resources.displayMetrics.density
            val widthPx = (250 * density).toInt()
            val heightPx = (300 * density).toInt()
            Log.d(TAG, "triggerDeerPeek: Размеры контейнера в пикселях: ${widthPx}x${heightPx} (плотность=$density)")

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
                Log.e(TAG, "triggerDeerPeek: Ошибка! В разметке не найден deerImageView")
            }

            Log.d(TAG, "triggerDeerPeek: Вызов windowManager.addView()...")
            windowManager.addView(overlayView, params)
            Log.i(TAG, "triggerDeerPeek: Окно оверлея успешно добавлено на экран системы")
            
            deerImageView?.setImageResource(R.drawable.deer_waving)
            val wavingAnimation = deerImageView?.drawable as? AnimationDrawable
            if (wavingAnimation != null) {
                wavingAnimation.start()
                Log.d(TAG, "triggerDeerPeek: Циклическая анимация махания лапкой запущена")
            } else {
                Log.w(TAG, "triggerDeerPeek: Предупреждение! Ресурс deer_waving не распознан как AnimationDrawable")
            }

            mainHandler.postDelayed({
                Log.d(TAG, "triggerDeerPeek: Время отображения вышло. Запуск удаления.")
                wavingAnimation?.stop()
                removeOverlay()
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "triggerDeerPeek: Критическая ошибка при отрисовке окна оверлея", e)
            isShowing = false
        }
    }

    private fun removeOverlay() {
        Log.d(TAG, "removeOverlay: Запрос удаления окна. overlayView существует=${overlayView != null}")
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView)
                Log.i(TAG, "removeOverlay: Окно оверлея успешно удалено из WindowManager")
            } catch (e: Exception) {
                Log.e(TAG, "removeOverlay: Ошибка при удалении View из WindowManager", e)
            }
            overlayView = null
        }
        isShowing = false
    }

    private fun startForegroundNotification() {
        Log.d(TAG, "startForegroundNotification: Инициализация уведомления шторки")
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
            .setContentText("Тестовый режим подробного логирования...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, notification)
            }
            Log.d(TAG, "startForegroundNotification: Сервис успешно переведен в режим Foreground")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundNotification: Не удалось перевести сервис в режим Foreground", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Сервис уничтожается")
        isListening = false
        audioRecord?.apply {
            try { stop(); release(); Log.d(TAG, "onDestroy: Микрофон освобожден") } catch (e: Exception) {}
        }
        try { shakeDetector?.stop(); Log.d(TAG, "onDestroy: Акселерометр остановлен") } catch (e: Exception) {}
        removeOverlay()
        super.onDestroy()
    }
}
