package com.lunya.deerpeek.core

import android.graphics.Bitmap
import android.util.Log
import com.lunya.deerpeek.ai.GeminiCore
import com.lunya.deerpeek.ai.GeminiImagenClient
import org.json.JSONObject

class LunyaBrain(
    private val geminiCore: GeminiCore,
    private val imagenClient: GeminiImagenClient
) {
    interface BrainCallback {
        fun onTextReady(text: String)
        fun onStickerReady(bitmap: Bitmap)
        fun onStatusChanged(isThinking: Boolean, isError: Boolean)
    }

    private var lastEmotion = "neutral"

    suspend fun executePipeline(input: String, callback: BrainCallback) {
        callback.onStatusChanged(isThinking = true, isError = false)

        // Инжектируем требование JSON-структуры в текущую сессию
        val jsonInstructionPrompt = """
            $input 
            Ответ СТРОГО в формате JSON. Никакого лишнего текста вне JSON структуры.
            Формат:
            {
              "response": "Твой высокоаналитический текст отчета",
              "emotion": "краткое описание эмоции на английском для генератора картинок, например: irritated market maker, analyzing data grid, cold calculation"
            }
        """.trimIndent()

        val rawGeminiOutput = geminiCore.executeInference(jsonInstructionPrompt)

        try {
            // Парсинг структурированного ответа ИИ
            val json = JSONObject(rawGeminiOutput)
            val textResponse = json.getString("response")
            val currentEmotion = json.getString("emotion")

            callback.onTextReady(textResponse)

            // Если эмоция изменилась — активируемImagen 3 для перерисовки ассета Луни
            if (currentEmotion != lastEmotion) {
                lastEmotion = currentEmotion
                Log.d("LunyaBrain", "Обнаружена девиация состояния. Запуск Imagen...")
                val newSticker = imagenClient.generateReactionSticker(currentEmotion)
                if (newSticker != null) {
                    callback.onStickerReady(newSticker)
                }
            }
            callback.onStatusChanged(isThinking = false, isError = false)

        } catch (e: Exception) {
            Log.e("LunyaBrain", "Ошибка разбора матрицы ответа: ${e.message}. Вывод сырых данных.")
            // Резервный режим на случай, если Gemini проигнорировал JSON-структуру
            callback.onTextReady(rawGeminiOutput)
            callback.onStatusChanged(isThinking = false, isError = rawGeminiOutput.contains("ОШИБКА"))
        }
    }
}
