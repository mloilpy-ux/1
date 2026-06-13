package com.lunya.deerpeek

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.concurrent.thread

// ==========================================
// 1. КОМПОНЕНТЫ СТРУКТУРЫ ДАННЫХ И ЗАГЛУШКИ
// ==========================================

class MemoryManager(context: Context)

class GeminiImagenClient(private val settingsManager: SettingsManager) {
    fun generateReactionSticker(emotion: String): Bitmap? = null
}

// ==========================================
// 2. УПРАВЛЕНИЕ КОНФИГУРАЦИЕЙ И НАСТРОЙКАМИ
// ==========================================

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("deerpeek_secure_prefs", Context.MODE_PRIVATE)

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_api_key", value).apply()

    var customProxyUrl: String
        get() = prefs.getString("custom_proxy_url", "https://generativelanguage.googleapis.com") ?: "https://generativelanguage.googleapis.com"
        set(value) = prefs.edit().putString("custom_proxy_url", value).apply()

    var selectedVoice: String
        get() = prefs.getString("selected_voice", "Kore") ?: "Kore"
        set(value) = prefs.edit().putString("selected_voice", value).apply()

    var systemCharacterPrompt: String
        get() = prefs.getString("system_character_prompt", "Ты — Луня, антропоморфный олень-аналитик. Твой стиль общения: прямой, саркастичный, технический.") ?: "Ты — Луня, антропоморфный олень-аналитик. Твой стиль общения: прямой, саркастичный, technical."
        set(value) = prefs.edit().putString("system_character_prompt", value).apply()

    var overlayOpacity: Float
        get() = prefs.getFloat("overlay_opacity", 0.95f)
        set(value) = prefs.edit().putFloat("overlay_opacity", value).apply()

    var updateIntervalMs: Long
        get() = prefs.getLong("update_interval_ms", 45000L)
        set(value) = prefs.edit().putLong("update_interval_ms", value).apply()

    var isTtsEnabled: Boolean
        get() = prefs.getBoolean("is_tts_enabled", true)
        set(value) = prefs.edit().putBoolean("is_tts_enabled", value).apply()
}

// ==========================================
// 3. СБОР ТЕЛЕМЕТРИИ СИСТЕМЫ
// ==========================================

class TelemetryCollector(private val context: Context, private val settingsManager: SettingsManager) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun getSystemSnapshot(): JSONObject {
        val json = JSONObject()
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availableRamGb = memoryInfo.availMem / (1024 * 1024 * 1024.0)
        
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (scale > 0) (level / scale.toFloat()) * 100 else -1.0

        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val netType = when {
            capabilities == null -> "OFFLINE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "UNKNOWN"
        }
        
        val apiLatency = measureApiLatency()
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus else 0

        val clipboardText = try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/*") == true) {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            } else ""
        } catch (e: Exception) { "" }

        json.put("available_ram_gb", String.format("%.2f", availableRamGb))
        json.put("battery_percent", batteryPct.toInt())
        json.put("network_type", netType)
        json.put("api_latency_ms", apiLatency)
        json.put("thermal_throttling_level", thermalStatus)
        json.put("clipboard_content", clipboardText.take(1500))
        json.put("workspace_context", readWorkspaceContext())
        
        return json
    }

    private fun measureApiLatency(): Int {
        return try {
            val startTime = System.currentTimeMillis()
            val customEndpoint = settingsManager.customProxyUrl.removeSuffix("/")
            val url = URL("$customEndpoint/v1beta/models")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1200
            connection.readTimeout = 1200
            connection.requestMethod = "GET"
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            if (code in 200..404) (System.currentTimeMillis() - startTime).toInt() else -1
        } catch (e: Exception) { -1 }
    }

    private fun readWorkspaceContext(): JSONObject {
        val workspaceJson = JSONObject()
        try {
            val targetDir = context.getExternalFilesDir("workspace")
            if (targetDir != null && targetDir.exists()) {
                val logFile = File(targetDir, "latest_error.log")
                if (logFile.exists()) workspaceJson.put("tracked_log", logFile.readText().take(800))
            }
        } catch (e: Exception) { }
        return workspaceJson
    }
}

// ==========================================
// 4. ЯДРО СЕТЕВОГО КЛИЕНТА И ИНТЕГРАЦИЯ TTS
// ==========================================

class GeminiCore(private val settingsManager: SettingsManager, private val context: Context) {
    private val modelName = "gemini-2.5-flash"
    private val ttsModelName = "gemini-2.5-flash-preview-tts"
    private var audioTrack: AudioTrack? = null

    suspend fun executeInference(systemPrompt: String, screenshotBytes: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val apiKey = settingsManager.geminiApiKey
        if (apiKey.isBlank()) return@withContext createLocalErrorJson("Ключ API отсутствует.")

        val baseUrl = settingsManager.customProxyUrl.removeSuffix("/")
        val endpoint = "$baseUrl/v1beta/models/$modelName:generateContent?key=$apiKey"

        try {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.doOutput = true

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemPrompt) })
                            if (screenshotBytes != null) {
                                put(JSONObject().apply {
                                    put("inlineData", JSONObject().apply {
                                        put("mimeType", "image/png")
                                        put("data", Base64.encodeToString(screenshotBytes, Base64.NO_WRAP))
                                    })
                                })
                            }
                        })
                    })
                })
            }

            OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val parts = JSONObject(responseText).optJSONArray("candidates")?.getJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    connection.disconnect()
                    return@withContext parts.getJSONObject(0).optString("text", "")
                }
            }
            connection.disconnect()
        } catch (e: Exception) { }
        return@withContext createLocalErrorJson("Сбой сети.")
    }

    fun speakText(text: String) {
        if (!settingsManager.isTtsEnabled || settingsManager.geminiApiKey.isBlank()) return
        thread {
            try {
                val baseUrl = settingsManager.customProxyUrl.removeSuffix("/")
                val endpoint = "$baseUrl/v1beta/models/$ttsModelName:generateContent?key=${settingsManager.geminiApiKey}"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", "Say: $text") }) })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply { put("AUDIO") })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply { put("voiceName", settingsManager.selectedVoice) })
                            })
                        })
                    })
                }

                OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val parts = JSONObject(responseText).optJSONArray("candidates")?.getJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                            if (inlineData != null && inlineData.optString("mimeType").contains("audio")) {
                                playPcmAudio(Base64.decode(inlineData.getString("data"), Base64.DEFAULT))
                                break
                            }
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) { }
        }
    }

    private fun playPcmAudio(pcmBytes: ByteArray) {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            val sampleRate = 24000
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            val offset = if (pcmBytes.size > 44 && pcmBytes[0] == 'R'.toByte() && pcmBytes[1] == 'I'.toByte()) 44 else 0
            audioTrack?.write(pcmBytes, offset, pcmBytes.size - offset)
        } catch (e: Exception) { }
    }

    private fun createLocalErrorJson(message: String): String = JSONObject().apply {
        put("analysis_report", message)
        put("emotion_tag", "system_error")
        put("alert_level", "critical")
        put("execute_action", false)
        put("suggested_fix", "")
    }.toString()
}

// ==========================================
// 5. ЛОГИКА АНАЛИЗА И ЭВРИСТИКА ОШИБОК
// ==========================================

class LunyaBrain(private val geminiCore: GeminiCore, private val imagenClient: GeminiImagenClient, private val settingsManager: SettingsManager) {
    interface BrainCallback {
        fun onTextReady(text: String)
        fun onStickerReady(bitmap: Bitmap)
        fun onStatusChanged(isThinking: Boolean, isError: Boolean)
        fun onExecuteAction(clipboardFix: String)
    }

    suspend fun executePipeline(telemetry: JSONObject, forceTrigger: String, callback: BrainCallback, screenshotBytes: ByteArray? = null) {
        callback.onStatusChanged(true, false)
        val latency = telemetry.optInt("api_latency_ms", -1)
        val clipText = telemetry.optString("clipboard_content", "")
        
        val offlineResult = analyzeOfflineHeuristics(clipText, telemetry)
        val agentSystemPrompt = "${settingsManager.systemCharacterPrompt}\nTelemetry: $telemetry\nTrigger: $forceTrigger\nRespond STROGO JSON."

        val rawOutput = try { geminiCore.executeInference(agentSystemPrompt, screenshotBytes) } catch (e: Throwable) { "" }

        try {
            val report: String
            val alertLevel: String
            val executeAction: Boolean
            val suggestedFix: String
            val isAiValid = rawOutput.isNotEmpty() && !rawOutput.contains("system_error")

            if (isAiValid) {
                val json = JSONObject(sanitizeJsonString(rawOutput))
                report = json.getString("analysis_report")
                alertLevel = json.getString("alert_level")
                executeAction = json.getBoolean("execute_action")
                suggestedFix = json.optString("suggested_fix", "")
            } else {
                report = offlineResult.getString("analysis_report")
                alertLevel = offlineResult.getString("alert_level")
                executeAction = offlineResult.getBoolean("execute_action")
                suggestedFix = offlineResult.getString("suggested_fix")
            }

            callback.onTextReady("[$alertLevel] PING: ${if (latency == -1) "OFFLINE" else "${latency}ms"} | $report")
            geminiCore.speakText(report)
            if (executeAction && suggestedFix.isNotEmpty()) callback.onExecuteAction(suggestedFix)
            callback.onStatusChanged(false, alertLevel == "critical")
        } catch (e: Exception) {
            callback.onTextReady("[STABLE] Показатели в норме.")
            callback.onStatusChanged(false, false)
        }
    }

    private fun analyzeOfflineHeuristics(clip: String, telemetry: JSONObject): JSONObject = JSONObject().apply {
        put("emotion_tag", "computing").put("alert_level", "info").put("execute_action", false).put("suggested_fix", "")
        val thermal = telemetry.optInt("thermal_throttling_level", 0)

        when {
            clip.contains("ModuleNotFoundError:") -> {
                val mod = clip.substringAfter("named '").substringBefore("'")
                put("analysis_report", "[OFFLINE AI] Отсутствует библиотека '$mod'.").put("execute_action", true).put("suggested_fix", "pip install $mod").put("alert_level", "warning")
            }
            clip.contains("Unresolved reference:") -> {
                put("analysis_report", "[OFFLINE AI] Дефект компиляции Kotlin/Java.").put("alert_level", "warning")
            }
            thermal > 1 -> {
                put("analysis_report", "[ALERT] Термальный троттлинг процессора!").put("alert_level", "critical")
            }
            else -> put("analysis_report", "Система штатно собирает логи. Ошибок компиляции не обнаружено.")
        }
    }

    private fun sanitizeJsonString(input: String): String {
        var clean = input.trim()
        if (clean.startsWith("```")) {
            val matcher = Pattern.compile("
http://googleusercontent.com/immersive_entry_chip/0

#### Шаг 3: Перезапуск
Выполни принудительную очистку кэша сборщика и запусти компиляцию повторно:

```bash
./gradlew clean assembleDebug
