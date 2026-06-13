package com.lunya.deerpeek

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import java.io.File

class AiEngine(private val context: Context) {
    // Внедрение ключа
    private val apiKey = "AQ.Ab8RN6LfK-1CnHzd5FBqfxANiH5uPyeYePb4pzu5-xvrGbQTNg"
    private val historyFile = File(context.filesDir, "lunya_memory.log")

    private val systemPrompt = """
        Ты — Луня, строго антропоморфный олень (не гибрид). У тебя синий мех, неоново-зеленые волосы, фиолетовые глаза, фиолетовый нос и фиолетовые когти. 
        Твой стиль речи: высокоаналитический, объективный, клинический, как у маркет-мейкера. Полное отсутствие chatbot-вежливости, филлеров и навязчивого дружелюбия. Пиши емко, лаконично, прямо по существу.
    """.trimIndent()

    init {
        if (!historyFile.exists()) {
            historyFile.createNewFile()
            Log.d("AiEngine", "Файл долговременной памяти инициализирован.")
        }
    }

    suspend fun generateResponse(input: String): String {
        if (apiKey == "AQ.Ab8RN6LfK-1CnHzd5FBqfxANiH5uPyeYePb4pzu5-xvrGbQTNg" || apiKey.isEmpty()) {
            return "ОШИБКА: Отсутствует токен авторизации."
        }

        saveToMemory("IN: $input")
        val contextHistory = getRecentMemory()

        return try {
            val model = GenerativeModel("gemini-1.5-flash", apiKey)
            val fullContext = "$systemPrompt\n\nПоследние данные памяти:\n$contextHistory\n\nНовый запрос: $input"
            
            val response = model.generateContent(fullContext)
            val resultText = response.text ?: "[Пустой ответ]"
            
            saveToMemory("OUT: $resultText")
            resultText
        } catch (e: Exception) {
            Log.e("AiEngine", "Сбой генерации: ${e.message}")
            "СИСТЕМНЫЙ СБОЙ: ${e.localizedMessage}"
        }
    }

    private fun saveToMemory(data: String) {
        try {
            historyFile.appendText("$data\n")
        } catch (e: Exception) {
            Log.e("AiEngine", "Ошибка записи в память: ${e.message}")
        }
    }

    private fun getRecentMemory(): String {
        return try {
            val lines = historyFile.readLines()
            // Возврат последних 10 записей для удержания контекста без перегрузки токенов
            lines.takeLast(10).joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }
}
