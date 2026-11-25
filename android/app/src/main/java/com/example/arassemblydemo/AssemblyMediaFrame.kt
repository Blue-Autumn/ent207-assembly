package com.example.arassemblydemo

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/** The CameraX TextureView and its overlays share one clip and pixel origin. */
internal object AssemblyMediaFrame {
    fun clip(view: View) {
        val radius = 16f * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        view.clipToOutline = true
    }
}
