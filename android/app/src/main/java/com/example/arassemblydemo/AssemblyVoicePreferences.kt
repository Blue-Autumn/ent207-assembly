package com.example.arassemblydemo

import android.content.Context
import java.util.Locale

/** One switch for step instructions, camera prompts and AI replies. */
object AssemblyVoicePreferences {
    data class Selection(val engine: String? = null, val voice: String? = null)

    fun selection(context: Context, language: Locale): Selection {
        val prefs = context.getSharedPreferences("assembly-voice-v1", Context.MODE_PRIVATE)
        return Selection(prefs.getString("engine-${language.language}", null), prefs.getString("voice-${language.language}", null))
    }

    fun select(context: Context, language: Locale, selection: Selection) {
        context.getSharedPreferences("assembly-voice-v1", Context.MODE_PRIVATE).edit()
            .putString("engine-${language.language}", selection.engine)
            .putString("voice-${language.language}", selection.voice)
            .apply()
    }

    fun enabled(context: Context): Boolean = context.getSharedPreferences("assembly-voice-v1", Context.MODE_PRIVATE)
        .getBoolean("automatic", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("assembly-voice-v1", Context.MODE_PRIVATE).edit().putBoolean("automatic", enabled).apply()
    }
}
