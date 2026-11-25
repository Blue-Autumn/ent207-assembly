package com.example.arassemblydemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Theme-independent track: some Huawei horizontal progress themes hide the remainder. */
internal class PortfolioProgressView(context: Context, done: Int, total: Int) : View(context) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fraction = if (total > 0) (done.toFloat()/total).coerceIn(0f,1f) else 0f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val radius = height/2f
        brush.color = 0xffdedfd5.toInt(); canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,brush)
        brush.color = 0xff272b21.toInt(); if(fraction>0f) canvas.drawRoundRect(0f,0f,width*fraction,height.toFloat(),radius,radius,brush)
    }
}
