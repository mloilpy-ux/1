package com.lunya.deerpeek.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.lunya.deerpeek.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GeminiImagenClient(private val settings: SettingsManager) {

    suspend fun generateReactionSticker(emotionDescription: String): Bitmap? = withContext(Dispatchers.IO) {
        val apiKey = settings.geminiApiKey
        if (apiKey.isEmpty()) {
            Log.e("ImagenClient", "Ключ отсутствует.")
            return@withContext null
        }

        // Промпт жестко фиксирует параметры персонажа во избежание галлюцинаций нейросети
        val strictCharacterPrompt = """
            Anthro male deer sticker named Lunya, blue fur, neon-green hair, purple eyes, purple nose. 
            Expression: $emotionDescription. Cinematic noir style, dark ambient, neon highlights, vector sticker outline, transparent background.
        """.trimIndent()

        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:generateImages?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            // Сборка JSON-пакета для Imagen 3
            val jsonPayload = JSONObject().apply {
                put("prompt", strictCharacterPrompt)
                put("numberOfImages", 1)
                put("aspectRatio", "1:1")
                put("outputMimeType", "image/png")
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonPayload.toString()) }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)
                val base64Image = jsonResponse
                    .getJSONArray("generatedImages")
                    .getJSONObject(0)
                    .getJSONObject("image")
                    .getString("imageBytes")

                val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                return@withContext BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } else {
                Log.e("ImagenClient", "Ошибка сервера HTTP: ${conn.responseCode} - ${conn.responseMessage}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("ImagenClient", "Критический сбой синтеза: ${e.message}")
            return@withContext null
        }
    }
}
