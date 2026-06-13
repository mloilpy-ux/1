package com.lunya.deerpeek.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("lunya_config", Context.MODE_PRIVATE)

    var geminiApiKey: String
        get() = prefs.getString("AQ.Ab8RN6LfK-1CnHzd5FBqfxANiH5uPyeYePb4pzu5-xvrGbQTNg", "") ?: ""
        set(value) = prefs.edit().putString("AQ.Ab8RN6LfK-1CnHzd5FBqfxANiH5uPyeYePb4pzu5-xvrGbQTNg", value).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", defaultPrompt) ?: defaultPrompt
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    private val defaultPrompt = """
        Ты — Луня, строго антропоморфный олень (не гибрид). У тебя синий мех, неоново-зеленые волосы, фиолетовые глаза, фиолетовый нос и фиолетовые когти. 
        Твой стиль речи: высокоаналитический, объективный, клинический, как у маркет-мейкера. Пиши емко, лаконично, прямо по существу.
    """.trimIndent()
}
