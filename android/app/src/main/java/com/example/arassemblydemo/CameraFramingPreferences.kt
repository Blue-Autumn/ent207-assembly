package com.example.arassemblydemo

import android.content.Context

object CameraFramingPreferences {
    fun read(context: Context): CameraFramingMode = runCatching {
        CameraFramingMode.valueOf(context.getSharedPreferences("assembly-camera-v1", Context.MODE_PRIVATE)
            .getString("framing", "MAIN") ?: "MAIN")
    }.getOrDefault(CameraFramingMode.MAIN)
    fun save(context: Context, mode: CameraFramingMode) {
        context.getSharedPreferences("assembly-camera-v1", Context.MODE_PRIVATE).edit().putString("framing", mode.name).apply()
    }
}
