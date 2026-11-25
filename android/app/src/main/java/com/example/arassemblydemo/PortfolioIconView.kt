package com.example.arassemblydemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Paths have no font baseline, so small native buttons cannot clip arrow/avatar glyphs. */
internal class PortfolioIconView(context: Context, private val symbol: String, color: Int) : View(context) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height) * .49f
        canvas.save(); canvas.translate((width-size)/2, (height-size)/2); canvas.scale(size/24f, size/24f)
        when(symbol) {
            "community" -> {
                canvas.drawPath(Path().apply {
                    moveTo(5f,3f); lineTo(19f,3f); quadTo(22f,3f,22f,6f)
                    lineTo(22f,14f); quadTo(22f,17f,19f,17f); lineTo(10f,17f)
                    lineTo(5f,21f); lineTo(5f,17f); quadTo(2f,17f,2f,14f)
                    lineTo(2f,6f); quadTo(2f,3f,5f,3f); close()
                }, brush)
                canvas.drawLine(7f,8f,17f,8f,brush); canvas.drawLine(7f,12f,13f,12f,brush)
            }
            "back" -> canvas.drawPath(Path().apply { moveTo(15f,4f); lineTo(7f,12f); lineTo(15f,20f) }, brush)
            "person" -> { canvas.drawCircle(12f,7f,3.8f,brush); canvas.drawPath(Path().apply { moveTo(4f,21f); cubicTo(4f,12f,20f,12f,20f,21f) },brush) }
            "projects" -> {
                canvas.drawPath(Path().apply { moveTo(2f,8f); lineTo(12f,3f); lineTo(22f,8f); lineTo(12f,13f); close() }, brush)
                for (y in listOf(12f,16.5f)) canvas.drawPath(Path().apply { moveTo(2f,y); lineTo(12f,y+5f); lineTo(22f,y) }, brush)
            }
            "saved" -> canvas.drawPath(Path().apply { moveTo(5f,3f); lineTo(19f,3f); lineTo(19f,21f); lineTo(12f,16f); lineTo(5f,21f); close() },brush)
            "heart" -> canvas.drawPath(Path().apply { moveTo(12f,21f); cubicTo(9f,18f,2f,13f,2f,8f); cubicTo(2f,2f,9f,1f,12f,6f); cubicTo(15f,1f,22f,2f,22f,8f); cubicTo(22f,13f,15f,18f,12f,21f); close() },brush)
            "history" -> {
                canvas.drawCircle(12f,12f,9f,brush)
                canvas.drawPath(Path().apply { moveTo(12f,6f); lineTo(12f,12f); lineTo(16f,14f) },brush)
            }
            "comment","community" -> canvas.drawPath(Path().apply { moveTo(4f,4f); lineTo(20f,4f); quadTo(22f,4f,22f,6f); lineTo(22f,15f); quadTo(22f,17f,20f,17f); lineTo(9f,17f); lineTo(4f,21f); lineTo(4f,17f); quadTo(2f,17f,2f,15f); lineTo(2f,6f); quadTo(2f,4f,4f,4f); close() },brush)
            "language" -> {
                canvas.drawCircle(12f,12f,9.5f,brush)
                canvas.drawOval(7.5f,2.5f,16.5f,21.5f,brush)
                canvas.drawLine(3f,12f,21f,12f,brush)
            }
            "voice" -> {
                canvas.drawPath(Path().apply { moveTo(3f,9f); lineTo(7f,9f); lineTo(12f,5f); lineTo(12f,19f); lineTo(7f,15f); lineTo(3f,15f); close() }, brush)
                canvas.drawArc(6f,5f,18f,19f,-47f,94f,false,brush)
                canvas.drawArc(3f,1f,22f,23f,-46f,92f,false,brush)
            }
            "tools" -> {
                for ((y,knob) in listOf(6f to 8f,12f to 16f,18f to 10f)) {
                    canvas.drawLine(3f,y,knob-2f,y,brush); canvas.drawCircle(knob,y,2f,brush); canvas.drawLine(knob+2f,y,21f,y,brush)
                }
            }
            "next" -> canvas.drawPath(Path().apply { moveTo(9f,5f); lineTo(16f,12f); lineTo(9f,19f) },brush)
            else -> { canvas.drawLine(3f,12f,21f,12f,brush); canvas.drawPath(Path().apply { moveTo(14f,5f); lineTo(21f,12f); lineTo(14f,19f) },brush) }
        }
        canvas.restore()
    }
}
