package com.lunya.deerpeek.core

import android.graphics.Bitmap
import android.util.Log
import com.lunya.deerpeek.ai.GeminiCore
import com.lunya.deerpeek.ai.GeminiImagenClient
import org.json.JSONArray
import org.json.JSONObject

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
        if (timelineHistory.size > 5) {
            timelineHistory.removeAt(0)
        }

        val historyArray = JSONArray()
        timelineHistory.forEach { historyArray.put(it) }

        val latency = telemetry.optInt("api_latency_ms", -1)

        val agentSystemPrompt = """
            Ты — автономный кибернетический аналитик. Твое имя — Луня (антропоморфный олень).
            Стиль: прямой, сухой, клинический, без вежливости.
            
            Входная телеметрия: $telemetry
            Хронология дельт: $historyArray
            Триггер запуска: $forceTrigger

            Инструкция обработки:
            1. Если в `tracked_log` или `source_code` есть ошибки выполнения скриптов, напиши патч и помести его в `suggested_fix`, переключив `execute_action` в true.

            Выдай ответ СТРОГО в формате JSON:
            {
              "analysis_report": "Технический вердикт. До 3 предложений.",
              "emotion_tag": "Промпт для Imagen 3",
              "alert_level": "info", "warning", или "critical",
              "execute_action": true/false,
              "suggested_fix": "Исправленный код или команда терминала"
            }
        """.trimIndent()

        val rawOutput = geminiCore.executeInference(agentSystemPrompt)

        try {
            val sanitizedOutput = rawOutput.substringAfter("```json").substringBefore("```").trim()
            val json = JSONObject(sanitizedOutput)
            
            val report = json.getString("analysis_report")
            val emotion = json.getString("emotion_tag")
            val alertLevel = json.getString("alert_level")
            val executeAction = json.getBoolean("execute_action")
            val suggestedFix = json.optString("suggested_fix", "")

            // Коррекция: Выводим отчет модели в любом случае. Лог пинга больше не блокирует интерфейс.
            val pingDisplay = if (latency == -1) "SLOW/PROXY" else "${latency}ms"
            val finalReport = "[$alertLevel] PING: $pingDisplay | $report"

            callback.onTextReady(finalReport)

            if (executeAction && suggestedFix.isNotEmpty()) {
                callback.onExecuteAction(suggestedFix)
            }

            if (emotion != lastEmotion && latency != -1) {
                lastEmotion = emotion
                val newAsset = imagenClient.generateReactionSticker(emotion)
                if (newAsset != null) {
                    callback.onStickerReady(newAsset)
                }
            }
            
            val isSystemError = alertLevel == "critical" || alertLevel == "warning"
            callback.onStatusChanged(false, isSystemError)

        } catch (e: Exception) {
            Log.e("LunyaBrain", "JSON Parsing failure")
            callback.onTextReady("RAW_LOG: $rawOutput")
            callback.onStatusChanged(false, true)
        }
    }
}
