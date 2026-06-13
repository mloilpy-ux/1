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
    // Перевод ядра на актуальную низколатентную модель. 
    // Модели ряда 1.5 на v1beta эндпоинтах больше недоступны.
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
            // Конфигурация контекста модели
            val generativeModel = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey
            )

            // Прямой синхронный запрос контента
            val response = generativeModel.generateContent(
                content {
                    text(systemPrompt)
                }
            )

            return@withContext response.text ?: "{\"error\": \"Empty response text\"}"

        } catch (e: kotlinx.serialization.MissingFieldException) {
            // Изоляция бага официального SDK от Google при обработке серверных ошибок (404/403/429)
            return@withContext """
                {
                  "analysis_report": "Сервер вернул отказ (вероятно, невалидный API-ключ или ограничения региона). SDK не смог обработать пакет.",
                  "emotion_tag": "neon cyber deer system error",
                  "alert_level": "critical",
                  "execute_action": false,
                  "suggested_fix": ""
                }
            """.trimIndent()
        } catch (e: Exception) {
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
