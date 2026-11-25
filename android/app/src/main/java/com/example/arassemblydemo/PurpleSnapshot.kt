package com.example.arassemblydemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint

internal object PurpleSnapshot {
    fun make(gray: ByteArray, w: Int, h: Int, centers: Map<Int,PurpleJointLocator.XY>, rotation: Int): Pair<Bitmap,Boolean> {
        val points=PurpleJointLocator.locate(gray,w,h,centers)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(gray.size) {
            val v=gray[it].toInt() and 255
            Color.rgb(v,v,v)
        },0,w,0,0,w,h)
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.rgb(0,240,160); style=Paint.Style.STROKE; strokeWidth=3f
        }
        points?.forEach { canvas.drawCircle(it.x.toFloat(),it.y.toFloat(),minOf(w,h)*0.021f,paint) }
        val oriented=if(rotation==0) bitmap else Bitmap.createBitmap(bitmap,0,0,w,h,Matrix().apply { postRotate(rotation.toFloat()) },true)
        return oriented to (points!=null)
    }
}
