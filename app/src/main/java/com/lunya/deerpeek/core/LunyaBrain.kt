package com.lunya.deerpeek.core

import android.graphics.Bitmap
import android.util.Log
import com.lunya.deerpeek.ai.GeminiCore
import com.lunya.deerpeek.ai.GeminiImagenClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

class LunyaBrain(
    private val geminiCore: GeminiCore,
    private val imagenClient: GeminiImagenClient
) {
    interface BrainCallback {
        fun onTextReady(text: String)
        fun onStickerReady(bitmap: Bitmap)
        fun onStatusChanged(isThinking: Boolean, isError: Boolean)
        fun onExecuteAction(clipboardFix: String)
    }

    private var lastEmotion = "neutral"
    private val timelineHistory = mutableListOf<JSONObject>()

    suspend fun executePipeline(telemetry: JSONObject, forceTrigger: String, callback: BrainCallback) {
        callback.onStatusChanged(true, false)

        timelineHistory.add(telemetry)
        if (timelineHistory.size > 5) timelineHistory.removeAt(0)

        val historyArray = JSONArray()
        timelineHistory.forEach { historyArray.put(it) }

        val latency = telemetry.optInt("api_latency_ms", -1)
        val agentSystemPrompt = buildSystemPrompt(telemetry, historyArray, forceTrigger)

        val rawOutput = try {
            geminiCore.executeInference(agentSystemPrompt)
        } catch (sdkException: Throwable) {
            Log.e("LunyaBrain", "Критический сбой транспортного уровня SDK. Запуск локального синтеза.", sdkException)
            val diagnostics = diagnoseException(sdkException)
            generateLocalFallback(telemetry, diagnostics)
        }

        try {
            val sanitized = sanitizeJsonString(rawOutput)
            val json = JSONObject(sanitized)
            
            val report = json.getString("analysis_report")
            val emotion = json.getString("emotion_tag")
            val alertLevel = json.getString("alert_level")
            val executeAction = json.getBoolean("execute_action")
            val suggestedFix = json.optString("suggested_fix", "")

            val pingDisplay = if (latency == -1) "SLOW/PROXY" else "${latency}ms"
            val finalReport = "[$alertLevel] PING: $pingDisplay | $report"

            callback.onTextReady(finalReport)

            if (executeAction && suggestedFix.isNotEmpty()) {
                callback.onExecuteAction(suggestedFix)
            }

            if (emotion != lastEmotion && latency != -1 && !rawOutput.contains("LOCAL_FALLBACK")) {
                lastEmotion = emotion
                val newAsset = imagenClient.generateReactionSticker(emotion)
                if (newAsset != null) callback.onStickerReady(newAsset)
            }
            
            val isSystemError = alertLevel == "critical" || alertLevel == "warning" || rawOutput.contains("LOCAL_FALLBACK")
            callback.onStatusChanged(false, isSystemError)

        } catch (parseException: Exception) {
            Log.e("LunyaBrain", "Ошибка разбора выходной структуры. Принудительный вывод сырых данных.")
            callback.onTextReady("[PARSER_ERROR] RAW_LOG: ${rawOutput.take(300)}")
            callback.onStatusChanged(false, true)
        }
    }

    private fun buildSystemPrompt(telemetry: JSONObject, history: JSONArray, trigger: String): String {
        return """
            Ты — автономный кибернетический аналитик. Твое имя — Луня.
            Стиль: прямой, сухой, клинический, без вежливости.
            Телеметрия: $telemetry
            История дельт: $history
            Триггер: $trigger

            Выдай ответ СТРОГО в формате JSON:
            {
              "analysis_report": "Технический вердикт до 3 предложений.",
              "emotion_tag": "focused",
              "alert_level": "info",
              "execute_action": false,
              "suggested_fix": ""
            }
        """.trimIndent()
    }

    private fun diagnoseException(t: Throwable): String {
        val msg = t.message ?: ""
        return when {
            msg.contains("location") || msg.contains("400") -> "GEO_BLOCKED (Запрос заблокирован Google. Настрой v2RayTun)"
            msg.contains("not found") || msg.contains("404") -> "ENDPOINT_404 (Неверный идентификатор модели в GeminiCore)"
            msg.contains("MissingFieldException") -> "PARSER_CRASH (Внутренний баг десериализации Google SDK)"
            else -> t.javaClass.simpleName
        }
    }

    private fun generateLocalFallback(telemetry: JSONObject, reason: String): String {
        val ram = telemetry.optDouble("available_ram_gb", 4.0)
        val battery = telemetry.optInt("battery_percent", 100)
        val netType = telemetry.optString("network_type", "UNKNOWN")
        
        val workspace = telemetry.optJSONObject("workspace_context")
        val hasLog = workspace?.has("tracked_log") == true

        val alertLevel = if (battery < 15 || ram < 1.2 || hasLog) "critical" else "warning"
        
        val reportBuilder = StringBuilder()
        reportBuilder.append("[LOCAL_FALLBACK] Контур инференса изолирован ($reason). ")
        
        if (hasLog) {
            reportBuilder.append("Обнаружен лог системного сбоя в workspace! ")
        } else {
            reportBuilder.append("Среда стабильна. Системные дельты удерживаются. ")
        }
        reportBuilder.append("ОЗУ: ${ram}GB свободно. Питание: $battery%. Сеть: $netType.")

        val fallbackJson = JSONObject().apply {
            put("analysis_report", reportBuilder.toString())
            put("emotion_tag", "computing")
            put("alert_level", alertLevel)
            put("execute_action", false)
            put("suggested_fix", "")
        }
        return fallbackJson.toString()
    }

    private fun sanitizeJsonString(input: String): String {
        var clean = input.trim()
        if (clean.startsWith("```")) {
            val matcher = Pattern.compile("
http://googleusercontent.com/immersive_entry_chip/0

---

Выполни сборку в терминале:
```bash
./gradlew clean assembleDebug
