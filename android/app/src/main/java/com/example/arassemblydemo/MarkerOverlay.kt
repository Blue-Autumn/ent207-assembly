package com.example.arassemblydemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/** All incoming points have ALREADY been transformed to PreviewView coordinates. */
class MarkerOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density; textSize = 13f * density }
    private var markers: List<Marker> = emptyList()
    private var projected: FloatArray? = null
    private var part: ProjectedPart? = null
    private val path=android.graphics.Path()
    private var frameAt = 0L
    var debug = false
        set(value) { field = value; invalidate() }
    var onExpired: (() -> Unit)? = null
    private val expiry = Runnable { clear(); onExpired?.invoke() }

    fun show(markers: List<Marker>, projected: FloatArray?, frameAt: Long, part: ProjectedPart?=null) {
        clear()
        val age = SystemClock.elapsedRealtimeNanos() - frameAt
        if (age !in 0 until 400_000_000L) return
        this.markers = markers.filter { it.points.size == 8 && it.points.all(Float::isFinite) }
        this.projected = projected?.takeIf { it.size == 24 && it.all(Float::isFinite) }
        this.frameAt = frameAt
        this.part=part
        postDelayed(expiry, ((400_000_000L - age) / 1_000_000L).coerceAtLeast(1))
        invalidate()
    }

    fun clear() { removeCallbacks(expiry); markers = emptyList(); projected = null; part=null; frameAt = 0; invalidate() }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (SystemClock.elapsedRealtimeNanos() - frameAt !in 0 until 400_000_000L) return
        if (debug) markers.forEach { marker ->
            val points = marker.points
            paint.color = if (marker.id in 0..3) Color.GREEN else Color.rgb(255, 180, 60)
            paint.strokeWidth = 1.5f * density
            for (corner in 0..3) {
                val next = (corner + 1) % 4
                canvas.drawLine(points[corner * 2], points[corner * 2 + 1], points[next * 2], points[next * 2 + 1], paint)
            }
            val label = "Raw ${marker.id}"
            val x = points[0].coerceIn(0f, (width - paint.measureText(label)).coerceAtLeast(0f))
            val y = (points[1] - 5 * density).coerceIn(16 * density, height.toFloat().coerceAtLeast(16 * density))
            canvas.drawText(label, x, y, paint)
        }
        part?.let {mesh ->
            paint.style=Paint.Style.FILL
            mesh.colors.indices.forEach {face ->
                path.rewind()
                for(i in 0..2) {val index=mesh.triangles[3*face+i]*2;val x=mesh.points[index];val y=mesh.points[index+1]
                    if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                path.close();paint.color=mesh.colors[face];canvas.drawPath(path,paint)
            }
            return
        }
        val p = projected ?: return
        fun line(a: Int, b: Int) { canvas.drawLine(p[2*a], p[2*a+1], p[2*b], p[2*b+1], paint) }
        paint.strokeWidth = 2.2f * density
        paint.color = Color.rgb(255, 218, 85)
        for (i in 0..3) { line(i, (i+1)%4); line(i+4, (i+1)%4+4); line(i, i+4) }
        paint.strokeWidth = 3f * density
        val colors = intArrayOf(Color.rgb(255,80,80), Color.rgb(80,255,110), Color.rgb(70,160,255))
        for (axis in 0..2) {
            paint.color = colors[axis]; line(8,9+axis)
            canvas.drawText(listOf("X","Y","Z")[axis],p[2*(9+axis)]+4*density,p[2*(9+axis)+1],paint)
        }
        paint.color = Color.WHITE
        canvas.drawCircle(p[16],p[17],3*density,paint)
    }
}
