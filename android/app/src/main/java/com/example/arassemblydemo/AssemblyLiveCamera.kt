package com.example.arassemblydemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import org.opencv.objdetect.Objdetect
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

// Analyze up to one frame per 32 ms; slower devices retain proportional headroom
// for preview and overlay rendering. KEEP_ONLY_LATEST prevents a stale frame backlog.
internal fun analysisIntervalMs(averageAnalysisMs: Double): Long =
    (averageAnalysisMs * 1.4).toLong().coerceIn(32L, 180L)

/**
 * A single camera owned by the guide. Host bounds are supplied by the Activity from the web visual.
 * Public methods and observations run on the main thread. Permission prompts belong to the Activity.
 * Coordinates describe the camera image only; they do not claim a calibrated part pose or pickup.
 */
class AssemblyLiveCamera(
    private val activity: ComponentActivity,
    private val host: FrameLayout,
    private val onObservation: (Observation) -> Unit,
    private val onMappedFrame: (MappedFrame) -> Unit = {},
    private val onFraming: (String) -> Unit = {}
) {
    /** A fresh same-frame observation in PreviewView coordinates, including empty detections. */
    data class MappedFrame(val token:String,val markers:List<Marker>,val width:Int,val height:Int,val atMs:Long,val cameraGeneration:Long = 0,val slotQuads:List<FloatArray> = emptyList())
    data class Observation(
        val token: String,
        val marker: Int? = null,
        val visible: Boolean = false,
        val state: String,
        val message: String,
        val x: Float? = null,
        val y: Float? = null,
        val viewWidth: Int = 0,
        val viewHeight: Int = 0,
        val frameWidth: Int? = null,
        val frameHeight: Int? = null,
        val cropLeft: Int? = null,
        val cropTop: Int? = null,
        val cropWidth: Int? = null,
        val cropHeight: Int? = null,
        val rotationDegrees: Int? = null
    )

    private data class Request(val ids: Set<Int>, val token: String, val enabled: Boolean, val revision: Long,
                               val finderDot:Boolean=true, val slotMarkerIds:Set<Int> = emptySet())
    private data class Geometry(val width: Int, val height: Int, val left: Int, val top: Int,
                                val cropWidth: Int, val cropHeight: Int, val rotation: Int)

    val preview = PreviewView(activity).apply {
        // Preview + analysis share preview.viewPort below; outputTransform includes this crop.
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        setBackgroundColor(Color.rgb(251, 251, 246))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    // The TextureView can exist before its first camera frame. Cover that interval
    // on bind, resize and lens changes, including the antialiased native corners.
    private val cameraPaper = View(activity).apply {
        setBackgroundColor(Color.rgb(251, 251, 246))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val streamObserver = Observer<PreviewView.StreamState> { state ->
        cameraPaper.animate().cancel()
        if (state == PreviewView.StreamState.STREAMING && request.enabled && resumed) {
            cameraPaper.animate().alpha(0f).setDuration(120).start()
        } else {
            cameraPaper.alpha = 1f
        }
    }
    private val dot = MarkerDot(activity)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val uiPending = AtomicBoolean(false)
    private val focus = LiveMarkerFocus()
    private var edgeSeenAt: Long? = null
    @Volatile private var request = Request(emptySet(), "", false, 0)
    @Volatile private var generation = 0L
    @Volatile private var released = false
    private var resumed = false
    private var provider: ProcessCameraProvider? = null
    private var providerLoading = false
    private var previewCase: Preview? = null
    private var analysisCase: ImageAnalysis? = null
    private var processor: ArucoProcessor? = null // analysis-executor owned
    private var framing = CameraFramingPreferences.read(activity)
    @Volatile private var framingReady = false
    private var framingLabel = "主摄"
    private var boundWidth = 0
    private var boundHeight = 0
    private var boundRotation = -1
    private var lastObservation: Observation? = null
    private var bindPosted = false

    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
                                                              oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            // A frame from the previous viewport must not paint over the resized preview.
            generation++
            boundWidth = 0
            boundHeight = 0
            clearTracking()
            scheduleBind()
        }
    }
    private val expiry = object : Runnable {
        override fun run() {
            if (!resumed || !request.enabled || released) return
            val now = SystemClock.elapsedRealtime()
            val lostFocus = focus.expire(now)
            val staleEdge = edgeSeenAt?.let { now - it >= FINDER_HOLD_MS } == true
            if (staleEdge) edgeSeenAt = null
            if (lostFocus || (staleEdge && focus.marker == null)) {
                if (edgeSeenAt != null) {
                    emitState("edge", "")
                } else {
                    dot.clear()
                    emitState("lost", "标签暂时离开画面，移回即可继续寻找")
                }
            }
            main.postDelayed(this, 100)
        }
    }

    init {
        host.addView(preview, FrameLayout.LayoutParams(-1, -1))
        host.addView(cameraPaper, FrameLayout.LayoutParams(-1, -1))
        host.addView(dot, FrameLayout.LayoutParams(-1, -1))
        preview.previewStreamState.observe(activity, streamObserver)
        preview.addOnLayoutChangeListener(layoutListener)
    }

    fun configure(ids: Set<Int>, token: String, enabled: Boolean, finderDot:Boolean=true, slotMarkerIds:Set<Int> = emptySet()) {
        requireMainThread()
        if (released) return
        val accepted = ids.filter { it in 0..249 }.toSet()
        val nextEnabled = enabled && accepted.isNotEmpty()
        val old = request
        if (old.ids == accepted && old.token == token && old.enabled == nextEnabled && old.finderDot==finderDot && old.slotMarkerIds==slotMarkerIds) {
            if (nextEnabled) scheduleBind()
            return
        }
        request = Request(accepted, token, nextEnabled, old.revision + 1, finderDot, slotMarkerIds)
        clearTracking()
        main.removeCallbacks(expiry)
        if (!nextEnabled) {
            stopCamera()
            emitState("paused", "")
        } else if (resumed) {
            emitState("searching", "对准这件零件的标签")
            main.post(expiry)
            scheduleBind()
        }
    }

    fun resume() {
        requireMainThread()
        if (released) return
        resumed = true
        if (request.enabled) {
            main.removeCallbacks(expiry)
            main.post(expiry)
            scheduleBind()
        }
    }

    fun pause() {
        requireMainThread()
        resumed = false
        main.removeCallbacks(expiry)
        stopCamera()
        clearTracking()
        emitState("paused", "")
    }

    fun switchLens() {
        requireMainThread()
        if (released) return
        framing = framing.next()
        CameraFramingPreferences.save(activity, framing)
        stopCamera()
        clearTracking()
        emitState("searching", "正在切换取景")
        scheduleBind()
    }

    internal fun highlight(regions: List<FinderHighlight.Region>, frameAt: Long) = dot.setRegions(regions, frameAt)

    fun framingLabel(): String = framingLabel

    fun release() {
        requireMainThread()
        if (released) return
        pause()
        released = true
        preview.removeOnLayoutChangeListener(layoutListener)
        executor.execute { processor?.close(); processor = null }
        executor.shutdown()
        preview.previewStreamState.removeObserver(streamObserver)
        cameraPaper.animate().cancel()
        host.removeView(cameraPaper)
        host.removeView(dot)
        host.removeView(preview)
    }

    private fun scheduleBind() {
        if (released || !resumed || !request.enabled || bindPosted) return
        bindPosted = true
        main.post {
            bindPosted = false
            bindCamera()
        }
    }

    private fun bindCamera() {
        if (released || !resumed || !request.enabled) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopCamera()
            clearTracking()
            emitState("permission", "允许相机后，就能直接寻找这件零件")
            return
        }
        val rotation = preview.display?.rotation ?: Surface.ROTATION_0
        val viewPort = preview.viewPort ?: return
        if (preview.width <= 0 || preview.height <= 0) return
        if (analysisCase != null && boundWidth == preview.width && boundHeight == preview.height && boundRotation == rotation) return
        val cameraProvider = provider
        if (cameraProvider == null) {
            if (providerLoading) return
            providerLoading = true
            val future = ProcessCameraProvider.getInstance(activity)
            future.addListener({
                providerLoading = false
                if (!released) {
                    try {
                        provider = future.get()
                        scheduleBind()
                    } catch (_: Exception) {
                        if (resumed && request.enabled) emitState("unavailable", "相机暂时无法启动，可返回后重试")
                    }
                }
            }, ContextCompat.getMainExecutor(activity))
            return
        }
        stopCamera()
        clearTracking()
        val bindingGeneration = generation
        try {
            framing = CameraFramingPreferences.read(activity)
            val lens = LensChoice.choose(cameraProvider.availableCameraInfos, framing.wide)
            val newPreview = Preview.Builder().setTargetRotation(rotation).build()
            val newAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    // Wide views shrink the physical tags. Request more analysis pixels rather than
                    // enlarging an already-decimated image; unsupported sizes retain CameraX fallback.
                    ResolutionStrategy(if (framing.wide) Size(1920, 1080) else Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()).build()
            val factory = ImageProxyTransformFactory().apply {
                // ArucoProcessor reads the cropped Y plane, without applying imageInfo rotation.
                isUsingCropRect = true
                isUsingRotationDegrees = false
            }
            var lastFrameAt = 0L
            var averageDetectionMs = 35.0
            var windowStart = 0L
            var detectTotal = 0.0
            var planeTotal = 0L
            var analysisTotal = 0.0
            var uiTotal = 0L
            var visibleFrames = 0
            var ageTotal = 0L

            newAnalysis.setAnalyzer(executor) { frame ->
                val snapshot = request
                val frameAt = SystemClock.elapsedRealtime()
                try {
                    if (released || !framingReady || generation != bindingGeneration || !snapshot.enabled ||
                        frameAt - lastFrameAt < analysisIntervalMs(averageDetectionMs) || uiPending.get()) return@setAnalyzer
                    lastFrameAt = frameAt
                    if (processor == null) {
                        ArucoProcessor.initialize()
                        processor = ArucoProcessor(Objdetect.DICT_4X4_250)
                    }
                    val (detection, quads, planeNanos) = processor!!.detectLive(frame, !snapshot.finderDot, snapshot.slotMarkerIds)
                    val analysisMs = (SystemClock.elapsedRealtime()-frameAt).toDouble()
                    averageDetectionMs += (analysisMs - averageDetectionMs) * .15
                    val sourceTransform = factory.getOutputTransform(frame)
                    val crop = frame.cropRect
                    val geometry = Geometry(frame.width, frame.height, crop.left, crop.top,
                        crop.width(), crop.height(), frame.imageInfo.rotationDegrees)
                    if (uiPending.compareAndSet(false, true)) {
                        main.post {
                            try {
                                if (resumed && !released && generation == bindingGeneration &&
                                    request.revision == snapshot.revision && request.enabled &&
                                    SystemClock.elapsedRealtime() - frameAt in 0..400) {
                                    val uiStart = SystemClock.elapsedRealtime()
                                    showDetection(snapshot, detection, sourceTransform, geometry, frameAt, quads)
                                    // Aggregate timing only, including native draw preparation but excluding display scanout.
                                    val now = SystemClock.elapsedRealtime()
                                    if (windowStart == 0L) windowStart = frameAt
                                    visibleFrames++
                                    detectTotal += detection.milliseconds
                                    planeTotal += planeNanos
                                    analysisTotal += analysisMs
                                    uiTotal += now - uiStart
                                    ageTotal += now - frameAt
                                    if (now - windowStart >= 5000L) {
                                        val sizeHint = "${geometry.cropWidth}x${geometry.cropHeight}"
                                        android.util.Log.d("AssemblyTiming", "crop=$sizeHint frames=$visibleFrames windowMs=${now-windowStart} planeUs=${planeTotal/visibleFrames/1000} detectorMs=${(detectTotal/visibleFrames).toInt()} analysisMs=${(analysisTotal/visibleFrames).toInt()} guideUiMs=${uiTotal/visibleFrames} toUiMs=${ageTotal/visibleFrames}")
                                        windowStart = now; visibleFrames = 0; detectTotal = 0.0; ageTotal = 0L
                                        analysisTotal = 0.0; uiTotal = 0L; planeTotal = 0L
                                    }
                                }
                            } finally {
                                uiPending.set(false)
                            }
                        }
                    }
                } catch (_: Exception) {
                    main.post {
                        if (resumed && !released && generation == bindingGeneration && request.revision == snapshot.revision) {
                            clearTracking()
                            emitState("unavailable", "暂时无法识别标签，请调整取景或返回后重试")
                        }
                    }
                } finally {
                    frame.close()
                }
            }
            previewCase = newPreview
            analysisCase = newAnalysis
            newPreview.setSurfaceProvider(preview.surfaceProvider)
            // Both streams must share this viewport for CoordinateTransform to be valid.
            val camera = cameraProvider.bindToLifecycle(activity, lens.selector, UseCaseGroup.Builder()
                .setViewPort(viewPort).addUseCase(newPreview).addUseCase(newAnalysis).build())
            boundWidth = preview.width
            boundHeight = preview.height
            boundRotation = rotation
            val zoom = camera.cameraInfo.zoomState.value
            val applied = framing.resolve(lens.isWide, zoom?.minZoomRatio ?: 1f, zoom?.maxZoomRatio ?: 1f)
            val future = camera.cameraControl.setZoomRatio(applied.ratio)
            future.addListener({
                if (generation == bindingGeneration && resumed && !released) {
                    val ok = runCatching { future.get() }.isSuccess
                    // Failed zoom keeps the actual camera's normal field of view, with an honest label.
                    framingLabel = if(ok) applied.label else if(lens.isWide) "广角" else "主摄"
                    framingReady = true
                    clearTracking()
                    onFraming(framingLabel)
                    emitState("searching", "让两件都入镜；接头细节可以靠近拍。")
                }
            }, ContextCompat.getMainExecutor(activity))
        } catch (_: Exception) {
            stopCamera()
            emitState("unavailable", "相机暂时无法启动，可返回后重试")
        }
    }

    private fun showDetection(snapshot: Request, detection: Detection, source: OutputTransform,
                              geometry: Geometry, frameAt: Long, slotQuads:List<FloatArray>) {
        val destination = preview.outputTransform ?: return
        val mappedSlots = mutableListOf<FloatArray>()
        val mapped = try {
            val transform = CoordinateTransform(source, destination)
            for (quad in slotQuads) {
                if (quad.size != 8) continue
                val mappedQuad = quad.copyOf()
                transform.mapPoints(mappedQuad)
                if (mappedQuad.all(Float::isFinite)) mappedSlots += mappedQuad
            }
            detection.markers.filter { !snapshot.finderDot || it.id in snapshot.ids }.mapNotNull { marker ->
                val points = marker.points.copyOf()
                transform.mapPoints(points)
                val center = markerQuadCenter(points) ?: return@mapNotNull null
                if (center.first !in 0f..preview.width.toFloat() || center.second !in 0f..preview.height.toFloat()) null
                else Marker(marker.id,points)
            }
        } catch (_: Exception) {
            clearTracking()
            emitState("searching", "正在对齐相机画面")
            return
        }
        onMappedFrame(MappedFrame(snapshot.token,mapped,preview.width,preview.height,frameAt,generation,mappedSlots))
        // Guides may have drawn a CAD region for this frame. The tag dots belong to the finder.
        if (!snapshot.finderDot) return
        dot.setTags(mapped)
        if(mapped.isEmpty()){dot.hideTagPoint();return}
        val centers=mapped.associate { it.id to requireNotNull(markerQuadCenter(it.points)) }
        if (!finderFramed(centers.values, preview.width, preview.height)) {
            edgeSeenAt = frameAt
            dot.hideTagPoint()
            if (focus.marker == null) emitState("edge", "")
            return
        }
        edgeSeenAt = null
        val selected = focus.observe(centers.keys, frameAt) ?: return
        val center = centers.getValue(selected)
        dot.show(center.first, center.second)
        emit(Observation(snapshot.token, selected, true, "tracking", "找到这件零件了",
            center.first, center.second, preview.width, preview.height,
            geometry.width, geometry.height, geometry.left, geometry.top,
            geometry.cropWidth, geometry.cropHeight, geometry.rotation))
    }

    private fun stopCamera() {
        cameraPaper.animate().cancel()
        cameraPaper.alpha = 1f
        framingReady = false
        generation++
        analysisCase?.clearAnalyzer()
        val owned = listOfNotNull(previewCase, analysisCase)
        if (owned.isNotEmpty()) provider?.unbind(*owned.toTypedArray())
        previewCase = null
        analysisCase = null
        boundWidth = 0
        boundHeight = 0
        boundRotation = -1
    }

    private fun clearTracking() {
        edgeSeenAt = null
        focus.clear()
        dot.clear()
    }

    private fun emitState(state: String, message: String) = emit(Observation(
        request.token, state = state, message = message, viewWidth = preview.width, viewHeight = preview.height))

    private fun emit(observation: Observation) {
        if (lastObservation == observation) return
        lastObservation = observation
        onObservation(observation)
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "AssemblyLiveCamera must be used on the main thread" }
    }

    private class MarkerDot(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var point: Pair<Float, Float>? = null
        private val density = resources.displayMetrics.density

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }

        private var outlines = emptyList<Pair<android.graphics.Path,Float>>()
        private var tags = emptyList<android.graphics.Path>()
        private var observedAt = 0L

        fun setRegions(regions: List<FinderHighlight.Region>, frameAt: Long) {
            if (regions.isEmpty() && outlines.isEmpty()) return
            observedAt = frameAt
            outlines = regions.map { region ->
                android.graphics.Path().apply {
                    fillType = android.graphics.Path.FillType.EVEN_ODD
                    for (ring in region.rings) {
                        if (ring.size < 3) continue
                        moveTo(ring[0].x.toFloat(), ring[0].y.toFloat())
                        for (i in 1 until ring.size) lineTo(ring[i].x.toFloat(), ring[i].y.toFloat())
                        close()
                    }
                } to region.opacity
            }
            invalidate()
        }

        fun hideTagPoint() {
            point = null
            invalidate()
        }

        fun setTags(markers: List<Marker>) {
            observedAt = SystemClock.elapsedRealtime()
            tags = markers.map { marker ->
                val corners = marker.points
                val (cx, cy) = requireNotNull(markerQuadCenter(corners))
                val side = kotlin.math.hypot(corners[0] - corners[2], corners[1] - corners[3]).coerceAtLeast(1f)
                val grow = 1f + (14f * density / side).coerceAtMost(1.2f)
                android.graphics.Path().apply {
                    for (i in 0..3) {
                        val x = cx + (corners[i*2] - cx) * grow
                        val y = cy + (corners[i*2+1] - cy) * grow
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
            }
            invalidate()
        }

        fun show(x: Float, y: Float) {
            point = x to y
            invalidate()
        }

        fun clear() {
            point = null
            tags = emptyList()
            outlines = emptyList()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val age = SystemClock.elapsedRealtime() - observedAt
            if (age !in 0..400 || (outlines.isEmpty() && tags.isEmpty() && point == null)) return
            paint.strokeJoin = Paint.Join.ROUND
            for ((path, opacity) in outlines) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((92*opacity).toInt(), 24, 212, 146)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.color = Color.WHITE
                paint.alpha = (255*opacity).toInt()
                paint.strokeWidth = 4.5f * density
                canvas.drawPath(path, paint)
                paint.color = Color.rgb(0, 136, 92)
                paint.alpha = (255*opacity).toInt()
                paint.strokeWidth = 2.3f * density
                canvas.drawPath(path, paint)
            }
            for (path in tags) {
                paint.style = Paint.Style.STROKE
                paint.color = Color.WHITE
                paint.strokeWidth = 5f * density
                canvas.drawPath(path, paint)
                paint.color = Color.rgb(4, 156, 103)
                paint.strokeWidth = 2.8f * density
                canvas.drawPath(path, paint)
            }
            postInvalidateDelayed(401L - age)
            val (x, y) = point ?: return
            val pulse = (sin(SystemClock.elapsedRealtime() * Math.PI * 2 / 1100) + 1).toFloat() / 2
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(18, 148, 100)
            paint.alpha = (45 + 45 * pulse).toInt()
            canvas.drawCircle(x, y, (11 + 3 * pulse) * density, paint)
            paint.color = Color.WHITE
            paint.alpha = 245
            canvas.drawCircle(x, y, 7 * density, paint)
            paint.color = Color.rgb(18, 148, 100)
            paint.alpha = (190 + 65 * pulse).toInt()
            canvas.drawCircle(x, y, 4.5f * density, paint)
            postInvalidateOnAnimation()
        }
    }
}
