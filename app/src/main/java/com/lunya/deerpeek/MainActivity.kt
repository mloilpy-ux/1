package com.lunya.deerpeek

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("deer_prefs", Context.MODE_PRIVATE)

        // Контейнер-скролл для кучи настроек
        val scrollView = ScrollView(this)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scrollView.addView(rootLayout)
        setContentView(scrollView)

        // --- КНОПКА ЗАПУСКА ---
        val startButton = Button(this).apply {
            text = "ЗАПУСТИТЬ ОЛЕНЯ И СЛУЖБЫ"
        }
        rootLayout.addView(startButton)

        // Вспомогательная функция для создания заголовков блоков настроек
        fun addHeader(text: String) {
            val tv = TextView(this).apply {
                setText(text)
                textSize = 16f
                setPadding(0, 32, 0, 8)
                setTextColor(0xFF000000.toInt())
            }
            rootLayout.addView(tv)
        }

        // --- НАСТРОЙКА: СТАРТОВАЯ ПОЗИЦИЯ ---
        addHeader("Стартовое положение на экране:")
        val positionSpinner = Spinner(this)
        val positions = arrayOf("Левый нижний угол", "Правый нижний угол", "Левый верхний угол", "Правый верхний угол")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, positions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        positionSpinner.adapter = adapter
        positionSpinner.setSelection(prefs.getInt("start_gravity_index", 0))
        rootLayout.addView(positionSpinner)

        // --- НАСТРОЙКА: РАЗМЕР ---
        val sizeValueTv = TextView(this)
        val currentSize = prefs.getInt("deer_size", 250)
        sizeValueTv.text = "Размер оверлея: ${currentSize}dp"
        rootLayout.addView(sizeValueTv)
        
        val sizeSeekBar = SeekBar(this).apply {
            max = 500
            progress = currentSize
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = if (progress < 100) 100 else progress
                    sizeValueTv.text = "Размер оверлея: ${p}dp"
                    prefs.edit().putInt("deer_size", p).apply()
                    notifyServiceSettingsChanged()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        rootLayout.addView(sizeSeekBar)

        // --- НАСТРОЙКА: ПРОЗРАЧНОСТЬ ---
        val alphaValueTv = TextView(this)
        val currentAlpha = prefs.getInt("deer_alpha", 100)
        alphaValueTv.text = "Прозрачность: $currentAlpha%"
        rootLayout.addView(alphaValueTv)

        val alphaSeekBar = SeekBar(this).apply {
            max = 100
            progress = currentAlpha
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = if (progress < 10) 10 intervals else progress
                    alphaValueTv.text = "Прозрачность: $p%"
                    prefs.edit().putInt("deer_alpha", p).apply()
                    notifyServiceSettingsChanged()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        rootLayout.addView(alphaSeekBar)

        // --- НАСТРОЙКА: ЧУВСТВИТЕЛЬНОСТЬ МИКРОФОНА ---
        val micValueTv = TextView(this)
        val currentMicThreshold = prefs.getInt("mic_threshold", 3000)
        micValueTv.text = "Порог шума микрофона: $currentMicThreshold"
        rootLayout.addView(micValueTv)

        val micSeekBar = SeekBar(this).apply {
            max = 15000
            progress = currentMicThreshold
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = if (progress < 500) 500 else progress
                    micValueTv.text = "Порог шума микрофона: $p"
                    prefs.edit().putInt("mic_threshold", p).apply()
                    notifyServiceSettingsChanged()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        rootLayout.addView(micSeekBar)

        // --- ОБРАБОТКА СТАРТА ---
        startButton.setOnClickListener {
            if (checkPermissions()) {
                // Сохраняем гравитацию перед запуском
                val gravityFlag = when (positionSpinner.selectedItemPosition) {
                    1 -> Gravity.BOTTOM or Gravity.END
                    2 -> Gravity.TOP or Gravity.START
                    3 -> Gravity.TOP or Gravity.END
                    else -> Gravity.BOTTOM or Gravity.START
                }
                prefs.edit()
                    .putInt("start_gravity_index", positionSpinner.selectedItemPosition)
                    .putInt("start_gravity_flag", gravityFlag)
                    .apply()

                val intent = Intent(this, AppService::class.java).apply {
                    action = "FORCE_PEEK"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Служба оленя перезапущена!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun notifyServiceSettingsChanged() {
        // Отправляем интент сервису, чтобы он обновил параметры на лету, если он уже запущен
        val intent = Intent(this, AppService::class.java).apply {
            action = "UPDATE_SETTINGS"
        }
        startService(intent)
    }

    private fun checkPermissions(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return false
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { 
            ActivityCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED 
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            return false
        }
        return true
    }
}
