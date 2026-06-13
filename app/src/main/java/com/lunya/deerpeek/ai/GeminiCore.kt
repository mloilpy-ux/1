package com.lunya.deerpeek.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.lunya.deerpeek.data.MemoryManager
import com.lunya.deerpeek.data.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GeminiCore(
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager,
    private val context: Context
) {
    private val modelName = "gemini-2.5-flash"
    private val ttsModelName = "gemini-2.5-flash-preview-tts"
    private var audioTrack: AudioTrack? = null

    /**
     * Выполнение мультимодального инференса (поддерживает отправку скриншота)
     */
    suspend fun executeInference(systemPrompt: String, screenshotBytes: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val apiKey = settingsManager.geminiApiKey
        if (apiKey.isBlank()) {
            return@withContext createLocalErrorJson("API-ключ отсутствует.")
        }

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        try {
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doOutput = true

            val requestBody = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            // 1. Текстовый промпт
                            put(JSONObject().apply {
                                put("text", systemPrompt)
                            })
                            // 2. Скриншот (если передан)
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
                }
                put("contents", contentsArray)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        connection.disconnect()
                        return@withContext parts.getJSONObject(0).optString("text", "")
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.e("GeminiCore", "Inference error", e)
        }

        return@withContext createLocalErrorJson("Сбой сети или геоблокировки.")
    }

    /**
     * Запуск синтеза речи Луни через Gemini TTS API и немедленное воспроизведение PCM аудио
     */
    fun speakText(text: String) {
        val apiKey = settingsManager.geminiApiKey
        if (apiKey.isBlank()) return

        Thread {
            try {
                val speechPrompt = "Say cheerfully and directly: $text"
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$ttsModelName:generateContent?key=$apiKey"
                
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", speechPrompt)
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply { put("AUDIO") })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", "Kore")
                                })
                            })
                        })
                    })
                }

                OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseText)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                val inlineData = part.optJSONObject("inlineData")
                                if (inlineData != null && inlineData.optString("mimeType").contains("audio")) {
                                    val base64Audio = inlineData.getString("data")
                                    val rawAudioBytes = Base64.decode(base64Audio, Base64.DEFAULT)
                                    playPcmAudio(rawAudioBytes)
                                    break
                                }
                            }
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("GeminiCore", "TTS generation or playback failed", e)
            }
        }.start()
    }

    /**
     * Воспроизведение несжатого PCM-16 потока через системный тракт Android
     */
    private fun playPcmAudio(pcmBytes: ByteArray) {
        try {
            audioTrack?.stop()
            audioTrack?.release()

            val sampleRate = 24000
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            
            val headerOffset = if (pcmBytes.size > 44 && pcmBytes[0] == 'R'.toByte() && pcmBytes[1] == 'I'.toByte()) 44 else 0
            audioTrack?.write(pcmBytes, headerOffset, pcmBytes.size - headerOffset)
        } catch (e: Exception) {
            Log.e("GeminiCore", "AudioTrack play error", e)
        }
    }

    private fun createLocalErrorJson(message: String): String {
        val json = JSONObject().apply {
            put("analysis_report", "Сбой: $message. Локальный режим включен.")
            put("emotion_tag", "neon cyber deer system error")
            put("alert_level", "critical")
            put("execute_action", false)
            put("suggested_fix", "")
        }
        return json.toString()
    }
}
