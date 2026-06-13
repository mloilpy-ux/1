package com.lunya.deerpeek.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.lunya.deerpeek.data.MemoryManager
import com.lunya.deerpeek.data.SettingsManager

class GeminiCore(
    private val settings: SettingsManager,
    private val memory: MemoryManager
) {
    suspend fun executeInference(input: String): String {
        val key = settings.geminiApiKey
        if (key.isEmpty()) return "ОШИБКА: Токен API не инициализирован."

        memory.record("REQ: $input")
        val contextBuffer = memory.extractContext()

        return try {
            val model = GenerativeModel("gemini-1.5-flash", key)
            val payload = "${settings.systemPrompt}\n\n[CACHE]\n$contextBuffer\n\n[NEW_REQ]: $input"
            
            val response = model.generateContent(payload)
            val output = response.text ?: "[NIL]"
            
            memory.record("RES: $output")
            output
        } catch (e: Exception) {
            "СИСТЕМНЫЙ ОТКАЗ: ${e.localizedMessage}"
        }
    }
}
