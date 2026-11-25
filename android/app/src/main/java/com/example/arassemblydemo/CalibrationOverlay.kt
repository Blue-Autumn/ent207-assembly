package com.example.arassemblydemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class CalibrationOverlay(context: Context): View(context) {
    private var points=FloatArray(0)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.CYAN;strokeWidth=2f}
    private val expiry=Runnable {clear()}
    fun show(p: FloatArray) {removeCallbacks(expiry);points=p;invalidate();postDelayed(expiry,400)}
    fun clear() {removeCallbacks(expiry);points=FloatArray(0);invalidate()}
    override fun onDetachedFromWindow() {clear();super.onDetachedFromWindow()}
    override fun onDraw(canvas: Canvas) {super.onDraw(canvas);for(i in points.indices step 2) canvas.drawCircle(points[i],points[i+1],4f,paint)}
}
