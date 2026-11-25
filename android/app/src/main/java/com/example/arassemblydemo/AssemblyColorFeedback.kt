package com.example.arassemblydemo

import android.content.Context

internal object AssemblyColorFeedback {
    private const val PREF = "assembly-feedback-style"
    fun read(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("style", "pulse") ?: "pulse"
    fun save(context: Context, style: String) {
        if (style in setOf("pulse", "strong", "quiet"))
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("style", style).apply()
    }
}
