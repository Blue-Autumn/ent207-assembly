package com.example.arassemblydemo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Cards share static thumbnails; only a visible detail view schedules animated frames. */
internal class CommunityModelView(context: Context, private val model: CommunityModel, private val animateParts: Boolean = false) : View(context) {
    private data class Key(val model: CommunityModel, val width: Int, val height: Int)
    private class Scene(val renderer: CommunityRaster, val frame: CommunityRaster.Frame)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    private var bitmap: Bitmap? = null
    private var bitmapKey: Key? = null
    private var workerScene: Scene? = null
    private var pending: Future<*>? = null
    private var generation = 0
    private var lastYaw = Float.NaN
    private var lastSeparation = Float.NaN
    private var yaw = .63f
    private var begin = 0L
    private var touchTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var startYaw = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        begin = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        generation++
        pending?.let { it.cancel(false); (if (animateParts) motion else posters).purge() }
        pending = null
        workerScene = null
        bitmap = null
        bitmapKey = null
        lastYaw = Float.NaN
        dragging = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || !isAttachedToWindow) return
        val maxSize = if (animateParts) 640f else 440f
        val factor = min(1f,maxSize/maxOf(width,height))
        val key = Key(model,(width*factor).roundToInt().coerceAtLeast(1),(height*factor).roundToInt().coerceAtLeast(1))
        if (bitmapKey != key && !animateParts) {
            cache.get(key)?.let { bitmap=it; bitmapKey=key }
        }
        val image = bitmap
        if (image != null) {
            destination.set(0,0,width,height)
            canvas.drawBitmap(image,null,destination,paint)
        }
        if (!animateParts) {
            if (bitmapKey != key && pending == null) render(key,0f)
            return
        }
        val moving = ValueAnimator.areAnimatorsEnabled()
        val t = (if (dragging) touchTime else SystemClock.uptimeMillis()) - begin
        val cycle = if (moving) t.coerceAtLeast(0) % 4800 else 0
        val separation = when {
            cycle < 700 -> 0f
            cycle < 1460 -> eased((cycle-700)/760f)
            cycle < 1780 -> 1f
            else -> 1f-eased((cycle-1780)/1480f)
        }
        if (pending == null && (bitmapKey != key || lastYaw != yaw || lastSeparation != separation))
            render(key,separation)
        if (moving && !dragging && isShown && windowVisibility==VISIBLE) {
            when {
                cycle < 700 -> postInvalidateDelayed(701-cycle)
                cycle in 1460..1779 -> postInvalidateDelayed(1781-cycle)
                cycle >= 3260 -> postInvalidateDelayed(5501-cycle)
                pending == null -> postInvalidateOnAnimation()
            }
        }
    }

    private fun render(key: Key,separation: Float) {
        val token = generation
        val angle = yaw
        val existing = workerScene
        val executor = if (animateParts) motion else posters
        try {
            pending = executor.submit {
                val scene = existing?.takeIf { it.frame.width==key.width && it.frame.height==key.height } ?:
                    Scene(existing?.renderer ?: CommunityRaster(model),CommunityRaster.Frame(key.width,key.height))
                scene.renderer.render(scene.frame,angle,separation)
                val poster = if (!animateParts) Bitmap.createBitmap(scene.frame.pixels,key.width,key.height,Bitmap.Config.ARGB_8888) else null
                if (poster!=null) cache.put(key,poster)
                main.post {
                    if (generation!=token || !isAttachedToWindow) return@post
                    if (poster!=null) bitmap=poster else {
                        workerScene=scene
                        val frameBitmap = bitmap?.takeIf { it.width==key.width && it.height==key.height } ?:
                            Bitmap.createBitmap(key.width,key.height,Bitmap.Config.ARGB_8888)
                        frameBitmap.setPixels(scene.frame.pixels,0,key.width,0,0,key.width,key.height)
                        bitmap=frameBitmap
                    }
                    bitmapKey=key; lastYaw=angle; lastSeparation=separation
                    pending=null
                    postInvalidateOnAnimation()
                }
            }
        } catch (_: RejectedExecutionException) {
            // A fast fling can occupy all preview workers. Retry only while this view is attached.
            postInvalidateDelayed(64)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!animateParts) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x;downY=e.y;startYaw=yaw;return true }
            MotionEvent.ACTION_MOVE -> {
                val dx=e.x-downX
                if (!dragging && abs(dx)>slop && abs(dx)>abs(e.y-downY)) {
                    dragging=true;touchTime=SystemClock.uptimeMillis()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) { yaw=startYaw+dx*.012f/resources.displayMetrics.density;invalidate() }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                if (dragging) { dragging=false;begin+=SystemClock.uptimeMillis()-touchTime;invalidate() }
                else if (e.actionMasked==MotionEvent.ACTION_UP) performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }

    companion object {
        private val main=Handler(Looper.getMainLooper())
        private fun pool(name:String,threads:Int)=ThreadPoolExecutor(threads,threads,20,TimeUnit.SECONDS,ArrayBlockingQueue(32),
            java.util.concurrent.ThreadFactory { job -> Thread(job,name).apply { isDaemon=true } }).apply { allowCoreThreadTimeOut(true) }
        private val posters=pool("Community covers",2)
        private val motion=pool("Community preview",1)
        private val cache=object:LruCache<Key,Bitmap>(12*1024*1024) {
            override fun sizeOf(key:Key,value:Bitmap)=value.allocationByteCount
        }
        private fun eased(value:Float):Float { val t=value.coerceIn(0f,1f);return t*t*(3-2*t) }
    }
}
