package com.lunya.deerpeek

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import androidx.activity.ComponentActivity
import com.lunya.deerpeek.core.AppService
import com.lunya.deerpeek.data.SettingsManager

class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settingsManager = SettingsManager(this)

        // Переход к валидации токена и разрешений
        evaluateRuntimeState()
    }

    private fun evaluateRuntimeState() {
        // 1. Критическая проверка: наличие API токена
        if (settingsManager.getApiKey().isNullOrBlank()) {
            showTokenInputDialog()
            return
        }

        // 2. Проверка разрешений на системный оверлей
        if (checkOverlayPermission()) {
            startCoreService()
        } else {
            requestOverlayPermission()
        }
    }

    private fun showTokenInputDialog() {
        val input = EditText(this).apply {
            hint = "AIzaSy..." // Стандартный префикс ключей Google Gemini
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Конфигурация ядра Gemini")
            .setMessage("Введите ваш Gemini API Key для авторизации вычислительного кластера:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Записать") { dialog, _ ->
                val token = input.text.toString().trim()
                if (token.isNotEmpty()) {
                    settingsManager.setApiKey(token) // Запись в SharedPreferences
                    dialog.dismiss()
                    evaluateRuntimeState() // Повторный прогон проверок среды
                }
            }
            .setNegativeButton("Выход") { _, _ ->
                finish()
            }
            .show()
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 123)
        }
    }

    private fun startCoreService() {
        val intent = Intent(this, AppService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish() // Выгрузка Activity, управление переходит фоновому демону
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 123) {
            evaluateRuntimeState()
        }
    }
}
