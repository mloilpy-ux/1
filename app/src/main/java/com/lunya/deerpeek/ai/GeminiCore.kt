package com.lunya.deerpeek.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.lunya.deerpeek.data.MemoryManager
import com.lunya.deerpeek.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiCore(
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager
) {
    private val modelName = "gemini-2.5-flash"

    suspend fun executeInference(systemPrompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = settingsManager.geminiApiKey
        if (apiKey.isBlank()) {
            return@withContext """
                {
                  "analysis_report": "Критическая остановка: отсутствует API-токен в конфигураторе.",
                  "emotion_tag": "neon cyber deer system error",
                  "alert_level": "critical",
                  "execute_action": false,
                  "suggested_fix": ""
                }
            """.trimIndent()
        }

        try {
            val generativeModel = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey
            )

            val response = generativeModel.generateContent(
                content {
                    text(systemPrompt)
                }
            )

            return@withContext response.text ?: "{\"error\": \"Empty response text\"}"

        } catch (e: Exception) {
            // Динамическая проверка типа исключения во избежание сбоев компиляции при отсутствии явной зависимости
            val exceptionClassName = e.javaClass.name
            if (exceptionClassName.contains("MissingFieldException") || exceptionClassName.contains("SerializationException")) {
                return@withContext """
                    {
                      "analysis_report": "Сервер вернул отказ (вероятно, невалидный API-ключ или ограничения региона). SDK не смог обработать пакет.",
                      "emotion_tag": "neon cyber deer system error",
                      "alert_level": "critical",
                      "execute_action": false,
                      "suggested_fix": ""
                    }
                """.trimIndent()
            }

            // Глобальный перехват аппаратных или сетевых исключений
            val escapedError = (e.message ?: "Unknown Core Exception").replace("\"", "\\\"")
            return@withContext """
                {
                  "analysis_report": "Сбой аппаратного контура инференса: $escapedError",
                  "emotion_tag": "neon cyber deer system error",
                  "alert_level": "critical",
                  "execute_action": false,
                  "suggested_fix": ""
                }
            """.trimIndent()
        }
    }
}
