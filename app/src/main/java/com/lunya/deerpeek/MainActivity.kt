package com.lunya.deerpeek

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("log_msg") ?: return
            appendLog(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Создаем интерфейс программно, чтобы не зависеть от XML
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padding = 32
        }

        val startButton = Button(this).apply {
            text = "ЗАПУСТИТЬ ОЛЕНЯ И ДАТЧИКИ"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(startButton)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        logTextView = TextView(this).apply {
            text = "--- СИСТЕМНЫЙ ЛОГ ДЕБАГА ---\nПриложение запущено. Ожидание старта сервиса...\n"
            textSize = 14f
            contentDescription = "Логи приложения"
        }
        
        scrollView.addView(logTextView)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        startButton.setOnClickListener {
            appendLog("Нажата кнопка запуска...")
            if (checkPermissions()) {
                startDeerService()
            }
        }

        // Регистрируем приемник логов от сервиса
        val filter = IntentFilter("com.lunya.deerpeek.LOG_BROADCAST")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(logReceiver, filter)
        }
    }

    private fun startDeerService() {
        try {
            val intent = Intent(this, AppService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            appendLog("Команда startService отправлена в систему.")
        } catch (e: Exception) {
            appendLog("КРИТИЧЕСКАЯ ОШИБКА СТАРТА СЕРВИСА: ${e.localizedMessage}")
        }
    }

    private fun appendLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logTextView.append("[$time] $text\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun checkPermissions(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            appendLog("Запрос разрешения на отображение поверх окон...")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return false
        }
        appendLog("Разрешение на оверлеи: ОК")

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter { 
            ActivityCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED 
        }

        if (missing.isNotEmpty()) {
            appendLog("Запрос разрешений: ${missing.joinToString()}")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            return false
        }
        
        appendLog("Все системные разрешения выданы.")
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            // Игнорируем
        }
    }
}
